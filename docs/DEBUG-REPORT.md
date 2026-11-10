# デバッグ・QA レポート

- 文書ID: HB-DEBUG-001（2026-10-31）
- 対象: Half-Browser v1.0.0（debug / signed release）
- 環境: macOS 26（arm64）、Android Emulator `mm_debug`（Android 14 / API 34 / arm64 / 1080×2400 / 420dpi）、
  JDK 17、AGP 9.1.1、Gradle 9.5.1、Android SDK platform 36.1 / build-tools 36.0.0。
- テストサーバー: `tools/qa-server/`（自己署名 RSA-2048、`https://192.168.0.165:8844`、HTTP Basic `halfuser` / realm `Half-Browser QA`）。
  「ローカル HTTPS + 自己署名 + Basic 認証」というユーザー要件の実環境を再現するために用意した。

---

## 1. QA 手順（再現可能）

```bash
# 1. 自己署名証明書と Basic 認証サーバー
tools/qa-server/make-cert.sh 192.168.0.165
(cd tools/qa-server && python3 server.py --port 8844) &

# 2. ビルドとインストール
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. UI 操作（テキスト一致でタップする補助）
python3 tools/adb-tap.py "Always trust this site"
python3 tools/adb-tap.py "Sign in" --last

# 4. 画面・ログ
adb exec-out screencap -p > shots/xx.png
adb logcat -b crash -d
```

実測した項目:

1. 初回アクセス → 証明書インタースティシャル（ホスト / 問題 / 詳細 / SHA-256 / 有効期限）
2. 「常に信頼」→ Basic 認証ダイアログ → 資格情報入力＋保存 → ページ表示
3. アプリ再起動 → SSL 警告も認証ダイアログも出ずに自動表示（ピン＋資格情報の永続化）
4. 設定 → 信頼した証明書の一覧・失効 → 次回読み込みで再警告
5. 「今回だけ信頼」→ 再起動後に再警告（保存されない）
6. 設定 → ダーク、日本語、UI 130%、タブレット 1600×2560/320dpi、横向き
7. 外部アプリ相当の `VIEW` Intent（コールド／ウォーム）→ ブックマーク名つきバーで表示
8. ホーム画面ショートカットのピン留め（ランチャー確認）、ショートカット起動
9. リリース APK（R8）で 1–8 の主要経路を再実行

---

## 2. 発見・修正した不具合

