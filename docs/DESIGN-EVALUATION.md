# 設計評価（Design Evaluation）

- 文書ID: HB-EVAL-001 / 版 1.0（2026-10-31）
- 対象: Half-Browser v1.1.0（`com.yukiorita.halfbrowser`）
- 評価者: 実装担当（メイン）＋ 独立レビュー担当（別コンテキスト、[REVIEW_REPORT.md](REVIEW_REPORT.md)）
- 位置づけ: 実装後の設計・セキュリティ・堅牢性・互換性・UX の自己評価。**未検証を PASS と書かない**。

---

## 1. 評価方法

| 手法 | 内容 |
|---|---|
| 静的レビュー | 全ソース・Manifest・Gradle 設定を独立レビュアーが read-only で監査（25 指摘、CRITICAL 0）。 |
| 単体テスト | JVM 57 件（URL 正規化、JSON コーデック、信頼判定、認証試行、グリッド、資格情報キー、日英パリティ ほか）。 |
| Lint | `lintDebug` Error 0 / Warning 63（大半は依存更新・未使用文字列・KTX 推奨）。 |
| リリースビルド | R8 + resource shrink、署名検証（apksigner v2、証明書 SHA-256 `36153aed…`）。 |
| 実機 QA | Sony SOV40（Android 11 / API 30 / WebView 153）＋ Android 14（API 34, arm64）エミュレータ。自己署名 HTTPS + Basic 認証サーバーをホストで起動し、信頼・認証・失効・再起動を実測。 |
| 敵対的確認 | `intent://`・`javascript:`・`file://`・`data:` の拒否、http での資格情報非送信、ピン変更時の再確認をコードと実機で確認。 |

---

## 2. アーキテクチャ評価

### 2.1 採用構成

- 単一 Activity（`MainActivity`）＋ Jetpack Compose。画面は `home` / `browser` / `settings` の 3 ルート。
- `AppContainer` がリポジトリを遅延生成（DI フレームワークなし）。プロセス寿命の単一インスタンス。
- 永続化は SharedPreferences（JSON ブロブ）＋ EncryptedSharedPreferences（認証情報のみ）。
- WebView は `AndroidView` でホストし、`BrowserState`（Compose state）と 2 つのクライアント
  （`HalfBrowserWebViewClient` / `HalfBrowserChromeClient`）で制御。
- 純粋ロジック（URL、信頼判定、キー生成、グリッド、認証試行、JSON）は `core/` に分離し JVM テスト対象。

### 2.2 強み

1. **責務の境界が明確**。Android API 依存は `data/`・`ui/`・`core/LocaleHelper` に限定し、判定ロジックは純粋関数。
2. **攻撃面が小さい**。JavaScript ブリッジなし、`file://`/`content://` 無効、第三者 Cookie 無効、カメラ/マイク/位置情報を拒否、
   `intent://` は起動しない、外部委譲は `tel/mailto/sms/geo` のみ。
3. **状態の一貫性**。設定・ブックマーク・信頼は単一 JSON ブロブの原子的書き込み。破損時は既定値へフォールバックし起動不能にならない。
4. **テスト可能性**。信頼判定・認証試行・資格情報キー・グリッド・URL 正規化を UI なしで検証できる。

### 2.3 弱み・構造的制約

| 制約 | 影響 | 判断 |
|---|---|---|
| タブ 1 枚 | 複数ページの並行閲覧ができない | 要件外。v1 のスコープを小さく保つため意図的に省略（ADR-0001）。 |
| WebView の SSL 例外はプロセス内キャッシュを持つ | 失効が即時反映されない場合がある | 失効時に WebView を再生成（`trustRevision` → `generation`）して回避。 |
| 証明書信頼は OS ストアでなくアプリ内ピン | 他アプリには影響しない／CA 検証の代替ではない | 要件（ローカル自己署名）に合わせた意図的設計（ADR-0002）。 |
| ファイル選択・クライアント証明書 非対応 | 一部の社内サイトで機能不足 | 既知の制限として README に明記。 |
| 既定ブラウザはユーザー操作が必要 | 外部リンク転送を強制できない | Android の仕様。RoleManager 導線と設定画面を用意（ADR-0005）。 |

