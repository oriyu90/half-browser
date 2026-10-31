# Half-Browser v1.1 実装計画（ブラウザ機能補完 + サイト通知）

- 文書ID: HB-PLAN-002 / 版 1.0（2026-11-01）
- 対象: Half-Browser v1.0.1（`com.yukiorita.halfbrowser`）
- 目的: (1) ファイル添付・カメラ等の外部連携・一般的なブラウザ動作の不足を補完する。
  (2) ブックマークしたサイトの更新をバックグラウンドで監視し、アプリを閉じていても通知する。
- 前提: 実装前に本計画と ADR-0011 / ADR-0012 を確定し、実装後に受入条件を実測する。

---

## 1. 現状の実測（2026-11-01 / エミュレータ API 34・WebView 113、実機 SOV40 API 30・WebView 153）

検証ページ `https://192.168.0.165:8844/features`（自己署名 + Basic 認証）を使用。

| 項目 | 現状 | 根拠 |
|---|---|---|
| ファイル添付（`<input type=file>`） | **不可**。タップしても何も起きない | `onShowFileChooser` 未実装（既定 `false`）。実測で Documents UI が起動しない |
| カメラ（`getUserMedia`） | **不可**。常に拒否 | `onPermissionRequest` が無条件 `deny()`。`mediaDevices` 自体は存在 |
| マイク | **不可** | 同上 |
| 地理位置情報 | **不可（プロンプトが出ない）** | `onGeolocationPermissionsShowPrompt` 未実装。`geolocation` は存在 |
| 外部アプリ（mailto / tel / sms / geo） | **動作** | `shouldOverrideUrlLoading` → `startExternalView`。自パッケージを除外した chooser |
| `intent://` | **意図的に拒否**（安全設計） | 任意コンポーネント起動の防止。fallback URL のみ読込 |
| `target=_blank` / 新規ウィンドウ | 同一タブで開く（実用上は動作） | `setSupportMultipleWindows(false)` + `onCreateWindow=false`。実測で `/second` へ遷移 |
| JavaScript ダイアログ（alert/confirm/prompt） | **動作** | 実測で「The page … says: alert works」 |
| ダウンロード | **動作** | `DownloadListener` → DownloadManager。実測で「Download started」トースト |
| 動画の全画面 | **未実装**（インライン再生のみ） | `onShowCustomView` / `onHideCustomView` 未実装 |
| Web Notifications API | **WebView 非対応** | 実測: `Notification: undefined` |
| Web Push / Service Worker Push | **WebView 非対応** | 実測: `PushManager: false`（`serviceWorker: true` だが Push 不可） |
| Cookie / JS / DOM ストレージ / ズーム | 動作 | v1.0.1 で設定済み |
| 自己署名 HTTPS / Basic 認証 | 動作 | v1.0.1 で実機検証済み |

### 1.1 通知に関する技術的制約（重要）

Android WebView は **Web Notifications API と Web Push を実装していない**（`Notification` が undefined、
`PushManager` が false）。したがって「サイト自身が送るプッシュ通知」を任意のサイトについて
WebView で受けることはできない。Service Worker を常駐させても Push 基盤がないため代替にならない。

そこで v1.1 の「サイト通知」は、**ブックマークしたページを WorkManager で定期的に取得し、
内容の変化を検出してローカル通知する**方式とする。これはサイト起点のプッシュではなく
「ページ更新監視」であり、UI・文書でその旨を明示する。

- 利点: WebView を起動しないため省メモリ。短時間の HTTP 取得のみで省電力。アプリ終了後も
  WorkManager が実行する。端末再起動後も OS がスケジュールを復元する。
- 制約: ログイン必須・JS レンダリング依存・Cookie セッション依存のページは変化を検出できない
  場合がある。最短間隔は WorkManager の制約により 15 分。Doze 中は遅延する。
- 将来: Web Push 対応にはサイト側の購読実装と FCM 等が必要で、汎用ブラウザでは不可能。
  必要なら専用クライアント（対象サイトごとのネイティブ実装）とする。

---

## 2. スコープ

### 2.1 実装する