| # | 症状 | 根本原因 | 修正 | 検証 |
|---|---|---|---|---|
| B-01 | ブラウザ上部バーが表示されず、Web ページがステータスバーまで全面を覆う | `WebView` に `layoutParams = MATCH_PARENT` を設定し、Compose の配置を無視して全画面にレイアウトされていた | `layoutParams` を削除し、`AndroidView` に測定を任せる。コンテンツは `Modifier.weight(1f)` + `clipToBounds` | スクリーンショット `shots/14-browser-chrome.png` |
| B-02 | ダークテーマで、背景色を指定しないページが黒背景＋黒文字で読めない | WebView の背景をアプリのダーク surface にしていた | ページ面は常に白。ダーク時は WebView の Algorithmic Darkening（機能検出時のみ）に委ねる | `shots/25-browser-ja-dark.png` |
| B-03 | アプリ起動中に外部リンクを受け取るとホームのまま（遷移しない） | ナビゲーション通知に `MutableSharedFlow` を使用。購読開始前の `tryEmit` が破棄されていた | バッファ付き `Channel` + `receiveAsFlow()` に変更 | コールド／ウォームの `am start -a VIEW` で実測 |
| B-04 | 設定で証明書を失効しても、開いている WebView では警告が出ない | WebView がプロセス内で SSL 例外の許可を保持していた | `trustRevision` を `AppContainer` で通知し、変更時に WebView を再生成（世代番号）して再読み込み | 失効 → 再読み込みで警告を実測 |
| B-05 | API 26–28 で証明書フィンガープリントが常に null（「常に信頼」が機能しない） | `SslCertificate.getX509Certificate()` は API 29 以降。`runCatching` が `NoSuchMethodError` を握り潰していた | `saveState()` の Bundle から X.509 を復元。既知キー→Bundle 走査の二段フォールバック。取得不能時は pin を無効化し「今回だけ」のみ許可 | API 34 で経路を確認。**API 26 実機は未検証** |
| B-06 | リリースビルドが R8 で失敗 | `androidx.security:security-crypto` が参照する Tink のアノテーションクラスが無く、primitive をリフレクションでロードする | `-dontwarn`（errorprone / javax.annotation）と Tink の keep ルールを追加 | `assembleRelease` 成功、実機で資格情報の保存・復号を確認 |
| B-07 | 署名なしリリースが生成され得る | `signingConfig` を環境変数がある場合だけ設定していた | release は常に署名設定を使用。パスワード未設定なら Gradle が明示的に失敗 | 環境変数なしで `packageRelease` → `SigningConfig "release" is missing required property "storePassword"` を確認 |
| B-08 | 「外部ブラウザで開く」が自分自身を再度開くことがある | 汎用 `ACTION_VIEW` の解決結果に自パッケージが含まれる | 自パッケージを除外した候補でのみ chooser を表示。候補 0 件はトースト | コード確認・メニュー操作 |
| B-09 | 証明書の失効後、同じ URL を開き直しても再読み込みされない | `lastRequestedUrl` と同一のため `LaunchedEffect` がロードをスキップ | 失効時に `generation` を進めて WebView を作り直し、現在 URL を再ロード | 失効 → 再読み込みを実測 |
| B-10 | 「今回だけ信頼」でキャンセルすると白画面のまま | `SslErrorHandler.cancel()` は `onReceivedError` を必ずしも発火しない | キャンセル時にエラーペイン（再試行付き）を明示表示 | `shots/31-ssl-cancel-error.png`（修正前）／修正後はエラーペイン |
| B-11 | ピンがホスト単位のため、同一ホストの別ポートの証明書と衝突する | 信頼キーが host のみ | `host + port + 証明書 SHA-256` に変更。設定表示も `host:port` | 単体テスト `TrustPolicyTest` |
| B-12 | 期限切れ・ホスト名不一致の証明書でも「常に信頼」できた | UI がフィンガープリントの有無だけで有効化していた | `TrustPolicy.canPin()` を追加し、`SSL_UNTRUSTED` のみ許可。不可時は説明を表示 | 単体テスト・コード確認 |
| B-13 | 保存に失敗しても「保存しました」と表示される | `CredentialStore.save()` の戻り値を無視していた | 戻り値を確認し、失敗時は `auth_save_failed` を表示（成功トーストは削除） | コード確認 |
| B-14 | 証明書変更時に 1 タップで古いピンを上書きできた | 「常に信頼」に二段確認がなかった | `Changed` の場合は確認ダイアログを追加 | コード確認（実機の証明書差し替えは未実施） |
| B-15 | 認証の自動入力が 2 回失敗すると同一画面で復活しない | 試行カウンタをリセットしていなかった | ページ表示コミット時にホストのカウンタをクリア（`onPageCommitVisible`） | 単体テスト `AuthAttemptTrackerTest` |
| B-16 | 同一ホストのサブリソースの SSL エラーがメインの判断を置き換える | すべての `SslError` でダイアログを出していた | メインフレーム URL と一致する場合のみダイアログ。サブリソースは拒否 | コード確認 |
| B-17 | `intent://` が任意の VIEW アプリを起動し得る（intent redirection） | `Intent.parseUri` の復元 Intent を chooser に渡していた | `intent://` は一切起動しない。`S.browser_fallback_url` のみ http(s) 検証して読み込み | コード確認・独立レビュー反映 |

---

## 3. クラッシュ・ログ確認

- `adb logcat -b crash -d`: debug / release の QA 期間を通じて **FATAL 0 件**。
- R8 リリースで `kotlinx.serialization`（ブックマーク・信頼・設定）と Tink（資格情報）の
  動作を実機で確認（再起動後の自動入力が成立）。
- WebView レンダラ強制終了（`chrome://crash`）は未実施。`onRenderProcessGone` の実装のみ確認。

---

## 4. 未実施・残課題

| 項目 | 理由 | 次の一手 |
|---|---|---|
| API 26–28 実機での証明書ピン | システムイメージが API 34 のみ | `sdkmanager "system-images;android-26;google_apis;arm64-v8a"` で AVD を作成し、`SslCertificate.saveState` 経路を実測 |
| ファイル選択（`<input type="file">`） | スコープ外 | `onShowFileChooser` + ActivityResult |
| レンダラ強制終了 | 時間・手法の制約 | `chrome://crash` または `adb shell am crash` で UI 復帰を確認 |
| 巨大ページ・500 ブックマークの性能 | 未測定 | Macrobenchmark / 実データ |
| 折りたたみ・RTL・TalkBack | 未検証 | 実機／エミュレータの追加マトリクス |
| 証明書差し替え（Changed）の実機確認 | QA サーバーの証明書を再生成して実施可能 | 手順は `tools/qa-server/make-cert.sh` の再実行 |

---

## 4.1 実機（SOV40 / Android 11）で追加発見した不具合

エミュレータ（API 34 / WebView 113）では再現せず、API 30 / WebView 153 の実機で見つかった 4 件は
v1.0.1 で修正した。詳細と証跡は [DEVICE-QA-SOV40.md](DEVICE-QA-SOV40.md) を参照。

