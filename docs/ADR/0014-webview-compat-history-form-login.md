# ADR-0014: WebView 互換シム・閲覧履歴・フォームログイン保存

- 日付: 2026-11-01
- 状態: 採用（v1.3.0）
- 関連: ADR-0003（認証情報の保存）, ADR-0013（バックアップ）

## 背景

DSH の Web UI（`https://192.168.0.165:3080/`、自己署名 HTTPS + フォームログイン）を
Half-Browser で開くと、ログイン画面までは表示できるが、ログイン後の SPA が真っ白のままだった。

エミュレータで再現して切り分けた結果、原因は 2 つあった。

1. **古い WebView の JS 不足**: WebView 113 では `Promise.withResolvers`（Chromium 119）と
   `AbortSignal.any`（Chromium 116）が無く、SPA の起動スクリプトが例外で停止していた。
2. **レイアウトビューポート高さが 0**: Compose の `AndroidView` 経由でホストした WebView では、
   `innerHeight` と `documentElement.clientHeight` は正しい（例: 783）のに、CSS の
   `100vh` / `100dvh` / `100svh` がすべて 0 に解決された。素の Activity に置いた WebView では
   正しく 783 になるため、Compose ホスト固有の挙動と判断した。DSH は
   `html, body { height: 100dvh !important }` と `body { grid-template-rows: 100dvh !important }`
   で全体を組んでいるため、この 0 が UI 全体の高さ 0 を招いていた。

さらにユーザー要望として、閲覧履歴と、自己署名 HTTPS サイトのフォームログイン
（DSH のログイン画面）の保存・自動入力が必要だった。既存の資格情報保存は HTTP Basic/Digest
専用で、HTML フォームには効かない。

## 決定

### 1. ドキュメント開始時の互換シム（`core/WebCompat.kt`）

`WebViewCompat.addDocumentStartJavaScript` で、ページのスクリプトより前に 2 つのスクリプトを注入する。

- `POLYFILL_JS`: `Promise.withResolvers`, `AbortSignal.any`, `AbortSignal.timeout`,
  `Object.groupBy`, `Map.groupBy`, `Promise.try`, `Array.fromAsync`, `Set` の集合演算, `URL.parse`。
  標準ライブラリの代替実装のみで、ネットワーク・DOM・アプリへの橋渡しはしない。
- `VIEWPORT_UNIT_FIX_JS`: まず `height: 100vh` のプローブで**壊れているか測る**。
  0 のときだけ、読み込み済みスタイルシートとインライン style の `*vh` 値を
  `innerHeight` 基準の px に置換する。DOMContentLoaded / load / resize で再実行。
  壊れていない WebView では何もしない（no-op）。

CSS を書き換えるのは強い手段だが、(a) 壊れている環境でのみ動く、(b) 置換は現在の
ビューポートに対する等価値であり、resize で再計算する、(c) ページの JS には触れない、
という条件で限定した。将来 Compose 側のホスト方法を変えて不要になれば、
このシムだけ削除できる。

### 2. 閲覧履歴（`core/History.kt` + `HistoryRepository`）

- URL・タイトル・最終訪問時刻・訪問回数を JSON で最大 500 件保持。
- 同一 URL は 30 分以内の再訪なら 1 件にまとめ、それ以上なら訪問回数を増やす。
- ホームの時計アイコンから一覧・個別削除・全消去。http(s) のみ記録。
- 履歴はバックアップには含めない（端末ごとの一時データと位置づける）。

### 3. HTML フォームのログイン保存（`core/FormLogin.kt`）

- ブックマークや Basic 認証とは別に、`realm = "form-login"` で
  `CredentialStore`（Keystore + EncryptedSharedPreferences）へ保存する。
- 保存はユーザー操作: ブラウザメニュー「このサイトのログインを保存」→
  `evaluateJavascript` で可視のパスワード欄と直近のユーザー名欄を読み、
  確認ダイアログ（サイト・ユーザー名・パスワード文字数）を出してから保存。
- 自動入力は https のみ、空欄のみ、**送信はしない**。設定で OFF にできる。
- ページからアプリへ値を渡すブリッジは作らない（アプリ→ページの一方向のみ）。

### 4. WebView バージョンの可視化

設定の「このアプリについて」に Android System WebView のバージョンを表示し、
120 未満なら更新を促す（Play ストアの WebView ページを開く）。
古い WebView では CSS/JS の不足で表示できないサイトがあるため。

## 影響

- DSH の Web UI がエミュレータ（WebView 113）で表示・操作できることを確認。
- 履歴とフォームログインはバックアップの対象外（フォームログインは資格情報なので
  バックアップの「保存したサインイン」に含まれる）。
- `SslErrorHandler` で許可したホストのサブリソースも許可するようにした
  （自己署名 SPA のスクリプト/スタイルが読めず半端に表示される問題の修正）。

## 検証

- JVM: `HistoryRulesTest`, `FormLoginTest`, `WebCompatTest`（シムの内容と非ネットワーク性）。
- エミュレータ API 34（WebView 113）: DSH ログイン→SPA 表示、履歴一覧、フォームログインの
  保存→Cookie 消去→再読込で自動入力（ユーザー名 `Yuki`・パスワード 13 文字）を確認。
- エミュレータ API 36（WebView 133）でも同じ手順で表示を確認。
