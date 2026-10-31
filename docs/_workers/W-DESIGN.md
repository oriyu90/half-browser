# W-DESIGN — TO-PLAN 設計批判 / テスト設計

- worker: W-DESIGN (design critic + test architect / read-only)
- parent: TO-PLAN-HB-01 / WP-PLAN-DESIGN
- 作成: 2026-10-31 (Asia/Tokyo) / 対象: D1–D11, `com.yukiorita.halfbrowser` minSdk 26 / targetSdk 36
- 立場: 同意・世辞なし。反証・修正案・検証手順のみ。
- 表記: 【事実】= 一次資料またはコードで確認 / 【推定】= 妥当な推測 / 【未検証】= 実験が必要。
- 注: リポジトリには既に実装が存在する（`HalfBrowserWebViewClient.kt` 223 行、`BrowserState.kt`、`CredentialStore.kt` 等）。本稿は D1–D11 を批判しつつ、**設計文書とコードが食い違っている箇所**を事実として指摘する（実装の修正は scope_out）。

STATUS: DONE

---

## 0. 判定サマリ（D1–D11）

| # | 判定 | 一言 |
|---|---|---|
| D1 | **変更（条件付き維持）** | WebView の ViewModel 保持は維持可。ただし `recreate` 後の baseContext 差し替え・`onRenderProcessGone`・プロセス死時は URL のみ復元、を必須条件に。 |
| D2 | **変更** | 「proceed が保持されうる」を設計前提に降ろす。帯域外プローブ＋(host,cert) キュー＋SPKI 併記＋26–28 縮退。 |
| D3 | **変更** | `useHttpAuthUsernamePassword()` をゲートから外す（意味が違う）。キーに scheme/port、epoch リセット、Main スレッド、Digest 特例。 |
| D4 | **維持＋補強** | SP+JSON は妥当。復旧モード・schemaVersion・要素単位 quarantine を追加。 |
| D5 | **変更** | adaptive bitmap・432px・リソース fallback・書記素クラスタ分割。 |
| D6 | **維持＋補強** | `intent://` の扱いが現状で危険。scheme allowlist・`isForMainFrame`・chooser 自己除外。 |
| D7 | **変更** | `enableShortcuts` 先行・ライフサイクルガード・ID 名前空間・1 セッション 1 回。 |
| D8 | **変更** | density 乗算を撤回（fontScale のみ／上限 1.5x）。列数は物理 dp。Dialog 内 Provider 必須。 |
| D9 | **変更** | 言語の真実を 1 つに。33+ で attachBaseContext に保存値を被せない。再生成後の WebView context。 |
| D10 | **維持＋補強** | cleartext は既に manifest で許可済み（正しい）。`allowContentAccess=false`・位置情報 false・third-party cookie の決定が未記載。 |
| D11 | **変更** | L3 のビルド設定が現状存在しない。Paparazzi/GMD/保持実験の位置づけを明確化。 |

---

## A. D2 SSL — 最大リスクと代替設計

### F-01 / CRITICAL / D2 / proceed 判断の「WebView 側保持」を設計前提にしていない

- **観測**: 公式は `WebViewClient.onReceivedSslError` に "The decision to call proceed() or cancel() may be retained to facilitate responses to future SSL errors." と明記。保持の主体・キー（host か cert か WebView か）・寿命はどこにも書かれていない。D2 は「毎回コールバックが来る」前提で、ピン台帳・インタースティシャル・「今回だけ」を設計している。
- **なぜ問題か**: 保持が WebView 側だと、以下が同時に壊れる。
  1. ピン未登録／失効／証明書変更でも 2 回目以降コールバックが来ず**素通り**する。ユーザーには何も表示されない。
  2. 「今回だけ」が実質「この WebView が生きている限り常に」になる。UI の約束が嘘になる。
  3. **ピン削除（信頼解除）が効かない**。台帳から消しても WebView が覚えていれば通信は通る。「削除したのに繋がる」はセキュリティ UI として最悪の失敗。
  4. サブリソースで proceed した場合、その保持がメインフレームにも及ぶか不明。
- **反証/根拠**: https://developer.android.com/reference/android/webkit/WebViewClient （参照日 2026-10-31）。保持の詳細は一次情報に無い＝**設計は「保持されうる」に倒すしかない**。
- **修正案**（すべて採用推奨。1 つでも欠けると穴が残る）:
  - **(a) 意味の再定義**: 「今回だけ」= *この WebView インスタンスの寿命まで*。UI 文言と AC をこれに合わせる。プロセス死・WebView 再生成で消えることを明記。
  - **(b) 帯域外プローブ（検知）**: ピン済みホストへの**トップレベル遷移開始時のみ**、アプリ自身が `SSLSocket`/`HttpsURLConnection` で leaf 証明書を取得し DER/SPKI を比較。不一致なら `view.stopLoading()` → インタースティシャル。これが「保持の穴」を塞ぐ唯一のアプリ側検知手段【推定】。コスト: 遷移ごと 1 RTT、CDN/LB/SNI/IPv4-v6 で別証明書 → 誤検知。対策: 一致判定は SPKI、プローブ失敗は fail-closed（インタースティシャル）、プローブはピン済みホストのみ。
  - **(c) 事後検知（無料）**: `WebView.getCertificate()` は【事実】API 1・「main top-level page の SSL 証明書、無ければ null」を返す。`onPageFinished` で https メインフレームのみ、この証明書の DER をピンと比較。**不一致＝ユーザー承認なしに別証明書で通信した**と確定できる（保持またはピン照合バグ）。コストゼロ、ただし main frame 限定・エラーページでは null。
  - **(d) 信頼解除時は WebView を破棄・再生成**。`state.ssl` の cancel だけでは保持は消えない。
  - **(e) 実験をリリースゲートに**（下記 AC-SSL-01）。
- **検証方法**: 同一 WebView・同一 `host:port` で証明書 A→B に差し替え（qa-server を SIGUSR1 で reload）、2 回目の `onReceivedSslError` 発火有無と `getCertificate()` の値をログ。API 26/29/33/36 で記録。

### F-02 / HIGH / D2 / 「同時1件・2件目は即 cancel」はサブリソースでページを壊す

- **観測**: 画像 20 枚の自己署名ページでは `onReceivedSslError` がサブリソース分も発火しうる【推定・実機要確認】。D2 は 2 件目以降を即 cancel する。現実装はさらに「新しい challenge が来たら前の challenge を cancel して置き換える」（`state.ssl?.cancel()` → 新規）。結果、**最後の 1 件だけが保留され、他は全部キャンセル**される。
- **なぜ問題か**: ユーザーが「今回だけ」を押しても、cancel 済みのサブリソースは戻らない。画像も CSS も欠けたページが出て、再読み込みしない限り直らない。「壊れたページ + 証明書ダイアログ」は初心者には因果不明。
- **修正案**: 保留単位を `(host, certFingerprint)` にする。同一キーのハンドラは**キューに積む**（上限 32、超過分は cancel + バナー「このページの一部を読み込めませんでした」）。決定時に同一キューを一括 proceed / cancel。別証明書は即 cancel。承認後、メインフレームが https なら `reload()`（POST 中なら「再読み込みしてください」表示）。
- **検証方法**: qa-server に自己署名 + 画像 20 枚のページ。L3 で「インタースティシャルが 1 回だけ」「承認後のページで画像が 20/20 表示」を assert。

### F-03 / HIGH / D2 / API 26–28 では指紋が取れず「常に Unknown」＝信頼を記憶できない

