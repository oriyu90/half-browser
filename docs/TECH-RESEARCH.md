# Half-Browser 技術調査記録（TECH-RESEARCH）

- 文書ID: HB-TR-001
- 版: 1.0
- 調査日（参照日）: **2026-10-31**
- 調査担当: TO-PLAN（Worker-RESEARCH / Worker-DESIGN の成果を TO-PLAN が一次資料で再検証したうえで統合）
- 対象: Android System WebView ベースのブラウザアプリ（`com.yukiorita.halfbrowser`, minSdk 26 / targetSdk 36 / compileSdk 36.1）

## 0. この文書の読み方

### 0.1 確信度の定義

| 確信度 | 意味 | 判定基準 |
|---|---|---|
| **確定** | 公式一次資料（developer.android.com の API リファレンス／ガイド、AOSP ソース、AndroidX リリースノート）に明記されている | 出典 URL を本書に記載し、2026-10-31 に取得できた |
| **推定** | 公式資料の記述から論理的に導ける、または公式のコード例・設計から強く示唆されるが、明文の保証はない | 「なぜ推定か」を併記 |
| **未検証** | 実機・エミュレータ・実サーバーでの確認が必要。本書では断定しない | 検証予定の AC 番号を併記 |

### 0.2 調査方法と再現性

- 一次資料は `curl` で取得し、HTML をテキスト化して該当節を機械的に確認した（`web_fetch` は developer.android.com の巨大ページで冒頭のみに truncate されるため、本文確認には `curl` を用いた）。取得はすべて 2026-10-31。
- HTTP ステータスはすべて 200。取得した生テキストは作業用に一時保存し、本書には**主張・出典 URL・確信度**を転記した。
- 公式ドキュメントの記述と、アプリ要件（自己署名証明書のインタースティシャル）が**意図的に相反する**箇所がある。これは §1.3 に「公式見解との差分」として明示し、隠さない。

### 0.3 本書の結論サマリ（実装に直結する10点）

1. `onReceivedSslError` は**アプリが必ず `cancel()` か `proceed()` を呼ぶ**契約。呼ばないとリクエストが宙に浮く。公式は「常に cancel、ユーザーに問うな」と明記しており、本アプリのインタースティシャルは**公式推奨からの意図的逸脱**である（§1.3）。
2. `SslErrorHandler.proceed()` の判断は **WebView 側に恒久保存される公開 API が無い**。「常に信頼」はアプリ側のピン（`TrustedSite`）で毎回 `proceed()` を再実行して初めて成立する。ただし公式文言 "may be retained" の解釈には**未検証の曖昧さ**があり、AC-12b で実測する（§1.2）。
3. 証明書の X509 取得は **API 29 以上で `SslCertificate.getX509Certificate()`**、**API 26–28 では `SslCertificate.saveState()` の Bundle キー `x509-certificate`（DER バイト列）** を使う。ここを誤ると minSdk 26 で `NoSuchMethodError` になる（§2.1）。**最重要の落とし穴**。
4. `HttpAuthHandler` は `Handler` のサブクラスで、`proceed`/`cancel` は **UI スレッド必須**。`useHttpAuthUsernamePassword()` は「そのホストの保存済み資格情報が**以前サーバーに拒否された**なら false」を返す、という**プラットフォーム標準のループ防止信号**がある（§3.2）。
5. `ShortcutManager.requestPinShortcut` は **前景 Activity/Service が必須**（無いと `IllegalStateException`）、戻り値 true は「ランチャーが機能対応」の意味だけで**ピン留め成功ではない**。ユーザー拒否時はコールバックが来ない。数秒かかるので**ワーカースレッド推奨**（§4.1）。
6. `RoleManager.ROLE_BROWSER` は API 29+。`createRequestRoleIntent` → `startActivityForResult` で **RESULT_OK / RESULT_CANCELED**。OS が既定ブラウザを決めるため、**アプリが強制転送することはできない**（§5）。
7. `androidx.security:security-crypto` は **1.1.0-beta01（2025-06-04）で全 API が非推奨**（「プラットフォーム API と Keystore 直接利用を推奨」）。本リポジトリは `1.1.0-alpha06` を指しており、**非推奨 API を alpha 版で使う**状態。設計上是認するが、隔離インターフェース必須（§3.4、ADR-0003）。
8. `WebViewAssetLoader` の予約ドメインは **`appassets.androidplatform.net`**。`file://` を使わずにローカル資産を HTTPS オリジンで配信できる（§6.3）。
9. ウィンドウ幅クラスは **compact <600dp / medium 600–840 / expanded 840–1200 / large 1200–1600 / extra-large ≥1600dp**（2026年時点で5区分）。本アプリは 4 区分（compact/medium/expanded/extra-large 相当）に丸めて列数を決める（§7.1）。
10. edge-to-edge は **Android 15（API 35）以降で自動有効**。`WindowCompat.enableEdgeToEdge(window)` を明示し、インセット前提のレイアウトを組む必要がある（§7.3）。

---

## 1. WebView の SSL エラー処理

### 1.1 `WebViewClient.onReceivedSslError` の契約

**主張**: `onReceivedSslError(WebView, SslErrorHandler, SslError)` は SSL エラー発生時に呼ばれ、ホストアプリは `SslErrorHandler.cancel()` か `SslErrorHandler.proceed()` の**どちらかを必ず呼ばなければならない**。呼ばない限りそのリソース要求は完了しない（＝ハンドラ保持＝リクエスト停止）。

- 出典: <https://developer.android.com/reference/android/webkit/WebViewClient#onReceivedSslError(android.webkit.WebView,%20android.webkit.SslErrorHandler,%20android.net.http.SslError)>
- 参照日: 2026-10-31 / 確信度: **確定**

公式原文（要旨）:

> Notifies the host application that an SSL error occurred while loading a resource. The host application must call either `SslErrorHandler.cancel()` or `SslErrorHandler.proceed()`.

**主張**: このコールバックは**回復可能な証明書エラー**でのみ呼ばれる。回復不能（サーバーがクライアント認証を拒否した等）の場合は `onReceivedError` に `ERROR_FAILED_SSL_HANDSHAKE` が渡る。

- 出典: 同上 / 参照日: 2026-10-31 / 確信度: **確定**

**実装上の帰結**: 「戻る」で `onReceivedError` 側の分岐も必要。SSL ダイアログだけ作って `onReceivedError` を放置すると、ハンドシェイク失敗時に無言の空白ページになる。

### 1.2 `SslErrorHandler.proceed()` の意味と限界

**主張**: `proceed()` は「SSL 証明書エラーを無視して通信を継続せよ」という指示であり、`cancel()` は要求を中止する。

