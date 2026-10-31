# W-RESEARCH — Half-Browser プラットフォームAPI調査ノート

- worker: W-RESEARCH (research, read-only)
- parent: TO-PLAN-HB-01 / WP-PLAN-RESEARCH
- 作成: 2026-10-31 (Asia/Tokyo) / 参照日: 2026-10-31
- 対象: `com.yukiorita.halfbrowser` / minSdk 26 / targetSdk 36 / compileSdk 36.1 / Kotlin 2.2.10 / Compose M3
- 取得方法: `curl` で developer.android.com の HTML を取得し、本文テキストを抽出（`web_fetch` は devsite の巨大ナビで本文前に truncate されたため、同一 URL を curl で再取得した。FETCH LOG 参照）。

---

## FETCH LOG

`web_fetch` は developer.android.com のリファレンスページで **ナビゲーション部分のみ返して本文が truncate** された（HTTP 200 だが本文未取得）。以降は同一 URL を `curl -m 30` で取得し、ローカルで HTML→テキスト変換して本文を読んだ。HTTP ステータスは curl の実測値。

| # | URL | HTTP | 取得 | 取得した見出し / 備考 |
|---|-----|------|------|----------------------|
| 1 | https://developer.android.com/reference/android/webkit/WebViewClient?hl=en | 200 | ○(curl) / △(web_fetch: 本文truncate) | `onReceivedSslError`, `onReceivedHttpAuthRequest`, `onRenderProcessGone` |
| 2 | https://developer.android.com/reference/android/webkit/SslErrorHandler | 200 | △(web_fetch: 本文truncate) | — |
| 3 | https://developer.android.com/reference/android/net/http/SslCertificate?hl=en | 200 | ○(curl) | `getX509Certificate`, `saveState`, `restoreState` |
| 3b | https://developer.android.com/reference/android/webkit/SslCertificate | **404** | ✗ | 到達不可（正しいパッケージは `android.net.http`） |
| 4 | https://developer.android.com/reference/android/webkit/HttpAuthHandler | 200 | ○(curl) | `proceed`, `cancel`, `useHttpAuthUsernamePassword` |
| 5 | https://developer.android.com/reference/android/net/http/SslError?hl=en | 200 | ○(curl) | `getCertificate`, `getPrimaryError`, `getUrl`, `hasError` |
| 6 | https://developer.android.com/reference/android/webkit/WebViewDatabase?hl=en | 200 | ○(curl) | `clearHttpAuthUsernamePassword`, `hasHttpAuthUsernamePassword` |
| 7 | https://developer.android.com/reference/android/webkit/WebSettings?hl=en | 200 | ○(curl) | file/content access, mixed content, SafeBrowsing, JS/DOMStorage |
| 8 | https://developer.android.com/reference/android/webkit/CookieManager?hl=en | 200 | ○(curl) | `setAcceptCookie`, `setAcceptThirdPartyCookies` |
| 9 | https://developer.android.com/reference/android/app/role/RoleManager?hl=en | 200 | ○(curl) | `ROLE_BROWSER`, `createRequestRoleIntent`, `isRoleAvailable`, `isRoleHeld` |
| 10 | https://developer.android.com/reference/android/app/LocaleManager?hl=en | 200 | ○(curl) | `setApplicationLocales`, `getApplicationLocales` |
| 11 | https://developer.android.com/reference/androidx/core/content/pm/ShortcutManagerCompat?hl=en | 200 | ○(curl) | `requestPinShortcut`, `isRequestPinShortcutSupported`, `pushDynamicShortcut` |
| 12 | https://developer.android.com/reference/androidx/core/content/pm/ShortcutInfoCompat?hl=en | 200 | △ | クラス概要のみ（Builder の setter 詳細は別ページ、未取得） |
| 13 | https://developer.android.com/reference/android/content/pm/ShortcutManager?hl=en | 200 | ○(curl) | `getMaxShortcutCountPerActivity`, `requestPinShortcut`, `isRequestPinShortcutSupported` |
| 14 | https://developer.android.com/reference/androidx/compose/material3/windowsizeclass/WindowSizeClass?hl=en | 200 | ○(curl) | `calculateFromSize`, `widthSizeClass`/`heightSizeClass` |
| 14b | https://developer.android.com/reference/androidx/compose/material3/windowsizeclass/WindowSizeClass | **404** | ✗ | Java パスは存在せず、`/reference/kotlin/...` のみ |
| 15 | https://developer.android.com/reference/kotlin/androidx/compose/ui/unit/Density?hl=en | 200 | ○(curl) | `LocalDensity`, `fontScale`, `Dp.toPx` 等 |
| 15b | https://developer.android.com/reference/androidx/compose/ui/unit/Density | **404** | ✗ | 同上（kotlin パスのみ） |
| 16 | https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader?hl=en | 200 | ○(curl) | `appassets.androidplatform.net`, `shouldInterceptRequest` |
| 17 | https://developer.android.com/reference/androidx/core/view/WindowInsetsCompat?hl=en | 200 | △ | 取得のみ・本文精読せず（§6 で「未検証」） |
| 18 | https://developer.android.com/guide/topics/resources/app-languages?hl=en | 200 | ○(curl) | Per-app language preferences / `autoStoreLocales` / `generateLocaleConfig` |
| 19 | https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes?hl=en | 200 | △ | 取得のみ・本文精読せず（境界値は未検証） |
| 20 | https://developer.android.com/develop/ui/views/launch/shortcuts/creating-shortcuts | **301** | ✗ | リダイレクト（追跡せず・未取得） |

---

## 1. WebView SSL エラー処理と TOFU ピン留め

STATUS: DONE（一部 未検証）

### 1.1 コールバックの発火条件と意味

- 主張: `WebViewClient.onReceivedSslError(WebView view, SslErrorHandler handler, SslError error)` は API 8 から存在し、「SSL エラーが発生した際にホストアプリに通知する」。
  - 出典: https://developer.android.com/reference/android/webkit/WebViewClient?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: ページ更新日 2026-08-03 UTC。シグネチャは Java 表記。Kotlin では `override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError)`。

- 主張: ホストアプリは `cancel()` か `proceed()` の**どちらかを必ず呼ばなければならない**（呼ばないとロードが宙吊りになる）。既定の挙動は「cancel」。
  - 出典: https://developer.android.com/reference/android/webkit/WebViewClient?hl=en （"The host application must call either SslErrorHandler.cancel() or SslErrorHandler.proceed()." / "The default behavior is to cancel the resource loading process."）
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: コールバック内で即 return すると handler 未処理になり、そのリソースのロードが止まったままになる。