**A. ブラウザ機能の補完**
1. ファイル選択（`<input type=file>`）: 単一/複数/保存モード、accept の反映、SAF。
2. カメラ撮影による添付: 画像 accept 時に「ファイル / カメラ」を選べる。FileProvider 経由の一時ファイル。
3. Web 権限プロンプト: カメラ・マイク（`PermissionRequest`）、位置情報（`GeolocationPermissions`）。
   ページ origin を表示し、許可/拒否を選択。Android の実行時権限も要求。許可はセッション中のみ
   （永続許可は持たない）。
4. 動画の全画面表示（`onShowCustomView` / `onHideCustomView`、システムバー非表示、戻るで解除）。
5. 既存の外部アプリ連携・JS ダイアログ・ダウンロード・`target=_blank` の回帰維持。

**B. サイト更新通知（バックグラウンド）**
1. ブックマーク単位の監視 ON/OFF。
2. WorkManager による定期チェック（15/30/60/180 分、既定 30 分）。Wi-Fi のみ・電池残量低下時に
   実行しない制約。指数バックオフ。アプリ終了・再起動後も動作。
3. 変化検出: HTML から本文テキストと `<title>` を抽出し SHA-256 で比較。初回はベースライン保存のみ。
4. 通知: サイト名・新タイトル・短い本文抜粋。タップで該当ブックマークを開く。同一サイトは
   30 分のクールダウンで連続通知を抑制。
5. 自己署名 HTTPS / Basic 認証のサイトにも対応（保存済みピンと資格情報を利用）。
6. 通知管理 UI: 設定に「サイト通知」セクション（監視中サイト一覧、ON/OFF、最終確認時刻、
   最終エラー、今すぐ確認、全体 ON/OFF、間隔、Wi-Fi のみ、通知権限の状態と要求）。
7. Android 13+ の `POST_NOTIFICATIONS` 実行時権限、通知チャンネル、通知タップのディープリンク。

### 2.2 実装しない（明示）

- サイト起点の Web Push / Service Worker 通知（WebView 非対応。§1.1）。
- ログイン後ページの完全なレンダリング監視（Cookie セッション・JS 実行を伴う監視はしない）。
- タブの複数化、バックグラウンドでの WebView 常駐、フォアグラウンドサービス。
- 位置情報の永続許可、カメラ/マイクの永続許可。

---

## 3. 設計

### 3.1 ブラウザ機能（ADR-0012）

```
BrowserScreen
 ├─ fileChooserLauncher (ActivityResultContracts.StartActivityForResult)
 │    ├─ params.createIntent() → SAF（複数/保存対応）
 │    └─ 画像時は「ファイル / カメラ」ダイアログ → FileProvider の一時 Uri
 ├─ permissionLauncher (RequestMultiplePermissions)
 ├─ WebPermissionDialog（カメラ/マイク、origin 表示）
 ├─ GeolocationDialog（origin 表示、許可はセッション）
 └─ FullscreenVideoDialog（onShowCustomView の View をホスト）
```

- `HalfBrowserChromeClient` にコールバックを注入:
  `onShowFileChooser`, `onPermissionRequest`, `onGeolocationPermissionsShowPrompt`,
  `onShowCustomView`, `onHideCustomView`。
- ファイル選択の `ValueCallback<Array<Uri>>` は 1 件だけ保持。結果受信時に
  `params.parseResult(resultCode, data)` を優先し、カメラ時は自前 Uri。
- FileProvider: `${applicationId}.fileprovider`、`cacheDir/camera/`。
- カメラ/マイク: `CAMERA` / `RECORD_AUDIO` を Manifest に追加。許可後に `request.grant(resources)`。
- 位置情報: `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`。許可後に
  `callback.invoke(origin, true, false)`（remember=false）。
- 全画面: `Dialog(usePlatformDefaultWidth=false)` に `FrameLayout` を置き、カスタム View を add。
  解除時に remove + `onCustomViewHidden()`。`WindowInsetsController` でバーを隠す。
- 既存の `onCreateWindow` は同一タブ読込のまま（単一タブ設計）。文書に明記。