---

## 3. セキュリティ評価

### 3.1 自己署名証明書（TOFU ピン）

- 信頼の単位は **host + port + 証明書 DER の SHA-256**。証明書が変われば `Changed` として再警告し、
  「常に信頼」には**二段確認**を要求する。
- `SslErrorHandler.proceed()` は WebView の公式推奨ではない。補償統制として
  (a) 証明書情報の明示、(b) 明示的なユーザー操作なしに proceed しない、(c) 読み込み後に
  `WebView.certificate` を再照合し、不一致ならピンを失効させて停止、(d) 設定から失効可能、を実装。
- 期限切れ・ホスト名不一致・未到来の証明書は **pin 不可**（「今回だけ」のみ）。
- API 26–28 では `getX509Certificate()` が存在しないため、`SslCertificate.saveState()` の Bundle から
  X.509 を復元するフォールバックを持つ（**API 26 実機での実測は未実施**。API 34 で同等経路を確認）。

### 3.2 HTTP 認証情報

- キーは `scheme://host:port|realm`。**http では保存も自動入力もしない**。
- 保存は Android Keystore（AES-256-GCM）＋ EncryptedSharedPreferences。バックアップ／端末移行から除外。
- 自動入力は同一キーで最大 2 回。ページが実際に表示されたらカウンタをリセット。
- 保存失敗は握り潰さずトーストで通知。Keystore 利用不可時はメモリ保持のみとし、設定画面に警告。
- 残存リスク: 端末 unlocked 前提（生体認証ゲートなし）、UI からのコピー防止なし。v1 の範囲外として明記。

### 3.3 入力とナビゲーション

- アドレスバーは `http/https` のみ許可。`javascript:` / `file:` / `data:` / `intent:` は拒否。
- `intent://` は**一切起動しない**（fallback URL のみ検証して読み込み）。intent redirection を排除。
- 外部スキーム委譲は `tel/mailto/sms/smsto/geo` に限定。`ACTION_VIEW` は自パッケージを除外した chooser。
- 独自スキーム `halfbrowser://` は BROWSABLE を付けず、`bookmarkId` は URL の canonical 一致を検証。
- ブックマーク編集の URL は `normalizeForBookmark` で検証（検索クエリへの誤変換なし）。

### 3.4 プライバシー

- 解析 SDK・広告・アカウントなし。`WebView.MetricsOptOut` を宣言。
- 第三者 Cookie 無効。認証情報をログへ出さない（`Log` は保存失敗の警告のみ、値なし）。
- 画面キャプチャ等の OS 機能は無効化していない（要件外）。

---

## 4. クラッシュ安全性・メモリ安全性

| シナリオ | 対策 | 検証 |
|---|---|---|
| WebView のレンダラプロセス死亡 | `onRenderProcessGone` で破棄＋世代番号を進めて再生成、エラーペイン表示 | コード確認のみ（強制死亡は未実施） |
| 設定変更（回転・サイズ・密度・夜間） | Manifest `configChanges` で Activity 再生成を抑制。Compose の `rememberSaveable` | 回転・タブレット・ダークで実測 |
| 画面離脱 | `DisposableEffect` で stopLoading → about:blank → detach → destroy | 実測（ホーム往復） |
| 不正 Intent | `runCatching` で全体を包み、未知スキーム・空データを無視 | コード確認 |
| 保存データ破損 | JSON デコード失敗は既定値／空リスト。信頼・ブックマークの不正要素は除外 | 単体テスト |
| Keystore 利用不可 | メモリ保持にフォールバック、`isPersistent=false` を UI 表示 | コード確認 |
| 認証ハンドラの二重実行 | `SslChallenge` / `AuthChallenge` の `settled` フラグ | コード確認 |
| 大量ブックマーク | LazyVerticalGrid、キー付きアイテム | 500 件の実測は未実施（コード上の想定） |

- `largeHeap` は使用しない。ショートカット用 Bitmap は 108dp 相当（最大 432px）のみ生成。
- WebView はレンダラが別プロセスで、ページメモリはアプリヒープと分離される。

---

## 5. 互換性