- 主張: 公式ドキュメントは「**`cancel()` を常に呼び、エラーを越えて proceed してはならない**」「**SSL エラーについてユーザーにプロンプトを出してはならない**（ユーザーは十分な情報に基づく判断ができず、WebView はエラー詳細を意味のある形で表示する UI を提供しない）」と明記している。
  - 出典: https://developer.android.com/reference/android/webkit/WebViewClient?hl=en （Warning / Note 段落）
  - 参照日: 2026-10-31
  - 確信度: 確定（文言として）／「では TOFU インタースティシャルは設計として不可か」は**未検証**（公式は明示的に非推奨。§8-1 参照）
  - 補足/落とし穴: 本プロジェクトの (a) 自己署名 HTTPS + TOFU は、この公式警告と正面から衝突する。**「公式が非推奨と明記している領域に踏み込む」ことを設計判断として記録する必要がある**（実装可否ではなく、リスク受容の判断）。この警告は Google Play ポリシーではなく API リファレンスの注意書きであり、技術的には proceed 可能。

- 主張: **`proceed()`/`cancel()` の判断は「将来の SSL エラーへの応答を容易にするために保持（retained）されうる」**と明記されている。
  - 出典: https://developer.android.com/reference/android/webkit/WebViewClient?hl=en （"The decision to call proceed() or cancel() may be retained to facilitate responses to future SSL errors."）
  - 参照日: 2026-10-31
  - 確信度: 確定（文言）／保持の粒度（ホスト単位か WebView インスタンス単位か、期限はあるか）は**未検証**
  - 補足/落とし穴: **これが TOFU 設計の最大の落とし穴**。タスク前提の「判断が永続化されない」は公式文言と一致しない。一度 `proceed()` すると、同じホストの以降のエラーでコールバックが呼ばれず素通りする可能性がある。→ ピン留めの「確認した」記録と WebView 側の保持状態が二重管理になり、**インタースティシャルが出ないのに接続は通る**状態を想定した設計・テストが必要。対策候補（提案）: ①ピン未登録ホストでは毎回 `cancel()` してから自前画面で再ロードする、②WebView インスタンスを作り直す、③そもそも `proceed()` を初回のみに限定せず「毎回 cancel → 自前ダイアログ → 承認時のみ同じ URL を再ロード」する。**実際の保持挙動は実機検証が必要（未検証）**。

- 主張: このコールバックは**回復可能（recoverable）な SSL 証明書エラーのみ**に対して呼ばれる。回復不能なエラー（サーバーがクライアントを拒否した場合など）では `onReceivedError(WebView, WebResourceRequest, WebResourceError)` が `ERROR_FAILED_SSL_HANDSHAKE` 付きで呼ばれる。
  - 出典: https://developer.android.com/reference/android/webkit/WebViewClient?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: 「SSL エラーは全部 onReceivedSslError に来る」という前提は誤り。ハンドシェイク失敗系は `onReceivedError` 側で拾う必要があり、**エラー UI を 2 箇所に実装**することになる。

- 主張: サブリソース（画像・iframe 等）でも呼ばれるかは、リファレンスに「while loading a resource」とあるのみで**明示がない**。
  - 出典: https://developer.android.com/reference/android/webkit/WebViewClient?hl=en
  - 参照日: 2026-10-31
  - 確信度: 未検証（**推定**: メインフレームに限らずサブリソースでも発火する。実機で要確認）
  - 補足/落とし穴: サブリソースでも発火するなら、1 ページのロードで**同じホストのインタースティシャルが複数回**要求されうる。→ ホスト単位のデデュープ（同一ホストの処理中フラグ）と、`proceed()` を呼ぶまで他のリクエストが待つ設計が必要。

### 1.2 証明書（X509）の取り出し

- 主張: `SslError.getCertificate()` は `SslCertificate` を返す。`SslError` には他に `getPrimaryError()`（最も severe なエラー）、`getUrl()`、`hasError(int)` がある。
  - 出典: https://developer.android.com/reference/android/net/http/SslError?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: `hasError()` で `SslError.SSL_UNTRUSTED` / `SSL_EXPIRED` / `SSL_IDMISMATCH` / `SSL_NOTYETVALID` / `SSL_DATE_INVALID` / `SSL_INVALID` を個別判定できる。**自己署名なら通常 `SSL_UNTRUSTED`**（推定）。「期限切れ」と「未知の CA」を区別してメッセージを出すのに使える。

- 主張: `SslCertificate.getX509Certificate()` は **API 29 で追加**。返り値は `X509Certificate`、証明書が無ければ `null`。
  - 出典: https://developer.android.com/reference/android/net/http/SslCertificate?hl=en （"getX509Certificate / Added in API level 29 / public X509Certificate getX509Certificate()"）
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: **minSdk 26 なので API 26〜28 では使えない**。`Build.VERSION.SDK_INT >= 29` 分岐が必須。

- 主張: API 26〜28 向けの公式手段は `SslCertificate.saveState(SslCertificate)` → `Bundle` / `SslCertificate.restoreState(Bundle)`（いずれも API 1）。`saveState` は失敗時 `null` を返しうる。
  - 出典: https://developer.android.com/reference/android/net/http/SslCertificate?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定（API の存在と戻り値）／**Bundle のキー名 `"x509-certificate"` とバイト列形式は 未検証**（AOSP `SslCertificate.java` 未取得）
  - 補足/落とし穴: キー名は公式リファレンスに記載がない**実装詳細**。AOSP 由来の知識に依存するため、**キー名をハードコードせず `restoreState` 経由で `X509Certificate` を得る**か、`getEncoded()` 相当の DER バイト列を `Bundle.getByteArray("x509-certificate")` で読むなら**実機で存在確認するテストを書く**こと。キー名が変わると静かに `null` になり、ピン照合が常に「不明」になる事故になる。