- **観測**: `SslCertificate.getX509Certificate()` は API 29+。実装の `certificateFingerprint()` は `certificate?.x509Certificate` を `runCatching` で包んでいるだけ（`BrowserState.kt:92-95`）。26–28 では `NoSuchMethodError` が握り潰され **常に null**。`fingerprint == null` → `TrustDecision.Unknown` → インタースティシャルは出るが、**保存する指紋が無いので「常に信頼」を押しても次回また出る**（無限リピート）。さらに `runCatching` が `NoSuchMethodError` を隠すため、Lint の `NewApi` も検出しにくい。
- **なぜ問題か**: minSdk 26 の端末（＝要件の対象）で TOFU 機能が静かに死ぬ。クラッシュしないので気づかれず、「毎回ダイアログが出るブラウザ」として評価が落ちる。
- **修正案**: API 26–28 は `SslCertificate.saveState(certificate)` の Bundle から DER を取り出す経路を第一にする（キー名 `"x509-certificate"` は非公開なので、キー名決め打ちではなく **Bundle 内の byte[] を走査して `CertificateFactory.generateCertificate` に通るものを探す**フォールバックを併用）。それも失敗したら「この端末では証明書を記憶できません」と明示し、**セッション限りの一時許可のみ**（保存ボタンを出さない）。`runCatching` で `NoSuchMethodError` を飲む実装は禁止（`Build.VERSION.SDK_INT` 分岐 + `@RequiresApi`）。
- **検証方法**: API 26 エミュレータで自己署名ページ → インタースティシャル表示 → 「常に信頼」→ 再読み込みでダイアログが出ないこと。Bundle 走査の単体テスト（合成 Bundle）。

### F-04 / MEDIUM / D2 / ピン対象 DER SHA-256 は要件に合致するが、更新で必ず「Changed」になる

- **観測**: 要件は「証明書 SHA-256」。実装も DER の SHA-256。妥当。ただし自己署名証明書は再発行（有効期限延長・鍵再生成）が日常的で、DER ピンは**必ず**不一致になる。同じ鍵で再発行しただけでも再確認。
- **なぜ問題か**: 「Changed」警告が頻発するとユーザーは反射的に「常に信頼」を押す（warning fatigue）。警告の意味が失われる。さらに F-01 の保持と組み合わさると「警告が出ないまま新証明書で通信」も起こる。
- **修正案**: **両方保存**する。`spkiSha256`（照合の主キー）と `certSha256`（表示・監査・要件充足）。判定は 3 状態: SPKI 一致 & DER 一致 = Trusted / SPKI 一致 & DER 不一致 = **Renewed**（弱い警告「証明書が更新されました（公開鍵は同一）」、ワンタップ承認）/ SPKI 不一致 = Changed（強い警告、常に信頼は明示操作 2 段）。要件の「証明書 SHA-256 を表示・保存」は DER を保存することで満たす。
- **検証方法**: 同一鍵・新期限の証明書を openssl で作り、Renewed 分岐に入ること（L1 の純関数 + L3）。

### F-05 / MEDIUM / D2 / ピン鍵が host のみ → 同一ホストの複数ポート・複数証明書で交互に「Changed」

- **観測**: `TrustPolicy.decide(host, fingerprint, saved: TrustedSite?)` は host につき 1 件しか持てない（`container.trust.find(host)`）。LAN 機器は `https://192.168.0.10:8443` と `:9443` で別の自己署名証明書を使うことが普通にある。
- **なぜ問題か**: A を信頼 → B で Changed → B を信頼 → A で Changed の無限ループ。ユーザーは「このブラウザは壊れている」と判断する。
- **修正案**: キーは host のまま、値を **証明書の集合**（host あたり最大 8、全体 50 host）にする。ポートはキーに含めない（証明書の同一性はポートに依らない。含めると「ポート違いは別サイト」という別の誤りになる）。上限に達したら最も古い pin を LRU で落とし、UI に「古い信頼を 1 件破棄しました」を出す。
- **検証方法**: L1 で 2 証明書の交互 decide。L3 で 2 ポートの qa-server。

### F-06 / LOW / D2 / 60 秒タイムアウトは「読んでいる最中に cancel」

- **観測**: D2 は 60 秒でタイムアウト。証明書の Subject/Issuer/指紋を読み比べるには 60 秒は短い。タイムアウト後にユーザーが押すと、cancel 済み handler への proceed（挙動未定義）。
- **修正案**: タイムアウトは「ダイアログを閉じて cancel」まで。状態を null にすることが唯一の真実で、遅延した UI コールバックは `settled` フラグで無視（実装済みの `SslChallenge.settled` は良い）。タイマーはナビゲーション epoch で必ずキャンセル。できればタイムアウトは 120 秒 + 「このまま待つ」。
- **検証方法**: L1 の状態機械（timeout → late proceed が no-op）。

### D2 の代替設計（毎回 cancel → 自前 UI → 再ロード）の評価

| 副作用 | 評価 |
|---|---|
| POST 本文喪失 | **致命的**。`loadUrl()` 再実行は GET になる。自己署名 LAN 機器の設定画面は POST が主戦場。設定の保存・ファーム更新・ワンタイムトークンが飛ぶ。 |
| 二重リクエスト | GET は無害、POST は二重適用（設定の重複適用、二重送信）。 |
| リダイレクト | 最終 URL ではなく `error.url` を使わないと認証フローが壊れる。元 URL を覚えておく必要。 |
| 履歴/bfcache | `loadUrl` は履歴を 1 つ増やす。戻るが壊れる。`replace` 相当は無い。 |
| サブリソース | 全サブリソースが cancel → 大量の欠落。再ロードで二度手間。 |

**採否: 単独では不採用**。初回の未知ホストは「保留 → 承認 → proceed」が正しい（現行 D2 の骨格は維持）。毎回 cancel は (i) 信頼解除直後、(ii) 保持が実機で確認された後の強制再確認、の 2 ケースに限定する。

---

## B. D3 HTTP 認証 — 再入・失効・失敗検知

### F-07 / HIGH / D3 / `useHttpAuthUsernamePassword()` は「アプリが ESP に保存した資格情報」の話ではない

- **観測**: 公式の定義は「現在のホストに対して**保存済みの**資格情報が使用に適するか。**以前サーバーに拒否されていれば false**」。この「保存済み」は WebView/WebViewDatabase 側の保存分。D3 は EncryptedSharedPreferences に自前保存しており、WebView 側には何も保存していない。したがって戻り値は「常に false（保存が無い）」か「常に true（拒否記録が無い）」のどちらかになり、**ゲートとして機能しない**【推定・要実機確認】。さらに現実装（`HalfBrowserWebViewClient.kt:120`）はこの API を一切使っておらず `attempts < 2` の自前カウンタで動いている。**設計文書とコードが矛盾**。
- **なぜ問題か**: 「公式 API でループ防止している」という safety claim が嘘になる。レビューで突かれる。また将来この API を実装した人が、false 固定により自動入力が永久に無効だと誤解する。
- **修正案**: D3 から `useHttpAuthUsernamePassword()` を削除し、**自前カウンタに一本化**する（現実装の方向が正しい）。WebViewDatabase に保存する選択肢は、秘密がアプリ鍵で守られないため不可。
- **検証方法**: API 26/33 実機で、ESP のみ保存した状態の初回 401 で戻り値をログ。false なら確定。L1 では「ゲートが自前カウンタのみ」を interface で表現。