- minSdk 26 / targetSdk 36 / compileSdk 36.1。Java 17。
- API 29+ (`SslCertificate.getX509Certificate`, `RoleManager`)、API 31+（動的カラー）、
  API 33+（`localeConfig`）はすべてバージョンガード。
- `WebViewFeature.ALGORITHMIC_DARKENING` は機能検出。非対応でも白背景で可読。
- 言語切替は `attachBaseContext` + `Configuration`。`Locale.setDefault` は使わない（プロセス副作用回避）。
- **未検証**: API 26–28 実機、WebView 未搭載端末、折りたたみ、RTL、TalkBack、超大型ページ。

---

## 6. UX / 多言語 / 適応

- ブックマークから開いたときは 左=ホーム、中央=ブックマーク名、右=3 点メニュー（折りたたみ）。
  メニューは 追加/編集・共有・リンクコピー・再読み込み・PC 版・外部ブラウザ・設定・閉じる。
- ホームはアプリ風タイル（モノグラム）＋検索バー＋空状態＋既定ブラウザ導線。長押しで編集・削除・共有・ショートカット。
- UI スケール 80–140%、文字スケール 80–150%、列数 自動/3/4/5/6/8。タブレット・横向きを実測。
- 文字列は 176 キー × 日英。単体テストでキー集合・空文字・プレースホルダ一致を強制。
- アクセシビリティ: 主要 IconButton に contentDescription、タイルに説明、色コントラストは Material 3 の
  light/dark スキームに準拠（独立レビューで重大なコントラスト指摘なし）。

---

## 7. 品質指標（実測）

| 指標 | 値 |
|---|---|
| JVM 単体テスト | **68 件 / 失敗 0**（v1.1 で SiteContent / WatchRules / 通知コーデックを追加） |
| Lint | **Error 0** / Warning 63（依存更新・未使用文字列・KTX 推奨が大半） |
| 署名付きリリース APK | 2,112,300 B（R8 + shrink） |
| 署名 | v2、証明書 SHA-256 `36:15:3A:ED:…:0F:37` |
| コールドスタート（エミュレータ） | 約 2.7 秒（`am start -W`） |
| 実機クラッシュ | debug / release とも FATAL 0 件（QA 期間） |
| 日英文字列パリティ | 176/176 キー一致 |

---

## 8. リスク登録（残存）

| ID | リスク | 重大度 | 状態・対応 |
|---|---|---|---|
| RK-01 | `SslErrorHandler.proceed()` の将来互換（WebView アップデートで挙動変更） | 中 | 明示操作・事後照合・失効を実装。将来 GeckoView/Network Security Config への移行を検討。 |
| RK-02 | API 26–28 の証明書 DER 取得が未実測 | 中 | Bundle 走査の二段フォールバックを実装。取得不能時は「常に信頼」を無効化し、今回だけ許可。API 26 実機での確認を残課題とする。 |
| RK-03 | WebView レンダラ強制終了の実地検証なし | 低 | 実装済み。`chrome://crash` 等での検証を将来実施。 |
| RK-04 | ファイル選択・クライアント証明書 非対応 | 中 | README に明記。必要なら `onShowFileChooser` 実装。 |
| RK-05 | http ページの取り扱い（平文） | 中 | ブラウザとして許可。認証情報は送らない。混合コンテンツは互換モード。 |
| RK-06 | 大規模ブックマーク・巨大ページの性能未測定 | 低 | Lazy リスト。将来 Paging／プロファイル測定。 |
| RK-07 | 認証情報の端末 unlocked 前提 | 低 | 生体認証ロックは v2 候補。 |

---

## 8.1 v1.1.0 の追加設計

- **ブラウザ補完**: FileProvider + SAF + ACTION_IMAGE_CAPTURE、Web 権限（カメラ/マイク/位置情報）の
  origin 表示付き確認、HTML5 全画面。権限は永続化せずセッション中のみ。
- **サイト通知**: WorkManager の PeriodicWork（15/30/60/180 分、ネットワーク・電池制約）で
  ブックマークを直列取得し、本文の SHA-256 変化を検知してローカル通知。
  WebView を起動せず、1 サイト 512 KiB / 10 秒 / 最大 20 サイト。自己署名は host+port ピン、
  Basic 認証は https のみ既存ストアから 1 回試行。