- 出典: <https://developer.android.com/reference/android/webkit/SslErrorHandler>
- 参照日: 2026-10-31 / 確信度: **確定**

**主張（限界）**: `proceed()` の判断が**どこで保持されるかは公式文言だけでは確定しない**。`WebViewClient` のドキュメントには次の一文がある。

> The decision to call proceed() or cancel() may be retained to facilitate responses to future SSL errors.

- 出典: <https://developer.android.com/reference/android/webkit/WebViewClient#onReceivedSslError(android.webkit.WebView,%20android.webkit.SslErrorHandler,%20android.net.http.SslError)>
- 参照日: 2026-10-31 / 確信度: 文言は**確定**、解釈は**未検証**

**解釈は2通りあり、どちらかで設計が変わる**（W-RESEARCH の指摘。TO-PLAN も同じ論点に到達した）。

| 解釈 | 内容 | 設計への影響 |
|---|---|---|
| **(A) アプリ側が保持する** | 「ホストアプリが判断を覚えておき、次回以降のコールバックで同じ判断をしてもよい」という**実装のヒント**。WebView 自身は何も覚えない | 本アプリの TOFU（アプリ側のピン台帳）でそのまま成立する |
| **(B) WebView が保持する** | 一度 `proceed()` すると、同じホストの以後の SSL エラーでコールバックが呼ばれず素通りする | **証明書変更を検知できなくなる**（`Changed` を出せない）。セキュリティ上の重大な穴 |

- **TO-PLAN の判断**: Chromium ベースの WebView はリクエスト単位で SSL 判断を扱い、ホスト単位の恒久例外を持つ公開 API は存在しないため、**(A) が実装の実態である可能性が高い（推定）**。ただし**推測でセキュリティ境界を決めない**。
- **必須の検証（AC-12b）**: ①ピン済みホストを再読み込み → コールバックが来るか ②ピン済みホストの証明書を差し替えて再読み込み → `Changed` が出るか ③アプリ再起動後も同じか。デバッグビルドのコールバックログで確認する。
- **フォールバック設計（Plan B、実装はしないが設計に残す）**: 検証の結果 (B) だった場合、「**毎回 `cancel()` → 自前ダイアログ → 承認時に同じ URL を再ロード**」へ切り替える。この方式の副作用は、(i) `POST` の本文が失われる、(ii) リクエストが二重になる、(iii) リダイレクト途中で承認すると最終 URL ではなく途中 URL を再ロードする、の3点。ADR-0002 に記録済み。

- 補足: したがって「このサイトを常に信頼」は、**(a) ホスト＋証明書 SHA-256 を永続化し、(b) 次回以降の `onReceivedSslError` でピン一致を確認して即 `proceed()` を呼ぶ**、というアプリ側の再実行で実現する。ADR-0002 の前提。

**主張**: 公式は `proceed()` を**使うこと自体を推奨していない**。

> Warning: When an SSL error occurs, the host application should always call `cancel()` rather than `proceed()` because an invalid SSL certificate means the connection is not secure.

- 出典: <https://developer.android.com/reference/android/webkit/SslErrorHandler#proceed()>
- 参照日: 2026-10-31 / 確信度: **確定**

### 1.3 公式見解と本アプリ要件の差分（意図的逸脱の明示）

**主張**: 公式ドキュメントは「SSL エラーについて**ユーザーに問い合わせるな**」と明記している。

> Note: Do not prompt the user about SSL errors. Users are unlikely to be able to make an informed security decision, and WebView does not provide a UI for showing the details of the error in a meaningful way.

- 出典: <https://developer.android.com/reference/android/webkit/WebViewClient#onReceivedSslError(android.webkit.WebView,%20android.webkit.SslErrorHandler,%20android.net.http.SslError)>
- 参照日: 2026-10-31 / 確信度: **確定**

**本アプリの判断**: ユーザー要件は「自宅 LAN の `https://192.168.0.165:8844` のような自己署名 HTTPS を、警告で止めずに開けるようにする」こと。したがって公式の「問うな／proceed するな」には**従わない**。ただし公式が懸念する「ユーザーが判断できない」問題に対して、次の**補償統制**を設計に組み込む（ADR-0002）。

| 補償統制 | 内容 |
|---|---|
| 判断材料の提示 | ホスト、エラー種別（`getPrimaryError()` を人間可読化）、発行者（issuer DN）、Subject（subject DN）、有効期間、SHA-256 フィンガープリント（`AA:BB:…` 形式）を提示 |
| 既定値は「戻る」 | ダイアログの既定フォーカス／主ボタンは「戻る」。誤タップで信頼しない |
| 「常に信頼」の格下げ | 危険色（error container）で表示し、ホスト＋フィンガープリントの完全一致でのみ有効と説明 |
| 証明書変更時は自動 proceed しない | `TrustDecision.Changed` は必ず再確認。以前のピン情報を併記して差分を見せる |
| 監査可能性 | 信頼済みサイト一覧から個別失効できる。失効＝次回アクセスで再警告 |
| スコープの限定 | ピンは「ホスト＋証明書」に限定。ワイルドカードも「この端末のすべての証明書を許す」も提供しない |

**残るリスク（隠さない）**: ユーザーが「常に信頼」を押した後の MITM は検出できない（TOFU の本質的限界）。詳細は ADR-0002 §Consequences。

### 1.4 ハンドラのライフサイクル（実装上の必須事項）

| 項目 | 設計判断 | 根拠・確信度 |
|---|---|---|
| 同時保留数 | 1 WebView につき**最大1件**。2件目が来たら即 `cancel()` して記録のみ | 複数ハンドラを UI で捌くと、どれがどのリクエストか追えなくなる（推定） |
| UI 破棄時 | `ViewModel.onCleared()` で保留中のハンドラを必ず `cancel()` | 呼ばないとリクエストが残留する（§1.1 確定） |
| タイムアウト | 60 秒で自動 `cancel()` | ダイアログ放置でページが固まるのを防ぐ（推定・UX要件） |
| サブリソース | メインフレーム以外（画像・iframe）でも発火し得る。UI にはロード中のメインフレームかを併記 | コールバックはリソース単位（確定: 「while loading a resource」） |
| 「今回だけ」 | `proceed()` のみ。永続化しない。次回遷移・再読み込みで再警告 | §1.2（確定） |
| 「戻る」 | `cancel()` → `stopLoading()` → 履歴があれば `goBack()`、無ければ社内エラーページ | 推定（UX） |

### 1.5 `onReceivedClientCertRequest` との区別

**主張**: クライアント証明書要求は `onReceivedClientCertRequest` で処理する別系統。本アプリは**クライアント証明書を要求するサイトを対象外**とし、既定動作（要求を無視）とする。

