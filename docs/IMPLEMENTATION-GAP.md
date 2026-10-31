# 実装ギャップ分析（AS-IS / TO-BE）

> **2026-10-31 追記（解決状況）**: 本ギャップ文書は TO-PLAN 時点（実装途中）の記録である。その後、
> 以下を実装側で修正し、単体テスト 57 件・lint Error 0・署名付き release APK の実機 QA まで完了した。
>
> | ID | 状態 | 対応 |
> |---|---|---|
> | G-01 | **解決** | `intent://` は起動しない（fallback URL のみ検証して読み込み） |
> | G-02 | **解決** | API 26–28 は `SslCertificate.saveState` の Bundle から X.509 を復元（既知キー→走査） |
> | G-03 | **解決（方針変更）** | ダイアログはメインフレームのみ。サブリソースは silent cancel |
> | G-04 | **解決** | 読み込み後に `WebView.certificate` を再照合。失効時は WebView を再生成 |
> | G-05 | **解決** | 認証キーを scheme + host + port + realm に。http は保存・自動入力しない |
> | G-06 | **一部解決** | 保存失敗をトースト通知。Keystore 破損時の退避通知は v1.1 候補 |
> | G-07 | **解決** | release は常時署名。環境変数なしはビルド失敗（実測） |
> | G-08 | **解決** | `onRenderProcessGone` で破棄＋世代番号で再生成 |
>
> 詳細は [REVIEW-RESPONSE.md](REVIEW-RESPONSE.md)、検証は [FINAL-VERIFICATION.md](FINAL-VERIFICATION.md) を参照。
> 以下の本文は当時の分析として保存する（歴史的記録）。

- 文書ID: HB-GAP-001 / 版 1.0（2026-10-31）
- 作成: TO-PLAN（HB-PLAN-01）
- 目的: 本フェーズ中に `app/src/` に**実装が存在する**ことが判明したため、[PLAN.md](PLAN.md) の設計（TO-BE）と現物（AS-IS）を突き合わせ、**リリース前に必ず直すべき差分**を記録する。
- 注意: 本フェーズの指示は「app/ 配下は変更しない（docs のみ）」。したがって本ファイルは**修正指示であり、コード修正は行っていない**。
- 検証方法: 2026-10-31 時点の `app/src/main/java/**` を直接読み、`file:line` を記録した。**未実行のテストを PASS とは書かない**。

---

## 0. 結論

実装は PLAN の骨格（単一 Activity + Compose、`core` 純粋ロジック、`TrustPolicy`、`Codecs`、`BookmarkIcons`、`EncryptedSharedPreferences`、`ShortcutInfoCompat`）に**概ね沿っている**。一方で、**リリース不可の欠陥が 8 件（CRITICAL 1 / HIGH 7）** ある。特に G-01（`intent://` の任意コンポーネント起動）と G-02（API 26–28 でピンが機能しない）は、それぞれ**セキュリティ**と**要件の根幹**に関わる。

| 重大度 | 件数 | 内訳 |
|---|---|---|
| CRITICAL | 1 | G-01 |
| HIGH | 7 | G-02, G-03, G-04, G-05, G-06, G-07, G-08 |
| MEDIUM | 7 | G-09, G-10, G-11, G-12, G-16, G-17, G-18 |
| LOW | 3 | G-13, G-14, G-15 |

**推奨ゲート判定: G2（作業フェーズ）は REWORK。** 上記 HIGH 以上を修正し、AC-12b（proceed 保持の実測）を実施するまでリリース不可。

---

## 1. 良い点（維持する）