### F-08 / HIGH / D3 / 資格情報キーに scheme と port が無い → https で保存した秘密が http で送られる

- **観測**: D3 のキーは `scheme+host+port+realm` と書かれているが、実装の `CredentialStore.keyOf()` は `"host|realm"` のみ（`CredentialStore.kt:124`）。`StoredCredential` にも scheme/port が無い。
- **なぜ問題か**: `http://example.com` の 401 で、`https://example.com` 用に保存した資格情報が自動入力される。**平文 HTTP への資格情報ダウングレード**。同一ホストの 8080 と 8443 が別 realm でも取り違える。
- **修正案**: キー = `scheme + host + port + realm`（realm は長さ上限 256・制御文字除去・保存キーは SHA-256 短縮可）。**https 以外では自動入力をしない**（手動入力も「暗号化されていません」警告付き）。Digest も同様。
- **検証方法**: L1（キー生成・衝突）、L3（http と https の qa-server で自動入力の有無）。

### F-09 / MEDIUM / D3 / 「同一キー再要求」による失敗検知は Digest の正常系を誤検知する

- **観測**: 失敗＝「自動 proceed 直後に同一キーで再要求」と定義。しかし Digest は `stale=true`（nonce 期限切れ）で**正常に 401 を返す**。クライアントは同じ資格情報で再試行するのが正しい。他にも、http→https リダイレクトで同じ realm、プロキシ 407、認証付きフォームページ自体が Basic 保護、など「2 回目がある正常系」は珍しくない。逆に、1 回目で成功したのにページ内 iframe が別 realm を要求するケースはキーが違うので誤検知しない（これは設計が正しい）。
- **修正案**: 判定条件を「(i) **保存済み**資格情報で自動 proceed した、(ii) 同一ナビゲーション epoch 内、(iii) 同一キー、(iv) Digest の `stale` ではない」の 4 条件 AND にする。カウンタは「手動入力成功」「新規ナビゲーション開始」「`onPageFinished`」でリセット。上限 2 は**自動入力の抑止のみ**で、ダイアログは常に出す（ユーザーをロックアウトしない）。現実装は `authAttempts` をリセットしないため、一度 2 回失敗すると**そのセッション中ずっと**自動入力が無効のまま（F-09 の実害）。
- **検証方法**: qa-server に `stale=true` を返す Digest モードを実装し、L4 で「ダイアログが出ないこと」。Basic の 2 回 401 は L3。

### F-10 / MEDIUM / D3 / handler 保持の失敗モード（遷移・二重 proceed・スレッド）

- **観測と評価**:
  - **画面回転**: manifest の `configChanges` で再生成を回避しており、handler は ViewModel 側で生存。設計としては成立。ただし `uiMode`/`fontScale`/`density` まで握っているため、OS 設定変更でも再生成されない（意図通りか要確認）。
  - **プロセス死**: handler は消える。WebView の状態復元が bfcache 由来だと 401 が再発行されず、**ダイアログ無しで 401 ページ**が出る可能性【推定】。→ 復元時は必ず再ロードし、認証はサーバーの 401 に再度挑ませる。
  - **ページ遷移**: JS の `location=` やリダイレクトでページが変わると handler は失効【推定】。現実装は `onPageStarted` で保留を破棄していない → **遷移後もダイアログが残り、proceed すると無効 handler**（クラッシュまたは無視）。→ `onPageStarted`（URL が変わった場合）で保留を cancel + state=null。
  - **二重 proceed**: `AuthChallenge.settled` で防止済み（実装は良い）。ただし「保存」を IO で行ってから proceed する場合、**IO スレッドから proceed を呼ぶ**事故が起きる。`proceed` は UI スレッド必須（公式）。→ 保存は IO、proceed は `withContext(Dispatchers.Main)`。
  - **タイムアウト後の遅延応答**: state を null にした後の UI コールバックは `settled` で無視。
- **修正案**: `(host, realm, navigationEpoch)` を持つ `PendingAuth` に一本化し、epoch 不一致は破棄。proceed 直前に `Looper.myLooper() == Looper.getMainLooper()` を assert（debug ビルド）。
- **検証方法**: L1 の状態機械（遷移・timeout・二重呼び出し）、L3 で「ダイアログ表示中に JS で遷移 → クラッシュしない・ダイアログが消える」。

### F-11 / MEDIUM / D3 / Digest に保存パスワードを使う危険

- **観測**: Digest は平文パスワードから response を計算する。`proceed(user, pass)` は WebView 内部で計算するため、アプリは平文を保持し続ける必要がある。http:// 上の Digest は challenge/response を傍受してオフライン辞書攻撃が可能。
- **修正案**: 保存は「https かつ（ピン済み or 信頼済み）」のホストのみ。http では保存ボタンを出さず、自動入力もしない。UI に「この接続は暗号化されていません」を常時表示。
- **検証方法**: L1（保存可否の純関数）、L3（http で保存ボタンが無い）。

---

## C. EncryptedSharedPreferences 非推奨と鍵無効化

### F-12 / HIGH / C / 鍵無効化・復号失敗が「静かな資格情報喪失」になっている

- **観測**: `CredentialStore` は初期化時に `runCatching` で ESP 生成を握り、失敗すると `prefs = null` → **メモリのみ**にフォールバックし、警告は Log だけ（`CredentialStore.kt:40-53`）。`readAll()` も decode 失敗を `emptyMap()` に握り潰す（104-109）。ユーザーには何も表示されない。
- **なぜ問題か**: 端末のロック変更・復元・Keystore 不整合で保存済み資格情報が消えるが、ユーザーは「保存したはず」と思っている。次の起動で 401 ダイアログが出て初めて気づく。サポート不能な「静かな喪失」はクラッシュより悪い。
- **修正案**: `CredentialVault` interface（find/save/delete/clear/isPersistent + `VaultState { Ok, Unavailable, Wiped(reason) }`）の背後に隔離。復号不能時は (1) `halfbrowser_secure.corrupt-<ts>` にリネーム、(2) 新規作成、(3) **次回起動時に 1 回だけ**スナックバー「端末のロック変更などにより保存済みの認証情報を破棄しました。ブックマークは影響を受けません」。`emptyMap()` を黙って返さない。同一プロセス内で即再作成しない（Keystore 不整合が続く場合がある）。
- **検証方法**: L2（fake vault）/ L3（`adb shell` で keystore を壊すのは困難 → 単体で「ESP 生成が例外」を注入できるようにコンストラクタで factory を差す）。

### F-13 / MEDIUM / C / 非推奨ライブラリ受容の判断は妥当。ただし移行コストと移行トリガを先に決める

- **観測**: security-crypto は 1.1.0-beta01 で全 API 非推奨（リポジトリは alpha06）。Tink ベースで、自前 Keystore 実装より事故が少ないのは事実。
- **判断**: **v1 は受容可**。理由: (a) 実装済みで実績がある、(b) 代替の自前実装は鍵無効化・IV 管理・`KeyPermanentlyInvalidatedException` 処理を含む 200–300 行、(c) 非推奨は「削除」ではない。
- **条件**: (1) alpha/beta 固定を `libs.versions.toml` で明示し、更新時に必ず L3 を通す、(2) `CredentialVault` で隔離（上記）、(3) 移行トリガを決める: 「security-crypto が Maven から消えた」「AGP/compileSdk 更新でビルド不能」「鍵無効化の再現率が高い端末で問題」。