- 出典: <https://developer.android.com/reference/android/webkit/WebViewClient#onReceivedClientCertRequest(android.webkit.WebView,%20android.webkit.ClientCertRequest)>
- 参照日: 2026-10-31 / 確信度: **確定**（API の存在）／対象外判断は**推定**（要件に無い）

---

## 2. 証明書情報の取得と TOFU ピン留め

### 2.1 X509 証明書の取り出し（API レベル差が最大の罠）

**主張**: `SslError.getCertificate()` は **non-null の `SslCertificate`** を返す。

- 出典: <https://developer.android.com/reference/android/net/http/SslError#getCertificate()>
- 参照日: 2026-10-31 / 確信度: **確定**

**主張**: `SslCertificate.getX509Certificate()` は **API 29 で追加**された public API。**API 26–28 には存在しない**。

- 出典: <https://developer.android.com/reference/android/net/http/SslCertificate#getX509Certificate()>（"Added in API level 29"）
- 参照日: 2026-10-31 / 確信度: **確定**

**主張**: `SslCertificate.saveState(SslCertificate)` は `Bundle` を返し、X509 が利用可能な場合は **キー `"x509-certificate"` に DER エンコードされた `byte[]`** を格納する。`restoreState(Bundle)` はそこから `CertificateFactory("X.509")` で復元する。

- 出典: AOSP `SslCertificate.java`（`X509_CERTIFICATE = "x509-certificate"`）<https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/http/SslCertificate.java>（取得は GitHub ミラー <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/net/http/SslCertificate.java>）
- 参照日: 2026-10-31 / 確信度: **確定**（AOSP ソース）／**推定**（将来もキー名が安定している保証はない）

**設計（ADR-0002 へ）**: フィンガープリント算出は次の2段構え。

```kotlin
// 概念コード（実装は TO-EXEC）。API 29+ と 26-28 の分岐を純粋関数の外に閉じ込める。
fun x509Of(cert: SslCertificate): X509Certificate? =
    if (Build.VERSION.SDK_INT >= 29) cert.x509Certificate
    else SslCertificate.saveState(cert)
        ?.getByteArray("x509-certificate")
        ?.let { CertificateFactory.getInstance("X.509").generateCertificate(it.inputStream()) as? X509Certificate }
```

- 両方失敗した場合（`null`）は**「常に信頼」を無効化**し、「今回だけ」と「戻る」のみ提示する。ハッシュできないものをピン留めしてはならない。
- 既存 `TrustPolicy.fingerprintOf()` は `certificate.encoded` の SHA-256 を小文字 hex で返す。`TrustedSite.fingerprintSha256` の定義（小文字 hex・区切り無し）と一致しており**そのまま使える**（`app/src/main/java/com/yukiorita/halfbrowser/core/TrustPolicy.kt:27`）。

### 2.2 TOFU ピン留め設計の妥当性と限界

**主張（妥当性）**: ホスト＋証明書 SHA-256 の組を保存し、次回の `onReceivedSslError` で一致を確認して `proceed()` する方式は、**「初回にユーザーが明示的に承認した証明書以外では自動 proceed しない」**という性質を満たす。これは自己署名証明書の実運用（自宅ラボ）で一般的な TOFU の適用であり、WebView に永続化機構が無い以上、**アプリ側で持つ以外の実現手段が無い**（§1.2）。

- 参照日: 2026-10-31 / 確信度: **推定**（設計原則からの演繹。フレームワーク保証ではない）

**限界（明示する）**:

| # | 限界 | 影響 | 対策 |
|---|---|---|---|
| L1 | 初回接続時の MITM は防げない（TOFU の定義） | 初回に攻撃者の証明書を信頼し得る | 初回は必ずフィンガープリント全体を表示。ユーザーがサーバー側の値と突き合わせられるようコピー機能を提供 |
| L2 | 証明書更新（Let's Encrypt 含む）で `Changed` になる | 正規の更新でも再警告 | 「以前のピン」を表示し、更新だと分かる情報（notBefore/notAfter）を併記 |
| L3 | ピンは「ホスト」単位で、ポートは含めない | 同一ホストの別ポートに**同一証明書**が置かれていれば自動信頼される | 同一証明書＝同一秘密鍵保有者なのでリスク増分は小さい（推定）。表示上は `host:port` を出す |
| L4 | ユーザーが「常に信頼」を乱用する | 実質的に検証無効化 | 一覧・失効導線、追加時に警告、件数上限（後述） |
| L5 | 証明書の**期限切れ**でも `proceed()` できてしまう | 期限切れ証明書の常用 | 期限切れは「常に信頼」を**許可しない**（今回だけのみ）。`SSL_EXPIRED` / `SSL_DATE_INVALID` を区別 |
| L6 | アプリのデータ削除・端末移行でピンが消える | 再警告（安全側） | 許容。バックアップ対象にしない（`backup_rules.xml` の方針と整合） |
| L7 | ピン汚染（悪意あるアプリがピンを書く） | 通常は不可能（アプリ専用領域） | `allowBackup` 経由の復元のみ注意。秘密ではないが整合性は保つ |

**推奨する追加統制**: ピン件数の上限（例: 50 件）。超過時は最古の `lastSeenAt` を消すのではなく**追加を拒否**し、一覧から失効させる（無言の失効はセキュリティ判断をユーザーから奪うため）。

### 2.3 エラー種別の人間可読化

**主張**: `SslError.getPrimaryError()` は最も深刻なエラーを int で返し、`SSL_NOTYETVALID` / `SSL_EXPIRED` / `SSL_IDMISMATCH` / `SSL_UNTRUSTED` / `SSL_DATE_INVALID` / `SSL_INVALID` などの定数がある。`hasError(int)` で複合エラーを判定できる。

- 出典: <https://developer.android.com/reference/android/net/http/SslError>
- 参照日: 2026-10-31 / 確信度: **確定**

**表示方針**: 1つの `SslError` に複数エラーが入り得るため、primary だけでなく `hasError()` で全該当を列挙する。文字列は resources 化（`ssl_error_untrusted` 等）。**生の int や英語例外メッセージを UI に出さない**。

### 2.4 事後検証（`WebView.getCertificate()`）— 保持の穴を塞ぐ安価な手段

**主張**: `WebView.getCertificate()` は「**メインのトップレベルページ**の SSL 証明書、無ければ null（＝安全でないページ）」を返す public API。

- 出典: <https://developer.android.com/reference/android/webkit/WebView#getCertificate()>
- 参照日: 2026-10-31 / 確信度: **確定**（シグネチャと意味）／**推定**（常に最新のメインフレーム証明書を返すという運用）