| # | 事実 | 根拠 |
|---|---|---|
| P-1 | `allowFileAccess=false` / `allowContentAccess=false` を明示 | `ui/browser/BrowserScreen.kt:585-586` |
| P-2 | JS ブリッジ（`addJavascriptInterface`）が存在しない | 全文 grep で 0 件 |
| P-3 | 第三者 Cookie を明示的に無効化 | `BrowserScreen.kt:506` |
| P-4 | `safeBrowsingEnabled = true`（API 26+ 分岐） | `BrowserScreen.kt:591` |
| P-5 | `setWebContentsDebuggingEnabled(BuildConfig.DEBUG)` のみ | `BrowserScreen.kt:604` |
| P-6 | 危険スキームを WebView に読ませず、システムへ委譲する方針 | `HalfBrowserWebViewClient.kt:60-65` |
| P-7 | `onReceivedSslError` に `@SuppressLint("WebViewClientOnReceivedSslError")` を付け、意図的逸脱であることを明示 | `HalfBrowserWebViewClient.kt:67` |
| P-8 | `SslChallenge` / `AuthChallenge` に `settled` フラグがあり二重呼び出しを防止 | `ui/browser/BrowserState.kt:53-65, 77-89` |
| P-9 | 認証情報の保存ファイル名が `halfbrowser_secure` で、バックアップ除外設定と一致 | `data/CredentialStore.kt:129` / `res/xml/*.xml` |
| P-10 | `onReceivedError` はメインフレームのみをエラー表示に反映 | `HalfBrowserWebViewClient.kt:150` |
| P-11 | `core/` は `LocaleHelper` を除き Android 非依存を維持 | `core/*.kt` |

---

## 2. 修正必須（CRITICAL / HIGH）

### G-01 / CRITICAL / `intent://` を `Intent.parseUri` → `startActivity` している

- **観測**: `HalfBrowserWebViewClient.handleSystemScheme()` が `intent:` スキームを `Intent.parseUri(url, Intent.URI_INTENT_SCHEME)` で復元し、`resolveActivity` の後 `context.startActivity(...)` している（`ui/browser/HalfBrowserWebViewClient.kt:164-174`）。
- **なぜ問題か**: 悪意ある Web ページが `intent://x#Intent;component=com.victim/.ExportedActivity;S.extra=...;end` のような URL を置くだけで、**端末内の任意の exported コンポーネントを attacker 制御の extras 付きで起動**できる。これは Android の古典的で深刻な脆弱性パターン（"Intent Redirection"）。`resolveActivity` は存在確認しかしないため防御にならない。
- **修正案（いずれか。推奨は a）**:
  - **(a) `intent:` を一切起動しない**。`handleSystemScheme` で `intent:` は「このリンクは開けません」トーストのみ。ブラウザとしての実用上も、`intent:` リンクは稀。
  - (b) どうしても必要なら、復元後に `intent.component == null && intent.action == ACTION_VIEW && scheme in (http,https)` を検証し、`selector`/`flags` を落としてから起動。**`component` が指定された Intent は拒否**。
- **検証**: AC-46。悪性 HTML（`intent://...#Intent;component=...;end`）を置いたページで、他アプリが起動しないこと。L4 + コードレビュー。
- **参照**: PLAN §7.1 T9 / TECH-RESEARCH §6.1（外部スキーム）

### G-02 / HIGH / API 26–28 で証明書フィンガープリントが常に null（ピンが機能しない）

- **観測**: `certificateFingerprint()` が `runCatching { certificate?.x509Certificate }` としている（`ui/browser/BrowserState.kt:92-95`）。`SslCertificate.getX509Certificate()` は **API 29 で追加**されたため、API 26–28 では `NoSuchMethodError` が `runCatching` に握られ **常に null** になる。`subjectOf` / `issuerOf` / `notAfterOf` も同様（`BrowserState.kt:97-113`）。
- **なぜ問題か**: `fingerprint == null` → `TrustDecision.Unknown` → インタースティシャルは出るが**保存する指紋が無い**。「常に信頼」を押しても次回また出る（無限リピート）。要件 2 の根幹が minSdk 26 で静かに死ぬ。クラッシュしないため気づかれない。さらに `runCatching` が `NoSuchMethodError` を飲むため、Lint の `NewApi` も検出しにくい。
- **修正案**:
  1. `Build.VERSION.SDK_INT >= 29` の分岐を**明示**し、`@RequiresApi(29)` のヘルパに閉じ込める（`runCatching` で `NoSuchMethodError` を飲まない）。
  2. 26–28 は `SslCertificate.saveState(cert)` の `Bundle` から `x509-certificate`（DER）を取り出し `CertificateFactory` で復元。キー名は非公開のため、**既知キー → Bundle 内の byte[] を走査して X.509 としてパースできるもの**、の2段フォールバックにする。
  3. それでも取れない場合は「この端末では証明書を記憶できません」と表示し、**「常に信頼」を無効化**（「今回だけ」のみ）。
