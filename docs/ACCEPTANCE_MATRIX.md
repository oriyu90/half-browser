# Half-Browser 受け入れ条件マトリクス（ACCEPTANCE_MATRIX）

- 文書ID: HB-AC-001 / 版 1.0（2026-10-31）
- 作成: TO-PLAN（HB-PLAN-01）
- 関連: [PLAN.md](PLAN.md) / [TECH-RESEARCH.md](TECH-RESEARCH.md) / [ADR/](ADR/)
- **状態の初期値はすべて `NOT_TESTED`**（本フェーズは計画のみで、実装コードは未着手）。実装フェーズで `PASS / FAIL / PARTIAL / BLOCKED` に更新する。
- 検証層の略号: **L1**=JVM純粋 / **L2**=JVMコンポーネント / **L3**=instrumented / **L4**=手動QA（エミュレータ・実機） / **S**=静的検査（lint・grep・apksigner 等）

---

## 1. ブラウザ基本機能（要件 1）

| AC | 要件 | 検証方法 | 合格条件 | 担当WP | 状態 |
|---|---|---|---|---|---|
| AC-01 | URL 入力・検索・正規化が正しい | L1: `UrlNormalizerTest`（ホスト風入力、検索フォールバック、日本語クエリ、IDN、ポート付き、末尾スラッシュ）。L4: 実際に入力して表示確認 | `https://` 補完・検索・拒否の3分岐が全ケースで期待どおり。例外を投げない | WP-02 | NOT_TESTED |
| AC-02 | `javascript:` / `file:` / `data:` / `blob:` をアドレスバーから実行しない | L1: `UrlNormalizerTest` の拒否ケース。L4: 貼り付けて Enter | すべて `null` を返し、ページに何も起きない | WP-02 | NOT_TESTED |
| AC-02b | `core/` が Android 非依存を保つ | S: `grep -rn "^import android" app/src/main/java/com/yukiorita/halfbrowser/core/` が 0 件 | 出力 0 行 | WP-02 | NOT_TESTED |
| AC-03 | 永続化が壊れたデータで起動不能にならない | L1: `CodecsTest`（空・壊れ JSON・未知キー・型違い・範囲外スケール・不正 URL ブックマーク）。L4: adb で prefs を壊して起動 | 既定値へフォールバックし、クラッシュしない。不正ブックマークは一覧に出ない | WP-02, WP-03 | NOT_TESTED |
| AC-04 | 戻る / 進む / リロードが動く | L4: 3ページ遷移して各ボタン | `canGoBack`/`canGoForward` に応じて有効/無効が切り替わり、遷移が正しい | WP-06 | NOT_TESTED |
| AC-05 | Cookie / JavaScript / DOM ストレージが有効 | L4: JS 必須サイト（例: 検索エンジン）と localStorage を使うページ | ページが機能する。設定画面のトグルが即時反映 | WP-06 | NOT_TESTED |
| AC-06 | `target=_blank` のリンクが開く | L4: テストページの `target=_blank` をタップ（`supportMultipleWindows=false`） | 同一 WebView 内で遷移する（真っ白・無反応にならない）。TECH-RESEARCH U14 | WP-06 | NOT_TESTED |
| AC-07 | タイトル / 進捗 / URL がツールバーに反映 | L4: 低速回線で読み込み | 進捗が 0→100 に動き、タイトルが更新され、URL が現在ページと一致 | WP-06 | NOT_TESTED |
| AC-08 | 共有が動く | L4: メニュー → 共有 | `ACTION_SEND` の chooser に現在 URL とタイトルが渡る | WP-06 | NOT_TESTED |
| AC-09 | PC 版サイト表示が切り替わる | L4: UA を確認できるページで切替 | デスクトップ UA に変わり、リロード後に反映。戻すと元に戻る | WP-06 | NOT_TESTED |

## 2. 自己署名 HTTPS（要件 2）