- **制約**: WebView は Web Push / Notification API 非対応のため、サイト起点のプッシュではなく
  ページ変化監視である（ADR-0011）。Cookie/JS 依存ページは検出できない場合がある。
- **R8**: WorkManager の Room 生成クラスと Worker の keep ルールが必須（v1.1.0 で追加）。

## 9. 総合評価

- **設計の一貫性**: 良好。小さい攻撃面、純粋ロジックの分離、明示的なユーザー同意にもとづく信頼。
- **要件充足**: 主要要件（自己署名 HTTPS の信頼、認証情報の保存・自動入力、テーマ、ホーム、外部リンク、ショートカット、適応、日英）を実装・実測。
- **品質**: 単体テスト 57 件、lint 0 error、署名済みリリースで E2E 動作。独立レビューの HIGH 指摘は修正済み
  （残る MEDIUM/LOW は [REVIEW-RESPONSE.md](REVIEW-RESPONSE.md) に採否を記録）。
- **実機固有の修正**: 失効の即時反映（`clearSslPreferences`）、読込前タイトル、認証キャンセル時の
  401 表示、ダイアログ外タップによる入力消失を v1.0.1 で修正（[DEVICE-QA-SOV40.md](DEVICE-QA-SOV40.md)）。
- **判定**: **リリース可（PASS_WITH_NOTES）**。ただし API 26–28 実機・ファイル選択・レンダラ強制終了は未検証として明記する。

---

## 10. v1.6.0 の追加設計評価（2026-11-09）

- **VPN（ADR-0018）**: 公式 userspace WireGuard（`com.wireguard.android:tunnel`）。スコープは
  allow-list（`IncludedApplications = 自パッケージ`）で fail-closed に保証する。除外リスト方式は
  Android 11+ のパッケージ可視性で列挙漏れ＝fail-open になるため不採用。プロファイルは
  Keystore 暗号化保存、設定検証は厳格パーサ（鍵の canonical 形式・Endpoint・CIDR・上限）。
  再接続時の `stopSelf` 競合（新トンネルが即座に破壊される）を実測し、切断後 600ms の
  クールダウンで解消した。
- **JS 攻撃対策（ADR-0019）**: document-start シム＋ネイティブ予算の二層防御。
  alert 連打 20 → 12 表示・8 ブロック、popup 5 → 全ブロック、beforeunload 登録 5 → 3 ブロック。
  ダイアログはアプリ側で origin 表示。`location = 'javascript:...'` の代入は実行されるが
  その alert は予算で止まる（シムで止められない限界を明記）。
- **品質指標**: JVM テスト **162 件 / 失敗 0**、lint **Error 0**、日英 **424/424** キー一致。
- **互換性**: バックアップ形式・設定 JSON の既存フィールド・証明書ピン・資格情報ストアは
  不変。設定の追加フィールドは既定値付き。VPN プロファイルはバックアップ対象外（秘密鍵方針）。
- **未検証（正直な記録）**: エミュレータ（user-net）では protect 済み UDP がトンネル内を
  ループし、WireGuard ハンドシェイクがホストに届かない。公式 WireGuard アプリでも同一設定で
  再現したためエミュレータ環境の制限。実機での対向接続は残課題。詳細は
  [DEBUG-REPORT.md](DEBUG-REPORT.md) §v1.6.0。
- **判定**: 上記残課題を明記した上で **リリース可（PASS_WITH_NOTES）**。

---

## 11. v1.6.1 の精査・修正評価（2026-11-09）

v1.6.0 に対する独立精査（2 体並行監査）で挙がった指摘を修正した。

- **重大度：高の修正**: JS ガードトグルがシム注入に連動していなかった（OFF でも制限が効く）→
  注入条件化＋設定変更時の WebView 再生成。DialogBudget の枯渇が WebView 寿命単位だった →
  ページ開始時にリセット。VPN エディタの空名・重複名が無言無視 → インラインエラー化。
  MAX_PROFILES 超過で「保存成功」を表示 → 戻り値検査で失敗扱い。
