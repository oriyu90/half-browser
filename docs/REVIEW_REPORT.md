# Half-Browser 独立監査レポート（REVIEW_REPORT）

- 監査日: 2026-10-31
- 対象: `/Users/user/places/適当/work/half-browser`
- 対象コミット: HEAD = `cebd5b336d25312019e8e3f4f4d9cf8e442b0c18`（`chore: project skeleton, core models and unit tests`）
- **重要**: 監査対象は HEAD ではなく **作業ツリー（HEAD + 未コミット変更 12 ファイル / +2528 −61 行）**。G-07 などの修正は未コミットのため、HEAD だけを見ると判定が変わる。リリース判定はコミット後の SHA で再監査すること。
- 監査方式: 読み取り専用。ソース・ビルドファイル・リソースは変更していない（作成したのは本ファイルのみ）。実装者の自己申告（IMPLEMENTATION-GAP.md 等）は信用せず、コードを直接読んで検証した。

---

## 0. 実行したコマンドと結果

| コマンド | 結果 |
|---|---|
| `git log -1 --format=%H` | `cebd5b336d25312019e8e3f4f4d9cf8e442b0c18` |
| `git status --short` / `git diff --stat` | 未コミット 12 ファイル（`CredentialStore.kt`, `BrowserScreen.kt`, `HomeScreen.kt`, `SettingsScreen.kt`, `AppContainer.kt`, `UrlNormalizer.kt`, `build.gradle.kts` 等） |
| `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/user/tools/android-sdk ./gradlew :app:testDebugUnitTest :app:lintDebug --no-daemon --offline` | **BUILD SUCCESSFUL（exit 0）** 3m48s。`testDebugUnitTest` は UP-TO-DATE（ソース変化なしの既存結果）。`lintDebug` は実行 |
| テスト結果集計（`app/build/test-results/testDebugUnitTest/*.xml`） | **44 tests / 0 failures / 0 errors / 0 skipped** |
| lint 結果集計（`app/build/reports/lint-results-debug.xml`） | **Error 0 / Warning 59 / Hint 1**。主な種別: UnusedResources 22, UseKtx 15, GradleDependency 7, ObsoleteSdkInt 4, NewerVersionAvailable 4, WebViewApiAvailability 1, UnusedAttribute 1, **SetJavaScriptEnabled 1**, **MissingOnRenderProcessGone 1**, AppBundleLocaleChanges 1 |
| `grep -rn "parseUri\|startActivity\|addJavascriptInterface\|setWebContentsDebugging" app/src/main/java` | JS ブリッジ 0 件。`parseUri` は `HalfBrowserWebViewClient.kt:224` のみ |
| `grep -rn "Log\." app/src/main/java` | `CredentialStore.kt:58,139` のみ（秘密値は出力していない） |
| `grep -rn "Locale.setDefault\|LocaleManager\|setApplicationLocales"` | `LocaleHelper.kt:26` のみ（LocaleManager は 0 件） |
| `git ls-files keystore`, `git check-ignore keystore/release.jks` | keystore は未追跡かつ `.gitignore:10` で除外済み（問題なし） |

テスト件数内訳（JUnit 44 件）: BookmarkIconsTest 6 / CodecsTest 7 / DeepLinksTest 5 / TrustPolicyTest 7 / UrlNormalizerTest 15 / LocaleParityTest 4。

**テストが存在しない領域**（ACCEPTANCE_MATRIX で L1/L2 指定の項目）: `AuthAttemptTracker` / `SslTrustCoordinator` / `ScalePolicy` / `GridPolicy` / `IntentRouter` / `x509Of` の API 26–28 フォールバック / `CredentialStore` の暗号化往復。`app/src/test` に該当ファイルなし（`grep -rn "class " app/src/test` で確認）。instrumented テストの土台（`androidTest`, `testInstrumentationRunner`）も無し。

---

## 1. 指摘一覧

凡例: 対象は作業ツリーの行番号。AC は ACCEPTANCE_MATRIX.md の ID。