| AC | 要件 | 検証方法 | 合格条件 | 担当WP | 状態 |
|---|---|---|---|---|---|
| AC-10 | 自己署名 HTTPS で独自インタースティシャルが出る | L4: `https://10.0.2.2:8844/`（`tools/qa-server.py --cert a`） | ブラウザ標準の警告ではなく**アプリ独自ダイアログ**が表示される | WP-07 | NOT_TESTED |
| AC-11 | 証明書情報 5 項目が表示される | L4: 同上。ホスト / エラー種別 / 発行者 / Subject / SHA-256 | 5 項目すべてが表示され、フィンガープリントが `openssl x509 -fingerprint -sha256` の値と一致 | WP-07 | NOT_TESTED |
| AC-12 | API 26–28 でも証明書が取得できる | L4: API 26 / 28 エミュレータで AC-10 | インタースティシャルが出て、フィンガープリントが表示される（`NoSuchMethodError` が無い） | WP-07 | NOT_TESTED |
| AC-12c | 事後検証が機能する（信頼解除が効く） | L4: ピン済みホストを開いたまま設定でピンを失効 → 再読込。L1: 事後照合の純関数 | 失効後は再警告が出る（WebView 再生成 or `getCertificate()` 照合で検知）。ピンと異なる証明書を検知したら `Changed` を出す | WP-07 | NOT_TESTED |
| AC-12b | `proceed()` 判断の保持挙動を実測する | L4: ピン済みホストを (a) 再読込 (b) 証明書差し替え (c) アプリ再起動 の3条件で開き、デバッグログで `onReceivedSslError` の発火を確認 | 3条件すべてでコールバックが発火し、(b) で `Changed` が出る。**出ない場合は Plan B へエスカレーション**（TECH-RESEARCH §1.2） | WP-07 | NOT_TESTED |
| AC-13 | ハンドラが漏れない（タイムアウト・破棄・二重） | L2: `SslTrustCoordinatorTest`（Fake handler）。L4: ダイアログ放置 60 秒 / 回転 / 戻る | どの経路でも `cancel()` か `proceed()` が**ちょうど1回**呼ばれる。ANR・固まりが無い | WP-07 | NOT_TESTED |
| AC-14 | 「今回だけ」は保存されない | L4: 今回だけ → リロード | 再度インタースティシャルが出る（保存されていない） | WP-07 | NOT_TESTED |
| AC-15 | 「常に信頼」で自動続行し、証明書変更で再警告 | L4: 常に信頼 → 再読込（自動で通る）→ サーバーを `--cert b` で再起動 → 再読込 | 1回目は無警告で表示。証明書変更後は `Changed` ダイアログ（以前の情報付き）が出て、自動続行しない | WP-07 | NOT_TESTED |
| AC-16 | 信頼済みサイトの一覧・失効 | L4: 設定 → 信頼済みサイト → 削除 → 再アクセス | 一覧に host / 追加日時 / フィンガープリント短縮表示。削除後は再警告 | WP-05, WP-07 | NOT_TESTED |
| AC-17 | ピン不可条件が守られる | L1: `TrustPolicyTest` + ポリシー関数。L4: 期限切れ証明書 / CN 不一致 | 期限切れ・未開始・IDMISMATCH・X509 取得失敗では「常に信頼」が**無効**。上限 50 件で追加拒否 | WP-02, WP-07 | NOT_TESTED |
| AC-18 | ピンが安全側に失効する | L4: アプリのデータ削除 → 再アクセス | 再警告が出る（自動信頼しない） | WP-07 | NOT_TESTED |

## 3. ホームとブックマーク（要件 5）

| AC | 要件 | 検証方法 | 合格条件 | 担当WP | 状態 |
|---|---|---|---|---|---|
| AC-19 | ブックマークの追加・編集・削除・並び | L4: 追加 → 編集（タイトル/glyph/色）→ 削除。L1: `CodecsTest` 往復 | 変更が即時反映され、再起動後も保持。重複 URL は新規作成せず更新 | WP-03, WP-04 | NOT_TESTED |
| AC-20 | モノグラムが決定的で読みやすい | L1: `BookmarkIconsTest`（同一 URL 同一色、glyph 導出、絵文字＝書記素、日本語1文字）。L4: ダーク/ライトで目視 | 同一 URL は常に同じ色。絵文字タイトルで豆腐にならない。ダークでもコントラスト確保 | WP-02, WP-04 | NOT_TESTED |
| AC-21 | 500 件でも実用速度 | L4: 500 件投入して起動・スクロール・追加 | 起動 2 秒以内（エミュレータ）、スクロールが目視で滑らか、追加が 200ms 以内 | WP-03 | NOT_TESTED |

## 4. HTTP 認証（要件 3）

