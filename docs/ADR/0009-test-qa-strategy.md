# ADR-0009: テスト・QA 戦略

- 状態: **Accepted**（2026-10-31）
- 決定者: TO-PLAN（HB-PLAN-01）
- 関連: 要件 11 / ADR-0002, 0003, 0005 / AC-01〜AC-51（計 56 件）

## Context

要件 11 は「JVM 単体テスト（純粋ロジック: URL 正規化、ブックマーク JSON、信頼判定、スケール計算、ショートカット ID、日英文字列キー整合）、lint 0 エラー、リリース APK 署名検証、Android エミュレータでの実機 QA」を求める。

本アプリの検証が難しい点:

- WebView の SSL 例外・HTTP 認証は**プラットフォームのコールバック**であり、JVM では再現できない。
- 自己署名 HTTPS サーバーが必要（Basic / Digest 両方）。
- 端末・API レベル・画面サイズ・テーマの組み合わせが多い。
- `EncryptedSharedPreferences` は Keystore に依存し、Robolectric では動かない。

既存のテスト基盤は `testImplementation(libs.junit)` のみ（`app/build.gradle.kts`）。`androidTest` は未設定。

## Decision

### 1. テストを4層に分ける（何をどこで保証するかを固定する）

| 層 | 実行環境 | 対象 | ツール |
|---|---|---|---|
| **L1 純粋ロジック** | JVM（`src/test`） | `UrlNormalizer` / `Codecs` / `TrustPolicy` / `BookmarkIcons` / `GridPolicy` / `WidthBucket` / `AuthAttemptTracker` / `IntentRouter` / 文字列パリティ / スケール丸め | JUnit4（既存） |
| **L2 コンポーネント** | JVM（`src/test`） | Repository の状態遷移（Fake ストア）、`AuthAttemptTracker` の時系列 | JUnit4 + kotlinx-coroutines-test（**追加**） |
| **L3 結合（実機/エミュレータ）** | `src/androidTest` | `CredentialVault` の暗号化往復、`ShortcutManagerCompat` の対応判定、WebView のスモーク（ページロード） | AndroidX Test（**追加**） |
| **L4 手動 QA** | エミュレータ | SSL インタースティシャル、認証ダイアログ、ショートカットのピン留め、適応レイアウト、言語切替、署名済み APK | チェックリスト（`docs/QA-CHECKLIST.md` を実装フェーズで作成） |

**原則**: L1 に落とせるロジックは必ず L1 に落とす（速く、安定し、CI で回る）。L3/L4 は「プラットフォームに触る部分」だけに限定する。

### 2. L1 の具体（AC との対応）

| テストクラス | 検証内容 | 対応 AC |
|---|---|---|
| `UrlNormalizerTest` | `javascript:` / `file:` / `data:` 拒否、ホスト風入力への https 補完、検索フォールバック、`canonicalForCompare` の正規化（末尾スラッシュ・既定ポート・大小文字）、不正入力で例外を投げない | AC-01, AC-02 |
| `CodecsTest` | 壊れた JSON → 既定値、未知キー無視、`uiScale`/`fontScale`/`gridColumns` の丸め、`validateWebUrl` を通らないブックマークの除外、往復同一性 | AC-03 |
| `TrustPolicyTest` | Trusted / Unknown / Changed の3値、指紋の正規化（大文字・コロン・空白）、`formatFingerprint` の表示形式、ホスト大小文字 | AC-10, AC-15 |
| `BookmarkIconsTest` | 同一 URL → 同一色、`colorSeed` 優先、グリフ導出（英字・日本語・絵文字＝書記素）、`shortcutId` 往復、ラベル 40 文字 | AC-19, AC-28 |
| `GridPolicyTest` | 境界値 599.9/600/839.9/840/1199.9/1200、`autoColumns` の 3〜8 クランプ、設定値の優先 | AC-34 |
| `DensityScaleTest` | `uiScale`/`fontScale` の合成（最大 2.1）、丸め誤差、範囲外入力のクランプ | AC-34 |
| `AuthAttemptTrackerTest` | 1回目失敗で自動継続、2回目で抑止、成功でリセット、キーが scheme/port/realm を含む、別キーに影響しない | AC-24 |
| `IntentRouterTest` | VIEW http/https、`halfbrowser://`、SEND の URL 抽出、資格情報入り URL の拒否、長すぎる URL の拒否、`javascript:` 拒否 | AC-29, AC-30 |
| `StringParityTest` | キー集合一致、書式指定子一致、plurals 一致、空値なし、日本語ハードコード検出 | AC-32 |