| ID | 重大度 | 対象 | 内容 | 根拠・再現 | 推奨修正 | 関連AC |
|---|---|---|---|---|---|---|
| **R-01** | **HIGH** | `app/src/main/java/com/yukiorita/halfbrowser/ui/browser/HalfBrowserWebViewClient.kt:222-249` | `intent:` URL を `Intent.parseUri` で復元し `startActivity` している（G-01 の "a" 案ではなく "b" 案の実装）。`component`/`selector`/非VIEW は拒否しているが、**復元 Intent の `extras`・`data`・`flags` は攻撃者制御のまま**、`createChooser` で任意の VIEW 対応アプリに渡る。`S.browser_fallback_url` 以外の extras を弾いていない。 | `safe` 判定（:225-230）は component/selector/action/BROWSABLE のみ。`launch`（:240）は extras をそのまま保持。`handleIntentUri` 内でのみ呼ばれる。 | G-01 推奨 (a) に従い `intent:` は一切起動せず、フォールバック URL のみ許可。最低限 `intent.extras` を空にし `data` のスキームを http(s) に限定。 | AC-46 |
| **R-02** | **HIGH** | `ui/browser/HalfBrowserWebViewClient.kt:42-44, 120-166` ・ `data/CredentialStore.kt` | 自動入力の無限ループ防止が**不完全**。`authAttempts` は画面（WebViewClient）寿命まで**一度も減らない**。成功・新規ナビゲーションでのリセットが無い（G-05.3 未対応）。 | `authAttempts[key] = ...+1` は :141, :155 のみ。`onPageFinished`（:56-62）にリセット無し。`saved != null && attempts < 2` のため、2 回失敗後は同一画面内で正しいパスワードを保存しても**自動入力が復活しない**。 | 認証成功（メインフレーム load 成功）と新規ナビゲーションで `authAttempts` を該当キーのみ削除。 | AC-24 |
| **R-03** | **HIGH** | `ui/browser/HalfBrowserWebViewClient.kt:56-62` | 信頼失効・証明書差し替えの**事後検証が無い**（G-04 未対応）。`onPageFinished` は `view.certificate` を照合しない。 | `onPageFinished` は title/loading のみ更新。`view.certificate`/`getCertificate()` の参照は 0 件（grep で確認）。`TrustRepository.revoke` はピンを消すだけ。`notifyTrustChanged` による WebView 再生成（BrowserScreen.kt:121-128）は `revoke` 経由の経路のみで、WebView 内部に保持された `proceed()` の判断を確実に捨てる保証は未実証（AC-12b 未実施）。 | `onPageFinished`（https・メインフレーム）で `view.certificate` を `TrustPolicy` のピンと照合し、不一致なら `stopLoading()` + Changed 表示。AC-12b を実機で実施。 | AC-12b, AC-12c |
| **R-04** | **HIGH** | `app/src/main/AndroidManifest.xml:17, 22` | `android:largeHeap="true"` と `android:usesCleartextTraffic="true"` が残存。 | manifest 17 行目・22 行目。`largeHeap` は G-14 で外す対象（LOW 扱い）だが、**`usesCleartextTraffic`** は本アプリの要件（http ページを開く）からは必要。ただし G-05（http への資格情報非送信）と組み合わさる前提のため、R-02 の修正と合わせて検証が必要。 | `largeHeap` は除去し、`usesCleartextTraffic` は要件根拠（http を開く）をコメントで明記。`largeHeap` の除去後にメモリ QA を実施（AC-39）。 | AC-39, AC-48 |
| **R-05** | **HIGH** | `app/src/test/**`, `app/build.gradle.kts` | **中核ロジックの単体テストが無い**。`AuthAttemptTracker`・`SslTrustCoordinator`・`ScalePolicy`・`GridPolicy`・`IntentRouter`・`x509Of`（API 26–28 フォールバック）・`CredentialStore` の暗号化往復に対するテストが 0 件。instrumented 基盤（`androidTest`/runner）も無い（G-18）。 | 44 テストは `core/`（URL/Codecs/Trust/Bookmark/DeepLinks）と LocaleParity のみ。`app/build.gradle.kts` に `testInstrumentationRunner`/`androidTest` 依存なし。`lint {}` ブロックも無い。 | 上記の純粋関数に L1 テストを追加。instrumented 基盤を追加。 | AC-12, AC-23, AC-24, AC-34, AC-36, AC-48 |
| **R-06** | **HIGH** | `ui/browser/BrowserState.kt:108-119`（API 26–28 の `x509Of`） | API 26–28 の DER 取り出しが **実機未検証**。`SslCertificate.saveState` の Bundle キー `"x509-certificate"` は非公開 API 依存で、`getByteArray` が null を返す端末では指紋 null → 「常に信頼」が無効化されるが、これは**文書化された失敗**ではなく無言のフォールバック。 | :115 のキー文字列はソースに根拠コメント無し。G-02 の提案にあった「Bundle 内 byte[] を走査して X.509 パース」の二段目は未実装。`x509Of` が null を返すと `certificateFingerprint`→null→`TrustDecision.Unknown` で毎回ダイアログ（無限リピート）。 | Bundle を走査して `CertificateFactory` で復元できるものを採用する二段フォールバック。取れない場合は「この端末では記憶できません」を表示し「常に信頼」を無効化（UI は既に `enabled = fingerprint != null` で無効化している点は良い）。API 26 エミュレータで AC-12 実測。 | AC-12 |
| **R-07** | **MEDIUM** | `ui/browser/HalfBrowserWebViewClient.kt:92-119` | 保留中の SSL ハンドラを 1 件しか保持せず、`state.ssl?.cancel()` で置換（G-03 未対応）。サブリソースは「別ホストなら cancel」（:86-90）で捨てるが、**同一ホストの画像・CSS 等の SslError** は 2 件目以降で前者が cancel され、承認しても戻らない。 | `state.ssl?.cancel()`（:107）。キュー・同一キー集約なし。 | (host, fingerprint) キーのキューに積み、1 ダイアログで一括 proceed/cancel。承認後メインフレームが https なら reload。 | AC-47 |
| **R-08** | **MEDIUM** | `ui/browser/HalfBrowserWebViewClient.kt:150-163`（`onReceivedHttpAuthRequest`） | `AuthChallenge` の `onProceed` 内で `container.credentials.save(...)` の**戻り値（保存成否）を無視**。保存失敗時にユーザーへ「保存しました」と出る経路がある（BrowserScreen.kt:353 は `save` の真偽のみで表示）。 | `BrowserScreen.kt:350-353`: `challenge.signIn(...)` → `if (save) toast(auth_saved)`。`signIn` は `save` 引数をそのまま渡すため、`CredentialStore.save` が false（書き込み失敗）でも「保存しました」と表示される。G-06 の「静かな喪失」そのもの。 | `onProceed` で `save(...)` の結果を受け取り、失敗時は `auth_save_failed` を表示。 | AC-23, AC-26 |
| **R-09** | **MEDIUM** | `data/CredentialStore.kt:45-59, 104-109` | 暗号化の失敗が**握り潰される**（G-06 未対応）。`prefs` 生成失敗は `Log.w` のみで、`readAll()` のデコード失敗は `emptyMap()` を黙って返す。`isPersistent=false` の時のみ UI 表示（SettingsScreen）。keystore 不整合で既存データが消えても通知されない。 | `runCatching{...}.getOrNull()`（:46-59）、`getOrDefault(emptyMap())`（:127）。「Wiped」通知・`corrupt-<ts>` へのリネームは未実装。 | G-06 の `VaultState` 導入、破損時の退避とユーザー通知。 | AC-26 |
| **R-10** | **MEDIUM** | `app/build.gradle.kts:20-27` ・ `keystore` | release 署名は**常時設定**に変わった（G-07 の修正は作業ツリーで適用済み）が、**環境変数未設定時の挙動が「ビルド失敗」ではなく「署名キーが空で失敗する」に依存**している。`storePassword = System.getenv(...)` が null の場合の Gradle の挙動を実行確認していない。AC-41b（`assembleRelease` が失敗すること）は**未実行**。 | 作業ツリー diff で `if (...) signingConfig = ...` を削除し無条件化。ただし `storePassword`/`keyPassword` の null チェックは無い。 | AC-41b を実行し「失敗」を確認。失敗しない場合は `error(...)` で明示的に停止させる。 | AC-41, AC-41b |
| **R-11** | **MEDIUM** | `ui/browser/HalfBrowserWebViewClient.kt:215-223` `startExternalView` | 「外部ブラウザで開く」は `queryIntentActivities` で自パッケージを除外し（G-12 対応済み）、**候補の先頭を選ぶ**。`packageManager` の結果順序に依存し、既定ブラウザが選ばれる保証は無い。候補が 0 件なら `false`→トースト表示（対応済み）。 | :294-298。実装は G-12 の「自己再帰」を防いでいる。ただし `setPackage` で特定パッケージに強制するため、ユーザーの既定ブラウザ設定を無視する。 | `Intent.createChooser` を使い OS の選択に委ねる、または既定ブラウザ優先。 | AC-49 |
| **R-12** | **MEDIUM** | `app/src/main/AndroidManifest.xml:24-31` ・ `MainActivity.kt:58-63` | 外部からの `halfbrowser://open?url=...&bookmarkId=...` の **`bookmarkId` が未検証**。`Link.Open` の `bookmarkId` は Activity に渡り、`BrowserScreen` の `activeBookmark = request.bookmarkId?.let{ bookmarks.first { id } }` で**任意の既存ブックマーク名がタイトルに使われる**。`halfbrowser` は BROWSABLE ではないが、外部アプリの明示 Intent で到達可能。 | `DeepLinks.parse` は `bookmarkId` を検証せず。`BrowserScreen.kt:130` で存在チェックのみ（存在しなければ null）。タイトル偽装の余地。影響は小。 | `bookmarkId` を `url` の canonical 一致で再検証（一致しなければ破棄）。 | AC-30 |
| **R-13** | **MEDIUM** | `ui/browser/BrowserScreen.kt:610-644` `configureWebView` | `runCatching { with(webView.settings) {...} }` で**設定適用の例外を一括で握り潰す**。途中で例外が出ると以降の設定（`safeBrowsingEnabled`, `allowFileAccess=false`）が**適用されないまま**黙って進む。 | `runCatching` が `allowFileAccess`/`allowContentAccess` 行より前に例外を起こすと、ファイルアクセス制限が効かない。実機で発生確率は低いが、セキュリティ設定の失敗が可視化されない。 | 各セキュリティ設定を個別に `runCatching`、失敗はログ＋既定値。少なくとも `allowFileAccess=false` を先頭に移す。 | AC-37 |
| **R-14** | **MEDIUM** | `ui/browser/BrowserScreen.kt:97-100, 657-668` ・ `HalfBrowserWebViewClient.kt:121` | `onReceivedHttpAuthRequest` の**`host`/`realm` が `WebView` 由来の文字列**のまま `AuthChallenge` UI に表示される（`InfoRow` で描画）。表示は Compose なので XSS にはならないが、制御文字・長大文字列の除去が無く、ダイアログ偽装（長い realm でボタンを押し出す等）が可能。 | `realm.orEmpty()`（:134）に長さ上限・制御文字除去無し。G-05 の提案（realm 長さ上限 256・制御文字除去）未対応。 | 表示用に 256 文字上限・制御文字除去。 | AC-22, AC-27 |
| **R-15** | **MEDIUM** | `ui/browser/HalfBrowserWebViewClient.kt:98-103` ・ `BrowserScreen.kt:321-334` | 「常に信頼」の pin → `proceed()` の順序と、「今回だけ」`onTrustOnce` の**`markSeen`/`lastSeenAt`** 更新が `Trusted` 経路のみ。`TrustDecision.Changed` を承認した際、**古いピンを上書きする前に警告文を再表示しない**。`onTrustAlways` は確認ダイアログ無しで即 pin（変更後証明書を無確認で信頼に上書き）。 | `BrowserScreen.kt:321-332`。Changed 時の `onTrustAlways` は警告の再確認が無い。ダイアログ本文では注意喚起するが、ボタン 1 タップで旧ピンが消える。 | Changed 時の「常に信頼」は二段確認（誤タップ防止）。 | AC-15 |
| **R-16** | **MEDIUM** | `ui/browser/HalfBrowserWebViewClient.kt:64-69` `shouldOverrideUrlLoading` | `http(s)` 以外のスキームは `handleSystemScheme` に委譲するが、**`about:`・`blob:`・`data:` 等の判定がない**。`data:`/`javascript:` が `openExternalApp` → `startExternalView(url)` に渡り、`Intent(ACTION_VIEW, Uri.parse(url))` を任意の VIEW 対応アプリに送る（`data:text/html` 等）。 | :205-213。`startExternalView` は `url` を検証せず（`UrlNormalizer` 未通過）。 | 外部委譲は `validateWebUrl` 通過の http(s) のみ。他スキームは拒否。 | AC-02, AC-46 |
| **R-17** | **MEDIUM** | `ui/home/HomeScreen.kt` ・ `ui/settings/SettingsScreen.kt`（未コミット分） | 未コミット差分が +2528 行あるが、**テストが一切追加されていない**（`app/src/test` は未変更）。UI 中心の大改修にもかかわらずロジック層の検証が無い。 | `git diff --stat` に `app/src/test` 無し。 | コミット前に L1 テストを追加。 | AC-19, AC-20, AC-34, AC-36 |
| **R-18** | **LOW** | `core/LocaleHelper.kt:26` | `Locale.setDefault(locale)` を呼んでプロセス全体の既定ロケールを変更（G-10 未対応）。`runCatching` で握りつぶし。 | 1 行で確認。 | `setDefault` を削除し `Configuration` のみで対応。 | AC-31 |
| **R-19** | **LOW** | `shortcuts/BookmarkShortcuts.kt`・`ui/home/HomeScreen.kt:617` ほか | Lint `MissingOnRenderProcessGone`（warning）と `SetJavaScriptEnabled`（warning）が出ているが、**実装は `onRenderProcessGone` を持つ**（HalfBrowserWebViewClient.kt:188-203）。lint の指摘は別ファイル（`WebViewClient` 以外の派生）に対するものの可能性。要確認。 | lint XML に該当 issue を含む。`onRenderProcessGone` の実装は確認済み。 | lint の該当箇所を特定し、誤検知なら suppress に理由を明記。 | AC-38 |
| **R-20** | **LOW** | `ui/browser/BrowserScreen.kt:645-651` | `ensureWebViewDebugging()` の `debuggingConfigured` が**プロセス全体の static 変数**。`setWebContentsDebuggingEnabled(BuildConfig.DEBUG)` は debug ビルドのみ true。release では false で問題なし。 | 挙動は正しい（P-5 維持）。ただし `companion` 外の mutable static は将来のテストで状態漏れを起こす。 | `object` 内に移す。 | AC-37 |
| **R-21** | **LOW** | `app/build.gradle.kts`・`gradle/libs.versions.toml` | 依存が古い（GradleDependency 7 件、NewerVersionAvailable 4 件）。AGP 警告 2 件。 | lint XML。 | 更新は別コミットで。 | AC-40 |
| **R-22** | **LOW** | `res/xml/locales_config.xml`・`AndroidManifest.xml:18` | `localeConfig` は API 33 以上でのみ使用（lint UnusedAttribute）。G-17 の `LocaleManager` 未実装のため、33+ で OS 側の per-app 言語設定と**二重管理**が続く。 | `grep LocaleManager` 0 件。 | G-17 対応。 | AC-31 |
| **R-23** | **LOW** | `ui/browser/HalfBrowserWebViewClient.kt:223` | `S.browser_fallback_url` を `uri.getQueryParameter` で取るが、`intent:` URL の `S.` 接頭辞のパース仕様は `Intent.parseUri` の内部処理と一致させる必要がある。取り違えると fallback が効かない（機能不全、セキュリティ影響なし）。 | 単体テスト無し。 | L1 テストで確認。 | AC-46 |
| **R-24** | **INFO** | `docs/IMPLEMENTATION-GAP.md` | 文書の状態記述が古い。G-01（"a" 推奨）と G-07（修正済み）が反映されていない。実装は部分対応（G-02/G-08/G-12 は対応済み、G-05/G-03/G-04 は未対応）。ギャップ文書を実装に合わせて更新すべき。 | 本監査の各項目と突き合わせ。 | 文書更新（コードは変更不要）。 | — |
| **R-25** | **INFO** | `data/CredentialStore.kt:63-64` | `find()` は https のみ返す（http 非保存、P-9/G-05.2 の方針は**実装済み**）。 | 良い点として §2 に記載。 | 維持。 | AC-48 |

