# ADR-0005: 外部 URL の受け取りと既定ブラウザへの導線

- 状態: **Accepted**（2026-10-31）
- 決定者: TO-PLAN（HB-PLAN-01）
- 関連: 要件 6 / TECH-RESEARCH §5 / ADR-0006 / AC-29〜AC-33

## Context

要件 6 は:

- `VIEW` + `http`/`https` + `BROWSABLE` の intent-filter を宣言し、既定ブラウザ候補になる
- 外部アプリから開かれた URL がブックマーク登録済みなら「ブックマークから開いた」状態（専用ツールバー）で表示する
- 既定ブラウザへの設定を促す導線（RoleManager / 設定画面フォールバック）
- `halfbrowser://` 独自スキームと `ACTION_SEND` text/plain も受ける
- **Android の仕様上、既定ブラウザはユーザーが OS 設定で選ぶ必要があり、強制転送はできない**ことを正直に設計・文書化する

現行 `AndroidManifest.xml` は既に以下を宣言している（**変更不要**）:

- `VIEW` × `http`/`https` × `DEFAULT`/`BROWSABLE`
- `VIEW` × `halfbrowser`（**BROWSABLE を付けない**＝Web ページから起動不可）
- `SEND` × `text/plain`
- `MainActivity` は `exported="true"`, `launchMode="singleTask"`
- `<queries>` に http/https の VIEW

## Decision

### 1. 受け付ける入力と検証

| 入力 | 抽出 | 検証 |
|---|---|---|
| `ACTION_VIEW` + `http(s)` | `intent.data` | `UrlNormalizer.validateWebUrl()`。通らなければ**ホームを表示**し、エラートースト |
| `ACTION_VIEW` + `halfbrowser://` | `DeepLinks.parse()`（既存） | スキーム一致・ホスト別分岐・`url` パラメータの再検証（既存実装が実施済み） |
| `ACTION_SEND` + `text/plain` | `EXTRA_TEXT` から**最初の http(s) URL を正規表現で抽出**。無ければ全文を検索クエリとして扱う | 抽出 URL を `validateWebUrl()`、検索語は長さ上限（例 500 文字） |
| `ACTION_MAIN` + `LAUNCHER` | なし | ホーム（最後に開いたページではなく**ホーム**。要件 5） |
| ショートカット起動 | `Intent` の `shortcutId` extras / `halfbrowser://bookmark/<id>` | ブックマーク存在確認。無ければホーム + 通知（ADR-0006） |

**拒否するもの**（明示）:

- `javascript:`, `file:`, `content:`, `data:`, `intent:`, `blob:` — アドレスバーと同様に弾く（`UrlNormalizer` の方針と一致）。
- URL 中の資格情報（`https://user:pass@host/`）。**ユーザー情報を含む URL は読み込みを拒否**し、「URL に認証情報が含まれています」と表示する（フィッシングとログ漏れの防止）。
- 極端に長い URL（8KB 超）— 拒否。
- 外部からの `halfbrowser://` は **BROWSABLE が無いため他アプリからは起動できない**（自アプリのショートカット専用）。この設計を維持する。

### 2. ブックマーク一致の判定と「ブックマークから開いた」状態

- 一致判定は `UrlNormalizer.canonicalForCompare(url)` の文字列一致（既存）。末尾スラッシュ・既定ポート・ホスト大小文字を吸収する。
- 一致した場合、`NavigationRequest.Open(url, bookmarkId, fromExternalApp=true)` を生成する。
- ツールバーは「ブックマークモード」: 左=ホームへ戻る、中央=**ブックマーク名**、右=3点メニュー。
- **重要**: ブックマークモードは「そのページに留まっている間」だけ有効。リンク遷移で URL が変わったら通常モード（中央=ページタイトル/ドメイン）へ戻す。これは `onPageStarted` で `canonicalForCompare(currentUrl) == canonicalForCompare(bookmark.url)` を評価して切り替える。
- 外部から来たがブックマーク未登録の場合、通常モードで開き、メニューに「ブックマークに追加」を出す（追加後はブックマークモードへ昇格）。

### 3. 既定ブラウザへの導線（OS 制約を隠さない）

```
設定 →「既定のブラウザ」
  ├─ API 29+ かつ roleManager.isRoleAvailable(ROLE_BROWSER)
  │    ├─ isRoleHeld == true  → 「このアプリは既定のブラウザです」表示のみ
  │    └─ isRoleHeld == false → [既定のブラウザに設定] → createRequestRoleIntent → startActivityForResult
  │                               RESULT_OK → 成功表示 / RESULT_CANCELED → 「変更されませんでした」
  ├─ API 29+ だが isRoleAvailable == false
  │    → ボタン無効。「この端末ではアプリから既定ブラウザを設定できません。設定アプリ → アプリ → 既定のアプリ から選択してください」と手順を表示
  └─ API 26–28（RoleManager 無し）
       → [端末の設定を開く] : Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS（解決できなければ ACTION_APPLICATION_DETAILS_SETTINGS）
```