**設計への効き方**: §1.2 の解釈 (B)（WebView が `proceed()` を保持する）が真だった場合でも、`onPageFinished`（https・メインフレームのみ）で `getCertificate()` の SHA-256 をピンと照合すれば、**ユーザー承認なしに別証明書で通信したことを事後検知**できる。コストはゼロ（追加通信なし）。

- 不一致時の動作: `stopLoading()` → `Changed` インタースティシャルを表示。ピンが無いホストでは何もしない（「今回だけ」を妨げない）。
- 限界: メインフレーム限定。エラーページでは null。サブリソースは対象外。
- 実装上の注意: `getCertificate()` は API 1 から存在するが、返る `SslCertificate` から X509 を取り出す際は §2.1 の API 29 分岐が同樣に必要。

---

## 3. HTTP 認証（Basic / Digest）

### 3.1 コールバックとハンドラ

**主張**: `onReceivedHttpAuthRequest(WebView, HttpAuthHandler, String host, String realm)` は HTTP 認証要求時に呼ばれ、既定動作は**キャンセル**。`HttpAuthHandler` で応答を設定する。ハンドラは **UI スレッドで使うこと**。

- 出典: <https://developer.android.com/reference/android/webkit/WebViewClient#onReceivedHttpAuthRequest(android.webkit.WebView,%20android.webkit.HttpAuthHandler,%20java.lang.String,%20java.lang.String)>
- 参照日: 2026-10-31 / 確信度: **確定**

**主張**: `HttpAuthHandler.proceed(String username, String password)` は資格情報を渡して続行、`cancel()` は中止。`HttpAuthHandler` は `android.os.Handler` のサブクラスであり、`proceed`/`cancel`/`useHttpAuthUsernamePassword` はいずれも **UI スレッド必須**。

- 出典: <https://developer.android.com/reference/android/webkit/HttpAuthHandler>
- 参照日: 2026-10-31 / 確信度: **確定**

**主張**: `useHttpAuthUsernamePassword()` は「そのホスト（コールバック対象）に保存されている資格情報が**使用に適しているか**」を返す。**「以前サーバーに拒否された」場合は false**。

> Credentials are not suitable if they have previously been rejected by the server for the current request.

- 出典: <https://developer.android.com/reference/android/webkit/HttpAuthHandler#useHttpAuthUsernamePassword()>
- 参照日: 2026-10-31 / 確信度: **確定**

**設計への効き方**: これは**プラットフォームが提供する自動認証ループ防止信号**である。「保存済み資格情報で自動 proceed してよいか」の一次判定にこれを使い、そのうえでアプリ側の連続失敗カウンタを重ねる（二重の防御）。ADR-0003。

### 3.2 Basic と Digest の差

| 項目 | Basic | Digest | 出典・確信度 |
|---|---|---|---|
| 資格情報の送信 | `Authorization: Basic base64(user:pass)`（**可逆**） | チャレンジ応答に基づくハッシュ。平文パスワードは送らない | RFC 7616/7617（**確定**）。WebView 内部実装は非公開（**推定**） |
| `proceed(user, pass)` の扱い | そのまま使える | WebView がチャレンジ情報を保持している必要がある。`proceed` は同じ経路 | `HttpAuthHandler` の記述から**推定**（Basic/Digest を区別する API は無い＝**確定**） |
| アプリ側の保存 | 平文パスワードを復号可能に保存する必要がある | 同左（Digest 計算にもパスワードが要る） | **推定**（方式上不可避） |
| 危険 | **HTTP（非 TLS）上の Basic はパスワードが実質平文** | 非 TLS でもパスワードそのものは流れないが、リプレイ耐性はサーバー実装依存 | RFC（**確定**） |

**設計**: 認証ダイアログで `http://` のときは「この接続は暗号化されていません。パスワードが平文で送られます」と**警告を必ず出す**（保存は許可するが、既定チェックを外す）。`https://` の自己署名サイトでは SSL インタースティシャル通過後に認証が来るため、**「信頼していないホストに資格情報を送らない」**という順序保証を状態機械で担保する（PLAN §6.2）。

### 3.3 自動入力ループの危険

**主張（危険の機構）**: 保存済み資格情報で自動 `proceed()` すると、サーバーが 401 を返すたびに `onReceivedHttpAuthRequest` が再発火し、**アプリが無限に proceed し続ける**。UI スレッドで同期ループすると ANR になり得る。

- 参照日: 2026-10-31 / 確信度: **推定**（機構は API 仕様から自明。実測は AC-24 で行う）

**対策（ADR-0003 で確定）**:

1. `useHttpAuthUsernamePassword() == false` なら**自動 proceed しない**（プラットフォーム信号）。
2. キー = `scheme + "://" + host + ":" + port + "/" + realm`（realm は空を許容）。同一キーで**自動 proceed した直後 1 ページロード内に再要求**が来たら「失敗」とみなしカウンタ +1。
3. カウンタ 1 回目: ダイアログを出すが**ユーザー名をプリフィル**、パスワードは空。
4. カウンタ 2 回目以降: そのキーの自動入力と自動 proceed を**セッション中禁止**（アプリ再起動またはページの成功ロードでリセット）。
5. ハンドラは `AtomicBoolean` で**一度だけ**消費。二重 `proceed` は `IllegalStateException` の恐れ（推定）＋状態破壊。
6. ダイアログに 120 秒のタイムアウトを設け、超過で `cancel()`。

### 3.4 保存方式（EncryptedSharedPreferences の現状）

**主張**: `androidx.security:security-crypto` は **1.1.0-beta01（2025-06-04）で「全 API を非推奨、既存のプラットフォーム API と Android Keystore の直接利用を推奨」** とリリースノートに明記された。`alpha07`（2025-04-09）でも同様。**1.1.0 stable が存在する**（2026-09-09 更新のリリースノート一覧に記載）。

- 出典: <https://developer.android.com/jetpack/androidx/releases/security>
- 参照日: 2026-10-31 / 確信度: **確定**（非推奨化の事実）／**推定**（1.1.0 stable の実体と挙動）

**本リポジトリの現状**: `gradle/libs.versions.toml` の `securityCrypto = "1.1.0-alpha06"`（2023-04-19 リリース）。**非推奨化前の alpha** を使っており、非推奨警告も出ない代わりに、修正・改善も入らない。

**設計判断（ADR-0003）**: 要件が「Android Keystore + EncryptedSharedPreferences」と明示しているため**採用する**が、次の三点を必須とする。