- 主張: SHA-256 フィンガープリントは `java.security.MessageDigest.getInstance("SHA-256")` に `X509Certificate.getEncoded()`（DER）を渡して算出する、が標準的な手段。
  - 出典: 一次情報の明示記述は**未取得**（`java.security.MessageDigest` はプラットフォーム標準 API）
  - 参照日: 2026-10-31
  - 確信度: 推定（API の存在は確定、Half-Browser での採用レシピとしての裏取りは未検証）
  - 補足/落とし穴: `getEncoded()` は `CertificateEncodingException` を投げうる。**証明書の DER は再発行で変わる**ため、ピンは「証明書の SHA-256」か「公開鍵（SPKI）の SHA-256」かを先に決める必要がある。自己署名＋TOFU なら**公開鍵ピン**の方が更新に強い（提案。決定は TO-PLAN）。

- 主張: `onReceivedClientCertRequest` は SSL サーバー証明書のエラー通知とは**別物**（クライアント証明書＝mTLS をサーバーが要求した時のコールバック）。
  - 出典: 一次情報を本次調査では取得できず
  - 参照日: 2026-10-31
  - 確信度: 未検証（**推定**: 別コールバックであり、本プロジェクトの (a) とは無関係。ハンドラの `proceed(PrivateKey, X509Certificate[])` / `cancel()` / `ignore()` という構成も未確認）
  - 補足/落とし穴: 混同すると「自己署名対応のつもりでクライアント証明書ハンドラを実装する」事故になる。**本次調査では未検証**なので、実装時にリファレンスで確認すること。

---

## 2. WebView HTTP 認証（Basic/Digest）

STATUS: DONE（Digest 差分と handler 再利用は 未検証）

- 主張: `WebViewClient.onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm)` は API 1。既定の挙動は**リクエストをキャンセル**。渡された `HttpAuthHandler` は **UI スレッドで使わなければならない**。
  - 出典: https://developer.android.com/reference/android/webkit/WebViewClient?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: `host` と `realm` が引数で来るので、保存キーは `(host, realm)` のペアにする（`realm` はサーバー側で自由に変えられるため、キーに realm を含めないと別領域の資格情報が混ざる）。

- 主張: `HttpAuthHandler.proceed(String username, String password)` の戻り値は **`void`（boolean ではない）**。`cancel()` も `void`。どちらも「ホストアプリの UI スレッドで呼ぶこと」が Note で明記。
  - 出典: https://developer.android.com/reference/android/webkit/HttpAuthHandler?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: **成功/失敗は戻り値で分からない**。資格情報が誤っていればサーバーが再度 401 を返し、`onReceivedHttpAuthRequest` が**再度呼ばれる**。これが自動入力ループの原機構。ループ防止はアプリ側の責務。

- 主張: `HttpAuthHandler.useHttpAuthUsernamePassword()` は **boolean** を返し、「現在のホスト（`onReceivedHttpAuthRequest` が呼ばれたホスト）に対して保存済みの資格情報が**使用に適しているか**」を示す。**「現在のリクエストに対してサーバーに以前拒否された」場合は不適（false）**と定義されている。
  - 出典: https://developer.android.com/reference/android/webkit/HttpAuthHandler?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: **これがループ防止の正しいフック**。「保存済み資格情報があるから自動で proceed」を無条件にやると、拒否済みでも再送し続けて無限ループ＋アカウントロックを招く。`useHttpAuthUsernamePassword() == true` の時だけ自動入力し、false なら**必ずユーザー入力UIを出す**設計にする。

- 主張: 保存済み資格情報は `WebViewClient.onReceivedHttpAuthRequest` の間に **WebViewDatabase から自動では読まれない**。「アプリが読むかどうかはアプリ次第」。
  - 出典: https://developer.android.com/reference/android/webkit/WebViewDatabase?hl=en （`clearHttpAuthUsernamePassword` の説明）
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: つまり **`WebView.getHttpAuthUsernamePassword(host, realm)` / `WebViewDatabase.getHttpAuthUsernamePassword(host, realm)` を自分で呼ぶ**か、EncryptedSharedPreferences から自分で引くか。後者（自前保存）を選ぶなら WebViewDatabase は使わない、と割り切るのが単純（提案）。

- 主張: `WebViewDatabase.clearHttpAuthUsernamePassword()` は **WebViewDatabase 内の保存分しか消さない**。HTTP 認証に使われた資格情報は**ネットワークスタック側にもキャッシュされうる**ため、このメソッドでは消えない。WebView はクライアント側ログアウトの仕組みを提供せず、ログアウトは**サイト側の設計（401 を返して無効化する等）で実装すべき**。
  - 出典: https://developer.android.com/reference/android/webkit/WebViewDatabase?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: 「保存を削除したのに認証が通る」というバグ報告になりやすい。**削除UIの説明文にこの限界を書く**か、削除後に WebView を作り直す/`clearCache` を併用する（効果は未検証）。

- 主張: `WebViewDatabase.hasHttpAuthUsernamePassword()`（API 1）は保存済み HTTP 認証資格情報の有無を返す。
  - 出典: https://developer.android.com/reference/android/webkit/WebViewDatabase?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: 「いずれかのホストに」保存があるかしか分からない（ホスト単位ではない）。UI の「保存済みを全部消す」導線程度にしか使えない。

- 主張: Basic と Digest の扱い差、および `HttpAuthHandler` インスタンスの再利用可否は、リファレンスに**明示がない**。
  - 出典: https://developer.android.com/reference/android/webkit/HttpAuthHandler?hl=en （該当記述なし）
  - 参照日: 2026-10-31
  - 確信度: 未検証（**推定**: handler はコールバックごとの一回性オブジェクトで、保持して後から呼ぶのは不正。非同期に使う場合は「コールバックを抜ける前に握っておき、UI 応答後に UI スレッドで呼ぶ」形になり、ダイアログ表示中にページ遷移すると handler が無効化されるリスクがある）
  - 補足/落とし穴: **非同期利用（ダイアログ）前提の設計では、キャンセル・画面回転・ページ遷移時の handler 失効を必ず考慮**する。失効した handler に proceed するとクラッシュまたは無視される（挙動未検証）。リクエストの同一性は `(host, realm)` ＋ 自前のシーケンス番号で管理するのが安全（提案）。

---

## 3. ShortcutManagerCompat（ホーム画面ショートカット）

STATUS: DONE（アイコン要件・8.0/12/13 差分・バックアップは 未検証）