### 3. L3（instrumented）の範囲

- `CredentialVaultInstrumentedTest`: 実機 Keystore で保存 → 読み出し → 削除 → 全消去。**平文が `SharedPreferences` の XML に現れないこと**を `context.getSharedPreferences("halfbrowser_secure", MODE_PRIVATE).all` の文字列ダンプで確認（値が暗号化されている）。AC-23。
- `ShortcutSupportInstrumentedTest`: `isRequestPinShortcutSupported` の戻り値と `maxShortcutCountPerActivity` が取得できること（値は端末依存なので assert しない）。AC-28 の前提確認。
- `WebViewSmokeTest`: `https://example.com` ではなく**ローカルの `WebViewAssetLoader` 配信ページ**を読み、`onPageFinished` が来ることを確認（ネットワーク非依存）。AC-37。
- **SSL インタースティシャルと認証ダイアログは L3 にしない**（WebView のコールバックをテストから駆動する公開 API が無く、flaky）。L4 で手動確認し、ロジック部分は L1 で担保する。

### 4. 自己署名 HTTPS + Basic/Digest テストサーバー（`tools/qa-server.py`）

- Python 3 標準ライブラリのみ（`http.server`, `ssl`, `hashlib`, `base64`）。**外部依存・課金なし**。
- 証明書: 初回起動時に `openssl` で自己署名を生成（`SAN = DNS:localhost, IP:127.0.0.1, IP:10.0.2.2`）。`10.0.2.2` はエミュレータからホストを指す既定アドレス。
- エンドポイント:

| パス | 挙動 | 用途 |
|---|---|---|
| `/` | 200、HTML（現在の認証状態を表示） | 正常系 |
| `/basic` | `Authorization: Basic` を要求。realm=`half-browser` | Basic 認証（AC-22） |
| `/digest` | `Authorization: Digest` を要求（MD5, qop=auth） | Digest 認証（AC-25） |
| `/always401` | 常に 401 を返す | **無限ループ防止**の検証（AC-24） |
| `/cert-a`, `/cert-b` | 同一ポートで証明書を切り替えて起動（`--cert a|b`） | `Changed` 判定（AC-15） |
| `/slow` | 応答を 10 秒遅延 | ハンドラ保持・タイムアウト（AC-13） |

- 起動例: `python3 tools/qa-server.py --port 8844 --cert a`
- **警告**: このサーバーは QA 専用。平文 HTTP も同時に立てる（`--http-port`）ことで「http で Basic」の警告表示を確認する。

### 5. エミュレータマトリクス（L4）

| 軸 | 値 | 理由 |
|---|---|---|
| API | 26, 29, 33, 36 | `getX509Certificate` の分岐（29）、`LocaleManager`（33）、最新（36）、最古（26） |
| 画面 | 電話 縦 / 横、10 インチ タブレット 縦 / 横、折りたたみ（Pixel Fold 相当） | compact / medium / expanded / extra-large、ヒンジ跨ぎの既知制約 |
| 表示 | 既定、文字サイズ最大（2.0）、画面サイズ最大 | 2.1 倍スケール（AC-34） |
| テーマ | ライト、ダーク、システム追従、動的カラー ON/OFF | AC-33 |
| ナビ | ジェスチャー、3 ボタン | インセットの差（AC-35） |
| 言語 | 日本語、English、システム | AC-31 |
| ネットワーク | 機内モード、遅い回線（エミュレータの network speed） | エラー処理、タイムアウト |

