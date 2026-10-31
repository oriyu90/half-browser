# ADR-0006: ホーム画面ショートカット（ピン留め + 動的）

- 状態: **Accepted**（2026-10-31）
- 決定者: TO-PLAN（HB-PLAN-01）
- 関連: 要件 7 / TECH-RESEARCH §4 / ADR-0004 / AC-28

## Context

要件 7 は、ブックマークごとに `ShortcutManagerCompat.requestPinShortcut` でホーム画面ショートカットを作成し（アイコンはモノグラム Bitmap、ラベルはブックマーク名）、動的ショートカットにも登録し、ショートカット起動時は該当ブックマークを直接開くことを求める。

プラットフォームの制約（TECH-RESEARCH §4.1、すべて公式明記）:

- `requestPinShortcut` は **前景 Activity または前景 Service** が無いと `IllegalStateException`。
- 戻り値 `true` は「ランチャーが機能対応」の意味のみ。**ピン留め成功ではない**。
- ランチャーが**ユーザーに承認ダイアログ**を出す。拒否時は**コールバックが来ない**。
- 数秒かかることがあるため**ワーカースレッド推奨**。
- 同一 ID が **disabled** だと `IllegalArgumentException`。
- `getMaxShortcutCountPerActivity()` は static + dynamic の上限（**ピン留めは別枠**）。
- `setDynamicShortcuts` はレート制限あり、上限超過で `IllegalArgumentException`。
- `isRequestPinShortcutSupported()` は App Lock 有効時に false。`onResume` で再問い合わせ推奨。
- **アプリからピン留めショートカットを削除することはできない**（無効化のみ）。

## Decision

### 1. ピン留めはユーザー操作からのみ、前景で、IO スレッドで

- トリガー: ホームのタイル長押しメニュー →「ホーム画面に追加」。**自動・一括ピン留めはしない**（OS が毎回ダイアログを出すため）。
- 実行前に `ShortcutManagerCompat.isRequestPinShortcutSupported(context)` を**その場で**評価する（`onResume` 時点のキャッシュを使わない）。
  - false の場合: メニュー項目を無効化し、「この端末のランチャーはピン留めに対応していません」と表示。
- 実行は `withContext(Dispatchers.IO)`。`Activity` の前景状態は呼び出し時点で成立している必要があるため、**ユーザー操作のコールバックから直接**起動する（バックグラウンドジョブに投げない）。
- 例外処理:

| 例外 | 原因 | UI |
|---|---|---|
| `IllegalStateException` | 前景でない / 端末ロック中 | 「画面を開いた状態で再度お試しください」 |
| `IllegalArgumentException` | 同一 ID が disabled | その ID を `enableShortcuts()` してから1回だけ再試行。再失敗ならエラー表示 |
| `SecurityException` | 権限・プロファイル制限 | 「このユーザープロファイルでは追加できません」 |

- コールバック `IntentSender` には **unexported の manifest 宣言 BroadcastReceiver**（`ShortcutPinReceiver`）を使う。`PendingIntent.FLAG_IMMUTABLE` を付ける。受信したら「ホーム画面に追加しました」を表示（成功時のみ）。**拒否時は何も来ない**ため、UI は「追加を要求しました」で止め、成功を断定しない。

### 2. ショートカット ID とラベル

- ID: `BookmarkIcons.shortcutId(bookmarkId)` = `"bookmark_<uuid>"`（既存実装）。**不変**（編集しても ID を変えない＝ピン留めが迷子にならない）。
- ラベル: `BookmarkIcons.shortcutLabel(title, url)`（40 文字上限、空ならホスト名）。ピン留め後にタイトルを変えた場合は `updateShortcuts()` で追従。
- 起動 Intent は**明示 Intent**:

```kotlin
Intent(context, MainActivity::class.java).apply {
    action = Intent.ACTION_VIEW
    data = Uri.parse(DeepLinks.bookmarkUri(bookmarkId))   // halfbrowser://bookmark/<id>
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
```

  明示 Intent にすることで、他アプリが同一スキームを横取りしても影響を受けない。

### 3. アイコン（Bitmap）

- 216×216 px、`Bitmap.Config.ARGB_8888`。
- 背景: `BookmarkIcons.colorFor(url, colorSeed)` の不透明色。角丸 24px。
- グリフ: `BookmarkIcons.glyphFor(title, url, glyph)` を白・太字・中央配置（フォントサイズは 216 の 45% 目安、2 文字なら縮小）。
- **Compose の `Density` に依存させない**（UI スケールの影響を受けない、ADR-0007 S6）。純粋関数 `MonogramBitmap.render(bookmark, sizePx): Bitmap` として切り出し、`android.graphics` のみに依存させる（JVM テストは不可だが、サイズ・色のロジックは `BookmarkIcons` 側でテスト済み）。
- `IconCompat.createWithBitmap(bitmap)` で `ShortcutInfoCompat.Builder.setIcon()` に渡す。

### 4. 動的ショートカット（`setDynamicShortcuts`）