- 主張: `ShortcutManagerCompat.requestPinShortcut(Context context, ShortcutInfoCompat shortcut, IntentSender callback)` は **boolean** を返し、「ランチャーがこの機能をサポートしていれば true」。`callback` は null 可で、ピン留めされた時に送られる `IntentSender`。
  - 出典: https://developer.android.com/reference/androidx/core/content/pm/ShortcutManagerCompat?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: 戻り値 true は「**要求を送れた**」であって「**ピン留めされた**」ではない。確定通知は `IntentSender`（`ShortcutManagerCompat.createShortcutResultIntent` 系）で受ける。ユーザーが拒否した場合は**何も返らない**（下記）。

- 主張: プラットフォーム `ShortcutManager.requestPinShortcut(ShortcutInfo, IntentSender)` は API 26。既定ランチャーが要求を受け取り**ユーザーに承認を求める**。承認されればショートカットが作られ `resultIntent` が送られる。**拒否された場合は呼び出し元に何も送られない**。
  - 出典: https://developer.android.com/reference/android/content/pm/ShortcutManager?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: 「拒否」と「まだ処理中」と「ランチャーが黙って捨てた」が**区別できない**。UI は「追加を要求しました」で止め、**確定的な成功表示をしない**のが正しい。確認ダイアログは**ユーザー操作必須**で、アプリから自動承認はできない。

- 主張: `requestPinShortcut` は**フォアグラウンドの Activity またはフォアグラウンドサービスを持つアプリのみ**呼べる。そうでなければ `IllegalStateException`。また「数秒かかることがあるのでワーカースレッドから呼ぶべき」。
  - 出典: https://developer.android.com/reference/android/content/pm/ShortcutManager?hl=en （"Only apps with a foreground activity or a foreground service can call this method. Otherwise, it'll throw IllegalStateException." / "This method may take several seconds to complete, so it should only be called from a worker thread."）
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: **メインスレッドで呼ぶと ANR リスク**、バックグラウンド（Worker/Receiver）から呼ぶと例外。→ 「ユーザーがブックマーク長押し → 画面表示中に IO ディスパッチャで実行」が正しい形。

- 主張: `ShortcutManagerCompat.isRequestPinShortcutSupported(Context)` は boolean。プラットフォーム版は「既定ランチャーがピン留めをサポートしない場合」または「**App Lock が有効な場合**」に false を返し、**`onResume()` 等で再照会すべき**。既定ランチャーが変われば値も変わりうる。
  - 出典: https://developer.android.com/reference/androidx/core/content/pm/ShortcutManagerCompat?hl=en ／ https://developer.android.com/reference/android/content/pm/ShortcutManager?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: 起動時に 1 回だけ判定して Boolean をキャッシュすると、ランチャー変更後に**押しても何も起きないボタン**が残る。→ メニュー表示のたび（または onResume）に再評価。

- 主張: `getMaxShortcutCountPerActivity(Context)` は「各ランチャーアイコンが同時に持てる **static と dynamic** ショートカットの最大数」を返す。プラットフォーム版も同文言。
  - 出典: https://developer.android.com/reference/androidx/core/content/pm/ShortcutManagerCompat?hl=en ／ https://developer.android.com/reference/android/content/pm/ShortcutManager?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定（文言）／「**ピン留め（pinned）はこの上限に含まれない**」は文言からの**推定**
  - 補足/落とし穴: 文言が "static and dynamic" に対しピン留めを挙げていないため、**ピン留めは別枠**と読める（推定）。ただし「pin 済み shortcut が dynamic と同一 ID だと dynamic 側が更新される」挙動があるため、**上限計算と同一性判定は別物**として扱う。実機で上限到達テストを推奨。

- 主張: `setDynamicShortcuts(Context, List<ShortcutInfoCompat>)` は boolean を返し、**rate-limited**（false になりうる）。上限超過や不変ショートカットの更新では `IllegalArgumentException`、ユーザーロック中は `IllegalStateException`。既存 dynamic は全置換、同一 ID の pin 済み mutable は更新される。
  - 出典: https://developer.android.com/reference/androidx/core/content/pm/ShortcutManagerCompat?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: **レート制限**は「ブックマークを編集するたびに同期」するとすぐ踏む。→ 変更をデバウンスしてまとめて 1 回呼ぶ。

- 主張: `pushDynamicShortcut(Context, ShortcutInfoCompat)` は 1.5.0 追加で boolean。同一 ID の dynamic/pinned があれば**mutable なものを更新**。上限に達している場合、**最も rank の低い既存ショートカットを削除して場所を空ける**。rank 未設定なら 0 になり**リスト先頭に追加**される。API 25〜29 では addDynamicShortcuts/removeDynamicShortcuts によるシミュレーションのため rate-limit される。
  - 出典: https://developer.android.com/reference/androidx/core/content/pm/ShortcutManagerCompat?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: 便利だが**既存ショートカットを黙って追い出す**。ユーザーが自分でピン留めしたものを消さないためにも、Half-Browser の「ホームグリッドのブックマーク」と「OS ショートカット」は**別概念として管理**し、push は使わず明示的な `setDynamicShortcuts`＋明示 rank の方が事故が少ない（提案）。

- 主張: 削除・無効化系: `removeDynamicShortcuts(Context, List<String>)`、`removeAllDynamicShortcuts(Context)`、`removeLongLivedShortcuts(Context, List<String>)`、`disableShortcuts(Context, List<String>, CharSequence? disabledMessage)`（無効時はユーザーに独自メッセージ表示）、`enableShortcuts(Context, List<ShortcutInfoCompat>)`。
  - 出典: https://developer.android.com/reference/androidx/core/content/pm/ShortcutManagerCompat?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: **ピン留め（pinned/long-lived）はアプリから勝手に消せない**のが原則で、`removeLongLivedShortcuts` は「長命ショートカット」用の限定的な API。ブックマーク削除時にピン留めを消そうとしても消えないケースがあるため、**「削除してもホームのアイコンは残り、タップすると無効メッセージ」**を仕様として許容するか、`disableShortcuts` で無効化する設計にする。

- 主張: アイコンの要件（Bitmap か AdaptiveIcon か）、Android 8.0/12/13 の挙動差、バックアップ/復元時の注意は、本次調査では**一次情報を取得できていない**。
  - 出典: （`creating-shortcuts` ガイドは HTTP 301 で未取得）
  - 参照日: 2026-10-31
  - 確信度: 未検証
  - 補足/落とし穴: 一般に「ピン留めショートカットはランチャーが描画するため **リソース ID のアイコン（adaptive icon）が要求され、Bitmap だとランチャー側で失敗しうる**」と言われるが**未検証**。モノグラムアイコン (c) を Bitmap 生成で作る設計の場合、**ここが最大の地雷**になりうる。→ TO-PLAN は「アイコンをリソース化できるか（文字ごとに多数の drawable は非現実的）／ピン留めを諦めて dynamic shortcut のみにするか」を判断する必要がある。