| AC | 要件 | 検証方法 | 合格条件 | 担当WP | 状態 |
|---|---|---|---|---|---|
| AC-22 | Basic 認証でダイアログが出て認証できる | L4: `https://10.0.2.2:8844/basic`（realm=half-browser） | ホスト・レルムが表示され、正しい資格情報でページが開く。誤りはエラー表示 | WP-08 | NOT_TESTED |
| AC-23 | 資格情報が暗号化保存される | L3: `CredentialVaultInstrumentedTest`（保存→読出→削除）。S: prefs XML をダンプして平文探索 | 往復が一致。`halfbrowser_secure.xml` にユーザー名/パスワードの平文が**含まれない** | WP-03 | NOT_TESTED |
| AC-24 | 自動入力の無限ループが起きない | L2: `AuthAttemptTrackerTest`。L4: `https://10.0.2.2:8844/always401` を保存済み資格情報で開く | 自動 proceed は最大2回。3回目以降は自動入力せずダイアログ（理由表示）。CPU 使用率が張り付かない | WP-02, WP-08 | NOT_TESTED |
| AC-25 | Digest 認証が通る | L4: `https://10.0.2.2:8844/digest`。事前に `curl --digest` でサーバー単体を確認 | 認証が成功しページが表示される。失敗時はダイアログに戻る | WP-08, WP-11 | NOT_TESTED |
| AC-26 | Keystore 異常時にクラッシュしない | L4: 端末の PIN 変更後に復号を試す（または鍵を adb で無効化） | クラッシュせず、保存済みが消えた旨を表示して再入力を促す | WP-03 | NOT_TESTED |
| AC-27 | 一覧・削除・ログ漏れなし | L4: 設定 → 認証情報 → 削除。S: `grep -rn "StoredSecret\|CredentialMeta" app/src/main | grep -i "log\|println"` が 0 件 | 一覧に host/realm/username。削除で消える。秘密がログに出ない | WP-03, WP-05 | NOT_TESTED |

## 5. ショートカット・外部 URL・言語（要件 6, 7）

| AC | 要件 | 検証方法 | 合格条件 | 担当WP | 状態 |
|---|---|---|---|---|---|
| AC-28 | ショートカットの作成・起動・無効化 | L4: 長押し→追加（許可/拒否）、タップ起動、ブックマーク削除、タイトル変更、動的リスト | 許可時のみアイコンが出て該当ブックマークが開く。拒否時に成功表示しない。削除で無効化＋起動時フォールバック。TECH-RESEARCH U12 | WP-09 | NOT_TESTED |
| AC-29 | 外部アプリの URL をブックマークモードで開く | L4: `adb shell am start -a android.intent.action.VIEW -d https://…` | ブックマーク登録済みなら中央がブックマーク名・左がホーム。未登録は通常モード | WP-10 | NOT_TESTED |
| AC-29b | `ROLE_BROWSER` の適格性と要求導線 | L4: API 29 / 33 / 36 で `isRoleAvailable` と要求ダイアログ | 自アプリが候補に出る。`RESULT_OK` 後に `isRoleHeld` が true。不可の端末では手順を表示 | WP-10 | NOT_TESTED |
| AC-30 | `halfbrowser://` と SEND と再入の検証 | L1: `IntentRouterTest`。L4: `am start -a android.intent.action.SEND --es android.intent.extra.TEXT "https://example.com"`、巨大テキスト、`javascript:` | 正しい URL/検索に解決。危険スキーム・資格情報入り URL・8KB 超は拒否。二重起動しない | WP-02, WP-10 | NOT_TESTED |
| AC-31 | 言語を切り替えられ、再起動後も保持 | L4: API 26 / 33 / 36 で 日本語⇄English⇄システム。プロセス kill 後も確認 | 全画面が即時切替。33+ はシステム設定と一致。26–32 は `recreate()` 後に同一 URL へ復帰 | WP-01, WP-05 | NOT_TESTED |

## 6. テーマ・適応 UI（要件 4, 8）

| AC | 要件 | 検証方法 | 合格条件 | 担当WP | 状態 |
|---|---|---|---|---|---|
| AC-32 | 日英の文字列が完全一致 | L1: `StringParityTest` | キー集合・書式指定子・plurals が一致。空値なし。日本語ハードコード 0 件 | WP-02 | NOT_TESTED |
| AC-33 | ライト/ダーク/システム/動的カラー | L4: 4 通り切替 + ダークで全画面確認 | 全画面が追従。コントラスト不足の文字が無い。動的カラー OFF でも崩れない | WP-01, WP-05 | NOT_TESTED |
| AC-34 | UI スケール・文字スケールが正しく効く | L1: `ScalePolicyTest`。L4: 0.8 / 1.0 / 1.4 × 0.8 / 1.5（最大 2.1）で全画面 | 設定どおり拡縮。文字が切れず、タップ領域が 48dp 以上（主要操作）。ダイアログも同じ倍率 | WP-01, WP-04 | NOT_TESTED |
| AC-35 | 画面サイズ・回転・インセット・カットアウト | L4: 電話縦横 / 10" タブレット縦横 / 折りたたみ / 3ボタン・ジェスチャー | コンテンツがシステムバー・カットアウトに隠れない。回転で WebView が再読み込みされない | WP-01, WP-06 | NOT_TESTED |
| AC-36 | グリッド列数（自動 / 固定） | L1: `GridPolicyTest`（境界値 600/840/1200、3〜8 クランプ）。L4: 各設定で目視 | 自動で幅に応じ 3〜8 列。固定値が優先。狭すぎる場合も列数を勝手に変えない | WP-02, WP-04 | NOT_TESTED |