- **検証**: AC-12（API 26 エミュレータ）。L1 で Bundle 走査の単体テスト（合成 Bundle）。

### G-03 / HIGH / SSL ハンドラを1件しか保持せず、2件目以降を cancel している

- **観測**: `onReceivedSslError` で `state.ssl?.cancel()` してから新しい `SslChallenge` に置き換える（`HalfBrowserWebViewClient.kt:92-104`）。
- **なぜ問題か**: 自己署名ページで画像・CSS など**サブリソースでも発火する場合**（未検証だが可能性が高い）、保留中のハンドラが cancel され、ユーザーが承認しても**キャンセル済みのサブリソースは戻らない**。結果「画像の欠けたページ + 証明書ダイアログ」になり、原因が分からない。承認後に `reload()` しない実装だと特に悪い。
- **修正案**: 保留単位を `(host, fingerprint)` にし、同一キーのハンドラを**キュー（上限 16）**に積む。ダイアログは1つだけ表示し、決定時に同一キューを**一括 proceed / cancel**。別証明書は即 cancel。承認後、メインフレームが https なら `reload()`（POST 中なら「再読み込みしてください」表示）。
- **検証**: AC-47。自己署名 + 画像20枚のページで、ダイアログが1回だけ・承認後に画像が全部表示される。

### G-04 / HIGH / 事後検証が無く、信頼解除が効かない可能性

- **観測**: `onPageFinished` はタイトル等の更新のみ（`HalfBrowserWebViewClient.kt:52-58`）。`WebView.getCertificate()`（メインフレームの証明書を返す public API）による照合が無い。`TrustRepository.revoke()` はピンを消すだけ（`data/TrustRepository.kt:66-68`）。
- **なぜ問題か**: 公式文言 "The decision to call proceed() or cancel() may be retained…" の解釈次第で、WebView が一度の `proceed()` を保持し、以後**コールバック無しで別証明書の通信が通る**可能性がある。その場合「信頼済みサイトから削除したのに繋がる」という、セキュリティ UI として最悪の失敗になる。
- **修正案（安価で効果的）**:
  1. `onPageFinished`（https・メインフレームのみ）で `view.certificate` を取得し、`TrustPolicy` のピンと照合。**不一致なら即 `stopLoading()` して `Changed` インタースティシャル**を出す（＝事後検知）。
  2. `TrustRepository.revoke(host)` 実行時に **WebView を破棄・再生成**（保持された判断を捨てる）。
  3. AC-12b の実測を行い、保持される場合は「トップレベル遷移時に帯域外 TLS プローブ（`SSLSocket` で leaf 証明書を取得して比較）」を追加。
- **検証**: AC-12b / AC-12c。

### G-05 / HIGH / 認証キーが `host|realm` のみ。失敗カウンタもリセットされない

- **観測**: `key = "$safeHost|$safeRealm"`（`HalfBrowserWebViewClient.kt:116`）、`CredentialStore.keyOf()` も `host|realm`（`data/CredentialStore.kt:124-125`）。`authAttempts` は成功時にリセットされず、画面（WebViewClient インスタンス）の寿命まで残る（`:42, :120-121, :134`）。`StoredCredential` に scheme/port が無い（`CredentialStore.kt:14-20`）。
- **なぜ問題か**: (a) `https://host` 用に保存した資格情報が `http://host` の 401 で**自動送信**される（平文 HTTP へのダウングレード）。(b) 同一ホストの別ポート（8080/8443）を取り違える。(c) 一度 2 回失敗すると、その後**正しいパスワードで手動ログインしても自動入力が復活しない**（UX 劣化）。
- **修正案**:
  1. `AuthKey(scheme, host, port, realm)` を導入し、保存・照合の両方で使う（`realm` は長さ上限 256・制御文字除去）。
  2. **`http://` では自動入力しない**（手動入力のみ、警告表示）。保存も既定 OFF。
  3. `authAttempts` は「メインフレームのロード成功」「新規ナビゲーション開始」「手動ログイン成功」でリセット。
  4. `useHttpAuthUsernamePassword()` は**ゲートに使わない**（WebView 側の保存分の話であり、ESP 保存では意味が不定）。補助ログのみ。
