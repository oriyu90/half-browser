# 最終検証（Final Verification）

- 文書ID: HB-FINAL-001（2026-10-31）
- 対象: Half-Browser v1.0.1 / `versionCode 2`
- APK: `app-release.apk`（1,953,793 B、署名 v2、証明書 SHA-256 `36:15:3A:ED:27:25:EF:E4:9D:A3:59:F8:F8:71:0E:A9:DE:6E:1D:3F:79:01:F3:53:3E:C9:F8:AF:A2:2A:0F:37`（APK の SHA-256 は Releases の `SHA256SUMS.txt`））
- 実行環境: Android 14（API 34）arm64 エミュレータ、1080×2400 / 420dpi、自己署名 HTTPS + Basic 認証サーバー
- 自動テスト: `:app:testDebugUnitTest` **57 件 / 失敗 0**、`:app:lintDebug` **Error 0**（Warning 63）

> 証跡のスクリーンショットは `shots/`（Git 管理外）に保存している。README 掲載分は `docs/images/` に抜粋。

---

## 1. 要件と検証結果

| # | 要件 | 結果 | 証拠・方法 |
|---|---|---|---|
| 1 | Chromium 互換ブラウザとして動く | **PASS** | Android System WebView（Chromium 113）で表示。URL 入力・検索（DuckDuckGo/Google/Bing）・戻る/進む・再読み込み・共有・リンクコピー・PC 版サイト・ダウンロードを実装。`shots/13-page-loaded.png`, `shots/14-browser-chrome.png` |
| 2 | 自己署名 HTTPS を警告付きで開ける | **PASS** | インタースティシャルにホスト／問題／発行先／発行元／SHA-256（OpenSSL の fingerprint と一致）／有効期限を表示。`shots/30-ssl-details-ja.png` |
| 3 | 「今回だけ信頼」 | **PASS** | 続行してページ表示。アプリ再起動後は再警告（保存されない）ことを実測。 |
| 4 | 「このサイトを常に信頼」 | **PASS** | ホスト + ポート + 証明書 SHA-256 をピン。再起動・再インストール後も警告なしで表示。`shots/41-release-reopen.png` |
| 5 | 証明書が変わったら再確認 | **PARTIAL** | 実装（`Changed` 判定＋二段確認）と単体テストはあり。QA サーバーの証明書を差し替えた実機確認は未実施。 |
| 6 | 信頼済み証明書の一覧・失効 | **PASS** | 設定に host:port・指紋・追加日・失効。失効後、WebView を再生成して再警告することを実測。`shots/12-auth.png` 前後 |
| 7 | HTTP Basic/Digest 認証 | **PASS** | `onReceivedHttpAuthRequest`。Basic 認証サーバーでサインインを実測。Digest は同一ハンドラで処理（専用サーバーでの実測は未実施）。 |
| 8 | ユーザー名・パスワードの保存 | **PASS** | Android Keystore + EncryptedSharedPreferences。保存ファイルは暗号文であることを確認（`shared_prefs/halfbrowser_secure.xml`）。バックアップ対象外。設定から削除可能。 |
| 9 | 保存済み資格情報の自動入力 | **PASS** | アプリ再起動後、SSL 警告も認証ダイアログも出ずにページ表示（release APK で実測）。 |
| 10 | ダーク／ライト／システム | **PASS** | 3 モード + 動的カラー（API 31+）。`shots/20-settings-dark.png`, `shots/22-home-ja-dark.png` |
| 11 | ホームにブックマークをアプリ風タイル表示 | **PASS** | モノグラムアイコン（オフライン生成、色は URL から決定、文字は編集可）、タイトル 2 行、長押しメニュー。`shots/15-home-with-bookmark.png` |
| 12 | 外部で開かれたブックマーク URL をこのアプリへ | **PASS**（OS 設定が必要） | http/https の `VIEW` + BROWSABLE でブラウザ候補。エミュレータで `RoleManager.ROLE_BROWSER` を付与し、`am start -a VIEW` のコールド／ウォーム両方で受信。ブックマーク済み URL はブックマーク名つきバーで表示。Android の仕様上、既定ブラウザはユーザーが選ぶ必要がある。 |
| 13 | ブックマークごとのホーム画面ショートカット | **PASS** | `ShortcutManagerCompat.requestPinShortcut`。ランチャーの確認ダイアログを経てピン留めを実測。`dumpsys shortcut` で `Pinned: bookmark_<uuid>`、モノグラム Bitmap を確認。`shots/17-launcher-shortcut.png` |
| 14 | スマホ／タブレット／アスペクト比対応 | **PASS** | 1080×2400（411dp）、1600×2560/320dpi（800dp）、横向き 2560×1600 を実測。`docs/images/tablet-home.png`, `shots/28-landscape-home.png` |
| 15 | 設定からスケール調整 | **PASS** | UI 130% + 文字 100% を適用し、設定画面の折返し・見切れがないことを確認。`shots/26-settings-scaled-ja.png`。列数 自動/3/4/5/6/8。 |
| 16 | ブックマーク時の上部バー（左:ホーム、中央:名前、右:3点） | **PASS** | ブックマーク名・ホスト・鍵アイコン・★・⋮。メニューは 8 項目を折りたたみ表示。`shots/14-browser-chrome.png` |
| 17 | 日本語 / English 完全対応 | **PASS** | 176 キー × 2 言語。単体テストでキー集合・空文字・プレースホルダ一致を強制。アプリ内切替（システム/日本語/English）を実測。`shots/21-settings-ja-dark.png` |
| 18 | クラッシュ安全性 | **PASS**（一部未検証） | QA 期間の FATAL 0 件。不正 Intent・破損 JSON・SSL キャンセル・レンダラ死亡をハンドリング。レンダラ強制終了の実地試験は未実施。 |
| 19 | リリース・配布 | **PASS** | 署名付き APK を GitHub Releases で公開。`apksigner verify` 成功、SHA-256 記録。 |
| 20 | 保守情報・署名鍵の保管 | **PASS** | 非公開 `common-rules-document` の `keystores/half-browser-upload-key.jks` に保管し、`sync-keystore.sh` で push。保守情報は同リポジトリの `half-browser.md`。 |