---

## 4. RoleManager と既定ブラウザロール

STATUS: PARTIAL（manifest 条件・OS の強制転送不可は 未検証）

- 主張: `RoleManager` は **API 29（Android 10）** で追加。`isRoleAvailable(String roleName)` → boolean、`isRoleHeld(String roleName)` → boolean、`createRequestRoleIntent(String roleName)` → `Intent`（非 null）はいずれも API 29。
  - 出典: https://developer.android.com/reference/android/app/role/RoleManager?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: **minSdk 26 なので 26〜28 では RoleManager 自体が存在しない**。`SDK_INT >= 29` ガードが必須。26〜28 は「既定ブラウザ」の概念がなく、`ACTION_VIEW` の Intent チューザーで都度選ばれる（推定）。

- 主張: `createRequestRoleIntent` が返す Intent は `Activity.startActivityForResult(Intent, int)` に渡す。**ロールが付与されれば `resultCode == Activity.RESULT_OK`、そうでなければ `RESULT_CANCELED`**。
  - 出典: https://developer.android.com/reference/android/app/role/RoleManager?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: `startActivityForResult` は非推奨だが、**この API は resultCode ベースなので `ActivityResultContracts.StartActivityForResult` で受ける**のが現行流儀（`result.resultCode` を見る）。`RESULT_CANCELED` は「拒否」と「ダイアログを閉じた」の両方を含むため、**必ず `isRoleHeld()` で最終確認**する。

- 主張: ロールを保持するとロール固有の特権が与えられ、**ロールを失うとその特権も revoke される**。
  - 出典: https://developer.android.com/reference/android/app/role/RoleManager?hl=en （"Upon becoming a role holder, the application may be granted certain privileges that are role specific. When the application loses its role, these privileges will also be revoked."）
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: 常に `isRoleHeld()` を真とする状態をキャッシュしない。**起動時・復帰時に再評価**し、失ったら「既定ブラウザではない」UI に落とす。

- 主張: `ROLE_BROWSER` は `String` 型の定数で「ブラウザロールの名前」。定数の実値・および `ROLE_BROWSER` を要求するために manifest で満たす必要のある条件（`ACTION_VIEW` で `http`/`https` を処理し `CATEGORY_DEFAULT`/`CATEGORY_BROWSABLE` を持つ等）は、本次調査では**一次情報を取得できていない**。
  - 出典: https://developer.android.com/reference/android/app/role/RoleManager?hl=en （定数一覧のみ確認）
  - 参照日: 2026-10-31
  - 確信度: 未検証（**推定**: 実値は `"android.app.role.BROWSER"`。manifest 条件は上記の intent-filter）
  - 補足/落とし穴: **定数は `RoleManager.ROLE_BROWSER` を参照し、文字列リテラルを書かない**（推定値のハードコード回避）。manifest 条件が満たされていないと `createRequestRoleIntent` の画面に自アプリが出ない、あるいは要求が無効になる可能性がある（未検証）。

- 主張: 「OS が他ブラウザへの強制転送を許さない」点（外部 VIEW intent を横取りして別ブラウザへ投げる等の制限）は、本次調査では**一次情報を取得できていない**。
  - 出典: なし（未取得）
  - 参照日: 2026-10-31
  - 確信度: 未検証
  - 補足/落とし穴: 既定ブラウザになった場合、`ACTION_VIEW` は**自分に来る**。ここで「別のブラウザで開く」を実装しようとしても、OS のロール設計上**ユーザーの明示操作なしの転送は想定されていない**（推定）。TO-PLAN は (d)「外部 VIEW intent と既定ブラウザロール」のスコープを「自分が受ける／他アプリへ明示的に投げる手段は提供しない」と定義するのが安全（提案）。

---

## 5. WebView セキュリティ設定

STATUS: DONE（一部デフォルト値は 推定）

- 主張: `setAllowFileAccess(boolean)` — 既定値は **targetSdk が Q(29) 以下なら true、R(30) 以上なら false**。公式は「外部由来の URL を開くなら有効にしない」「`file://` ではなく `androidx.webkit.WebViewAssetLoader` を使う」「Q 以下をターゲットする場合は明示的に false にせよ」と明記。なお `file:///android_asset` と `file:///android_res` は**この設定では制御されず引き続きアクセス可能**。
  - 出典: https://developer.android.com/reference/android/webkit/WebSettings?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: targetSdk 36 なので既定は false だが、**明示的に false を書く**べき（意図の記録）。また「false にしたから asset も読めない」は誤り。

- 主張: `setAllowContentAccess(boolean)` — 既定は**有効**（content:// プロバイダからの読み込みを許可）。
  - 出典: https://developer.android.com/reference/android/webkit/WebSettings?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: ブラウザとして任意 URL を開くなら **false 推奨**（content:// 経由でローカルデータを読ませる攻撃面を閉じる）。ただしユーザーが content:// を開くユースケースは失われる。

- 主張: `setAllowFileAccessFromFileURLs(boolean)` / `setAllowUniversalAccessFromFileURLs(boolean)` は API 16 追加、**API 30 で非推奨**（"This setting is not secure, please use androidx.webkit.WebViewAssetLoader to load file content securely."）。
  - 出典: https://developer.android.com/reference/android/webkit/WebSettings?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: 非推奨＝無効ではなく**まだ動く**。ブラウザアプリでは**両方 false**（既定も false: targetSdk 16 以上で false、という記述が定番だが本ページの該当文は今回抽出できず→既定値は**推定**）。true にすると `file://` 上のスクリプトが任意のローカルファイルを読める。

- 主張: `setMixedContentMode(int)` — `KITKAT` 以下ターゲットは既定 `MIXED_CONTENT_ALWAYS_ALLOW`、`LOLLIPOP` 以上ターゲットは既定 `MIXED_CONTENT_NEVER_ALLOW`。**最も安全なのは NEVER_ALLOW**、ALWAYS_ALLOW は強く非推奨。
  - 出典: https://developer.android.com/reference/android/webkit/WebSettings?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: targetSdk 36 なら既定で NEVER_ALLOW。**COMPATIBILITY_MODE は「ブロックすると壊れるサイト」用の妥協**であり、ブラウザでは NEVER_ALLOW を既定にしつつサイト単位で緩める設計は複雑（提案: NEVER_ALLOW 固定）。