| ID | 症状 | 修正 |
|---|---|---|
| B-18 | 証明書の失効がアプリ再起動まで反映されない | 生きた WebView で `clearSslPreferences()` を実行してから再生成 |
| B-19 | 読込前のバータイトルが URL 全体 | host:port へフォールバック、URL 風タイトルを除外 |
| B-20 | 認証キャンセルで生の 401 が表示される | AUTH 用エラーペイン + Retry、401/407 を捕捉 |
| B-21 | キーボード表示中の外側タップで入力が消える | 各ダイアログを `dismissOnClickOutside=false` に |

## 4.2 v1.1.0 実装中に発見した不具合

| ID | 症状 | 原因 | 修正 |
|---|---|---|---|
| B-22 | release が起動直後にクラッシュ（`NoSuchMethodException: WorkDatabase_Impl.<init>`） | R8 が Room の生成 DB と Worker のコンストラクタを削除 | Room/WorkManager/Worker の keep ルールを追加。debug だけでなく release の起動確認を必須化 |
| B-23 | 「カメラで撮影」を押しても起動しない | FileProvider の authority が文字列補間されず `${context.packageName}.fileprovider` のままだった | `"${context.packageName}.fileprovider"` に修正 |
| B-24 | ACTION_IMAGE_CAPTURE が `SecurityException`（CAMERA 宣言済み・未許可） | CAMERA を Manifest に追加したため、撮影インテントに実行時許可が必要 | 撮影前に CAMERA を要求し、許可後に起動 |

## 6. v1.6.0 検証（2026-11-09、エミュレータ API 34 / WebView 113）

### 6.1 VPN

| 項目 | 結果 |
|---|---|
| 依存解決（Maven Central `com.wireguard.android:tunnel:1.0.20260102`） | 成功。AAR に全 ABI の `libwg-go.so` |
| プロファイル追加（手入力／ファイル読み込み） | 成功。不正設定は項目ごとの理由で拒否（実測） |
| システム同意 → 接続 | トンネル Up（`Device started`）。VPN ネットワークに自 UID のみ紐付けを確認 |
| 再接続 | 初回実装では切断→即時起動で新トンネルが破壊される競合を実測。切断後 600ms のクールダウンで修正し、接続維持を再確認 |
| ハンドシェイクのパケット到達 | **未確認**。ホスト上の対向サーバ（`tools/vpn-test/wg_responder.py`、純粋 Python 実装）にパケットが届かない。`/proc/net/dev` で eth0 TX が増えず tun0 TX が増える（トンネル内ループ）ことを確認。公式 WireGuard アプリでも同一設定で再現したため、エミュレータ（user-net）の protect 経路の制限と判断。実機確認が残課題 |

### 6.2 JS 攻撃対策（`tools/vpn-test/attack.html`、CDP で操作）

| 攻撃 | 結果 |
|---|---|
| alert 20 連打 | 12 表示（うち origin 付き）、残り 8 は自動ブロック。ループ終了を確認 |
| `window.open` 5 連打 | 5 件すべてブロック（`popupsBlocked=5`） |
| `beforeunload` 5 登録 | 3 件ブロック（`beforeUnloadBlocked=3`）。離脱は確認ダイアログ経由で可能 |
| `location = 'javascript:alert(1)'` | 実行されるが、その alert は予算でブロック。代入自体は止められない（ADR-0019 の限界） |

### 6.3 UI（日本語・英語）

- 設定→VPN、設定→セキュリティ（JS ガードトグル）、ホーム下部パネル（接続／切断／再接続、
  一覧の展開・選択表示・管理）を日英とも目視確認。FAB とパネルの重なりは `bottomBar` 化で解消。
- JVM テスト **162 件 / 失敗 0**、lint **Error 0**。

## 7. v1.6.1 精査・修正の検証（2026-11-09、エミュレータ API 34 / WebView 113）

| 項目 | 結果 |
|---|---|
| JS ガードトグル OFF | シム未注入（`__hbJsGuardInstalled` なし）、WebView 標準ダイアログにフォールバック |
| JS ガードトグル ON | WebView 再生成後にシム復帰を確認 |
| DialogBudget | ページ開始時にリセットされる配線を確認（コード＋単体テスト）。実機では alert 連打が従来通り 12 で止まることを再確認 |
| VPN エディタ | 空名で「Enter a profile name.」をインライン表示。不正設定の取り込みエラーも従来通り表示 |
| 再接続（DOWN シグナル待ち） | DOWN 09:45:37.421 → UP 09:45:37.574（約 74ms）で接続維持。固定ディレイなし |
| 接続 | 同意→接続で `Device started`＝トンネル Up を再確認 |
| JVM テスト | **170 件 / 失敗 0**、lint **Error 0**。署名済み release の起動も確認 |