1. 保存層を `CredentialVault` インターフェースで隔離し、`EncryptedSharedPreferences` 実装を差し替え可能にする（将来の Keystore 直接実装へ移行可能）。
2. 依存を `1.1.0` stable に固定し、`@Suppress("DEPRECATION")` の適用箇所を1ファイルに限定する。
3. Keystore 例外（鍵無効化・端末ロック変更・破損）を捕捉し、**保存済み資格情報を全消去してユーザーに再入力を求める**フェイルセーフを実装する（クラッシュさせない）。

---

## 4. ホーム画面ショートカット

### 4.1 `requestPinShortcut` の制約（公式に明記）

**主張**: `ShortcutManager.requestPinShortcut(ShortcutInfo, IntentSender)` は API 26 で追加。**前景 Activity または前景 Service を持つアプリのみ**呼べる（無ければ `IllegalStateException`）。ランチャーがユーザーに承認を求め、**拒否時はコールバックが送られない**。戻り値 `true` は「ランチャーが機能をサポート」の意味であり、**ピン留め成功を意味しない**。数秒かかることがあるため**ワーカースレッドから呼ぶ**ことが推奨。

- 出典: <https://developer.android.com/reference/android/content/pm/ShortcutManager#requestPinShortcut(android.content.pm.ShortcutInfo,%20android.content.IntentSender)>
- 参照日: 2026-10-31 / 確信度: **確定**

**主張**: 同一 ID のショートカットが存在し **disabled** の場合 `IllegalArgumentException`。App Lock 有効時はリクエストが拒否され `false` を返す。

- 出典: 同上 / 参照日: 2026-10-31 / 確信度: **確定**

**主張**: `isRequestPinShortcutSupported()` は API 26+。既定ランチャーが非対応、または **App Lock 有効時**に `false`。**`onResume` で再問い合わせすることを推奨**（ランチャー変更で結果が変わり得る）。

- 出典: <https://developer.android.com/reference/android/content/pm/ShortcutManager#isRequestPinShortcutSupported()>
- 参照日: 2026-10-31 / 確信度: **確定**

**主張**: `getMaxShortcutCountPerActivity()` は「ランチャーアイコン1つあたりの **static と dynamic** ショートカットの最大数」。**ピン留めショートカットはこの枠に含まれない**（記述が static+dynamic に限定されているため）。

- 出典: <https://developer.android.com/reference/android/content/pm/ShortcutManager#getMaxShortcutCountPerActivity()>
- 参照日: 2026-10-31 / 確信度: **確定**（記述）／**推定**（ピン留め除外の運用）

### 4.2 `ShortcutManagerCompat`（AndroidX）

**主張**: `ShortcutManagerCompat.requestPinShortcut(Context, ShortcutInfoCompat, IntentSender?)` は戻り値 boolean（ランチャー対応可否）。`setDynamicShortcuts` は既存 dynamic を**全置換**し、同一 ID の可変ピン留めは更新する。**レート制限**があり、`getMaxShortcutCountPerActivity` 超過や不変ショートカット更新で `IllegalArgumentException`。

- 出典: <https://developer.android.com/reference/androidx/core/content/pm/ShortcutManagerCompat>
- 参照日: 2026-10-31 / 確信度: **確定**

**設計（ADR-0006）**:

- ピン留めは**ユーザー操作（長押しメニュー）からのみ**。自動・一括ピン留めはしない（OS が毎回ダイアログを出すため UX 破綻）。
- 呼び出しは `Dispatchers.IO` で行い、`IllegalStateException`（前景でない）と `IllegalArgumentException`（disabled ID）を個別に捕捉して**ユーザーに見えるメッセージ**に変換する。
- 動的ショートカットは「最近開いたブックマーク最大4件」に限定し、`coerceAtMost(maxShortcutCount - 1)` で上限を割る。
- ブックマーク削除時は `disableShortcuts(ids)` でピン留めを無効化（アプリからは削除できないため）。起動インテント側は「ブックマークが無い」場合にホームへフォールバックし、トーストで通知する。
- アイコンは 108dp 相当（xxhdpi で 324px 程度）を目安に、**モノグラムを中央 60% に収めた Bitmap** を生成。Adaptive Icon はショートカットには不要（Bitmap で可）。

### 4.3 アイコン生成の要件

**主張**: ショートカットのアイコンは `ShortcutInfo.Builder.setIcon(Icon)` で指定し、`IconCompat.createWithBitmap()` が使える。ランチャーがマスク・縮小するため、**余白を確保した正方形**が安全。

- 出典: <https://developer.android.com/reference/androidx/core/content/pm/ShortcutInfoCompat.Builder>
- 参照日: 2026-10-31 / 確信度: **推定**（API の存在は確定、マスク挙動はランチャー依存）

**設計**: 216×216px、背景は `BookmarkIcons.colorFor()` の不透明色、角丸半径 24px、グリフは白の太字で中央配置。ダークテーマでも背景色は変えない（ランチャー側のテーマに依存させない）。

---

## 5. RoleManager と既定ブラウザ

**主張**: `RoleManager` は API 29 で追加。`ROLE_BROWSER` は `"android.app.role.BROWSER"`。`isRoleAvailable(String)` / `isRoleHeld(String)` / `createRequestRoleIntent(String)` を使う。要求 Intent を `startActivityForResult` に渡し、**許可なら `RESULT_OK`、拒否なら `RESULT_CANCELED`**。

- 出典: <https://developer.android.com/reference/android/app/role/RoleManager>
- 参照日: 2026-10-31 / 確信度: **確定**

**主張（OS 制約）**: 既定ブラウザの選択は**ユーザーが OS の UI で行う**ものであり、アプリが他人のリンクを自分へ強制転送する手段は無い。`ROLE_BROWSER` を保持していない状態で `VIEW` intent が来た場合、その intent を「奪い返す」ことはできない。

- 参照日: 2026-10-31 / 確信度: **確定**（Android のロール設計）／**推定**（個別 OEM の追加挙動）

**設計（ADR-0005）**:

| 状態 | 導線 |
|---|---|
| API 29+ かつ `isRoleAvailable(ROLE_BROWSER)` | 「既定のブラウザに設定」ボタン → `createRequestRoleIntent` → `startActivityForResult` |
| API 29+ だが未対応（`isRoleAvailable == false`） | ボタンを無効化し、「この端末ではアプリから設定画面を開けません」と説明。設定アプリの手順をテキスト表示 |
| API 26–28 | ロール API が無いため、`Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS`（API 24+）またはアプリ詳細設定への導線のみ |
| すでに保持（`isRoleHeld`） | 「既定のブラウザです」と表示し、ボタンは非表示 |

**manifest 要件**: `VIEW` + `http`/`https` + `BROWSABLE` + `DEFAULT`、`android:exported="true"`（Android 12+ で必須）。現行 `AndroidManifest.xml` は満たしている（`app/src/main/AndroidManifest.xml` の intent-filter）。