- 主張: `setSafeBrowsingEnabled(boolean)` は **API 26 追加**、対応端末では**既定で有効**。manifest のタグでも全体無効化できるが、**manifest タグよりこの API の方が優先度が高い**。
  - 出典: https://developer.android.com/reference/android/webkit/WebSettings?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: ブラウザアプリなら**有効のまま**。`onSafeBrowsingHit` を実装すれば独自インタースティシャルに差し替え可能（`SafeBrowsingResponse.backToSafety` / `proceed`）。※`onSafeBrowsingHit` は API 27。

- 主張: `setJavaScriptEnabled` の既定は **false**、`setDomStorageEnabled` の既定も **false**。`setSupportMultipleWindows` の既定は **false** で、**true にする場合は `WebChromeClient.onCreateWindow` の実装が必須**（false の時は `window.open()` や `target="_blank"` が同一 WebView で開かれる扱いになる）。
  - 出典: https://developer.android.com/reference/android/webkit/WebSettings?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: ブラウザとしては JS/DOMStorage を true にするが、**`setSupportMultipleWindows(true)` ＋ `onCreateWindow` 未実装だと新しいウィンドウが開かない**（タブ機能を作るなら必須）。`setSupportMultipleWindows(false)` のままでも `target="_blank"` は動くので、**まず false で出荷し、タブ実装時に true 化**が安全（提案）。

- 主張: `CookieManager.setAcceptCookie(boolean)` と `setAcceptThirdPartyCookies(WebView, boolean)` が存在する（後者は WebView インスタンス単位）。
  - 出典: https://developer.android.com/reference/android/webkit/CookieManager?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定（シグネチャ）／既定値は**未検証**
  - 補足/落とし穴: 第三者 Cookie は**ブラウザとしては既定許可が期待される**が、プライバシー設定として露出させるなら**インスタンス単位**である点に注意（WebView ごとに設定が必要で、タブを作るたびに適用）。

- 主張: `androidx.webkit.WebViewAssetLoader` は「ローカルの assets/resources を `http(s)://` URL で WebView に読ませる」ヘルパー。**Same-Origin ポリシーと互換**になる点が `file://` に対する利点。`shouldInterceptRequest` 内で使う前提で、**このコールバックはメインスレッドではない**（インスタンス自体はスレッドセーフ）。ローカルファイルは**自組織が所有するドメイン**か、既定の **`appassets.androidplatform.net`** にのみホストすべき。
  - 出典: https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: 実例では併せて `setAllowFileAccess(false)` 等を切っている（"Setting this off for security"）。Half-Browser のホーム画面を HTML で作る場合、`https://appassets.androidplatform.net/...` を使えば**同一オリジンで fetch が通る**。ただし `shouldInterceptRequest` は**非メインスレッド**なので、共有状態（ブックマークDB等）に触るならスレッド安全に。

- 主張: 補助的に、`setGeolocationEnabled` の既定は **true**（ただし位置情報 API の利用には権限と `WebChromeClient` の実装が別途必要）。
  - 出典: https://developer.android.com/reference/android/webkit/WebSettings?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: 「既定 true」なので**明示的に false** にしないと、権限を要求しないアプリでも挙動が読めない。ブラウザとして位置情報を許すなら権限フローとセットで設計。

### 5.x ブラウザアプリとしての推奨 / 危険な構成（提案・TO-PLAN 決定用）

| 設定 | 推奨 | 危険 |
|---|---|---|
| `allowFileAccess` | false 明示 | true（外部由来 URL を開くため） |
| `allowContentAccess` | false | true |
| `allowFileAccessFromFileURLs` | false | true |
| `allowUniversalAccessFromFileURLs` | false | true |
| `mixedContentMode` | NEVER_ALLOW | ALWAYS_ALLOW |
| `safeBrowsingEnabled` | true | false |
| `javaScriptEnabled` | true（ブラウザとして必須） | — |
| `domStorageEnabled` | true | false（多くのサイトが壊れる） |
| `supportMultipleWindows` | タブ実装まで false | true＋`onCreateWindow` 未実装 |
| `acceptThirdPartyCookies` | 設定で露出・既定は要判断 | 無条件 true |
| ローカルコンテンツ | WebViewAssetLoader | `file://` + UniversalAccess |

（この表は §5 の各主張の帰結としての**提案**であり、確定事項ではない。）

---

## 6. Compose 適応レイアウト・UIスケール・インセット

STATUS: PARTIAL（境界値・WindowInfoTracker・enableEdgeToEdge は 未検証）

- 主張: `androidx.compose.ui.platform.LocalDensity` は「画面の density。pixels / Dp / Int / TextUnit の変換に使う」。`Density` は `FontScaling` を継承し、**`fontScale`（"Current user preference for the scaling factor for fonts"）** を持つ。
  - 出典: https://developer.android.com/reference/kotlin/androidx/compose/ui/unit/Density?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定（API の存在と意味）
  - 補足/落とし穴: `LocalDensity` を `CompositionLocalProvider` で `Density(density, fontScale)` に差し替えると、**その配下の dp→px・sp→px 変換がすべて変わる**。実装方法（差し替え）と副作用は**推定**（下記）。

- 主張: `LocalDensity` 差し替えによる UI スケールの副作用（`Dialog`/`Popup`/`Canvas`/sp 換算）は一次情報に明記がない。
  - 出典: なし（本次調査で該当記述を取得できず）
  - 参照日: 2026-10-31
  - 確信度: 未検証（**推定**）
  - 補足/落とし穴（推定の内容・TO-PLAN の判断材料）:
    - `Dialog` と `Popup` は**別の Composition ツリー**を張るため、`CompositionLocalProvider` をアプリのルートに置いても**伝播しない**可能性が高い。各 Dialog の中でも同じ Provider を巻く必要がある。
    - `Canvas` / `drawBehind` 内の `dp.toPx()` は差し替え後の Density を使うため、**図形だけ拡大されてレイアウトが追従しない**ことがある。
    - `density` を大きくすると **dp 指定のレイアウトは拡大されるが、`WindowInsets` の px 値は実画面のまま**なので、インセットとの整合が崩れうる。
    - `fontScale` だけを変えると、**OS のフォントサイズ設定との二重掛け**になる（`Density(density, fontScale * userScale)` のように掛ける設計が無難・推定）。
    - 代替案: **`density` は触らず `fontScale` のみ倍率適用**し、レイアウト側は `WindowSizeClass` と dp の可変レイアウトで吸収する方が副作用が少ない（提案）。