## 7. セキュリティ・堅牢性（要件 10）

| AC | 要件 | 検証方法 | 合格条件 | 担当WP | 状態 |
|---|---|---|---|---|---|
| AC-37 | WebView が安全側に設定されている | S: コードレビュー + `grep`。L4: `file:///sdcard/` を開こうとする、混在コンテンツのページ | `allowFileAccess/ContentAccess=false`、`mixedContent=COMPATIBILITY_MODE`（決定は G-09）、JS ブリッジ 0 件、`safeBrowsing=true`、debug 以外で `setWebContentsDebuggingEnabled` が呼ばれない | WP-06 | NOT_TESTED |
| AC-38 | レンダラクラッシュから復帰する | L4: `chrome://crash` 相当（`about:crash` が無い端末では adb で `kill` を試す）／`WebView` のプロセス kill | アプリが落ちず、WebView が再生成されてページが再読込される | WP-06 | NOT_TESTED |
| AC-39 | WebView がリークしない | L4: ブラウザ画面を 20 回往復 → `dumpsys meminfo` と LeakCanary 相当の目視（依存追加はしないためヒープダンプで確認） | Activity インスタンスが保持されていない。`destroy()` が `onCleared` で呼ばれる | WP-06 | NOT_TESTED |

## 8. 品質・リリース（要件 11）

| AC | 要件 | 検証方法 | 合格条件 | 担当WP | 状態 |
|---|---|---|---|---|---|
| AC-40 | lint 0 errors | S: `./gradlew lintDebug` | `0 errors`（warnings は分類して記録。`abortOnError=true`） | WP-12 | NOT_TESTED |
| AC-41 | release APK の署名検証 | S: `apksigner verify --verbose --print-certs` | `Verifies` 成功、v2/v3 スキーム true、証明書 SHA-256 が `docs/RELEASE.md` の記録と一致 | WP-12 | NOT_TESTED |
| AC-42 | 単体テストが全件成功 | S: `./gradlew testDebugUnitTest` | 失敗 0、skip 0（意図的 skip はコメントで理由明記） | 全部 | NOT_TESTED |
| AC-43 | R8 後の release ビルドで主要機能が動く | L4: release APK を入れて AC-04/10/22/28/31 を再実施 | 難読化で WebView コールバックが壊れない。クラッシュしない | WP-12 | NOT_TESTED |
| AC-44 | エミュレータ QA マトリクス完了 | L4: `docs/QA-CHECKLIST.md` の必須項目 | API 26/29/33/36、縦横、タブレット、折りたたみ、夜間、文字最大、言語の全組み合わせで必須項目 PASS | WP-11 | NOT_TESTED |
| AC-45 | ドキュメント・ライセンス・プライバシー | S: ファイル存在 + 内容レビュー | `README.md` / `LICENSE`(MIT) / `docs/PRIVACY.md` / `docs/RELEASE.md` が存在し、自己署名の警告と既定ブラウザの OS 制約が明記されている | WP-12 | NOT_TESTED |

---

## 9. 追加 AC（実装ギャップ分析 G-01〜G-16 由来）