## 5. 再発防止

1. **実機でしか出ない不具合**（B-01, B-02, B-03, B-04）を優先して確認する。静的テスト・lint だけでは検出できない。
2. WebView の設定はセキュリティ関連を先頭に、**個別の `runCatching`** で適用する（1 つの失敗で後続を止めない）。
3. 外部入力（Intent、URL、SSL 証明書、Basic 認証の realm）はすべて長さ・スキーム・canonical 一致で検証する。
4. リリースは「ビルド成功」ではなく、**署名検証 → 実機インストール → 主要経路の再実行**までを 1 セットとする。

---

## 8. v1.7.0 検証（2026-11-10、エミュレータ API 36.1 / Medium_Phone_API_36.1）

QR コード取り込みの追加に伴う検証。

| # | 項目 | 結果 |
|---|---|---|
| 1 | JVM テスト 173 件（QR 往復 3 件を追加: 設定の QR 往復、マルチピア、非設定テキストの拒否） | PASS（失敗 0） |
| 2 | `lintDebug` | Error 0 |
| 3 | 署名付き release ビルド（R8） | 成功。`apksigner verify` 証明書 SHA-256 `36153aed…22a0f37`（v1.6.1 と同一鍵） |
| 4 | release APK インストール・起動 | 正常 |
| 5 | 設定 → VPN に「Scan QR code / QR コードをスキャン」ボタン（FlowRow 折り返し） | 表示確認（日英） |
| 6 | スキャナ起動 → カメラ実行時権限ダイアログ → 許可 → プレビュー表示 | 正常 |
| 7 | スキャナのキャンセル（BACK） | エラー表示なし・無操作（設計どおり） |
| 8 | .conf 取り込み → エディタ事前入力（名前/設定） → 保存 → 一覧表示 | 正常（QR と同じ後段経路） |
| 9 | 接続 → システム同意 → 「Connected to test-profile」、ステータスバーに VPN アイコン | 正常 |
| 10 | `dumpsys connectivity`: VPN ネットワークの `Uids: <{10224-10224, 20224-20224}>`（OwnerUid 10224 = Half-Browser） | **自 UID のみに適用を実証** |
| 11 | 接続中に許可リスト外の UID（shell）で `ping 8.8.8.8` | 0% loss（他アプリは通常経路のまま） |
| 12 | 切断 → Disconnected、プロファイル選択の維持 | 正常 |
| 13 | 言語切替（日本語）後の VPN タブ全文字列・スキャンボタン | 正常 |
| 14 | `logcat -d` の FATAL EXCEPTION | **0 件** |

### 未実施・残課題

- 実カメラでの QR 読み取り E2E（エミュレータ仮想シーンの `virtualscene-image` が本
  バージョンでは描画に反映されないため）。デコード自体は JVM 往復テストで担保。
  実機確認は v1.6.0 からの WireGuard ハンドシェイク実機確認と同時に実施する。
- QR 容量上限（約 2.9 KiB）を超える設定は QR 化不可（公式アプリと同じ制約）。

---

## 9. v1.7.1 検証（2026-11-10、エミュレータ API 36.1 / Medium_Phone_API_36.1）

Always-on VPN 復元と実 WireGuard ハンドシェイク E2E の検証。

| # | 項目 | 結果 |
|---|---|---|
| 1 | JVM テスト 176 件（`VpnAlwaysOnTest` 3 件を追加） | PASS（失敗 0） |
| 2 | `lintDebug` | Error 0 |
| 3 | 署名付き release ビルド（R8） | 成功。証明書 SHA-256 `36153aed…22a0f37` |
| 4 | Always-on 有効 → 再起動 → `dumpsys connectivity` に `VPN CONNECTED`（Half-Browser）が自動復元 | PASS |
| 5 | `dumpsys connectivity`: VPN ネットワークの `Uids: <{10224-10224, 20224-20224}>`（OwnerUid 10224） | 自 UID のみに適用を実証 |
| 6 | 実 WireGuard 対向（colima Docker、gRPC ポートフォワーダ経由）とのハンドシェイク | `wg show` の `latest handshake` 非ゼロ、転送カウンタ増加 |
| 7 | WebView で `http://10.8.0.1:8000` 読み込み → `HALF-BROWSER-TUNNEL-E2E-OK` 表示 | PASS |
| 8 | 許可リスト外の UID（shell, uid 2000）からの `10.8.0.1:8000` 到達 | タイムアウト（他アプリはトンネル外） |
| 9 | `logcat -d` の FATAL EXCEPTION | **0 件** |

### 未実施・残課題

- 実機（非エミュレータ）での Always-on 復元とハンドシェイク確認は引き続き残課題。
- Keystore 未アンロック時のリトライ挙動はロジック上対応済みだが実機未測定。