### F-14 / MEDIUM / C / 「全消去フェイルセーフ」は妥当だが条件付き

- **判断**: クラッシュループより全消去が正しい。ただし (a) 消すのは secure ファイルのみ（ブックマーク・設定は無傷）、(b) ユーザー通知必須、(c) 退避コピーを残す、(d) 消去後の再作成は次回起動。この 4 条件を満たすなら妥当。
- **移行コスト（Keystore 直実装）**: AES-256-GCM + AndroidKeyStore、IV 12byte を暗号文先頭に、`setUserAuthenticationRequired(false)`、`KeyPermanentlyInvalidatedException`/`AEADBadTagException` を Wiped に写像、既存 ESP からの one-shot マイグレーション、JVM テスト用 fake。**1.5–2 人日**。v1 では不要。

---

## D. D4 SharedPreferences + JSON

### F-15 / HIGH / D4 / `.bak` 方式の穴 — 両方破損で「静かに空」

- **観測**: D4 は「破損時 .bak 退避」。しかし (1) 復元直後に復元内容を .bak に上書きすると次回復元不能、(2) 両方破損した場合の規定が無い。SP の単一キー書き込みは AtomicFile で原子的【事実】だが、**JSON 文字列と .bak の 2 ファイル更新は原子的でない**。
- **なぜ問題か**: 破損時に `emptyList()` で起動すると、ユーザーには「ブックマークが全部消えた」と見える。最悪のデータ損失。
- **修正案**: (a) .bak は「直前の**正常にパースできた**内容」のみ、新内容のパース成功後に更新。(b) 両方破損時は**空で起動しない**。`bookmarks.corrupt.<ts>.json` に退避し、UI は「ブックマークを読み込めませんでした（ファイルは保全しています）」+ エクスポート導線 + 「新規に開始」の明示操作。(c) JSON に `schemaVersion`。未知の**高い**バージョンは上書き禁止（読み取り専用 + 警告）。(d) 要素単位で復元し、不正要素だけ quarantine に（1 件の不正で全滅させない）。
- **検証方法**: L1 で 6 パターン（正常 / 片側破損 / 両側破損 / 未知 version / 要素 1 件不正 / 空文字）。L3 でプロセス殺し→再起動。

### F-16 / MEDIUM / D4 / 並行・性能・書き込みスレッド

- **観測**: Mutex はプロセス内のみ。`SettingsRepository` 等が別インスタンスで同じファイルを触ると競合。500 件の JSON は 100–200KB、encode+commit で 10–40ms。
- **修正案**: (a) リポジトリは AppContainer が単一所有、`withLock` + 「書いた値をそのまま StateFlow に流す」（読み戻さない）。(b) `bookmarks` は `commit()`、設定は `apply()` と使い分けを明文化。(c) encode/commit は IO、UI からは 250ms デバウンス。(d) 起動時 read は 5–15ms なので同期でも可だが StrictMode で検証。
- **DataStore に行く基準**: 複数プロセス/複数書き手、1MB 超、部分更新が必要、型付きスキーマが必要、のいずれか。現状は該当せず **SP 維持**が正しい。
- **検証方法**: L1（500 件 encode/decode のベンチを JVM で）、L3（StrictMode `detectDiskWrites` で編集操作中に violation が無い）。

---

## E. D5 モノグラムとショートカット Bitmap

### F-17 / HIGH / D5 / ショートカット Bitmap 216px は「ランチャーが受理するか未検証」のまま出荷しない

- **観測**: `ShortcutInfoCompat.Builder.setIcon(IconCompat)` は Bitmap を受ける。ただしピン留めアイコンは**ランチャーが描画**し、Adaptive Icon（前景/背景）でない Bitmap は機種によってマスク・余白・背景色が変わる【未検証】。216px は 108dp@xhdpi 相当で、xxxhdpi のランチャーでは拡大される。
- **なぜ問題か**: 「ホーム画面に追加」した結果が**灰色の四角**や**文字が切れたアイコン**になる。機能の第一印象を決める箇所で、しかもアプリから修正できない（ピンは消せない）。
- **修正案**: `IconCompat.createWithAdaptiveBitmap()` を使う。前景 Bitmap は 108dp 相当（**432px**、xxxhdpi）にし、モノグラムは**セーフゾーン 66/108 の内側**に収める。背景はテーマ色の単色。生成に失敗したら `R.drawable.ic_shortcut_fallback`（リソース、Adaptive Icon XML）にフォールバック。**Bitmap 経路を唯一の経路にしない**。
- **検証方法**: Pixel Launcher / Samsung One UI / Xiaomi の 3 実機で L4（ピン留め → アイコン外観）。L3 では `ShortcutInfoCompat.icon` が null でないこと・生成例外が無いことまで。

### F-18 / MEDIUM / D5 / 「1–2 文字」を `take(n)` でやると濁点・絵文字・国旗が壊れる

- **観測**: 書記素クラスタを考慮しない実装（`title.take(1)`）は (i) 結合濁点 `か`+U+3099 を分離、(ii) ZWJ 絵文字（👨‍👩‍👧‍👦）を分解、(iii) 国旗（地域指示子 2 個）を 2 文字扱い、(iv) サロゲートペアで壊れる。
- **修正案**: `java.text.BreakIterator.getCharacterInstance()` で書記素クラスタ単位に分割し、最大 2 クラスタ。フォントサイズはクラスタ数と文字種（CJK / ラテン）で自動調整。長い英語タイトルは「最初の英数字」を大文字化。記号だけならホスト名の頭文字。**コントラストは HSL の L をテーマ別に固定**（light: 文字 L=0.30–0.35 / 背景 0.90、dark: 文字 0.78–0.85 / 背景 0.20）し、WCAG AA 4.5:1 を全 360 色相 × 2 テーマで検証。
- **検証方法**: L1（既存 `BookmarkIconsTest` を拡張。結合文字・ZWJ・国旗・空文字・記号のみ・1000 文字）。コントラストは相対輝度計算の JVM テスト。

---

## F. D6/D7 外部 URL・ピン留め

### F-19 / CRITICAL / D6 / `intent://` を `Intent.parseUri` → `startActivity` している（任意コンポーネント起動）

- **観測**: `HalfBrowserWebViewClient.handleSystemScheme()` は `intent://...#Intent;component=com.victim/.SecretActivity;end` を `Intent.parseUri` し、`resolveActivity != null` のみ確認して `startActivity` する（164–174 行）。`resolveActivity` は「起動可能」の証明にしかならない。**Web ページが任意の exported コンポーネントを起動できる**。`shouldOverrideUrlLoading` は `request.isForMainFrame` も見ていない。
- **なぜ問題か**: ブラウザは信頼境界。悪意あるページが `intent://` で他アプリの危険な exported Activity を起動できる。D6 の IntentRouter は「Android 型非依存で JVM テスト」と書くが、**この経路の検証が設計に無い**。
- **修正案**: `intent:` スキームは**ブラウザからは起動しない**（`openExternalApp` に渡さず「開けません」）。どうしても必要なら (i) `selector`/`component`/`package`/`sourceBounds` を含む Intent を拒否、(ii) `ACTION_VIEW` + `data` が http(s) のものだけ許可、(iii) `CATEGORY_BROWSABLE` を強制。`shouldOverrideUrlLoading` は `request.isForMainFrame == true` のときだけ外部スキームを処理（iframe の `tel:` で勝手に電話アプリが開く事故を防ぐ）。scheme allowlist は `http/https/tel/mailto/sms` のみ。
- **検証方法**: L1（IntentRouter に 12 種の敵性 URL を通す表駆動テスト）。L3 で「`intent://` ページを開いても他アプリが起動しない」。