- 内容: **最近開いたブックマーク最大 4 件**（`lastOpenedAt` を `Bookmark` に追加、既定 0）。
- 件数: `min(4, max(1, manager.maxShortcutCountPerActivity - 1))`。上限は端末で異なる（5 のことが多い）ため**必ず実行時に取得**する。
- 更新頻度: ブックマーク変更・ページ閲覧のたびに呼ばず、**500ms デバウンス**してまとめる（レート制限対策）。
- `setDynamicShortcuts` が `false`（レート制限）を返したら**黙って諦める**（次回更新で追いつく）。例外は捕捉してログ（秘密なし）。
- ピン留め済みショートカットと同一 ID の場合、`setDynamicShortcuts` は**可変ピンを更新**する（公式記述）。これを利用して、動的リストから外れたピン留めが消えないようにする（ピンは dynamic リストに含めなくても残る）。

### 5. 削除・無効化・復元

| 事象 | 動作 |
|---|---|
| ブックマーク削除 | ① 動的リストから除去 ② `disableShortcuts(listOf(id), "ブックマークが削除されました")` でピン留めを無効化（**削除はできない**） |
| ブックマーク編集（タイトル・グリフ・色） | `updateShortcuts()` でラベルとアイコンを更新。失敗はベストエフォート |
| ショートカット起動でブックマークが無い | ホームを表示し、「このブックマークは削除されています」を Snackbar。クラッシュしない |
| アプリ更新 | ショートカットは維持される（ID 不変のため） |
| 端末移行・バックアップ復元 | **ピン留めは復元されない**（OS 仕様）。動的ショートカットはアプリ起動時に再構築。README に明記 |
| アプリのデータ削除 | すべて消える。再追加が必要 |

- `disableShortcuts` は API 25+。`ShortcutManagerCompat.disableShortcuts(context, ids, disabledMessage)` を使う。
- 無効化された ID を再びピン留めしようとすると `IllegalArgumentException` になるため、§1 の再試行手順で `enableShortcuts()` を先に呼ぶ。

### 6. 検証（AC-28）

- エミュレータ（API 36、Pixel Launcher）で:
  1. 長押し →「ホーム画面に追加」→ ダイアログで**許可** → ホームにアイコンが出る → タップで該当ブックマークが開く
  2. 同じ操作で**拒否** → アプリはクラッシュせず「追加を要求しました」のまま（成功表示をしない）
  3. ブックマーク削除 → アイコンがグレーアウトし、タップで「削除されています」
  4. タイトル変更 → ラベルが追従（ランチャーによっては再起動が必要な旨を許容）
  5. 動的ショートカット: アプリ長押しで最近のブックマークが出る

## Consequences

### 利点

- 要件 7 を満たしつつ、OS の承認ダイアログを**迂回しない**（プラットフォームの意図に沿う）。
- 失敗（拒否・非対応・レート制限）を「成功」と誤表示しない設計。
- ピン留め ID が不変なので、編集でショートカットが迷子にならない。

### 欠点・リスク

| # | リスク | 対策 |
|---|---|---|
| R1 | ユーザーが拒否してもアプリは知れない | 成功を断定しない文言。コールバック受信時のみ成功表示 |
| R2 | ピン留めはアプリから削除できない（残骸が残る） | 無効化 + 起動時のフォールバック。README で説明 |
| R3 | ランチャーによってはアイコンが円形マスクされ、角丸が見えない | グリフを中央 60% に収める（マスク耐性） |
| R4 | 動的ショートカットのレート制限 | デバウンス + 戻り値無視 |
| R5 | `getMaxShortcutCountPerActivity()` が極端に小さい端末 | `max(1, max-1)` で最低1件は確保。0 なら動的を諦める |
| R6 | アイコン生成のコスト（216px の Bitmap を毎回） | 生成はピン留め時と更新時のみ。メモリキャッシュ（LRU 8件） |

### 実装上の帰結

- `ShortcutPublisher`（新規）: `suspend fun pin(bookmark): PinResult`、`suspend fun syncDynamic(recent: List<Bookmark>)`、`fun disable(ids: List<String>)`。
- `PinResult`: `Requested`（要求は出した）/ `Unsupported` / `Rejected`（例外）/ `Failed(reason)`。**成功はコールバックでのみ確定**するため、`pin()` は `Requested` を返す。
- manifest に `ShortcutPinReceiver`（`exported="false"`）を追加する（実装フェーズ）。

## Alternatives

### A. 静的ショートカット（`shortcuts.xml`）のみ

- 利点: 確実に存在する。ユーザー操作不要。
- 欠点: ブックマークごとに動的生成できず、XML は APK 固定。要件 7 を満たさない。
- 不採用。

### B. `requestPinShortcut` を自動で全ブックマークに実行

- 利点: ユーザーが選ぶ手間が無い。
- 欠点: OS が**1件ごとにダイアログ**を出すため、実質不可能。UX 破綻。
- 不採用。

### C. 独自の「ホーム画面」をアプリ内に作る（ランチャー的）

- 利点: OS 制約を受けない。
- 欠点: 要件は「ホーム画面ショートカット」であり、アプリ内ホームは既に要件 5 で作る。別物。
- 不採用。

### D. `setLongLived(true)` を付ける

- 利点: ショートカットが「長寿命」として扱われ、キャッシュ削除の対象外になる。
- 欠点: 長寿命ショートカットには追加の制約（dynamic か cached であること、`removeLongLivedShortcuts` での後始末）があり、v1 の複雑度に見合わない。
- 判断: v1 では付けない。必要になったら再検討。

## Status

**Accepted**。`Bookmark.lastOpenedAt` の追加は後方互換（既定値 0）で行う。
