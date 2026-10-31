# 設計評価（Design Evaluation）

- 文書ID: HB-EVAL-001 / 版 1.0（2026-10-31）
- 対象: Half-Browser v1.0.0（`com.yukiorita.halfbrowser`）
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
| 実機 QA | Android 14（API 34, arm64）エミュレータ。自己署名 HTTPS + Basic 認証サーバーをホストで起動し、信頼・認証・失効・再起動を実測。 |
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
| JVM 単体テスト | **57 件 / 失敗 0** |
| Lint | **Error 0** / Warning 63（依存更新・未使用文字列・KTX 推奨が大半） |
| 署名付きリリース APK | 1,953,141 B（R8 + shrink） |
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

## 9. 総合評価

- **設計の一貫性**: 良好。小さい攻撃面、純粋ロジックの分離、明示的なユーザー同意にもとづく信頼。
- **要件充足**: 主要要件（自己署名 HTTPS の信頼、認証情報の保存・自動入力、テーマ、ホーム、外部リンク、ショートカット、適応、日英）を実装・実測。
- **品質**: 単体テスト 57 件、lint 0 error、署名済みリリースで E2E 動作。独立レビューの HIGH 指摘は修正済み
  （残る MEDIUM/LOW は [REVIEW-RESPONSE.md](REVIEW-RESPONSE.md) に採否を記録）。
- **判定**: **リリース可（PASS_WITH_NOTES）**。ただし API 26–28 実機・ファイル選択・レンダラ強制終了は未検証として明記する。