- **検証**: AC-22 / AC-24 / AC-48。

### G-06 / HIGH / 資格情報の「静かな喪失」

- **観測**: `CredentialStore` は `MasterKey`/ESP 生成失敗時に `runCatching` で握り、`prefs = null` → **メモリのみ**にフォールバックし、`Log.w` のみ（`data/CredentialStore.kt:40-53`）。`readAll()` もデコード失敗を `emptyMap()` に握り潰す（`:104-109`）。
- **なぜ問題か**: 端末のロック解除方法変更・復元・Keystore 不整合で保存済みが消えるが、**ユーザーには何も表示されない**。「保存したはず」という期待を裏切り、次の 401 で初めて気づく。クラッシュより悪い失敗。
- **修正案**: `CredentialVault` インターフェース + `VaultState { Ok, Unavailable, Wiped(reason) }`。復号不能時は (1) `halfbrowser_secure.corrupt-<ts>` にリネーム、(2) 新規作成、(3) 次回起動時に**1回だけ**「保存済みの認証情報を破棄しました（ブックマークは無事です）」を表示。`emptyMap()` を黙って返さない。
- **検証**: AC-26。factory 注入で「ESP 生成が例外」を再現する L2 テスト。

### G-07 / HIGH / 環境変数が無いと「未署名の release APK」が成功扱いでできる

- **観測**: `app/build.gradle.kts` は `STORE_PASSWORD` と `KEY_PASSWORD` の両方が設定されている場合のみ `signingConfig` を適用する。したがって未設定でも `assembleRelease` は**成功**し、未署名 APK が生成される。
- **なぜ問題か**: AC-41（署名検証）を回し忘れると、インストール不能な APK を配布物として公開してしまう。CI でも「ビルド成功」に見える。
- **修正案**: release ビルドで署名情報が無い場合、**明示的なフラグ（例 `-PallowUnsignedRelease=true`）が無い限り `error(...)` で失敗**させる。あわせて `apksigner verify` をリリースチェックリストの必須項目にする（AC-41）。
- **検証**: AC-41b。環境変数なしで `assembleRelease` が**失敗**すること。

### G-08 / HIGH / `onRenderProcessGone` が未実装

- **観測**: `HalfBrowserWebViewClient` に `onRenderProcessGone` が無い（全文 grep で 0 件）。
- **なぜ問題か**: レンダラプロセスが死ぬと、既定動作では**アプリ全体がクラッシュ**する（公式: true を返さないとアプリが終了する）。低メモリ端末・巨大ページで現実に起こる。
- **修正案**: `onRenderProcessGone` を override して `true` を返し、`WebView` を破棄 → 同じ URL で再生成。ユーザーに「ページを再読み込みしました」を表示。
- **検証**: AC-38。

---

## 3. 要判断（MEDIUM）

### G-09 / MEDIUM / `mixedContentMode` が `COMPATIBILITY_MODE`（設計文書は NEVER_ALLOW）

- **観測**: `BrowserScreen.kt:587` は `MIXED_CONTENT_COMPATIBILITY_MODE`。ADR-0001 / TECH-RESEARCH §6.1 は `NEVER_ALLOW` と書いていた。
- **判断**: **実装（COMPATIBILITY_MODE）を採用し、文書を合わせる。** 理由: (a) 一般のブラウザとして HTTPS ページの画像程度は表示できないと実用に耐えない（Chrome も多くの mixed content を自動アップグレード/許可する）、(b) 機密性の担保は「**資格情報を http に送らない**」（G-05）と「自己署名の TOFU」で行う、(c) NEVER_ALLOW は将来の設定項目（"厳格モード"）として追加するのが筋。
- **反映**: ADR-0001 / TECH-RESEARCH §6.1 / AC-37 を修正済み（本フェーズで更新）。
- **検証**: AC-37 で「COMPATIBILITY_MODE であること」を確認し、**平文 http にパスワードを送らないこと**を AC-48 で担保する。