---

## 6. WebView のセキュリティ設定

### 6.1 ブラウザとして必要な設定と危険な設定

| 設定 | 本アプリ | 理由・出典 | 確信度 |
|---|---|---|---|
| `javaScriptEnabled` | **true** | ブラウザとして必須。JS ブリッジ（`addJavascriptInterface`）は**使わない** | 確定 |
| `domStorageEnabled` | **true** | DOM ストレージ（localStorage/sessionStorage）要件 | 確定 |
| `databaseEnabled` | **false**（不要） | WebSQL は廃止済み。有効化する理由が無い | 推定 |
| `allowFileAccess` | **false** | 公式: 「外部ソースの `file://` を開くアプリは有効にするな」。`WebViewAssetLoader` 推奨 | **確定** |
| `allowContentAccess` | **false** | `content://` 経由のローカル読取を塞ぐ | 確定（API の意味） |
| `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` | **false**（明示） | API 30 で非推奨。「安全でない、`WebViewAssetLoader` を使え」 | **確定** |
| `mixedContentMode` | **`MIXED_CONTENT_COMPATIBILITY_MODE`**（v1 決定） | Chrome に近い互換挙動。`NEVER_ALLOW` は安全だが HTTPS ページの画像程度も落ちて実用に耐えない。**機密性は「資格情報を http に送らない」で担保**する（ADR-0003 / G-05）。厳格モードは将来の設定項目 | 確定（定数）／判断は本書 §6.4 |
| `safeBrowsingEnabled` | **true**（API 26+） | 既定で有効。明示的に true を設定し、無効化しない | **確定** |
| `setSupportMultipleWindows` | **false（既定のまま）** | 既定は false。false でも `target=_blank` は同一 WebView 内で開く（推定）。true にする場合は `WebChromeClient.onCreateWindow` の実装が**必須**で、未実装だと新しいウィンドウが開かない。タブ実装（v1.1）まで false | **確定**（既定値・必須要件）／推定（同一 WebView 挙動） |
| `setWebContentsDebuggingEnabled` | **debug ビルドのみ true** | release で有効だと遠隔デバッグ可能 | 確定（API の意味） |
| Cookie | `CookieManager.setAcceptCookie(true)`、サードパーティは**既定 false**（設定で ON/OFF） | プライバシー既定 | 推定（方針） |
| `WebView.setWebContentsDebuggingEnabled` | `BuildConfig.DEBUG` のときのみ | リリースで無効化 | 確定 |
| `file:///android_asset` / `file:///android_res` | **到達可能のまま**（`allowFileAccess=false` でも制御されない） | 公式記述。よって「file を無効化したから安全」とは言えず、外部 URL から `file:///android_asset` を読ませない設計（=ローカル HTML を使わない）が必要 | **確定** |

**出典**:
- `WebSettings#setAllowFileAccess`（「Apps should not open file:// URLs from any external source」）: <https://developer.android.com/reference/android/webkit/WebSettings#setAllowFileAccess(boolean)> / 2026-10-31 / **確定**
- `WebSettings#setAllowFileAccessFromFileURLs`（API 30 非推奨）: <https://developer.android.com/reference/android/webkit/WebSettings#setAllowFileAccessFromFileURLs(boolean)> / **確定**
- `WebSettings#setSafeBrowsingEnabled`（API 26、既定有効）: <https://developer.android.com/reference/android/webkit/WebSettings#setSafeBrowsingEnabled(boolean)> / **確定**
- `WebSettings#MIXED_CONTENT_NEVER_ALLOW`: <https://developer.android.com/reference/android/webkit/WebSettings#MIXED_CONTENT_NEVER_ALLOW> / **確定**

### 6.2 レンダラプロセスのクラッシュ

**主張**: `onRenderProcessGone(WebView, RenderProcessGoneDetail)`（API 26+）でレンダラ終了を通知される。**true を返すとアプリはクラッシュしない**（WebView は使用不能になるので破棄・再生成が必要）。false を返すとアプリがクラッシュする。

- 出典: <https://developer.android.com/reference/android/webkit/WebViewClient#onRenderProcessGone(android.webkit.WebView,%20android.webkit.RenderProcessGoneDetail)>
- 参照日: 2026-10-31 / 確信度: **確定**

**設計**: `true` を返し、`WebView` を親から除去 → `destroy()` → 新しい `WebView` を生成して同じ URL を再ロード。クラッシュ安全性 AC-38 の根拠。

### 6.3 `WebViewAssetLoader`

**主張**: `WebViewAssetLoader` はローカル資産を `http(s)://` で配信し、同一生成元ポリシーを保てる。**予約ドメインは `appassets.androidplatform.net`**。`shouldInterceptRequest` 内で使う（別スレッド）。

- 出典: <https://developer.android.com/develop/ui/views/layout/webapps/load-local-content>、<https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader>
- 参照日: 2026-10-31 / 確信度: **確定**

**設計**: ホーム（ブックマークグリッド）は Compose で描くため、v1 ではアセット配信を**使わない**。将来「社内エラーページ」「オフラインページ」を HTML で出す場合に `https://appassets.androidplatform.net/assets/...` を使う（`file://` は使わない）。この判断は ADR-0004 に記録。

---

## 7. Compose の適応レイアウト・UI スケール・インセット

### 7.1 ウィンドウサイズクラス

**主張**: 2026 年時点の公式ブレークポイントは 5 区分。

| クラス | 幅 |
|---|---|
| Compact | `width < 600dp` |
| Medium | `600dp ≤ width < 840dp` |
| Expanded | `840dp ≤ width < 1200dp` |
| Large | `1200dp ≤ width < 1600dp` |
| Extra-large | `width ≥ 1600dp` |

- 出典: <https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes>
- 参照日: 2026-10-31 / 確信度: **確定**

**設計（ADR-0007）**: `androidx.compose.material3:material3-window-size-class` への**新規依存を追加しない**。理由: (a) 現行依存に無く、追加すると BOM 整合の検証が必要、(b) 判定は純粋関数（`GridPolicy.columnsFor(widthDp, setting)`）にでき、JVM 単体テストで境界値を検証できる。`BoxWithConstraints` の `maxWidth` を画面ルートで読み、600/840/1200dp の境界で 4 区分（compact/medium/expanded/extra-large）に丸める。

### 7.2 `LocalDensity` による UI スケール

**主張**: `LocalDensity` は `ProvidableCompositionLocal<Density>`。`Density` は `density` と `fontScale` を持ち、dp→px と sp→px の変換に使われる。