---

## 2. 主要な実測コマンド

```bash
# 単体テスト・lint
./gradlew :app:testDebugUnitTest :app:lintDebug

# 署名検証
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
# -> v2, CN=Yuki Orita, SHA-256 36153aed...

# 署名なしリリースが失敗すること（AC-41b）
env -u STORE_PASSWORD -u KEY_PASSWORD ./gradlew :app:packageRelease
# -> SigningConfig "release" is missing required property "storePassword"

# 外部 Intent（コールド）
adb shell am force-stop com.yukiorita.halfbrowser
adb shell am start -a android.intent.action.VIEW -d "https://192.168.0.165:8844/"

# ショートカット
adb shell dumpsys shortcut | grep -i halfbrowser
# -> Pinned: bookmark_<uuid>, intents=[halfbrowser://bookmark/...]
```

---

## 3. 未検証（PASS としない）

| 項目 | 状態 |
|---|---|
| API 26–28 実機での `SslCertificate.saveState` 経路 | NOT_TESTED（実装・単体テストのみ） |
| 証明書差し替え（Changed）の実機表示 | NOT_TESTED（実装・単体テストのみ） |
| WebView レンダラ強制終了からの復帰 | NOT_TESTED（実装のみ） |
| `<input type="file">` | NOT_SUPPORTED（スコープ外） |
| クライアント証明書 | NOT_SUPPORTED（明示的に cancel） |
| Digest 認証の実サーバー | NOT_TESTED（Basic で実測） |
| 折りたたみ・RTL・TalkBack | NOT_TESTED |
| 500 ブックマーク・巨大ページの性能 | NOT_TESTED |

---

## 3.1 実機（SOV40 / Android 11 / API 30）

署名済み v1.0.1 を Sony SOV40 にインストールし、エミュレータと同じ主要経路を実測した。
詳細は [DEVICE-QA-SOV40.md](DEVICE-QA-SOV40.md)。

- PASS: 自己署名 HTTPS の警告・詳細・常に信頼、Basic 認証の保存と再起動後の自動表示、
  失効の即時再警告（v1.0.1）、ライト/ダーク、日英切替、UI 130%、横向き、ブックマーク、
  ショートカット確認、外部リンク chooser、他のブラウザ chooser（自己除外）、認証/証明書キャンセル時のエラーペイン。