### F-20 / HIGH / D7 / ピン留めの失敗モードが設計に無い（disabled ID・拒否無通知・削除不能）

- **観測と帰結**:
  - 同一 ID が **disabled** だと `IllegalArgumentException`（確定事実）。再ピン要求の前に `enableShortcuts` が要る。
  - 戻り値 true は「要求を送れた」だけ。**拒否は永遠に通知されない**。成功トーストを出すと嘘になる。
  - 前景必須（Activity/Foreground service）。デバウンス後に背景だと `IllegalStateException`。
  - ブックマーク削除後も**ピンはアプリから消せない**。`disableShortcuts` で「無効メッセージ」にするのが上限。
  - アプリ更新で Activity/alias を変えると無効化。バックアップ復元ではピンは復元されない。
- **修正案**: (1) `requestPinShortcut` の前に `enableShortcuts(listOf(id))`、それでも例外なら ID を `pin_<bookmarkId>_<epoch>` にフォールバック。(2) UI は「追加を要求しました。ホーム画面をご確認ください」で止め、成功表示をしない。(3) `Lifecycle.currentState.isAtLeast(STARTED)` を確認してから IO で実行。(4) 1 セッション 1 回、要求中はボタン無効。(5) 削除時は `disableShortcuts` + UI 文言「ホーム画面のアイコンは自動では消えません」。(6) 動的とピンで ID を名前空間分離（`dyn_` / `pin_`）。
- **検証方法**: L1（fake ShortcutGateway で disabled 例外 → enable リトライ）。L4（実機で拒否・承認・削除後タップ）。

### F-21 / MEDIUM / D6 / `EXTRA_TEXT` 巨大入力と chooser の自己再帰

- **観測**: 共有テキストはサイズ上限が無い。既定ブラウザ時に「別のアプリで開く」で `createChooser` を使うと**自分自身が候補に出て**ループする。
- **修正案**: `EXTRA_TEXT`/`ClipData` は 8KB で切り詰め、URL 抽出は `Patterns.WEB_URL` で先頭 1 件、複数あれば確認ダイアログ。chooser は `queryIntentActivities` → 自パッケージを除外した explicit intent の集合から作る。外部へ投げた後の再帰は `FLAG_ACTIVITY_NEW_TASK` + `singleTask` で抑制。
- **検証方法**: L1（1MB 文字列で例外なし・8KB 切り詰め）、L3（既定ブラウザ状態で chooser に自分が出ない）。

---

## G. D8/D9 適応・スケール・言語

### F-22 / CRITICAL / D8 / `LocalDensity` 差し替えは Dialog/Popup に伝播しない（推定・要 1 時間検証）

- **観測**: Compose の `Dialog` は新しい window と新しい `AndroidComposeView` を作り、その root で `ProvideCommonCompositionLocals` が**その view の Density** を提供する。親の `CompositionLocalProvider(LocalDensity = ...)` は root で上書きされる【推定】。`Popup` は同一 window の ViewGroup なので伝播する可能性が高い【推定】。
- **なぜ問題か**: SSL インタースティシャルと認証ダイアログは**最も読みやすさが必要な画面**。そこだけ 2.1x が効かないと、アクセシビリティ要件は「主要画面で満たさない」ことになる。F-01/F-06 の判断 UI が小さい文字のままは本末転倒。
- **修正案**: `AppScale { content }`（`CompositionLocalProvider(LocalDensity = ...)`）を作り、**すべての `Dialog`/`Popup` の content の内側**で巻く。加えて「Dialog を使うときは必ず AppScale で巻く」ことを grep 可能な規約にし、L3 のスクリーンショットテストで 2.1x のダイアログを検証。
- **検証方法**: 1 時間の実験 — Dialog 内に `LocalDensity.current.density` を表示する Text を置き、外側で 2 倍に差し替えて値を見る。伝播しなければ上記修正。

### F-23 / HIGH / D8 / density と fontScale の二重乗算 + 3 列クランプで 2.1x が破綻する

- **観測**: D8 は「density と fontScale の両方に乗算」。OS の「表示サイズ」×「フォントサイズ」× アプリ設定で最大 4 倍超。グリッドは `usable/104dp` を 3..8 にクランプ。
- **なぜ問題か（2.1x の予測破綻箇所）**:
  1. compact 端末 360dp → 実効 171dp。クランプ最小 3 列 → **セル 57dp**。タイトル 2 行が入らず、タップターゲットも不足。
  2. 上部バー（URL バー + 進捗 + タイトル）が画面高の 30% 超。
  3. 証明書指紋 64 hex が 6 行に折り返し、ダイアログが画面を超える（`widthIn(max=560dp)` は拡大後の dp で画面幅を超える）。
  4. `Scaffold` のインセットと画面側 `safeDrawingPadding` の二重適用でステータスバー分が 2 倍。
- **修正案**: (a) **アプリ内スケールは fontScale のみ**。density は触らない（dp レイアウトは OS の表示サイズ設定が既に扱う）。(b) どうしても density を触るなら上限 1.5x、かつ**列数は物理 dp で決める**: `columns = floor(physicalWidthDp / (104 * scale))`、min 1。スケール後 dp で割ると必ず 1 列になるが、それが正しい。(c) ダイアログは `fillMaxWidth(0.92f)` + 縦スクロール。(d) インセットは root で px のまま `windowInsetsPadding`（density 差し替えの外側）。
- **検証方法**: Paparazzi（JVM、WebView 不要）で home/settings/dialog を 4 サイズ × 3 スケール。実機は L4。

### F-24 / HIGH / D8+D9 / インセットの二重適用と IME（API 26–29）

- **観測**: `enableEdgeToEdge` + `Scaffold(contentWindowInsets=...)` + 画面側 `safeDrawingPadding` は二重になりやすい。`decorFitsSystemWindows=false` にすると `adjustResize` は効かない。API 26–29 の IME インセットは遅延・不正確（既知）。
- **修正案**: ルールを 1 つに固定 — 「インセットを消費するのは Scaffold だけ。子画面は `innerPadding` のみ。オーバーレイだけ `safeDrawing` を直接使う」。IME は `imePadding()` のみ（`adjustResize` と併用しない）。API 26–29 は `WindowInsetsCompat` の `ime()` フォールバックを実機確認。
- **検証方法**: L3 で「ステータスバー分の padding(px) がスケール 1x/2.1x で同一」を assert。IME は L4（3 機種）。

### F-25 / HIGH / D9 / 言語の二重管理と `recreate()` 後の WebView context

- **観測**: D9 は 33+ を LocaleManager、26–32 を attachBaseContext。両方を書くと (i) 33+ で attachBaseContext が保存値でシステム設定を上書きし乖離、(ii) 読みを `LocaleManager.getApplicationLocales()` にすると「システム既定（空）」と「アプリ保存値」で解釈が割れる。さらに言語変更の `recreate()` で Activity だけ作り直され、**ViewModel 内の WebView の baseContext が旧 Activity のまま**（リーク + テーマ不整合）。現 manifest は `configChanges` に `uiMode`/`fontScale`/`density`/`layoutDirection` を入れているが `locale` は無いので、LocaleManager 変更時は再生成される。
- **修正案**: 真実は「アプリ設定 1 つ」。33+ は**書き込み時に LocaleManager へミラー**し、読みは常に `resources.configuration.locales[0]`。33+ の `attachBaseContext` は**上書きしない**（システムが適用済み）。26–32 のみ attachBaseContext で適用。`recreate()` 後は `onCreate` で `MutableContextWrapper.baseContext = this` を必ず更新。プロセス死からの復元は URL のみ（履歴・フォームは諦める）と明記。
- **検証方法**: L3（API 33 と 28 で ja↔en、即時反映・戻る/進む維持・プロセス死後維持）。