### 3.2 サイト通知（ADR-0011）

#### データ

```kotlin
@Serializable
data class SiteWatch(
    val bookmarkId: String,
    val enabled: Boolean = false,
    val lastCheckedAt: Long = 0,
    val lastChangedAt: Long = 0,
    val lastNotifiedAt: Long = 0,
    val fingerprint: String = "",
    val lastTitle: String = "",
    val lastError: String = "",
)

@Serializable
data class SiteWatchSettings(
    val enabled: Boolean = true,          // 全体スイッチ
    val intervalMinutes: Int = 30,        // 15/30/60/180
    val wifiOnly: Boolean = false,
    val lastRunAt: Long = 0,
    val lastRunResult: String = "",
)
```

- 保存: SharedPreferences + JSON（既存 `Codecs` パターン）。`SiteWatchRepository` が StateFlow を公開。
- 通知設定は `AppSettings` ではなく専用リポジトリ（関心の分離、設定画面の一覧性）。

#### スケジューラ

```
WatchScheduler.sync(context, settings, watches)
  PeriodicWorkRequestBuilder<SiteWatchWorker>(interval, MINUTES)
    .setConstraints(NetworkType.UNMETERED if wifiOnly else CONNECTED,
                    setRequiresBatteryNotLow(true))
    .setBackoffCriteria(EXPONENTIAL, 30s)
    .build()
  enqueueUniquePeriodicWork("site-watch", UPDATE, request)
```

- 監視が 0 件 or 全体 OFF → `cancelUniqueWork`。
- 初回 ON 時は `OneTimeWorkRequest` で即時ベースライン取得（通知はしない）。
- 設定変更（間隔/Wi-Fi）で UPDATE。

#### Worker

`SiteWatchWorker : CoroutineWorker`
1. `SiteWatchRepository` から enabled な監視 + ブックマークを取得。0 件なら成功終了。
2. 各サイトを**直列**に取得（同時接続 1）。1 サイトあたり:
   - 接続/読み取りタイムアウト 10 秒、最大 512 KiB、リダイレクト 5 回まで。
   - 自己署名: `TrustRepository` の host+port ピンに一致する証明書のみ許可する
     `X509TrustManager`（既定の CA 検証を先に試す）。一致しなければ失敗。
   - Basic 認証: 401 の `WWW-Authenticate` から realm を取り、`CredentialStore` の
     `https://host:port|realm` を 1 回だけ試す。http は送らない。
   - `User-Agent` は通常の WebView UA + ` HalfBrowserWatch/1.1`。
3. HTML を正規化（script/style/noscript 除去、タグ除去、空白圧縮、先頭 20,000 文字）し
   SHA-256 を計算。`<title>` を抽出。
4. ベースラインが空 → 保存のみ。同一 → 時刻更新のみ。
5. 変化あり → クールダウン（30 分）と全体 ON を確認して通知。`lastNotifiedAt` 更新。
6. 失敗 → `lastError` に短い理由（最大 200 文字）を保存。通知はしない。
7. 全サイト終了後、設定に lastRunAt / lastRunResult を保存。

- メモリ: レスポンスは 512 KiB で打ち切り、1 サイトずつ処理。Bitmap/WebView は使わない。
- 電力: 15 分未満の周期は使わない。画面 OFF でも WorkManager がまとめて実行。ネットワーク制約で
  オフライン時は走らない。電池残量低下時は走らない。

#### 通知

- チャンネル `site_watch`（IMPORTANCE_DEFAULT、説明「ブックマークしたページの更新」）。
- `NotificationCompat.Builder`
  - 小アイコン: アプリのモノクロアイコン。
  - タイトル: ブックマーク名。
  - 本文: 新しいページタイトル（なければ URL の host）+ 「ページが更新されました」。
  - `setContentIntent`: `MainActivity` に `halfbrowser://bookmark/{id}`。
  - `setAutoCancel(true)`, `setOnlyAlertOnce(true)`, `setGroup("site_watch")`。
  - 通知 ID = bookmarkId.hashCode()（同じサイトは上書き）。