- 出典: <https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/package-summary>（`LocalDensity`）、<https://developer.android.com/reference/kotlin/androidx/compose/ui/unit/Density>（`fontScale`）
- 参照日: 2026-10-31 / 確信度: **確定**

**設計**:

```kotlin
val base = LocalDensity.current
CompositionLocalProvider(
    LocalDensity provides Density(
        density = base.density * settings.uiScale,
        fontScale = base.fontScale * settings.fontScale,
    )
) { AppContent() }
```

**副作用（必ずテストする）**:

| # | 副作用 | 対策 |
|---|---|---|
| S1 | dp 指定の余白・アイコン・最小タップ領域がすべて `uiScale` 倍になる | 1.4 倍時に 48dp タップ領域が 67dp 相当になる（＝広がる分には安全）。0.8 倍時に 38dp となり**アクセシビリティ最小 48dp を割る** | タップ領域だけは**スケール前の dp で最小 48dp を保証**する `Modifier.minimumInteractiveSize()` 相当を用意 |
| S2 | 文字は `uiScale × fontScale` の二重掛けになる（最大 1.4 × 1.5 = 2.1 倍） | レイアウト崩れを許容するのではなく、`Text` に `maxLines` + `overflow` を徹底し、2.1 倍で画面テスト（AC-34） |
| S3 | `WindowInsets` の値を dp で読むと `uiScale` 倍になる | **インセットは LocalDensity 差し替えの外側**（Scaffold のルート）で適用し、内側で二重に適用しない |
| S4 | `Dialog` / `Popup` は別ウィンドウを持つ。CompositionLocal の伝播は**実装依存で未検証** | **伝播を前提にしない**。ダイアログ／ポップアップのコンテンツは `ScaledTheme`（テーマ + `LocalDensity` 差し替えをまとめたラッパー）で明示的に包む。テストで実寸を確認 |
| S5 | Compose Preview / スクリーンショットテストが実密度と異なる | Preview では `uiScale` を 1.0 に固定したバリアントを用意 |
| S6 | `Dp` を px に変換して Bitmap を作る箇所（アイコン）で二重スケール | アイコン生成は**スケール外の純粋関数**（`BookmarkIcons`）で完結させ、Compose の Density に依存させない |

### 7.3 edge-to-edge とインセット

**主張**: edge-to-edge は **Android 15（API 35）以降で自動的に有効**。それ以前は `WindowCompat.enableEdgeToEdge(window)` を `onCreate` で呼ぶ。システムバーは透明になり、アイコン色はテーマに応じて調整される。

- 出典: <https://developer.android.com/develop/ui/views/layout/edge-to-edge>
- 参照日: 2026-10-31 / 確信度: **確定**

**設計**: `enableEdgeToEdge()` を常に呼び（API 26 でも有効）、`Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` を使う。`windowSoftInputMode="adjustResize"` は現行 manifest に設定済み。IME は `Modifier.imePadding()`。カットアウトは `safeDrawing` に含まれるため個別対応不要（`shortEdges` は既存テーマで設定済み）。

**未検証**: 3ボタンナビの scrim、折りたたみのヒンジ（`WindowInsets.displayCutout` では取れない）の実機挙動 → AC-35 で確認。

---

## 8. アプリ内言語切替（AppCompat 非依存）

**主張**: API 33+ は `LocaleManager.setApplicationLocales(LocaleList)` / `getApplicationLocales()` が正式 API。後方互換の AndroidX API（`AppCompatDelegate.setApplicationLocales`）は **Android 12（API 32）以前では AppCompatActivity のコンテキストでしか動かない**。

- 出典: <https://developer.android.com/guide/topics/resources/app-languages>
- 参照日: 2026-10-31 / 確信度: **確定**

**本アプリの構成**: `ComponentActivity` + Compose（AppCompat 無し）。したがって:

| API | 方式 |
|---|---|
| 33+ | `LocaleManager.setApplicationLocales(LocaleList.forLanguageTags("ja"))`。OS が永続化し Activity を再生成する。アプリ内設定は**システム設定のミラー**として表示 |
| 26–32 | `BaseActivity.attachBaseContext()` で `Configuration.setLocales(LocaleList)` した `Context` を返す。切替時は保存 → `recreate()` |
| 共通 | `resourceConfigurations += listOf("en","ja")`（既存 `app/build.gradle.kts` に設定済み）、`android:localeConfig`（既存 `res/xml/locales_config.xml`）、`values/` と `values-ja/` の完全パリティ |

**注意点（推定を含む）**:

1. `attachBaseContext` は `super.onCreate` より前に呼ばれる。設定値は DI 初期化を待てないため、**軽量な SharedPreferences 直読み**で取得する（Application の `onCreate` に依存しない）。
2. `Application` コンテキストの Resources は切り替わらない。`applicationContext.getString()` を UI で使わない（Compose の `stringResource` は Activity コンテキストを使うため安全）。
3. API 33+ では `LocaleManager` が真実の源。アプリ内設定と二重管理しない（設定画面は `getApplicationLocales()` を読む）。
4. `recreate()` で WebView が破棄される → 現在 URL とスクロール位置を保存し復元する（AC-30）。
5. process death 後は `LocaleManager`（33+）／保存設定（26–32）から復元。

- 参照日: 2026-10-31 / 確信度: 1・3・4 は**確定**（API 仕様）、2・5 は**推定**（実測は AC-31）

---

## 9. ビルド・署名・検証