---

## H. テスト戦略

### 現状のビルド設定の事実（`app/build.gradle.kts`）

- `testInstrumentationRunner` が**無い**、`androidTestImplementation` が**無い**、`app/src/androidTest` ディレクトリが**無い** → **L3 は現状実行できない**。
- `testOptions.unitTests.isIncludeAndroidResources = true` だが Robolectric 依存が無い（現状は無意味）。
- `lint {}` ブロックが無い → 「lint 0 エラー」を強制する仕組みが無い。
- `testImplementation` は JUnit のみ。

### AC → レベルの割り当て（推奨）

| 対象 | L1 JVM | L2 JVM+ fake | L3 instrumented | L4 手動 |
|---|---|---|---|---|
| ピン算出・3 状態判定・キー生成 | ◎ | — | — | — |
| SSL キュー・timeout・epoch・settled | ◎ | ○ | — | — |
| SSL インタースティシャル実表示 | — | — | ◎（可能） | ○ |
| **proceed 保持の実挙動** | — | — | △（サーバー再起動が必要） | **◎ 必須** |
| サブリソース嵐 | — | ○ | ◎ | — |
| handshake 失敗（期限切れ証明書） | — | — | ◎ | ○ |
| 認証ダイアログ（Basic） | ○ | ○ | ◎ | — |
| 認証（Digest stale） | ○ | — | △ | **◎** |
| 資格情報の鍵無効化 | — | ◎（factory 注入） | ○ | — |
| 永続化の破損 6 パターン | ◎ | — | ○ | — |
| ショートカット要求の例外 | ○ | ◎ | ○ | — |
| ピンの実承認/拒否/外観 | — | — | △ | **◎** |
| IntentRouter（敵性 URL） | ◎ | — | ○ | — |
| 適応レイアウト/スケール | ◎（純関数） | — | ○ | ○ |
| ダイアログのスケール伝播 | — | — | ◎（スクショ） | — |
| 言語切替 | ◎（パリティ） | — | ◎ | — |
| 署名/lint | — | — | — | ◎（CI シェル） |

### qa-server.py の現実性

- **Basic**: 容易（`BaseHTTPRequestHandler` + `WWW-Authenticate: Basic realm="t"` + 401）。L3 で回せる。
- **Digest**: 実装可能（RFC 7616 MD5 qop=auth で約 80 行）。ただし **`stale=true` の再現が必須**（F-09 の検証に使う）。nonce/opaque を発行し、期限切れで `stale=true` を返すモードを用意。WebView の Digest 実装差で落ちる可能性があるため **AC は Basic、Digest は L4**。
- **自己署名 + 証明書差し替え**: `ssl.SSLContext` で可。**SIGUSR1 で証明書をリロード**できるようにすると F-01 の保持実験が自動化できる（同一 WebView のまま A→B）。
- ポート: エミュレータからは `10.0.2.2`、実機からは LAN IP。証明書の SAN に両方入れる。

### エミュレータ最小集合

**API 26 / 29 / 33 / 36 の 4 つ**。
- 26 = `getX509Certificate` 無し・RoleManager 無し・`saveState` 経路・`ime()` 不安定。
- 29 = `getX509Certificate` + RoleManager + TLS 挙動の基準。
- 33 = LocaleManager / per-app language。
- 36 = targetSdk 36 の edge-to-edge・予測型戻る・挙動変更。
- 28 は 26 と 29 の中間で固有差が小さい（cleartext 既定は targetSdk 依存で 26 でも同じ）→ 省略可。実機 1 台（Samsung か Xiaomi）を L4 に追加。

### CI で flaky になる項目

- L3 の WebView テスト（初回起動 1–2 秒、ダイアログアニメーション、ネットワーク）。対策: `waitUntil` + 15s、`@SdkSuppress`、失敗時にスクショ/ログを artifact 保存、**リトライ 1 回まで**。
- スクリーンショットテスト（フォント・レンダラ差）。Paparazzi は JVM なので比較的安定だが、フォント同梱が必要。
- SSL 保持実験は**CI に入れない**（L4 の記録実験）。

---

## I. AC の漏れ（領域ベース）

TO-PLAN の AC 一覧と突合せよ。以下は D1–D11 から読む限り AC が無い/薄い領域。

1. **クラッシュ安全性**: プロセス死・復元、破損データ、Keystore 無効化、**WebView レンダラクラッシュ**。`onRenderProcessGone` が D1–D11 のどこにも無い（F-26）。
2. **メモリ安全性**: `largeHeap="true"` が既に有効（リーク隠蔽）。ビットマップ・履歴・`onTrimMemory` の AC 無し。
3. **既存機能との互換性**: 既存 6 クラスの unit test が緑、`AppContainer`/`BookmarkRepository` の API 非破壊、minSdk 26 起動。
4. **lint 0 エラー**: 強制機構が無い（F-30）。`NewApi` は F-03 の `runCatching` で隠れている。
5. **署名検証**: `apksigner verify` の指紋一致、`debuggable=false`、debug 鍵の混入無し。**env 未設定でも release APK が「未署名で」生成される**（F-29）。
6. **Cookie/JS/DOM ストレージ**: 設定値の assert、Cookie の再起動永続と `flush()`、サードパーティ Cookie の既定、保存データのクリア導線。
7. **共有**: ACTION_SEND の受信（巨大テキスト・複数 URL）と送信（タイトル + URL）の両方。manifest は受信のみ。
8. **履歴/戻る進む/`target=_blank`**: `supportMultipleWindows=false` で同一 WebView に開く挙動の AC。
9. **データ削除**: 「閲覧データを消去」で Cookie/履歴/キャッシュ/資格情報/ピンがどうなるか。ピン削除は再信頼が必要になる旨の確認。
10. **アクセシビリティ**: 2.1x の TalkBack ラベル、48dp タッチターゲット、コントラスト。
11. **ネットワーク異常**: 機内モード、captive portal、http→https リダイレクト、タイムアウト。
12. **性能/ANR**: 起動時間、ページロード、ANR ゼロ、StrictMode。

### F-26 / HIGH / I / `onRenderProcessGone` 未実装 → レンダラ死でアプリが落ちる

- **観測**: D1–D11 に記述が無く、実装にも無い。WebView のレンダラプロセスが死ぬと（OOM/クラッシュ）、コールバックを実装していないアプリは **プロセスごとクラッシュ**する。
- **修正案**: `onRenderProcessGone` で `view.destroy()` → 状態を保持したまま WebView を再生成 → エラーバナー「ページを再読み込みしてください」。`didCrash()` を見てクラッシュループを避ける（同一ページで 2 回連続なら自動再読込しない）。
- **検証方法**: L4（`adb shell` でレンダラを kill、または `chrome://crash` 系）。自動化は困難。

### F-27 / MEDIUM / I / L3 の土台が無い（runner / deps / androidTest 無し）