- **強制転送はしない**。他アプリが明示的にパッケージ指定で呼んできた場合（`setPackage("com.yukiorita.halfbrowser")`）はそれを受けるが、こちらから横取りする手段は無い。
- 初回起動時に「既定のブラウザに設定しますか？」を**1回だけ**穏当に案内する（設定を強制するモーダルは出さない）。`AppSettings` に `defaultBrowserPromptShown: Boolean` を追加（既定 false、既存データは false 扱い＝後方互換）。
- 文言は「Half-Browser を既定のブラウザにすると、他のアプリのリンクがここで開きます。設定は OS が管理します。」とし、**アプリが勝手に切り替えたように見せない**。

### 4. Intent の再入とタスク

- `launchMode="singleTask"` + `onNewIntent`。新しい Intent は `setIntent(intent)` してから処理する。
- 既に同じ URL を表示中なら再ロードしない（`canonicalForCompare` で比較）。
- `FLAG_ACTIVITY_NEW_TASK` で来た場合も同一タスクに集約される（singleTask の性質）。WebView の状態は保持される。
- 処理は `MainActivity.onCreate` / `onNewIntent` の両方から同一の `IntentRouter` に通す（コード重複を避け、テスト可能にする）。

### 5. セキュリティ考慮

| 脅威 | 対策 |
|---|---|
| 悪意あるアプリが巨大/異常な URL を送る | 長さ上限、`validateWebUrl`、例外は捕捉してホームへ |
| `halfbrowser://` を外部から叩かれる | BROWSABLE を付けない（現行維持）。加えて `IntentRouter` 側でも「外部 Intent 由来の halfbrowser スキーム」を拒否する二重防御 |
| 資格情報入り URL によるフィッシング | 拒否 + 明示メッセージ |
| `ACTION_SEND` の本文に秘密が含まれる | 本文をログに出さない。検索クエリとして使う場合もログ禁止 |
| ブックマーク名をツールバーに出すことによる情報漏れ | ブックマーク名はユーザー自身のデータ。画面共有時のみ注意（`FLAG_SECURE` は付けない） |

## Consequences

### 利点

- 既存 manifest を変更せずに要件 6 を満たせる（`exported`, `singleTask`, `<queries>` が揃っている）。
- `IntentRouter` を純粋関数に近い形で切り出すことで、JVM テスト（`Intent` のモックは難しいため、`Uri` 文字列とアクション種別を渡す形）で検証できる。
- OS 制約を UI で説明するため、「設定したのに既定にならない」という誤解を生まない。

### 欠点・リスク

| # | リスク | 対策 |
|---|---|---|
| R1 | 端末により `ROLE_BROWSER` が取得できない（プリインストールブラウザが優先、OEM 制限） | 設定画面フォールバックを必ず用意。取得できないことを失敗扱いしない |
| R2 | 既定ブラウザになっても、アプリ内リンクが外部ブラウザで開くわけではない | 仕様。README に明記 |
| R3 | `singleTask` により、外部から来た Intent が既存タスクの状態を壊す | `IntentRouter` が現在の URL と比較し、同一なら無視。異なる場合は新しいタブではなく**現在の WebView で開く**（v1 は単一タブ） |
| R4 | `ACTION_SEND` の本文が URL でない場合、検索クエリとして送信される | ユーザーが「共有」した意図と一致（テキスト検索）。ログには出さない |
| R5 | API 26–28 ではロールの概念が無く、設定画面への遷移も端末差がある | `resolveActivity` で解決できなければアプリ詳細設定へフォールバックし、最悪は「設定アプリから手動で」と表示 |

### 実装上の帰結

- `IntentRouter`（新規、`core` 配下）: `fun route(action: String?, data: String?, mimeType: String?, text: String?): NavigationRequest`
  - Android 型（`Intent`）に依存しない引数にして JVM テスト可能にする。
- `MainActivity` は `IntentRouter` の結果を `BrowserViewModel.open(request)` に渡すだけ。
- 既定ブラウザ導線は `SettingsScreen` の1セクション。`RoleManager` は API 29+ のみ直接参照（`Build.VERSION` 分岐）。

## Alternatives

### A. `android:autoVerify` や App Links で横取りする

- 利点: 特定ドメインのリンクを確実に開ける。
- 欠点: **http/https の全リンクを対象にした既定ブラウザの代替にはならない**。ドメイン所有者の `assetlinks.json` が必要で、自宅 LAN の IP アドレスには使えない。
- 不採用（要件と無関係）。

### B. 既定ブラウザ設定を促すモーダルを初回に必ず出す

- 利点: 誘導が強い。
- 欠点: ユーザー體驗を損なう。OS の役割と競合する。ストアのポリシー上也方暮らし。
- 不採用（1回だけの非モーダル案内に留める）。

### C. 他アプリのリンクを Intent 傍受で奪う

- 利点: なし。
- 欠点: **Android のセキュリティモデル上不可能**。不可能であることを要件が明示している。
- 不採用（かつ実装しない）。

## Status

**Accepted**。`defaultBrowserPromptShown` の追加は `AppSettings` の後方互換（既定値付きフィールド追加）として行い、`Codecs.sanitize` の対象外（真偽値はそのまま）。