- 主張: Material3 の `WindowSizeClass`（`androidx.compose.material3:material3-window-size-class`, 1.0.0）は `WindowWidthSizeClass` と `WindowHeightSizeClass` を持ち、`widthSizeClass` / `heightSizeClass` で参照する。`calculateFromSize(DpSize, ...)` は 1.4.0 追加で `@ExperimentalMaterial3WindowSizeClassApi`。
  - 出典: https://developer.android.com/reference/kotlin/androidx/compose/material3/windowsizeclass/WindowSizeClass?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: `calculateFromSize` が experimental なので、**experimental を避けたいなら `BoxWithConstraints` + 自前の dp 閾値**で十分（外部 API の experimental 依存を減らせる）。境界値は下記（未検証）。

- 主張: サイズクラスの境界値（compact / medium / expanded = 600dp / 840dp）は、本次調査では一次情報を取得できていない。
  - 出典: （`use-window-size-classes` ガイドは取得したが本文未精読）
  - 参照日: 2026-10-31
  - 確信度: 未検証（**推定**: 幅 600dp 未満 compact、600〜839dp medium、840dp 以上 expanded。高さも同様）
  - 補足/落とし穴: **この数値を設計の唯一の根拠にしない**。実装時に `WindowWidthSizeClass` のドキュメントで確認すること。

- 主張: `BoxWithConstraints`、`androidx.window` の `WindowInfoTracker`、`enableEdgeToEdge`、`WindowInsets.safeDrawing`、`imePadding`、`Scaffold(contentWindowInsets=...)`、カットアウト対応は、本次調査では**一次情報を取得できていない**。
  - 出典: なし
  - 参照日: 2026-10-31
  - 確信度: 未検証
  - 補足/落とし穴: targetSdk 36 では**edge-to-edge が既定**（推定・未検証）。`enableEdgeToEdge()` を呼ばずに targetSdk 35+ にすると、**システムバーとコンテンツが重なる**可能性がある。TO-PLAN は「edge-to-edge 前提で `Scaffold` の `contentWindowInsets` と各画面の `safeDrawing` を最初から設計に入れる」ことを推奨（提案）。IME は `imePadding()` を入力欄側に、`adjustResize` との二重適用に注意（推定）。

---

## 7. アプリ内言語切替（AppCompatDelegate を使わない構成）

STATUS: PARTIAL（attachBaseContext 系は 未検証）

- 主張: Android 13（API 33）以降、`LocaleManager.setApplicationLocales(LocaleList)` / `getApplicationLocales()` でアプリ単位の言語を設定できる。**これらの API はシステム設定と自動同期**するため、アプリ内ピッカーで設定してもシステム設定側と一貫する。split APK と Auto Backup にも対応。
  - 出典: https://developer.android.com/reference/android/app/LocaleManager?hl=en ／ https://developer.android.com/guide/topics/resources/app-languages?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: `LocaleManager` は **API 33 追加**なので minSdk 26 では 33+ 分岐が必要。33 未満は自前実装（本プロジェクトは AppCompatDelegate を使わない方針なので、`attachBaseContext` 方式）。

- 主張: 「3rd party アプリはこれを**書き込み側**のサーフェスとして扱い、ロケールの読み取りは**プロセス内の `LocaleList`** で続けるべき」。
  - 出典: https://developer.android.com/reference/android/app/LocaleManager?hl=en （"Third party applications should treat this as a write-side surface, and continue reading locales via their in-process LocaleLists."）
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: つまり **UI の現在言語は `LocaleManager.getApplicationLocales()` を毎回読むのではなく、アプリ内の状態（`Context.resources.configuration.locales`）を真とする**。両者を混ぜると、33+ と 32- でコードパスが分岐して破綻する。

- 主張: AndroidX の後方互換 API は **Android 12（API 32）以下では `AppCompatActivity` の context で動作し、application context では動かない**（AppCompat 1.6.0 以上）。
  - 出典: https://developer.android.com/guide/topics/resources/app-languages?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: **本プロジェクトは AppCompatDelegate を使わない**方針なので、この制約は「AndroidX の後方互換 API は採用できない」ことを意味する（`ComponentActivity` + Compose 構成のため）。→ 32 以下は**完全自前**（`attachBaseContext` + `createConfigurationContext`）で実装する必要がある。

- 主張: Android 13 実機での初回起動時、および API 32 以下で `autoStoreLocales` を使う場合・独自バックアップから移行する場合は、`AppCompatDelegate.setApplicationLocales` を呼んで既存のユーザー設定をシステムへ引き渡す必要がある（コーナーケースとして公式が明記）。
  - 出典: https://developer.android.com/guide/topics/resources/app-languages?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定（ただし AppCompat 前提の記述）
  - 補足/落とし穴: AppCompat を使わない場合、**この移行処理は `LocaleManager.setApplicationLocales` を直接呼ぶ形に読み替える**必要がある（推定）。「アプリ内設定は保存されているのにシステム設定の言語一覧では別の言語」という不整合の典型原因。

- 主張: `generateLocaleConfig = true`（`androidResources {}`）で LocaleConfig を自動生成でき、`compileSdk 33+` が条件。AGP が res から locale を判定する。
  - 出典: https://developer.android.com/guide/topics/resources/app-languages?hl=en
  - 参照日: 2026-10-31
  - 確信度: 確定
  - 補足/落とし穴: **build.gradle.kts の変更は本 worker の scope_out**。TO-PLAN が採否を決める項目として起票。`resourceConfigurations` / `androidResources.localeFilters` によるリソース限定は本次調査では**未検証**。