| AC | 要件 | 検証方法 | 合格条件 | 担当WP | 状態 |
|---|---|---|---|---|---|
| AC-46 | `intent://` で任意コンポーネントを起動しない（G-01） | L4: `intent://x#Intent;component=com.android.settings/.Settings;end` を置いたページを開く。S: `Intent.parseUri` の使用箇所レビュー | 他アプリが起動しない。「開けません」の表示のみ。`component` 付き Intent を `startActivity` しない | WP-06 | NOT_TESTED |
| AC-47 | サブリソースの SSL エラーでページが壊れない（G-03） | L4: 自己署名 + 画像20枚のページ | ダイアログは1回。承認後に画像が 20/20 表示される（または自動 reload される） | WP-07 | NOT_TESTED |
| AC-48 | 平文 HTTP に資格情報を送らない（G-05） | L1: `AuthKey` の scheme/port 分離。L3: http と https の qa-server | `http://` では自動入力しない。`https` 用の秘密が `http` の 401 で送られない | WP-02, WP-08 | NOT_TESTED |
| AC-49 | 「外部ブラウザで開く」が自己再帰しない（G-12） | L4: 既定ブラウザに設定した状態でメニューから実行 | 自アプリ以外のブラウザが開く。候補が無ければその旨を表示 | WP-06 | NOT_TESTED |
| AC-50 | 同一ホストの複数証明書で無限往復しない（G-16） | L4: `:8844` と `:9443` で別証明書のサーバーを2本立て、交互にアクセス | 2回目以降はダイアログが出ない（両方 Trusted）。一覧には2件表示 | WP-07 | NOT_TESTED |
| AC-41b | 署名情報が無い release ビルドが失敗する（G-07） | S: 環境変数を未設定にして `./gradlew assembleRelease` | **失敗**する（未署名 APK が生成されない）。明示フラグ時のみ生成 | WP-12 | NOT_TESTED |
| AC-51 | 3点メニュー 9 項目がすべて機能する（要件 9） | L4: 共有 / URL コピー / ブックマーク追加・編集 / 再読み込み / PC版 / 外部ブラウザ / 設定 / 閉じる を各1回 | 9 項目すべてが期待動作。無効項目は理由が表示される | WP-06 | NOT_TESTED |

## 10. 集計

| 分類 | 件数 | 内訳 |
|---|---|---|
| ブラウザ基本（要件1） | 10 | AC-01〜AC-09（+02b） |
| 自己署名 HTTPS（要件2） | 11 | AC-10〜AC-18（+12b/12c） |
| ホーム/ブックマーク（要件5） | 3 | AC-19〜AC-21 |
| HTTP 認証（要件3） | 6 | AC-22〜AC-27 |
| ショートカット/外部/言語（要件6,7） | 5 | AC-28〜AC-31（+29b） |
| テーマ/適応（要件4,8） | 5 | AC-32〜AC-36 |
| セキュリティ/堅牢性（要件10） | 3 | AC-37〜AC-39 |
| 品質/リリース（要件11） | 6 | AC-40〜AC-45 |
| ギャップ分析由来（§9） | 7 | AC-46〜AC-51, AC-41b（AC-12c は §2 に配置） |
| **合計** | **56** | 内訳の合計（49 + 7）。自動化は L1/L2/L3/S の範囲、残りは L4 手動 |

### 9.1 自動化できない AC（正直な記録）

AC-10, AC-11, AC-12, AC-12b, AC-13（一部）, AC-14, AC-15, AC-16, AC-22, AC-25, AC-26, AC-28, AC-29, AC-29b, AC-30（一部）, AC-31, AC-33, AC-34, AC-35, AC-37（一部）, AC-38, AC-39, AC-43, AC-44 は **L4 手動**が必須。CI では保証できない。したがって「CI が緑＝リリース可」ではない。ADR-0009 §7 のゲートで人手の確認を必須にする。

### 9.2 要件との対応（抜け漏れ確認）

| 要件 | 対応 AC |
|---|---|
| 1 通常ブラウザ | AC-01〜AC-09 |
| 2 自己署名 HTTPS + TOFU | AC-10〜AC-18 |
| 3 Basic/Digest + 保存 | AC-22〜AC-27 |
| 4 テーマ + 動的カラー | AC-33 |
| 5 ホーム + モノグラム | AC-19〜AC-21 |
| 6 外部 URL + 既定ブラウザ | AC-29, AC-29b, AC-30 |
| 7 ホーム画面ショートカット | AC-28 |
| 8 画面サイズ + スケール | AC-34〜AC-36 |
| 9 上部バー | AC-07（表示）+ AC-29（ブックマークモード）+ PLAN §4.3（メニュー 9 項目は AC-04/08/09/19 で個別検証） |
| 10 クラッシュ/メモリ/互換 | AC-03, AC-26, AC-37〜AC-39 |
| 11 品質 | AC-32, AC-40〜AC-45 |

> 要件 9 のメニュー 9 項目（共有・URL コピー・ブックマーク追加/編集・再読み込み・PC版・外部ブラウザ・設定・閉じる）は、個別の AC に分割しきれていない。**AC-46（メニュー全項目の機能確認）を実装フェーズで追加する**ことを推奨（本表の改訂候補）。