- Android 13+ は `POST_NOTIFICATIONS` が無いと表示されない。設定画面に状態と要求ボタン。

#### UI

- 設定 → 「サイト通知」:
  - 通知権限の状態（許可済み / 未許可 / OSでブロック）と「通知を許可」。
  - 全体スイッチ、間隔（15/30/60/180 分）、Wi-Fi のみ。
  - 監視中ブックマーク一覧: アイコン、名前、ON/OFF、最終確認、最終エラー、「今すぐ確認」。
  - ブックマークが 0 件なら説明。
- ブックマーク長押しメニュー: 「通知をオン/オフ」（現在の状態を表示）。
- 初回 ON 時に通知権限を要求（API 33+）。拒否された場合は設定に誘導。

---

## 4. 受入条件（抜粋）

| ID | 条件 | 検証 |
|---|---|---|
| F-01 | `<input type=file>` で SAF が開き、選択したファイルがページに渡る | 実機/エミュレータで画像選択→JS の `files.length` を表示 |
| F-02 | 画像 accept 時にカメラ撮影を選べる | ダイアログ→カメラ→撮影→ページに渡る |
| F-03 | カメラ/マイクはページ origin 付きの確認後にのみ許可 | 許可/拒否の両方、`getUserMedia` の成否 |
| F-04 | 位置情報は確認後にのみ許可、拒否で失敗コールバック | 許可/拒否 |
| F-05 | 動画の全画面が開き、戻るで解除 | 実機で全画面ボタン |
| F-06 | 既存の JS ダイアログ・ダウンロード・mailto/tel・target=_blank が回帰しない | 既存テストページ |
| N-01 | 監視 ON のブックマークが 15 分以上で定期チェックされる | WorkManager の実行履歴、lastCheckedAt |
| N-02 | ページ内容が変わると通知が出る。変化がなければ出ない | テストサーバーの内容変更 |
| N-03 | アプリを終了・再起動しても監視が継続する | アプリ kill 後の lastCheckedAt |
| N-04 | 通知タップで該当ブックマークが開く | 通知タップ |
| N-05 | 自己署名 + Basic 認証のサイトを監視できる | QA サーバー |
| N-06 | 通知権限がない場合に案内が出る | API 33+ で拒否 |
| N-07 | 監視 0 件/全体 OFF で Worker が動かない | WorkManager 状態 |
| N-08 | 追加依存は WorkManager のみ。APK の増加が妥当 | APK サイズ比較 |

---

## 5. リスク

| リスク | 対策 |
|---|---|
| サイト取得が Cookie/JS 前提で変化を検出できない | 制約を UI/README に明記。初回ベースラインと lastError 表示 |
| 自己署名の信頼を誤用して任意証明書を受け入れる | host+port+ピン一致のみ許可。CA 検証を先に試す |
| WorkManager の 15 分制約を誤解させる | 設定に「最短 15 分・OS が遅延する場合あり」を表示 |
| バッテリー消費 | ネットワーク/電池制約、直列取得、512 KiB 上限、タイムアウト |
| 通知の連投 | 30 分クールダウン、同一サイトは通知 ID 上書き |
| ファイル選択の callback 取りこぼし | 単一 pending + 画面破棄時に null 返却 |
| 全画面 View のリーク | Dialog dispose で removeView + onCustomViewHidden |

---

## 6. 作業分解

1. 計画・ADR 確定（本文書、ADR-0011、ADR-0012）。
2. 依存追加（WorkManager）、Manifest（権限・FileProvider）、リソース（文字列、file_paths）。
3. ブラウザ補完（file chooser / camera / permissions / geolocation / fullscreen）。
4. 通知基盤（Codecs、SiteWatchRepository、SiteFetcher、SiteWatchWorker、WatchScheduler、Notifier）。
5. UI（設定セクション、ブックマークメニュー、権限要求）。
6. テスト（純粋ロジック: 指紋、差分判定、スケジューラ設定、Basic realm 解析）。
7. 実機/エミュレータ検証、設計評価、v1.1.0 リリース、Web/保守文書更新。