- 上記「現状のビルド設定の事実」の通り。D11 が L3 を前提にしているのに、実行基盤が無い。AC を書く前に `testInstrumentationRunner`、`androidTestImplementation`（`androidx.test:runner`、`ext:junit`、`espresso-core`、`compose-ui-test-junit4`）、`androidTest` ソースセットが必要（build 変更は scope_out なので TO-PLAN 判断）。

### F-28 / MEDIUM / I / lint 未強制 + `NewApi` の隠蔽

- `lint { abortOnError = true; warningsAsErrors += ... }` が無い。「0 エラー」は願望でしかない。`runCatching { certificate.x509Certificate }` は API 29+ 呼び出しを `NoSuchMethodError` として握るため、Lint の `NewApi` をすり抜けやすい（F-03）。

### F-29 / HIGH / I / env 未設定の release ビルドが「未署名 APK」で成功する

- **観測**: `build.gradle.kts` は `STORE_PASSWORD`/`KEY_PASSWORD` が無いと `signingConfig` を設定しない（36–38 行）。`assembleRelease` は**未署名 APK を作って成功**する。
- **なぜ問題か**: CI で「release ビルド成功」が出ても配布物はインストール不能。署名検証 AC が意味を失う。
- **修正案**: release ビルドで env が無ければ `throw GradleException`（または `tasks.named("packageRelease")` で検証）。AC は「env 無しで `assembleRelease` が失敗すること」。
- **検証方法**: CI シェルで `! ./gradlew assembleRelease`。

### F-30 / LOW / I / `largeHeap="true"` はメモリ安全性の AC と矛盾する

- WebView + Compose で largeHeap を要求するのは、リークを隠している可能性がある。削除して PSS を測るのが先。残すなら理由を ADR に書く（例: 大量タブ前提）。現状タブは無い。

---

## 指摘一覧（F-xx 索引）

| ID | 重要度 | 対象 | 1 行 |
|---|---|---|---|
| F-01 | CRITICAL | D2 | proceed 判断の WebView 側保持を設計前提にしていない（ピン台帳と二重管理、信頼解除が効かない） |
| F-02 | HIGH | D2 | 同時 1 件・2 件目 cancel でサブリソースが壊れ、承認後もページが不完全 |
| F-03 | HIGH | D2 | API 26–28 は指紋が取れず恒久 Unknown（信頼を記憶できない） |
| F-04 | MEDIUM | D2 | DER 固定ピンは再発行で必ず Changed → warning fatigue。SPKI 併記へ |
| F-05 | MEDIUM | D2 | host 1 件ピンでは同一ホスト複数証明書が交互に Changed |
| F-06 | LOW | D2 | 60 秒タイムアウトは読解中に cancel、遅延 proceed |
| F-07 | HIGH | D3 | `useHttpAuthUsernamePassword()` の意味が違い、ゲートとして機能しない（設計とコードも矛盾） |
| F-08 | HIGH | D3 | 資格情報キーに scheme/port が無く、https の秘密が http で送られうる |
| F-09 | MEDIUM | D3 | 「同一キー再要求」は Digest stale 等の正常系を誤検知。カウンタもリセットされない |
| F-10 | MEDIUM | D3 | 遷移で handler 失効・二重 proceed・IO スレッド proceed の失敗モード |
| F-11 | MEDIUM | D3 | Digest に保存パスワードを使う危険（http では保存しない） |
| F-12 | HIGH | C | 鍵無効化・復号失敗が「静かな資格情報喪失」（通知も退避も無い） |
| F-13 | MEDIUM | C | 非推奨受容は妥当。移行トリガと隔離（CredentialVault）が条件 |
| F-14 | MEDIUM | C | 全消去フェイルセーフは 4 条件付きで妥当。Keystore 直実装は 1.5–2 人日 |
| F-15 | HIGH | D4 | .bak の穴（両方破損で空起動＝全消失、順序で復元不能） |
| F-16 | MEDIUM | D4 | 並行・性能・書き込みスレッドの規定が無い。DataStore 基準を明文化 |
| F-17 | HIGH | D5 | ショートカット Bitmap 216px 非 adaptive はランチャーで破綻しうる |
| F-18 | MEDIUM | D5 | 書記素を考慮しない 1–2 文字切り出しで濁点・絵文字・国旗が壊れる |
| F-19 | CRITICAL | D6 | `intent://` を parse→startActivity（任意コンポーネント起動）。isForMainFrame も未確認 |
| F-20 | HIGH | D7 | ピン留めの失敗モード（disabled ID 例外・拒否無通知・削除不能）が設計に無い |
| F-21 | MEDIUM | D6 | EXTRA_TEXT 巨大入力と chooser の自己再帰 |
| F-22 | CRITICAL | D8 | LocalDensity 差し替えが Dialog に伝播しない（最重要画面がスケールしない） |
| F-23 | HIGH | D8 | density×fontScale の二重乗算 + 3 列クランプで 2.1x が破綻 |
| F-24 | HIGH | D8 | インセット二重適用と API 26–29 の IME |
| F-25 | HIGH | D9 | 言語の二重管理、recreate 後の WebView context 未更新（リーク） |
| F-26 | HIGH | D1 | `onRenderProcessGone` 未実装 → レンダラ死でアプリが落ちる |
| F-27 | MEDIUM | D11 | L3 の実行基盤（runner/deps/androidTest）が存在しない |
| F-28 | MEDIUM | I | lint 未強制 + `runCatching` が NewApi を隠す |
| F-29 | HIGH | I | env 未設定の release が未署名 APK で「成功」する |
| F-30 | LOW | I | `largeHeap=true` はメモリ安全性 AC と矛盾 |

---

## TO-PLAN への5つの質問（Yes/No）

1. **Q1**: 「今回だけ」の意味を「**この WebView インスタンスの寿命まで**（プロセス死・再生成で失効）」と定義し、UI 文言と AC をそれに合わせるか？ — Yes / No
2. **Q2**: proceed 保持の実機実験（API 26/29/33/36、同一 WebView で証明書 A→B 差し替え）を**出荷必須ゲート（L4）**として AC 化するか？ — Yes / No
3. **Q3**: D3 から `useHttpAuthUsernamePassword()` を削除し、epoch 付きの自前拒否カウンタに一本化するか（現実装は既にそうなっている）？ — Yes / No
4. **Q4**: UI スケールを density 乗算から **fontScale のみ（上限 1.5x）** に変更するか？ 変更しない場合、Dialog 内 Provider と「物理 dp での列数計算」を必須 AC にするか？ — Yes / No
5. **Q5**: minSdk 26 を維持し、**26–28 は「ピン記憶なし・セッション限りの一時許可のみ」に縮退**することを許容するか？（代替: minSdk 29 に上げる） — Yes / No

---

## AC 追加提案

検証方法は L1=JVM / L2=JVM+fake / L3=instrumented / L4=手動。合格条件は「客観的に判定できる」形で書く。

### SSL