- **重大度：中**: `halfbrowser_vpn.xml` がバックアップ除外から漏れていた → 除外規則に追加。
  `commit()` 失敗を無視していた → 失敗時は状態を進めず `lastWriteFailed` で通知。
  再接続の固定ディレイ → DOWN コールバック待ち（1500ms フォールバック）へ置換。
  シムの strict 代入による全体死の余地 → ブロック単位 try/catch。画面破棄時の jsDialog 未 settle →
  Dismiss settle。ホームの Failed 理由表示・選択表示統一・一覧高さ制限・consent 競合・
  エラーダイアログ競合を修正。
- **重大度：低**: パーサの事前サイズチェック、IPv6/CIDR 検証の穴、`Locale.ROOT`、
  Amnezia 系キー（公式 wg-quick 非対応）を拒否に統一、disconnect の profileId 温存、
  コールバック順序保証、デッドコード除去。
- **品質指標**: JVM テスト **170 件 / 失敗 0**、lint **Error 0**。
- **エミュレータ再検証**: トグル OFF→シムなし・標準ダイアログ、ON→シム復帰。空名のインラインエラー。
  再接続は DOWN→UP 約 74ms で接続維持。
- **判定**: **リリース可（PASS_WITH_NOTES）**。WireGuard ハンドシェイクの実機確認は
  v1.6.0 から継続して残課題。

---

## 12. v1.7.0 の追加設計評価（2026-11-10、QR コード取り込み）

設定 → VPN へ QR スキャン取り込みを追加した（[ADR-0020](ADR/0020-vpn-qr-import.md)）。

- **セキュリティ**: スキャン結果は「未検証テキスト」として扱い、ファイル取り込みと同一の
  経路（厳格パーサ → エディタ全文確認 → 保存）に流す。スキャン単独では保存・接続しない。
  接続時に `VpnScope` がスコープ行を常に再書き込みするため、QR の内容によって
  「このアプリのみ」が弱まることはない。サイズ上限（`MAX_BYTES`）、カメラ権限は既存宣言
  （`required=false`、実行時は zxing 側が要求）を再利用し、新しい攻撃面は Activity 結果の
  文字列 1 本のみ。
- **互換性**: 依存は `com.journeyapps:zxing-android-embedded:4.3.0`（Play 開発者サービス
  非依存、minSdk 26+）。既存の取り込み・接続・保存経路は無変更、strings は 4 キー追加のみ
  （日英パリティは `LocaleParityTest` が強制）。QR payload 形式は公式 WireGuard アプリと
  同一（wg-quick 設定テキスト）。
- **クラッシュ安全性**: `rememberLauncherForActivityResult` はコンポーザブル本体で無条件登録
  （条件分岐なし）。キャンセル（`RESULT_CANCELED`）は無操作、null contents も無視、
  過大 payload はパーサの手前で拒否。zxing の CaptureActivity はライブラリ同梱のため
  自前のカメラ状態機械を持たない。
- **メモリ安全性**: デコード結果は Activity 経由の 1 文字列のみ。ビットマップ・カメラ
  フレームはすべてライブラリ側で完結し、アプリ側に保持物を増やさない。
- **品質指標**: JVM テスト **173 件 / 失敗 0**（QR 往復 3 件を追加）、lint **Error 0**。
- **エミュレータ検証（API 36.1）**: スキャナ起動・カメラ権限フロー・キャンセル無操作、
  .conf 取り込み → エディタ事前入力 → 保存 → 接続 → `dumpsys connectivity` の
  `Uids: <{10224-10224, 20224-20224}>`（自 UID のみに VPN ネットワークを適用）を実証、
  接続中も許可リスト外の UID から ping 0% loss（他アプリは通常経路のまま）、切断、
  日英 UI、FATAL 0。
- **未検証（正直な記載）**: エミュレータ仮想シーンへの QR 画像配置が反映されず
  （`virtualscene-image` コマンドは OK を返すが描画が変わらない）、実カメラでの
  QR → デコード → エディタの E2E は未実施。デコードの正しさは JVM 往復テスト
  （同一 zxing デコーダ）で担保。実機での QR 読み取り確認は v1.6.0 のハンドシェイク
  実機確認と併せて継続課題。
- **判定**: **リリース可（PASS_WITH_NOTES）**。