| 項目 | 事実 | 出典 | 確信度 |
|---|---|---|---|
| `apksigner verify` | Android SDK Build Tools の `apksigner` で署名検証。`--print-certs` で証明書を表示 | <https://developer.android.com/tools/apksigner> | 確定 |
| `lint` | `./gradlew lint` は `lint-results-*.html/xml` を出力。`abortOnError` で失敗させられる | <https://developer.android.com/studio/write/lint> | 確定 |
| AGP 9 / compileSdk 36.1 | `compileSdk { version = release(36) { minorApiLevel = 1 } }` は AGP 9 系の新 DSL（現行 `app/build.gradle.kts` で使用中） | 既存ビルドファイル | 確定（現物） |
| 署名情報の管理 | keystore とパスワードはリポジトリに置かない。環境変数 `KEYSTORE_PATH` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`（現行 `app/build.gradle.kts` の実装） | 既存ビルドファイル | 確定（現物） |

**未検証**: 実際の `lint` 0 エラー達成、release ビルドの `apksigner verify` 成功は本フェーズでは未実行（コード未実装のため）。AC-41 / AC-42 で検証する。

---

## 10. 実装に効く落とし穴 TOP10

1. **`SslCertificate.getX509Certificate()` は API 29+**。minSdk 26 で無条件に呼ぶと `NoSuchMethodError`。`saveState()` フォールバック必須（§2.1）。
2. **SSL ハンドラを保持したまま画面を離れるとリクエストが固まる**。`onCleared` とタイムアウトで必ず `cancel()`（§1.4）。
3. **`HttpAuthHandler` は UI スレッド必須**。コルーチンで別ディスパッチャから `proceed` すると落ちる（§3.1）。
4. **`useHttpAuthUsernamePassword()` を無視した自動入力は無限ループの最短経路**（§3.2）。
5. **`requestPinShortcut` は前景必須＋戻り値が成功を意味しない**。バックグラウンドのコルーチンから呼ぶと `IllegalStateException`（§4.1）。
6. **`security-crypto` は全 API 非推奨**。alpha06 のままにせず 1.1.0 に固定し、隔離する（§3.4）。
7. **`LocalDensity` 差し替えはインセットにも効く**。インセット適用はスケール外側（§7.2 S3）。
8. **`attachBaseContext` は DI より前**。設定を同期読み出しできる軽量ストアが必要（§8）。
9. **`onRenderProcessGone` で false を返すとアプリが落ちる**。true + WebView 再生成（§6.2）。
10. **`onReceivedSslError` はサブリソースでも発火し得る（未検証）**。画像1枚でインタースティシャルが出ると UX が壊れるため、メインフレームかどうかを提示し、同一ホストの処理中はデデュープする（§1.4）。

---

## 11. 未検証事項（実装フェーズで確認する）

| # | 未検証項目 | 検証方法 | 対応AC |
|---|---|---|---|
| U1 | API 26–28 実機での `saveState()` フォールバック動作 | エミュレータ API 26 / 28 で自己署名 HTTPS を開く | AC-11 |
| U2 | `onReceivedSslError` が iframe / 画像で発火する頻度と順序 | テストサーバーに自己署名画像を埋め込む | AC-12 |
| U3 | Digest 認証が WebView で `proceed()` により通るか | Python の Digest 対応サーバーで実測 | AC-25 |
| U4 | `requestPinShortcut` のユーザー拒否時の挙動（コールバック無し） | エミュレータで拒否操作 | AC-28 |
| U5 | 折りたたみ端末のヒンジ／マルチウィンドウでの `maxWidth` | Pixel Fold エミュレータ | AC-35 |
| U6 | API 33+ の `LocaleManager` とアプリ内設定の整合 | API 33 / 36 エミュレータで言語変更→再起動 | AC-31 |
| U7 | `LocalDensity` 2.1 倍時のレイアウト崩れ | スクリーンショット比較 | AC-34 |
| U8 | release APK の `apksigner verify` と lint 0 エラー | CI / ローカル | AC-41, AC-42 |
| U9 | 3ボタンナビの scrim とカットアウト | エミュレータ設定変更 | AC-35 |
| U10 | `security-crypto` 1.1.0 の Keystore 例外時の挙動 | 端末のロック解除方法変更後に復号 | AC-26 |
| U11 | **`proceed()` の判断が WebView 側で保持されるか**（§1.2 の解釈 A/B） | ピン済みホストの再読込・証明書差し替え・アプリ再起動の3条件で、`onReceivedSslError` が毎回呼ばれるかをデバッグログで確認 | AC-12b |
| U12 | ショートカットのアイコンに `IconCompat.createWithBitmap` が受理されるか | ピン留めしてランチャー表示を目視。失敗時はリソースアイコン fallback | AC-28 |
| U13 | `ROLE_BROWSER` の manifest 適格条件とロール要求ダイアログへの自アプリ表示 | API 29 / 33 / 36 エミュレータで `isRoleAvailable` と要求画面を確認 | AC-29b |
| U14 | `supportMultipleWindows=false` で `target=_blank` が同一 WebView で開くか | テストページに `target=_blank` リンクを置いて確認 | AC-06 |

---

## 12. 出典一覧（すべて 2026-10-31 参照）

| # | URL | 用途 | 取得 |
|---|---|---|---|
| 1 | https://developer.android.com/reference/android/webkit/WebViewClient | SSL / 認証 / レンダラ / Safe Browsing コールバック | 200 |
| 2 | https://developer.android.com/reference/android/webkit/SslErrorHandler | proceed/cancel の意味 | 200 |
| 3 | https://developer.android.com/reference/android/net/http/SslError | getCertificate / getPrimaryError / エラー定数 | 200 |
| 4 | https://developer.android.com/reference/android/net/http/SslCertificate | getX509Certificate（API 29）/ saveState / restoreState | 200 |
| 5 | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/net/http/SslCertificate.java | Bundle キー `x509-certificate` の実装 | 200 |
| 6 | https://developer.android.com/reference/android/webkit/HttpAuthHandler | proceed / cancel / useHttpAuthUsernamePassword | 200 |
| 7 | https://developer.android.com/reference/android/webkit/WebViewDatabase | clearHttpAuthUsernamePassword | 200 |
| 8 | https://developer.android.com/reference/android/content/pm/ShortcutManager | requestPinShortcut / isRequestPinShortcutSupported / 上限 | 200 |
| 9 | https://developer.android.com/reference/androidx/core/content/pm/ShortcutManagerCompat | requestPinShortcut / setDynamicShortcuts | 200 |
| 10 | https://developer.android.com/reference/android/app/role/RoleManager | ROLE_BROWSER / createRequestRoleIntent | 200 |
| 11 | https://developer.android.com/reference/android/webkit/WebSettings | file access / mixed content / Safe Browsing | 200 |
| 12 | https://developer.android.com/reference/android/webkit/WebView | setWebContentsDebuggingEnabled | 200 |
| 13 | https://developer.android.com/jetpack/androidx/releases/security | security-crypto の非推奨化 | 200 |
| 14 | https://developer.android.com/develop/ui/views/layout/webapps/load-local-content | WebViewAssetLoader / appassets.androidplatform.net | 200 |
| 15 | https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes | ウィンドウ幅ブレークポイント | 200 |
| 16 | https://developer.android.com/develop/ui/views/layout/edge-to-edge | WindowCompat.enableEdgeToEdge / Android 15 自動有効 | 200 |
| 17 | https://developer.android.com/guide/topics/resources/app-languages | per-app language / 後方互換の制約 | 200 |
| 18 | https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/package-summary | LocalDensity | 200 |
| 19 | https://developer.android.com/reference/kotlin/androidx/compose/ui/unit/Density | fontScale | 200 |
| 20 | https://developer.android.com/tools/apksigner | 署名検証 | 200（本文は未精読・推定） |

> 注: web_search は本セッションで利用不可（API キー未設定）だったため、検索エンジン経由の二次情報は使っていない。すべて直接 URL 取得による一次資料である。