- 主張: `attachBaseContext` + `createConfigurationContext` / `Configuration.setLocales` の具体的な注意点（Activity 再生成、Application context への影響、RTL）は、本次調査では一次情報を取得できていない。
  - 出典: なし
  - 参照日: 2026-10-31
  - 確信度: 未検証
  - 補足/落とし穴（**推定**・要注意点の列挙に留める）: ① `attachBaseContext` は Activity ごとに呼ばれるので、**Application の context は更新されない**（Application から resources を引くと古いロケール）。② 言語変更後は **Activity の再生成（`recreate()`）** が必要で、Compose の状態は `rememberSaveable` でないと失われる。③ RTL は `Configuration.setLayoutDirection(localeList)` 相当を併せて設定しないと、言語を `ar`/`he` にしてもレイアウトが反転しない。④ `values-en`/`values-ja` の限定と `LocaleList` の順序（先頭が優先）。

---

## 8. 実装に効く落とし穴 TOP10

STATUS: DONE（内容は上記各章の帰結。§8-3〜5,9,10 は推定を含む）

1. **`proceed()` の判断が WebView 側に保持されうる** — 公式に "may be retained to facilitate responses to future SSL errors"。TOFU インタースティシャルが「2 回目以降出ないのに接続は通る」状態になる。ピン台帳と WebView の保持状態が二重管理になるので、**毎回 `cancel()` してから自前 UI → 承認時のみ再ロード**する設計を検討。
2. **`getX509Certificate()` は API 29+** — minSdk 26 では 26〜28 で `null`/NoSuchMethod。`saveState` の Bundle キー `"x509-certificate"` は**非公開の実装詳細**で、キー名依存のコードは静かに壊れる。分岐とテストを最初に置く。
3. **SSL エラーは 1 箇所では拾えない** — 回復可能な証明書エラーは `onReceivedSslError`、回復不能（ハンドシェイク失敗）は `onReceivedError(ERROR_FAILED_SSL_HANDSHAKE)`。エラー UI を 2 経路で設計する必要がある。
4. **HTTP 認証の自動入力ループ** — `proceed()` は `void` で成否を返さない。`useHttpAuthUsernamePassword()` が **false（＝拒否済み）の時に自動入力すると無限ループ**。必ずこの boolean を分岐に使い、false なら入力 UI を出す。
5. **HTTP 認証のログアウトは実装できない** — `clearHttpAuthUsernamePassword()` は DB しか消さず、**ネットワークスタックのキャッシュは消えない**。ログアウトはサイト側（401）の責務。UI 文言でこの限界を明示しないと「削除したのに通る」バグになる。
6. **`requestPinShortcut` は戻り値 true が成功を意味しない** — true は「要求を送れた」。承認されれば `IntentSender`、**拒否なら何も返らない**。さらにフォアグラウンド必須（無いと `IllegalStateException`）かつ「数秒かかるのでワーカースレッド」。成功トーストを出す設計は誤り。
7. **ピン留めはアプリから消せない** — ブックマーク削除とホームのアイコンは同期しない。`disableShortcuts` で無効化＋メッセージ表示を仕様化するか、ピン留めを提供しない（dynamic shortcut のみ）判断が必要。アイコンの Bitmap/AdaptiveIcon 要件は**未検証**だが、モノグラム Bitmap 設計とは相性が悪い可能性。
8. **`isRequestPinShortcutSupported` は動的に変わる** — ランチャー変更・**App Lock 有効化**で false になる。起動時 1 回のキャッシュは「押しても無反応なボタン」を生む。`onResume` で再評価。
9. **RoleManager は API 29、`resultCode` だけでは不足** — 26〜28 は存在しない。`RESULT_CANCELED` は「拒否」と「閉じた」の両方なので、**必ず `isRoleHeld()` で最終確認**。ロール喪失で特権が revoke されるため、保持状態をキャッシュしない。
10. **`LocalDensity` 差し替えは Dialog/Popup に伝播しない（推定）** — 別 Composition ツリーのため、UI スケールが「ダイアログだけ等倍」になる。`Canvas` の `toPx()` と `WindowInsets`(px) も不整合になりうる。`fontScale` のみ倍率適用＋レイアウト側で吸収する案を比較検討。

### 追加の注意（TOP10 外）
- `setSupportMultipleWindows(true)` は `WebChromeClient.onCreateWindow` 未実装だと新規ウィンドウが開かない。まず false で出荷。
- `shouldInterceptRequest`（WebViewAssetLoader の実行場所）は**非メインスレッド**。共有状態に触るならスレッド安全に。
- `pushDynamicShortcut` は上限到達時に**最も rank の低い既存ショートカットを黙って削除**する。ユーザーのピン留めを追い出さない設計が要る。
- `setJsEnabled`/`setDomStorageEnabled` の既定はどちらも **false**。ブラウザとしては明示的に true が必要（既定のまま動くと思い込むと何も表示されない）。

---

## 付録: 今回「未検証」とした項目の一覧

| 章 | 未検証項目 |
|---|---|
| §1 | `saveState` Bundle のキー名 `"x509-certificate"` と形式 / サブリソースでも発火するか / `proceed()` 判断の保持の粒度・期限 / `onReceivedClientCertRequest` のシグネチャと意味 / SHA-256 算出レシピの一次情報 |
| §2 | Basic と Digest の扱い差 / `HttpAuthHandler` の再利用可否・非同期利用時の失効挙動 |
| §3 | アイコン要件（Bitmap vs AdaptiveIcon） / Android 8.0・12・13 の挙動差 / バックアップ・復元時の注意 / `ShortcutInfoCompat.Builder` の必須項目（setter 詳細ページ未取得） |
| §4 | `ROLE_BROWSER` の定数実値 / ロール保持に必要な manifest 条件 / Android 10 以降の既定ブラウザ選択 UX の詳細 / Android 12+ の挙動差 / OS が強制転送を許さない点の一次情報 |
| §5 | `setAllowFileAccessFromFileURLs`/`setAllowUniversalAccessFromFileURLs` の既定値（本ページから抽出できず） / `setAcceptThirdPartyCookies` の既定値 |
| §6 | サイズクラスの境界値（600/840dp） / `WindowInfoTracker` / `enableEdgeToEdge` / `WindowInsets.safeDrawing` / `imePadding` / `Scaffold.contentWindowInsets` / カットアウト / `LocalDensity` 差し替えの副作用（Dialog・Popup・Canvas） |
| §7 | `attachBaseContext`+`createConfigurationContext` の注意点 / `resourceConfigurations`・`localeFilters` / Activity 再生成と状態保持 / Application context への影響 / RTL の具体的設定 |