### G-10 / MEDIUM / `LocaleHelper` がプロセス全体の `Locale.setDefault` を書き換える

- **観測**: `core/LocaleHelper.kt:26` で `Locale.setDefault(locale)` を呼んでから `createConfigurationContext` している。例外時は `getOrDefault(base)` で握り潰す。
- **なぜ問題か**: プロセス全体の既定ロケールを書き換えると、(a) 他コンポーネント（通知・日付書式・`String.format`）に副作用、(b) 言語を「システム」に戻したときに復元されない、(c) テスト間で状態が漏れる。
- **修正案**: `Locale.setDefault` を**呼ばない**。`Configuration.setLocales` + `createConfigurationContext` のみで UI は切り替わる。どうしても必要な箇所（日付書式）は明示的に `Locale` を渡す。
- **検証**: AC-31。言語切替後に `Locale.getDefault()` が変わっていないこと（L2/L3）。

### G-11 / MEDIUM / 言語変更時の WebView コンテキスト

- **観測**: `MainActivity` は `attachBaseContext` で locale を適用。`recreate()` すると ViewModel は保持されるため、WebView（と `MutableContextWrapper` があればその baseContext）が**旧 Activity を参照したまま**になり得る。
- **修正案**: 言語変更時は `BrowserScreen` の WebView を**明示的に破棄して再生成**する（URL は保存して復元）。`MutableContextWrapper` を使う場合は `onAttach`/`onDetach` で baseContext を更新する。
- **検証**: AC-31 + AC-39（リーク確認）。

### G-12 / MEDIUM / 「外部ブラウザで開く」が自分自身に解決し得る

- **観測**: `startExternalView()` は `ACTION_VIEW + CATEGORY_BROWSABLE + FLAG_ACTIVITY_NEW_TASK` を投げるだけで、**自パッケージを除外していない**（`HalfBrowserWebViewClient.kt:215-223`）。また `addCategory(BROWSABLE)` は発信 Intent としては意味が薄い。
- **なぜ問題か**: 既定ブラウザに設定されている場合、自分自身が解決されて**同じページを開き直す**（無限ループに見える）。ユーザーは「外部ブラウザで開く」を選んだのに何も変わらない。
- **修正案**: `Intent.createChooser` を使う、または `queryIntentActivities` から自パッケージを除外して明示的に起動。候補が無ければ「他のブラウザがありません」。
- **検証**: AC-08 / AC-49。

### G-17 / MEDIUM / API 33+ で `LocaleManager` を使っていない

- **観測**: 言語切替は `LocaleHelper.wrap()`（`attachBaseContext`）+ `recreate()` のみ。API 33+ の `LocaleManager.setApplicationLocales()` を呼んでおらず、`getApplicationLocales()` も読んでいない（`core/LocaleHelper.kt`, `ui/settings/SettingsScreen.kt`）。
- **なぜ問題か**: Android 13+ では「設定 → アプリ → 言語」に OS 側の per-app language UI が出る（`localeConfig` 宣言済み）。アプリ内設定と OS 設定が**二重管理**になり、「OS で日本語にしたのにアプリは英語のまま」「アプリで英語にしたのに OS 設定は日本語」という不整合が起きる。ADR-0008 は 33+ で `LocaleManager` を真実の源とする設計。
- **修正案**: `Build.VERSION.SDK_INT >= 33` の分岐を追加し、設定変更時に `LocaleManager.setApplicationLocales(...)` を呼ぶ。設定画面の表示は 33+ では `getApplicationLocales()` を優先。`attachBaseContext` は 26–32 専用に限定。
- **検証**: AC-31（API 33 / 36）。

### G-18 / MEDIUM / lint が強制されておらず、instrumented テストの土台が無い

- **観測**: `app/build.gradle.kts` に `lint { }` ブロックが無く（`abortOnError` 未設定）、`testInstrumentationRunner` と `androidTest` 依存も無い。`testImplementation` は JUnit のみ。
- **なぜ問題か**: AC-40（lint 0 errors）が**ゲートとして機能しない**（warning を無視してビルドが通る）。AC-23 / AC-28 の L3 検証が実行できない。
- **修正案**: `lint { abortOnError = true; warningsAsErrors = ... }`（少なくとも errors で失敗）、`testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`、`androidTestImplementation`（runner / ext-junit / core）、`testImplementation(kotlinx-coroutines-test)` を追加。
- **検証**: AC-40 / AC-42。