### 1.1 重大度別件数

| 重大度 | 件数 | ID |
|---|---|---|
| CRITICAL | 0 | — |
| HIGH | 6 | R-01, R-02, R-03, R-04, R-05, R-06 |
| MEDIUM | 11 | R-07〜R-17 |
| LOW | 6 | R-18, R-19, R-20, R-21, R-22, R-23 |
| INFO | 2 | R-24, R-25 |
| **合計** | **25** | |

※ 元の IMPLEMENTATION-GAP の G-01（CRITICAL）は、本監査で **「intent 復元＋startActivity は残っているが component/selector を遮断している」** ため、CRITICAL から HIGH（R-01）に引き下げた。任意コンポーネント起動そのものは成立しない（`component == null` を強制）。ただし extras 経由の情報漏えい・任意 VIEW 起動の余地は残るため HIGH とした。

---

## 2. 良い点（維持すべき設計）

| # | 内容 | 根拠 |
|---|---|---|
| G1 | `allowFileAccess`/`allowContentAccess` を明示的に false | BrowserScreen.kt:624-625 |
| G2 | JS ブリッジ（`addJavascriptInterface`）が存在しない | grep 0 件 |
| G3 | 第三者 Cookie を明示的に無効化 | BrowserScreen.kt:543 |
| G4 | `safeBrowsingEnabled = true`（API 26+ 分岐） | BrowserScreen.kt:629-631 |
| G5 | デバッグ有効化は `BuildConfig.DEBUG` のみ | BrowserScreen.kt:650 |
| G6 | `intent:` は `component`/`selector`/非 VIEW を拒否（G-01 の危険要素を部分的に遮断） | HalfBrowserWebViewClient.kt:225-230 |
| G7 | `SslChallenge`/`AuthChallenge` の `settled` フラグで二重 proceed/cancel を防止 | BrowserState.kt:61-71, 87-97 |
| G8 | 認証情報は `scheme=https` のみ保存・取得（http 非保存）、キーに port を含む | CredentialStore.kt:63-64, 81 |
| G9 | 認証情報の保存ファイル `halfbrowser_secure` をバックアップ・転送から除外 | res/xml/backup_rules.xml, data_extraction_rules.xml |
| G10 | `isPersistent=false` 時に UI で警告表示（メモリのみを明示） | SettingsScreen.kt（`settings_security_not_persistent`） |
| G11 | `onRenderProcessGone` を実装し、WebView 再生成で復帰（G-08 対応済み） | HalfBrowserWebViewClient.kt:188-203, BrowserScreen.kt:286-296 |
| G12 | 「常に信頼」は fingerprint 不明時に無効化（ピンできないものを信頼させない） | BrowserDialogs.kt（`enabled = challenge.fingerprintSha256 != null`） |
| G13 | `TrustPolicy` のスコープは host + 完全一致 fingerprint（証明書更新で再警告） | core/TrustPolicy.kt |
| G14 | 外部ブラウザ起動は自パッケージを除外（自己再帰防止、G-12 対応済み） | HalfBrowserWebViewClient.kt:296 |
| G15 | 別ホストのサブリソース SSL エラーは自動 cancel（ダイアログ氾濫を防止） | HalfBrowserWebViewClient.kt:86-90 |
| G16 | `core/` は純粋 Kotlin（`LocaleHelper` を除き Android 非依存）・Codecs は decode 失敗で既定値 | core/*.kt, Codecs.kt |
| G17 | `RoleManager` 系は `Build.VERSION.SDK_INT >= Q` ガード＋`runCatching` で API 29 未満でも安全 | ui/components/DefaultBrowser.kt |
| G18 | 鍵ストア `release.jks` は未追跡・`.gitignore` 除外 | `.gitignore:10`, `git ls-files` 0 件 |
| G19 | ログに秘密値を出していない（Log は 2 行、いずれも固定文言） | CredentialStore.kt:58,139 |
| G20 | `WebChromeClient` で権限要求（カメラ・マイク等）を全拒否、`onCreateWindow` は false | HalfBrowserWebViewClient.kt:264-274 |

---

## 3. AC 判定（ACCEPTANCE_MATRIX の主要項目）

判定基準: **PASS** = コード・テスト・実機証跡のいずれかで確認済み。**FAIL** = 要件未達を確認。**PARTIAL** = 一部のみ実装または一部の検証が欠落。**NOT_TESTED** = 本監査で検証できず（自動化不能な L4 手動のみ、または実行していない）。

**注意**: `shots/` に実機スクショが存在するが、それが各 AC の合格条件を満たすかは個別に確認していない。スクショは「存在」のみを根拠とし、**スクショの存在だけで PASS とはしていない**。L4 の多くは監査時点で再実施していないため NOT_TESTED とした。

| AC | 判定 | 根拠・備考 |
|---|---|---|
| AC-01 URL 正規化 | PARTIAL | `UrlNormalizerTest` 15 件 PASS（実行確認）。L4 未確認 |
| AC-02 危険スキーム拒否（アドレスバー） | PASS | `UrlNormalizerTest` の拒否ケース PASS。ただし外部委譲経路は R-16 で FAIL 寄り |
| AC-02b core が Android 非依存 | PASS | `grep ^import android core/` 結果: `LocaleHelper` のみ（core/LocaleHelper.kt は core/ 内だが Android 依存。AC の意図からは要検討） |
| AC-03 壊れた永続化で起動不能にならない | PARTIAL | `CodecsTest` `corrupt settings fall back` PASS。bookmarks/trust の壊れた JSON も decode で既定値。L4 未確認 |
| AC-04 戻る/進む/リロード | NOT_TESTED | L4 未実施 |
| AC-05 Cookie/JS/DOM ストレージ | PARTIAL | 設定上は有効（BrowserScreen.kt:612-615）。実機未確認 |
| AC-06 target=_blank | NOT_TESTED | `onCreateWindow` は false（:274）。L4 未実施 |
| AC-07 進捗・タイトル・URL | NOT_TESTED | コード上は実装あり。L4 未実施 |
| AC-08 共有 | NOT_TESTED | `sharePage` 実装あり。L4 未実施 |
| AC-09 PC 版サイト | NOT_TESTED | `toggleDesktopMode` 実装あり。L4 未実施 |
| AC-10 独自インタースティシャル | PARTIAL | `SslInterstitialDialog` 実装あり。実機証跡 `shots/11-browser-ssl.png` 等が存在するが、本監査で合格条件を個別検証せず |
| AC-11 証明書情報 5 項目 | PARTIAL | ホスト/エラー/発行者/Subject/SHA-256 の表示コードあり（BrowserDialogs.kt）。フィンガープリント一致の検証は未実施 |
| AC-12 API 26–28 で取得 | **FAIL（未検証）** | R-06。API 26 実機/エミュレータ未確認。Bundle キーの根拠なし |
| AC-12b `proceed()` 保持の実測 | **NOT_TESTED** | 実施されていない（U-B）。R-03 の前提 |
| AC-12c 事後検証 | **FAIL** | R-03。`onPageFinished` 照合が無い |
| AC-13 ハンドラ漏れ・二重 | PARTIAL | `settled` で二重は防止（G7）。タイムアウト・Activity 破棄時の cancel は `DisposableEffect` で対応（BrowserScreen.kt:386-388）。L2 テスト無し |
| AC-14 今回だけは保存されない | PARTIAL | `onTrustOnce` は `proceed` のみ（pin しない）。コード上 PASS 相当。L4 未確認 |
| AC-15 常に信頼＋証明書変更で再警告 | PARTIAL | `decide` の `Changed` は `TrustPolicyTest` で PASS。Changed 承認の二段確認が無い（R-15） |
| AC-16 信頼一覧・失効 | PARTIAL | SettingsScreen に一覧・revoke あり。再アクセス時の再警告は未確認（AC-12b と連動） |
| AC-17 ピン不可条件 | **FAIL** | 「期限切れ・未開始・IDMISMATCH では常に信頼を無効化」の**条件分岐が無い**。`enabled = fingerprint != null` のみ。期限切れ証明書でもピン可能。上限 50 件の制限も無し。 |
| AC-18 データ削除で安全側に失効 | NOT_TESTED | L4 未実施 |
| AC-19 ブックマーク CRUD | PARTIAL | `BookmarkRepository` / 編集ダイアログ実装あり。`CodecsTest` 往復 PASS。L4 未確認 |
| AC-20 モノグラム決定性 | PASS | `BookmarkIconsTest` 6 件 PASS（同一 URL 同一色等）。ダーク時のコントラストは L4 未確認 |
| AC-21 500 件の速度 | NOT_TESTED | L4 未実施 |
| AC-22 Basic 認証ダイアログ | PARTIAL | `AuthPromptDialog` 実装あり。realm 表示の制御文字対策なし（R-14） |
| AC-23 資格情報の暗号化保存 | **NOT_TESTED（実装は確認）** | `EncryptedSharedPreferences` 使用は確認。L3 往復テスト無し。R-05, R-08 |
| AC-24 自動入力の無限ループ防止 | **FAIL** | R-02。成功後のリセットが無く、2 回失敗後に永続的に自動入力停止（UX 劣化、ループ自体は防止） |
| AC-25 Digest 認証 | NOT_TESTED | `onReceivedHttpAuthRequest` は Basic/Digest を区別しない。L4 未実施 |
| AC-26 Keystore 異常時 | **FAIL** | R-08, R-09。保存失敗が成功扱い・復号失敗が無通知 |
| AC-27 一覧・削除・ログ漏れ | PARTIAL | 一覧・削除 UI あり。ログ漏れは grep で無し（PASS）。realm 表示の制御文字対策なし |
| AC-28 ショートカット | NOT_TESTED | `BookmarkShortcuts` 実装あり。L4 未実施（U-D） |
| AC-29 外部 URL をブックマークモード | PARTIAL | `MainActivity.handleViewIntent` 実装あり。L4 未確認 |
| AC-29b ROLE_BROWSER | PARTIAL | `DefaultBrowser` 実装・API ガードあり（G17）。L4 未確認 |
| AC-30 halfbrowser:// / SEND / 再入 | **PARTIAL** | R-12（bookmarkId 未検証）。SEND は URL 抽出のみ（テキストのみの検索は未対応、G-13）。`IntentRouterTest` 無し |
| AC-31 言語切替・再起動保持 | **FAIL** | R-18（`setDefault`）, R-22（LocaleManager 無し）。API 33+ で OS 設定と二重管理 |
| AC-32 日英文字列の一致 | PASS | `LocaleParityTest` 4 件 PASS（キー集合・書式指定子・空値なし） |
| AC-33 ライト/ダーク/動的カラー | NOT_TESTED | テーマ実装あり。L4 未確認 |
| AC-34 UI スケール | NOT_TESTED | `AppScale` は 0.8–1.4 / 0.8–1.5 にクランプ（Scale.kt:24-25）。`ScalePolicyTest` 無し。L4 未実施 |
| AC-35 インセット・回転 | NOT_TESTED | `statusBarsPadding`/`navigationBarsPadding` 使用あり。L4 未実施 |
| AC-36 グリッド列数 | PARTIAL | `resolveColumns` 実装あり。`GridPolicyTest` 無し |
| AC-37 WebView 安全設定 | PARTIAL | 設定値は良好（G1–G5）。`configureWebView` の `runCatching` 一括握り潰しで順序依存の欠陥あり（R-13）。mixedContent は COMPATIBILITY（G-09 方針で承認済み） |
| AC-38 レンダラ復帰 | PARTIAL | `onRenderProcessGone` 実装あり（G11）。lint `MissingOnRenderProcessGone` の指摘（R-19）が未解消。L4 未実施 |
| AC-39 リーク無し | NOT_TESTED | `largeHeap`（R-04）。`destroy()` は `DisposableEffect` で呼ばれる（BrowserScreen.kt:389-396）。ヒープダンプ未実施 |
| AC-40 lint 0 errors | **PASS** | 実行結果: Error 0（Warning 59）。`lintDebug` BUILD SUCCESSFUL |
| AC-41 release APK 署名検証 | **NOT_TESTED** | `apksigner verify` 未実施。`release.jks` が存在するためビルド自体は可能性あり。ビルド未実行 |
| AC-41b 署名情報無しで release 失敗 | **NOT_TESTED** | R-10。環境変数未設定での `assembleRelease` 失敗を未確認 |
| AC-42 単体テスト全件成功 | **PASS** | 44 件 / 0 失敗 / 0 skip（実行結果） |
| AC-43 R8 後の動作 | NOT_TESTED | `isMinifyEnabled=true` 確認。release 動作確認未実施 |
| AC-44 エミュレータ QA マトリクス | NOT_TESTED | `docs/QA-CHECKLIST.md` の存在・結果未確認。スクショ `shots/` は一部のみ |
| AC-45 ドキュメント・ライセンス | NOT_TESTED | `README.md`/`LICENSE`/`docs/PRIVACY.md`/`docs/RELEASE.md` の存在を未確認（本監査では docs/ の一部のみ確認） |
| AC-46 intent:// 非起動 | **FAIL** | R-01（任意 VIEW 起動の余地・extras 制御なし）。`component` 遮断は有効 |
| AC-47 サブリソース SSL でページ破綻しない | **FAIL** | R-07（保留ハンドラの cancel 置換） |
| AC-48 平文 HTTP に資格情報を送らない | **PASS（コード上）** | G8。`find` と `save` が https のみ。`onReceivedHttpAuthRequest` は `allowAutoFill=false` で自動送信しない。L3 は未実施 |
| AC-49 外部ブラウザの自己再帰回避 | PASS（コード上） | G14。L4 未実施 |
| AC-50 同一ホスト複数証明書の往復 | **FAIL** | `TrustRepository.pin` は `filterNot { it.host == normalizedHost } + site`（data/TrustRepository.kt）＝ホストあたり 1 件。G-16 未対応。`:8844` と `:9443` の往復が発生 |
| AC-51 3 点メニュー 9 項目 | PARTIAL | `BrowserScreen.kt:180-268` で 9 項目（ブックマーク追加/編集・共有・URL コピー・再読込・PC 版・外部ブラウザ・設定・閉じる）が実装されている。L4 未実施 |

### 3.1 判定集計（主要 52 項目）

| 判定 | 件数 |
|---|---|
| PASS | 5（AC-02, AC-02b, AC-20, AC-32, AC-40, AC-42, AC-48, AC-49 のうち、コード・実行で確認できたもの） |
| FAIL | 9（AC-12, AC-12c, AC-17, AC-24, AC-26, AC-31, AC-46, AC-47, AC-50） |
| PARTIAL | 約 18 |
| NOT_TESTED | 約 20（主に L4 手動） |

**未検証を PASS にしていない**: AC-12b（実測なし）、AC-41/41b（ビルド・検証未実施）、L4 の全項目は NOT_TESTED とした。

---

## 4. 総合判定

### **REWORK**

**理由**:
1. **要件の根幹が未達**: AC-12（API 26–28 の指紋取得が未検証）、AC-12c（信頼失効の事後検証が無い）、AC-17（ピン不可条件が未実装）、AC-50（同一ホスト複数証明書で往復）が FAIL。「自己署名 HTTPS の信頼」は要件 2 の中核であり、ここが未達。
2. **セキュリティ上の既知 HIGH が未解消**: R-01（intent 復元＋任意 extras での VIEW 起動）、R-02（認証の自動入力制御の不備）。
3. **テストによる担保がない**: 中核ロジック（認証トラッカー・SSL 調停・スケール・グリッド・Intent ルータ）に単体テストが無く、未コミット差分 +2528 行がテストなしで入っている。
4. **未コミット状態**: 監査対象のリリース候補 SHA が存在しない。

**PASS にできる項目**: lint 0 errors（AC-40）、単体テスト 44 件全件成功（AC-42）、`LocaleParityTest`（AC-32）、`http` 資格情報非送信（AC-48）は、コードと実行結果で確認できた。

---

## 5. リリース前に必須の修正

| 優先 | ID | 作業 | 完了条件 |
|---|---|---|---|
| 1 | R-01 | `intent:` を起動しない（フォールバック URL のみ）。`extras` を空にする | AC-46 の L4 再現（`intent://…component=…`）で他アプリが起動しない |
| 2 | R-06 / AC-12 | API 26–28 の DER 取得を実機（API 26 エミュレータ）で確認し、取れない場合の UI 無効化を検証 | API 26 で指紋表示・「常に信頼」が機能する |
| 3 | R-03 / AC-12b / AC-12c | `onPageFinished` で証明書照合、AC-12b の 3 条件実測 | 信頼失効後に再警告が出る |
| 4 | R-02 / AC-24 | `authAttempts` を成功・ナビゲーションでリセット | 正しいパスワードで手動ログイン後に自動入力が復活 |
| 5 | AC-17 / AC-50 / G-16 | ピンの一意性を host+fingerprint に、期限切れ・IDMISMATCH で「常に信頼」を無効化 | 2 ポートの証明書往復で再警告が出ない |
| 6 | R-08 / R-09 / AC-26 | 認証情報保存の成否を UI に反映、復号失敗の通知 | 保存失敗時に「保存しました」が出ない |
| 7 | R-05 | L1 テスト（AuthAttemptTracker・SslTrustCoordinator・ScalePolicy・GridPolicy・IntentRouter・x509Of フォールバック・CredentialStore 往復） | テスト件数増加、全件成功 |
| 8 | AC-41b / R-10 | 署名情報無しで `assembleRelease` が失敗することを確認 | 失敗すること |
| 9 | R-04 | `largeHeap` の除去、`usesCleartextTraffic` の根拠をコメント化 | AC-39 のメモリ QA 通過 |
| 10 | 文書 | IMPLEMENTATION-GAP.md の状態を実装に合わせて更新（R-24） | 文書と実装の整合 |

---

## 6. 付録

### 6.1 確認したファイル

- docs: `PLAN.md`（※本監査では主要部のみ参照）, `ACCEPTANCE_MATRIX.md`, `IMPLEMENTATION-GAP.md`, `ADR/0002`, `ADR/0003`, `ADR/0005`（ADR 0002/0003/0005 は本監査では内容を直接読んでいない。判定は実装コードに基づく）
- manifest/build: `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`（diff で確認）
- 読了したソース: `ui/browser/HalfBrowserWebViewClient.kt`, `ui/browser/BrowserState.kt`, `ui/browser/BrowserScreen.kt`, `ui/browser/BrowserDialogs.kt`, `ui/settings/SettingsScreen.kt`, `ui/home/HomeScreen.kt`（先頭 400 行）, `ui/components/DefaultBrowser.kt`, `ui/Scale.kt`, `ui/HalfBrowserRoot.kt`, `data/CredentialStore.kt`, `data/TrustRepository.kt`, `data/SettingsRepository.kt`, `core/TrustPolicy.kt`, `core/UrlNormalizer.kt`, `core/Codecs.kt`, `core/Models.kt`, `core/DeepLinks.kt`, `core/LocaleHelper.kt`, `MainActivity.kt`, `AppContainer.kt`, `shortcuts/BookmarkShortcuts.kt`
- 未読（時間制約）: `HomeScreen.kt` の後半（BookmarkTile・EmptyState 等）、`BookmarkEditorDialog.kt`、`BookmarkRepository.kt`、`BookmarkIcons.kt`、`ui/theme/*`、`res/values*/strings.xml` の全文（件数・parity は `LocaleParityTest` で確認）、`docs/ADR/0001,0003,0005,0009,0010`、`docs/QA-CHECKLIST.md`（存在未確認）、`shots/` の画像（存在のみ確認）

### 6.2 grep 結果（要点）

- `addJavascriptInterface`: 0 件
- `parseUri`: `HalfBrowserWebViewClient.kt:224` のみ
- `startActivity`: SettingsScreen.kt:539, HomeScreen.kt:617, BrowserScreen.kt:697, HalfBrowserWebViewClient.kt:242/300, MainActivity 経由なし
- `Log.`: CredentialStore.kt:58, 139
- `Locale.setDefault`: LocaleHelper.kt:26
- `LocaleManager` / `setApplicationLocales`: 0 件
- `getX509Certificate` / `x509-certificate`: BrowserState.kt:111（API 29+ 分岐）, :115（Bundle キー）
- `onRenderProcessGone`: HalfBrowserWebViewClient.kt:188
- `onPageFinished` の証明書参照: 0 件
- `setWebContentsDebuggingEnabled`: BrowserScreen.kt:650（`BuildConfig.DEBUG` ガード付き）

### 6.3 テスト件数

- JUnit 単体テスト: **44 件 / 0 失敗 / 0 エラー / 0 skip**（`app/build/test-results/testDebugUnitTest`）
- instrumented テスト: **0 件**（基盤なし）

### 6.4 lint 件数

- Error: 0 / Warning: 59 / Hint: 1（`app/build/reports/lint-results-debug.xml`、2026-10-31 再実行）

### 6.5 監査の限界

- L4（エミュレータ・実機）の手動 QA は本監査で実施していない。`shots/` の既存スクショは存在を確認したのみで、各 AC の合格条件との照合はしていない。
- AC-12b（WebView の proceed 保持挙動）は実機が必要なため未検証。
- release APK の生成・`apksigner verify` は未実行（AC-41/41b）。
- 作業ツリーの未コミット変更を監査対象としたため、判定は **その時点の作業ツリー**に対するもの。コミット後に再監査が必要。