- 実機で発見し v1.0.1 で修正: 失効が再起動まで効かない、URL がタイトルに出る、生 401 表示、
  ダイアログ外タップで入力消失。
- 未検証: 既定ブラウザの確定、ショートカットの最終配置、証明書差し替え、API 26–28 実機。

## 4. 判定

- 主要要件は **すべて実装され、release APK で実測済み**。
- 未検証項目は上表のとおり明示し、README と設計評価にも記載した。
- **リリース判定: PASS_WITH_NOTES**。


---

## 5. v1.1.0 追加検証（2026-11-01）

対象: `versionCode 3` / `versionName 1.1.0`（署名済み release、APK SHA-256 は Releases の SHA256SUMS.txt）
環境: Android 14 エミュレータ（API 34 / WebView 113）

| 項目 | 結果 | 証拠・備考 |
|---|---|---|
| ファイル添付（SAF） | **PASS** | `Choose image` → ダイアログ → ファイル選択 → Photo Picker で画像選択 → ページに `Choose File: 1000000037.png`。release でも確認 |
| カメラ撮影による添付 | **PASS** | カメラ権限ダイアログ → カメラアプリ起動 → シャッター → 確認 → ページに `capture_….jpg` |
| Web カメラ（getUserMedia） | **PASS** | origin 付き確認ダイアログ → 許可 → ページログ `camera: granted`、カメラ使用中インジケータ表示 |
| マイク | **PARTIAL** | 同一の `PermissionRequest` 経路を実装。専用の録音テストは未実施 |
| 位置情報 | **PARTIAL** | origin 付き確認 → Android の位置情報権限（正確/おおよそ）まで実測。エミュレータに位置フィックスが入らず `getCurrentPosition` の結果は未確認 |
| 動画の全画面 | **PASS** | 全画面ボタンで没入モードのダイアログが開き、システムバーが隠れる。戻るで復帰 |
| JS alert / confirm / prompt | **PASS** | 標準ダイアログを実測 |
| ダウンロード | **PASS** | 「Download started」トースト、DownloadManager へ |
| target=_blank | **PASS** | 同一タブで `/second` を表示（単一タブ設計） |
| サイト通知: 初回ベースライン | **PASS** | 監視 ON → lastCheckedAt / fingerprint 保存、通知なし |
| サイト通知: 変更検知と通知 | **PASS** | ページ内容変更 → `Check now` → `notified=1`、通知（タイトル=ブックマーク名） |
| 通知タップ | **PASS** | 通知タップで該当ブックマークをブラウザで表示 |
| バックグラウンド実行 | **PASS** | アプリを Home に送った状態で Worker が lastCheckedAt を更新（`top=launcher`） |
| プロセス kill 後の実行 | **PASS** | `am kill` 後、Worker が起動して変更を検知し通知（`notified=1`） |
| クールダウン | **PASS** | 30 分以内の再変更では通知せず、fingerprint のみ更新 |
| 通知権限（API 33+） | **PASS** | 設定から許可、通知チャンネル `site_watch` |
| 監視の ON/OFF・間隔・Wi-Fi のみ | **PASS** | 設定 UI で変更、WorkManager の制約に反映 |
| release 起動（R8） | **PASS** | WorkManager/Room の keep ルール追加後、release が起動・DB 初期化・通知まで動作 |
| バックグラウンド省電力 | **PASS（設計）** | WebView を起動しない Worker、15 分以上、電池残量低下時に実行しない、Wi-Fi のみ設定 |
| バックグラウンド省メモリ | **PARTIAL** | 画面停止時に `pauseTimers()` / `freeMemory()` を追加。プロセスはページ保持のため PSS 100MB 超のことがある。Worker 自体は WebView を使わない |

### 制約（重要）

- Android WebView は `Notification: undefined` / `PushManager: false` のため、**サイト起点の Web Push は受信できません**。
  v1.1 の「サイト通知」はブックマークページの**変化検知**です（ADR-0011）。
- Cookie セッションや JS レンダリングに依存するページは変化を検出できない場合があります。
- 最短間隔は 15 分。Doze 中は遅延します。
- SOV40（Android 11）実機での v1.1.0 検証は未実施（端末ロックのため）。v1.0.1 の実機結果は
  [DEVICE-QA-SOV40.md](DEVICE-QA-SOV40.md) を参照。