### G-16 / MEDIUM / ピンがホストにつき1件しか持てない

- **観測**: `TrustRepository.pin()` は `filterNot { it.host == host } + site` で**置換**する（`data/TrustRepository.kt:52`）。`find(host)` も1件のみ。
- **なぜ問題か**: 同一ホストの別ポートで別証明書を使う LAN 機器（`:8443` と `:9443`）で、A を信頼 → B で Changed → B を信頼 → A で Changed の**無限往復**になる。
- **修正案**: `TrustedSite` の一意性を `host + fingerprint` に変更し、ホストあたり最大 8 件・全体 50 件。`TrustPolicy.decide` は「一致するピンが1つでもあれば Trusted、無くて同一ホストにピンがあれば Changed（最新を previous として提示）」に拡張。UI の一覧は host ごとにグループ表示。
- **検証**: AC-15 / AC-50（2ポートの qa-server）。

---

## 4. 低（LOW）

| # | 観測 | 影響 | 修正案 | AC |
|---|---|---|---|---|
| G-13 | `ACTION_SEND` の本文に URL が無いと何も起きない（`MainActivity.handleSendIntent` が `firstUrlIn` の null で return） | テキスト共有が無反応 | URL が無ければ全文を検索クエリとして開く（要件 6 の「SEND text/plain も受ける」を素直に満たす） | AC-30 |
| G-14 | `android:largeHeap="true"` | リークを隠蔽し、メモリ安全性の検証を妨げる | v1 では外す（必要になったら理由付きで戻す） | AC-39 |
| G-15 | `isWebViewAvailable()` の `WebViewCompat` 分岐が minSdk 26 では不要 | 死コード | 削除（可読性） | — |

---

## 5. 修正の順序（TO-EXEC への推奨）

1. **G-01**（CRITICAL・セキュリティ）— 30 分。`intent:` を拒否するだけ。
2. **G-07**（未署名リリース防止）— 15 分。ビルドスクリプト1行。
3. **G-02**（API 26–28 の指紋）— 2 時間。分岐 + Bundle フォールバック + 単体テスト。
4. **G-08**（レンダラ）— 30 分。
5. **G-05**（AuthKey）— 2 時間。モデル変更 + 保存形式のマイグレーション（既存 `host|realm` キーは読み替え）。
6. **G-04 / G-03**（事後検証・キュー）— 半日。AC-12b の実測とセットで。
7. **G-06 / G-16 / G-09〜G-12 / G-13〜G-15** — 1 日。

**注意**: G-05 のキー変更は**保存済みデータのマイグレーション**を伴う。既存キー `host|realm` のエントリは `scheme=https` として読み替え、`http` 用には引き継がない（安全側）。

---

## 6. 未検証（実測が必要）

| # | 項目 | 方法 | AC |
|---|---|---|---|
| U-A | サブリソースで `onReceivedSslError` が発火するか | 自己署名 + 画像20枚のページ | AC-47 |
| U-B | `proceed()` の判断が WebView 側で保持されるか | 同一 WebView・証明書 A→B・再起動の3条件 | AC-12b |
| U-C | API 26–28 の Bundle から DER が取れるか | API 26 エミュレータ | AC-12 |
| U-D | ショートカット Bitmap（108dp×density）がランチャーで表示されるか | ピン留めして目視 | AC-28 |
| U-E | `COMPATIBILITY_MODE` で実際にどの mixed content が通るか | 混在コンテンツのテストページ | AC-37 |
| U-F | 言語変更 → `recreate()` で WebView がリークしないか | ヒープダンプ | AC-39 |

---

## 7. 参照

- [PLAN.md](PLAN.md) — TO-BE の設計
- [TECH-RESEARCH.md](TECH-RESEARCH.md) — 一次資料と落とし穴
- [ADR/](ADR/) — 各判断（特に 0002 / 0003）
- [ACCEPTANCE_MATRIX.md](ACCEPTANCE_MATRIX.md) — AC-46〜AC-50 を本分析で追加