### 6. CI とローカルの分担

- CI（GitHub Actions, ubuntu-latest, JDK 17）: `./gradlew testDebugUnitTest lintDebug`。**L1/L2 + lint**。エミュレータは回さない（コストと flaky のため）。
- ローカル（macOS + Android Studio）: L3/L4、`assembleRelease` + `apksigner verify`。
- 将来: `reactivecircus/android-emulator-runner` で L3 を nightly に回す（v1 では設定しない）。

### 7. 品質ゲート

| ゲート | 条件 |
|---|---|
| G-TEST-1 | `./gradlew testDebugUnitTest` が全件成功 |
| G-TEST-2 | `./gradlew lintDebug` が **0 errors**（warnings は分類して記録。`abortOnError = true`） |
| G-TEST-3 | L4 チェックリストの必須項目（AC-10〜AC-18, AC-22〜AC-28, AC-31〜AC-36）が PASS |
| G-TEST-4 | `./gradlew assembleRelease` 成功 + `apksigner verify --print-certs` 成功（AC-41） |
| G-TEST-5 | release APK に `setWebContentsDebuggingEnabled(true)` が含まれない（`BuildConfig.DEBUG` 分岐の確認） |

## Consequences

### 利点

- ロジックの大半が JVM で検証でき、CI が数分で回る。
- 「テストできないもの」を明示することで、未検証を未検証として報告できる（誠実さ）。
- テストサーバーが標準ライブラリのみなので、環境構築が簡単で再現性が高い。

### 欠点・リスク

| # | リスク | 対策 |
|---|---|---|
| R1 | SSL インタースティシャルが自動テストされない | ロジック（TrustPolicy）は L1、UI は L4。将来 UI Automator で自動化を検討 |
| R2 | L4 は手作業で属人的 | チェックリスト化し、各項目に「期待結果」を書く（`docs/QA-CHECKLIST.md`） |
| R3 | エミュレータの WebView バージョンが実機と異なる | 実機（開発者本人の端末）でも最低1回実施 |
| R4 | `security-crypto` は Robolectric で動かない | L3（実機/エミュレータ）で検証。L1 では `CredentialVault` の Fake を使う |
| R5 | Digest サーバーの実装ミスで「WebView が悪い」と誤診する | まず `curl --digest` でサーバー単体を検証してから WebView を試す |

### 実装上の帰結

- 追加依存（無料・OSS）: `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")`、`androidTestImplementation("androidx.test:runner")` / `androidx.test.ext:junit` / `androidx.test:core`。**有料ツールは使わない**。
- `android { defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" } }` を追加（実装フェーズ）。
- `tools/qa-server.py` は実装フェーズで作成（本フェーズでは設計のみ）。

## Alternatives

### A. Robolectric を導入して JVM で Android API を模擬

- 利点: 速い。SharedPreferences や Context のテストが JVM で書ける。
- 欠点: Keystore / WebView は模擬できない。依存と設定が増える。得られる保証が限定的。
- 不採用（v1）。`BookmarkRepository` のテストは Fake ストアで代替できる。

### B. UI Automator / Espresso で SSL ダイアログを自動化

- 利点: 回帰検出。
- 欠点: WebView のコールバックを外部から駆動できず、テストサーバーと証明書の準備が必要。flaky になりやすい。
- 不採用（v1）。将来の nightly で検討。

### C. 実機クラウド（Firebase Test Lab 等）

- 利点: 多機種。
- 欠点: **有料**。要件「新たな有料ツールを使わない」に反する。
- 不採用。

### D. テストを書かずに手動 QA のみ

- 利点: 速い。
- 欠点: 要件 11 に反する。回帰検出不能。
- 不採用。

## Status

**Accepted**。L4 のチェックリストは実装フェーズの成果物に含める。