| ID | 検証 | 合格条件 |
|---|---|---|
| AC-SSL-01 | L4: 同一 WebView・同一 host:port で証明書 A→B、`onReceivedSslError` 発火回数と `getCertificate()` を記録（API 26/29/33/36） | 4 API すべてで結果が記録され、設計（保持前提 or 非保持前提）と一致。不一致なら設計を修正した証跡がある |
| AC-SSL-02 | L3: 承認済みページの `onPageFinished` で `view.certificate` をピンと比較 | 不一致時にインタースティシャル or エラーが出る（無言で通らない） |
| AC-SSL-03 | L3: 自己署名 + 画像 20 枚 | インタースティシャルは 1 回だけ。承認後 20/20 表示 |
| AC-SSL-04 | L1+L3: expired / notYetValid / IDMISMATCH / INVALID | 「常に信頼」ボタンが存在しない。proceed しても保存されない |
| AC-SSL-05 | L3 API 26: 自己署名 → 常に信頼 → 再読込 | 2 回目にダイアログが出ない、または「記憶できない」と明示され保存ボタンが無い |
| AC-SSL-06 | L1+L3: 同一ホスト 2 証明書（2 ポート） | 両方信頼後、どちらもダイアログ無しで開く |
| AC-SSL-07 | L3: 期限切れ証明書で `ERROR_FAILED_SSL_HANDSHAKE` | エラー UI が出る（無反応・無限ロードでない） |
| AC-SSL-08 | L4: POST フォーム → インタースティシャル → 承認 | 二重送信が起きない、または「再送信が必要」と明示 |

### 認証

| ID | 検証 | 合格条件 |
|---|---|---|
| AC-AUTH-01 | L1+L3: ダイアログ表示中に JS で遷移 / timeout | クラッシュせず、ダイアログが消え、proceed が呼ばれない |
| AC-AUTH-02 | L1: fake handler で `Looper` を assert | proceed が Main スレッド |
| AC-AUTH-03 | L4: Digest `stale=true` | ダイアログが出ずに自動再試行 |
| AC-AUTH-04 | L1+L3: http の 401 | 自動入力しない。保存ボタンが無い |
| AC-AUTH-05 | L1: 手動成功 / 新規ナビゲーション / onPageFinished | カウンタが 0 に戻り、次の 401 で自動入力が復活 |

### 資格情報

| ID | 検証 | 合格条件 |
|---|---|---|
| AC-VLT-01 | L2: ESP 生成/復号失敗を注入 | secure ファイルのみ退避・破棄、ブックマーク無傷、次回起動で通知 1 回、クラッシュ 0 |
| AC-VLT-02 | L1: 破損 JSON | 例外を投げず Wiped 状態を返し、空を「正常」として返さない |

### 永続化

| ID | 検証 | 合格条件 |
|---|---|---|
| AC-DATA-01 | L1: 正常/片側破損/両側破損/未知 version/要素 1 件不正/空 | 両側破損で空起動しない。退避ファイルが残る |
| AC-DATA-02 | L1: schemaVersion=999 | 上書きしない（読み取り専用 + 警告） |
| AC-DATA-03 | L1+L3: 500 件 | cold load < 300ms（API 26 emu）、編集時に main-thread disk write の StrictMode violation 0 |

### アイコン/ショートカット

| ID | 検証 | 合格条件 |
|---|---|---|
| AC-ICO-01 | L1: 結合濁点・ZWJ・国旗・空・記号・1000 文字 | 1–2 書記素クラスタ、例外なし |
| AC-ICO-02 | L1: 全 360 色相 × light/dark | コントラスト比 ≥ 4.5:1 |
| AC-SHT-01 | L1+L3: アイコン生成 | adaptive bitmap 432px またはリソース fallback。例外 0 |
| AC-SHT-02 | L1: disabled ID を fake で再現 | `IllegalArgumentException` を投げず enable→再要求 |
| AC-SHT-03 | L2+L3: 背景遷移後に要求 | `IllegalStateException` を投げない（要求しない） |

### Intent / 共有

| ID | 検証 | 合格条件 |
|---|---|---|
| AC-INT-01 | L1: `intent://`（component/selector 付き）・`javascript:`・`data:`・`file://`・`content://` | 外部起動しない。http(s)/tel/mailto/sms のみ許可 |
| AC-INT-02 | L3: 1MB の EXTRA_TEXT | ANR/OOM なし、8KB に切り詰め、URL 1 件抽出 |
| AC-INT-03 | L1: 既定ブラウザ時の chooser | 自パッケージが候補に含まれない |

### UI

| ID | 検証 | 合格条件 |
|---|---|---|
| AC-UI-01 | L3: 2.1x で SSL/認証ダイアログのスクショ | 文字が 2.1x で描画される（density 差し替えが届く） |
| AC-UI-02 | L3: ステータスバー padding(px) を 1x/2.1x で比較 | 同一 px（二重適用なし） |
| AC-UI-03 | Paparazzi/L3: 2.1x × compact | グリッド ≥ 1 列、セル内テキストのクリップ 0、ダイアログが画面内 |
| AC-UI-04 | L3+L4: IME 表示 | 入力欄が隠れない（API 26/33） |

### 言語

| ID | 検証 | 合格条件 |
|---|---|---|
| AC-LOC-01 | L3: API 33 / 28 で ja↔en | 即時反映、戻る/進む/入力中 URL 維持 |
| AC-LOC-02 | L3: 言語変更後 `am kill` → 再起動 | 言語とブックマークが維持、クラッシュ 0 |

### 品質/リリース

| ID | 検証 | 合格条件 |
|---|---|---|
| AC-QLT-01 | CI: `lintDebug` | error 0、baseline 不使用、`abortOnError=true` |
| AC-QLT-02 | CI: `apksigner verify --print-certs` | 期待 SHA-256 と一致、`debuggable=false`、debug 鍵でない |
| AC-QLT-03 | CI: env 無し `assembleRelease` | **失敗する**（未署名 APK を作らない） |
| AC-QLT-04 | L4: レンダラ kill | アプリが落ちず、バナー + 再生成。連続 2 回で自動再読込しない |
| AC-QLT-05 | L3: WebSettings assert | JS=true, DOM=true, file=false, content=false, mixed=NEVER, SafeBrowsing=true, debugging=false(release) |
| AC-QLT-06 | L3: Cookie 設定 → 再起動 | セッション維持、`flush()` 呼び出し、クリア導線が機能 |
| AC-QLT-07 | L3: 共有の受信/送信 | 受信テキストから URL 抽出、送信 chooser にタイトル + URL |
| AC-QLT-08 | L4: 30 分ブラウジング | Activity リーク 0（LeakCanary debug）、PSS 増加が閾値内 |

合計 **40 件**（SSL 8 / 認証 5 / 資格情報 2 / 永続化 3 / アイコン・ショートカット 5 / Intent・共有 3 / UI 4 / 言語 2 / 品質 8）。※番号は提案であり、TO-PLAN の AC 表に採番し直してよい。

---

## 付記: 事実と推測の分離

- 【事実】公式ドキュメントの文言（proceed 保持、`useHttpAuthUsernamePassword` の定義、API 29+ の `getX509Certificate`、`requestPinShortcut` の前景要件・例外・拒否無通知、`WebView.getCertificate()` が API 1 で main top-level page の証明書を返すこと）。
- 【事実】リポジトリのコード（`CredentialStore.keyOf` が host|realm、`certificateFingerprint` が `x509Certificate` 直参照、`intent://` の startActivity、`largeHeap=true`、androidTest 無し、lint ブロック無し、release 署名が env 依存）。
- 【推定】LocalDensity が Dialog に伝播しない、サブリソースでも SSL コールバックが発火する、保持のキーが host 単位、`useHttpAuthUsernamePassword` が ESP 保存では false 固定。
- 【未検証】ショートカットの Bitmap アイコンが各ランチャーで受理されるか、API 26–28 の `saveState` Bundle のキー/形式、WebView の Digest stale 挙動。

