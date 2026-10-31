# Half-Browser 実装計画書（PLAN）

- 文書ID: HB-PLAN-001
- 版: 1.0（2026-10-31）
- 作成: TO-PLAN（HB-PLAN-01）
- 前提文書: [TECH-RESEARCH.md](TECH-RESEARCH.md) / [ADR/](ADR/) / [ACCEPTANCE_MATRIX.md](ACCEPTANCE_MATRIX.md)
- 対象リポジトリ: `oriyu90/half-browser`（ローカル: `~/places/適当/work/half-browser/`）
- 状態: **計画確定（実装フェーズ進行中）**

> **重要（2026-10-31 追記）**: 本フェーズ中に `app/src/` へ実装が追加された（`ui/` `data/` `shortcuts/` ほか、`src/test` 6 ファイル）。本書は **TO-BE の設計**であり、現物との差分は [IMPLEMENTATION-GAP.md](IMPLEMENTATION-GAP.md) に **G-01〜G-16** として記録した。**HIGH 以上 8 件が未修正**であり、G2 ゲートは `REWORK` 判定。

---

## 1. 目的

Android 向けの「半分だけブラウザ」を実用にする。具体的には、**普通のブラウザとして使えること**と、**自宅 LAN の自己署名 HTTPS + Basic/Digest 認証という「ブラウザが止める場面」を、ユーザーが理解したうえで通せること**を両立させる。

達成基準は [ACCEPTANCE_MATRIX.md](ACCEPTANCE_MATRIX.md) の AC-01〜AC-51（接尾辞付き AC-02b/12b/12c/29b/41b を含め**計 56 件**）。本書は「どう作るか」を、実装者が迷わない粒度で固定する。

### 1.1 設計原則（全判断の上位規範）

| # | 原則 | 意味 |
|---|---|---|
| P1 | **黙って安全側に倒さない。ただし黙って危険側にも倒さない** | 自己署名を通すが、必ずユーザーに材料を見せて選ばせる |
| P2 | **純粋ロジックは Android から隔離する** | `core/` は JVM で完全テスト可能に保つ（既存方針の踏襲） |
| P3 | **失敗を隠さない** | 例外は捕捉して UI 文言に写像。成功を断定しない（ショートカット等） |
| P4 | **依存を増やさない** | 新規ライブラリは「テスト用の無料 OSS」に限定。UI は既存 Compose で組む |
| P5 | **文字列は最初から resources** | 後から剥がす作業は必ず漏れる |
| P6 | **状態は1箇所に集約** | ViewModel + Repository の `StateFlow`。Composable は表示のみ |

---

## 2. スコープ / 非スコープ

### 2.1 スコープ（v1.0）

| # | 機能 | 対応要件 |
|---|---|---|
| S1 | URL 入力・検索・戻る/進む・リロード・共有・Cookie/JS/DOM ストレージ | 1 |
| S2 | 自己署名 HTTPS のインタースティシャル + TOFU ピン + 一覧/失効 | 2 |
| S3 | Basic/Digest 認証ダイアログ + 暗号化保存 + 自動入力 + ループ防止 + 一覧/削除 | 3 |
| S4 | ダーク/ライト/システム + 動的カラー ON/OFF | 4 |
| S5 | ホーム（ブックマークグリッド、モノグラム、編集、長押しメニュー） | 5 |
| S6 | 外部 URL 受け取り（VIEW / halfbrowser:// / SEND）+ ブックマークモード + 既定ブラウザ導線 | 6 |
| S7 | ホーム画面ショートカット（ピン留め + 動的） | 7 |
| S8 | 適応レイアウト + UI/文字スケール + グリッド列数 + edge-to-edge | 8 |
| S9 | 上部バー（ブックマークモード / 通常モード、3点メニュー 9 項目） | 9 |
| S10 | クラッシュ安全性・メモリ安全性・セキュリティ設定 | 10 |
| S11 | JVM テスト・lint 0・署名検証・エミュレータ QA | 11 |

### 2.2 非スコープ（v1.0 で作らない）

| # | 非スコープ | 理由 | 将来 |
|---|---|---|---|
| N1 | タブ / 複数ウィンドウ | 要件に無い。`supportMultipleWindows=false` で `target=_blank` は同一 WebView | v1.1 |
| N2 | 履歴の永続化・履歴画面 | 要件に無い（戻る/進むは WebView 内で足りる） | v1.2 |
| N3 | ダウンロードマネージャ | 要件に無い。`DownloadListener` で外部アプリへ委譲するのみ | v1.1 |
| N4 | favicon 取得・キャッシュ | プライバシーとオフライン性（ADR-0004） | オプトインで検討 |
| N5 | 広告ブロック / 拡張 | 要件に無い | 未定 |
| N6 | PDF 表示 | WebView の制約。外部アプリへ | 未定 |
| N7 | クライアント証明書（mTLS） | 要件に無い | 未定 |
| N8 | 同期・アカウント | 要件に無い。**通信はユーザーが開いたページのみ** | 未定 |
| N9 | クラッシュレポータ / 解析 SDK | プライバシーポリシー「収集しない」を守る | 無期限 |
| N10 | Play 配信 | ADR-0010（審査リスクと要件） | 未定 |
| N11 | 折りたたみヒンジ API | 幅ベースで実用上足りる（ADR-0007） | v1.1 |
| N12 | 設定の 2 ペイン化 | 情報量が足りない | 項目増加時 |

---

## 3. アーキテクチャ

### 3.1 全体像

```
MainActivity (BaseActivity: locale, edge-to-edge)
  └─ HalfBrowserApp (Compose)
       ├─ HalfBrowserTheme (themeMode, dynamicColor)
       ├─ Box(windowInsetsPadding(safeDrawing))        ← 実密度でインセット適用
       │    └─ ScaledTheme (LocalDensity = density*ui, fontScale*fs)
       │         └─ NavHost
       │              ├─ "home"      HomeScreen      ← HomeViewModel
       │              ├─ "browser"   BrowserScreen   ← BrowserViewModel（WebView 保持）
       │              ├─ "settings"  SettingsScreen  ← SettingsViewModel
       │              ├─ "trusted"   TrustedSitesScreen
       │              └─ "credentials" CredentialsScreen
       └─ (overlay) SslInterstitialDialog / HttpAuthDialog  ← ScaledTheme で明示的に包む
```

- **単一 Activity**。WebView は `BrowserViewModel` が保持し、`AndroidView` の factory で attach する。回転は manifest の `configChanges` で再生成されない（既存設定）。
- ナビゲーションは `navigation-compose`（既存依存）。ただし **URL はナビ引数で渡さない**（エンコード事故と長さ制限を避ける）。`BrowserViewModel.open(OpenRequest)` → `navigate("browser")` の順。
- ダイアログは Compose の `Dialog`。**`ScaledTheme` で包む**（`LocalDensity` の伝播を前提にしない。TECH-RESEARCH §7.2 S4）。

### 3.2 パッケージ構成と責務

```
com.yukiorita.halfbrowser
├── HalfBrowserApplication.kt        Application。何もしない（DI 無し）
├── BaseActivity.kt                  attachBaseContext で locale 適用（API 26–32）
├── MainActivity.kt                  setContent / IntentRouter / ViewModel 生成
│
├── core/                            ★ Android 非依存（JVM テスト対象）
│   ├── Models.kt                    Bookmark / TrustedSite / AppSettings / CredentialMeta / NavigationRequest（既存）
│   ├── TrustPolicy.kt               3値判定・指紋正規化（既存）
│   ├── UrlNormalizer.kt             入力→URL、canonicalForCompare（既存）
│   ├── Codecs.kt                    JSON encode/decode + sanitize（既存）
│   ├── BookmarkIcons.kt             色・グリフ・shortcutId（既存、書記素対応を追加）
│   ├── DeepLinks.kt                 halfbrowser:// パース（既存）
│   ├── GridPolicy.kt                [新] 列数算出（純粋）
│   ├── WidthBucket.kt               [新] 600/840/1200 の区分（純粋）
│   ├── AuthKey.kt                   [新] scheme+host+port+realm の正規化（純粋）
│   ├── AuthAttemptTracker.kt        [新] 自動入力の抑止状態（純粋・時系列）
│   ├── OpenRequest.kt               [新] ブラウザに渡す要求（url/bookmarkId/fromExternalApp）
│   ├── IntentRouter.kt              [新] Intent 相当の入力→NavigationRequest（純粋）
│   └── ScalePolicy.kt               [新] uiScale/fontScale の合成とクランプ（純粋）
│
├── data/                            ★ Android 依存（薄く保つ）
│   ├── Prefs.kt                     ファイル名・キー名の定数
│   ├── SettingsStore.kt             AppSettings の読み書き + StateFlow
│   ├── BookmarkRepository.kt        ブックマーク CRUD + StateFlow + .bak 退避
│   ├── TrustedSiteRepository.kt     ピン CRUD + 上限50
│   ├── LocaleStore.kt               言語設定の軽量読み出し（attachBaseContext 用）
│   ├── CredentialVault.kt           interface（list/secretFor/save/delete/deleteAll）
│   └── EncryptedPrefsCredentialVault.kt  実装（唯一 @Suppress("DEPRECATION")）
│
├── browser/
│   ├── BrowserViewModel.kt          WebView 保持・URL 状態・メニュー操作・再生成
│   ├── WebViewHost.kt               MutableContextWrapper で WebView を生成/attach
│   ├── HalfBrowserWebViewClient.kt  SSL/認証/エラー/レンダラ/URL 制御
│   ├── HalfBrowserWebChromeClient.kt title/progress/console（debug のみログ）
│   ├── SslTrustCoordinator.kt       PendingSslDecision の単一保留・タイムアウト・判定
│   └── HttpAuthCoordinator.kt       PendingAuthRequest の単一保留・自動入力・抑止
│
├── shortcut/
│   ├── ShortcutPublisher.kt         ピン留め/動的同期/無効化
│   ├── ShortcutPinReceiver.kt       ピン成功コールバック（exported=false）
│   └── MonogramBitmap.kt            Bitmap 生成（android.graphics のみ）
│
└── ui/
    ├── theme/                       Color.kt / Type.kt / Theme.kt / ScaledTheme.kt
    ├── layout/                      LocalWidthBucket.kt / Insets.kt / UnscaledMinTouchTarget.kt
    ├── nav/                         HalfBrowserNavHost.kt / Routes.kt
    ├── home/                        HomeScreen.kt / BookmarkTile.kt / BookmarkEditDialog.kt / HomeViewModel.kt
    ├── browser/                     BrowserScreen.kt / BrowserToolbar.kt / BrowserMenu.kt
    ├── ssl/                         SslInterstitialDialog.kt
    ├── auth/                        HttpAuthDialog.kt
    ├── settings/                    SettingsScreen.kt / TrustedSitesScreen.kt / CredentialsScreen.kt / SettingsViewModel.kt
    └── common/                      ConfirmDialog.kt / EmptyState.kt / SectionHeader.kt / ErrorText.kt
```

### 3.3 依存の向き（厳守）

```
ui ──→ browser ──→ data ──→ core
ui ──→ data ──→ core
core は何にも依存しない（Android / Compose / data を import しない）
```

- `core/` に `android.*` が入った時点で設計違反。CI で `grep -rn "^import android" app/src/main/java/com/yukiorita/halfbrowser/core/` が 0 件であることを確認する（AC-02b）。
- `data/` は `Context` を受けるが、ViewModel を参照しない。

---

## 4. 画面遷移

### 4.1 ルート

| route | 画面 | 主な遷移元 | 主な遷移先 |
|---|---|---|---|
| `home` | ホーム（グリッド + アドレスバー） | 起動、ツールバー左、エラー | `browser`, `settings`, 長押しメニュー |
| `browser` | ブラウザ（WebView + ツールバー） | ホーム、外部 Intent、ショートカット | `home`, `settings`（戻るで復帰） |
| `settings` | 設定（セクション一覧） | ツールバーメニュー、ホームの歯車 | `trusted`, `credentials` |
| `trusted` | 信頼済みサイト一覧 | 設定 | 確認ダイアログ（失効） |
| `credentials` | 保存済み認証情報一覧 | 設定 | 確認ダイアログ（削除） |

### 4.2 遷移図

```
[起動]
  ├─ ACTION_MAIN/LAUNCHER ───────────────→ home
  ├─ ACTION_VIEW http(s) ──┬─ ブックマーク一致 ─→ browser(bookmarkMode=true)
  │                        └─ 不一致 ───────────→ browser(bookmarkMode=false)
  ├─ ACTION_VIEW halfbrowser:// ── DeepLinks 解釈 ─→ home / browser / bookmark
  ├─ ACTION_SEND text/plain ── URL抽出 or 検索 ───→ browser
  └─ ショートカット ── bookmarkId 存在? ─┬─ 有 ─→ browser(bookmarkMode=true)
                                          └─ 無 ─→ home + Snackbar

home ── タイルtap ──────────────→ browser(bookmarkMode=true)
home ── アドレスバー確定 ────────→ browser
home ── タイル長押し ──┬─ 開く ─────→ browser
                       ├─ 編集 ─────→ BookmarkEditDialog（画面遷移なし）
                       ├─ 削除 ─────→ ConfirmDialog
                       └─ ホーム画面に追加 → ShortcutPublisher.pin()
home ── 歯車 ─────────────────→ settings

browser ── ツールバー左（ホーム） ─→ home
browser ── メニュー → 設定 ───────→ settings
browser ── SSL エラー ───────────→ SslInterstitialDialog（重ならない）
browser ── 401 ──────────────────→ HttpAuthDialog
```

### 4.3 「ブックマークモード」の定義

- 条件: `OpenRequest.bookmarkId != null` かつ現在 URL の `canonicalForCompare` がそのブックマークの URL と一致。
- ツールバー: 左=ホーム、中央=**ブックマーク名**、右=3点。
- ページ内リンクで URL が変わった瞬間に通常モードへ（`onPageStarted` で評価）。
- 通常モード: 左=戻る（履歴が無ければホーム）、中央=ページタイトル（空ならホスト）、右=3点。

---

## 5. 主要データモデル

既存の `core/Models.kt` を**壊さず拡張**する。フィールド追加は既定値付き（`ignoreUnknownKeys=true` で前方互換）。

### 5.1 Bookmark

```kotlin
@Serializable
data class Bookmark(
    val id: String,                 // UUID
    val title: String,
    val url: String,                // validateWebUrl 済み
    val glyph: String = "",         // 1-2 書記素。空 = 自動導出
    val colorSeed: Int = -1,        // -1 = URL ハッシュ、0..9 = パレット指定
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastOpenedAt: Long = 0L,    // [追加] 動的ショートカットの並び替え用
)
```

- 不変条件: `id` 空でない / `url` が http(s) として妥当 / `glyph` は 0〜2 書記素。
- 一意性: `canonicalForCompare(url)` が重複するなら新規作成しない（ADR-0004）。

### 5.2 AppSettings

```kotlin
@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,      // SYSTEM / LIGHT / DARK
    val dynamicColor: Boolean = false,
    val uiScale: Float = 1.0f,                        // 0.8..1.4
    val fontScale: Float = 1.0f,                      // 0.8..1.5
    val gridColumns: Int = 0,                         // 0=自動, 3/4/5/6/8
    val language: LanguageMode = LanguageMode.SYSTEM, // SYSTEM / JAPANESE / ENGLISH
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val openExternalAtHome: Boolean = false,
    val showAddressBar: Boolean = true,
    val defaultBrowserPromptShown: Boolean = false,   // [追加] 初回案内を1回だけ
    val thirdPartyCookies: Boolean = false,           // [追加] 既定 OFF
    val safeBrowsing: Boolean = true,                 // [追加] 既定 ON
)
```

- `Codecs.sanitize` を拡張: `uiScale` / `fontScale` のクランプ、`gridColumns` の許可集合、enum の妥当性。新規 Boolean は既定値へフォールバック。
- 保存は1つの JSON blob（原子的な単一書き込み）。

### 5.3 TrustedSite

```kotlin
@Serializable
data class TrustedSite(
    val host: String,                 // 小文字・ポート無し
    val fingerprintSha256: String,    // 証明書 DER の SHA-256（小文字 hex）
    val subject: String = "",
    val issuer: String = "",
    val addedAt: Long = 0L,
    val lastSeenAt: Long = 0L,
)
```

- 一意性: `host` で一意（`Codecs.decodeTrustedSites` の `distinctBy` を維持）。証明書更新時は**置換**（`Changed` 承認時）。
- 上限: 50 件（超過時は追加拒否。ADR-0002）。

### 5.4 CredentialMeta（＋ 秘密は別型）

```kotlin
data class CredentialMeta(          // 既存。秘密を含まない
    val id: String,                 // AuthKey のハッシュ先頭
    val host: String,
    val realm: String,
    val username: String,           // 表示用（暗号化ストアから復号して渡す）
)

data class AuthKey(val scheme: String, val host: String, val port: Int, val realm: String)

data class StoredSecret(val username: String, val password: String)  // [新] ログ禁止
```

- `CredentialMeta` の `username` は**画面表示のためだけ**に存在し、永続化時は暗号化ストア内（ADR-0003）。
- `StoredSecret` は `toString()` を `"StoredSecret(***)"` に上書きする（事故防止）。

### 5.5 OpenRequest

```kotlin
data class OpenRequest(
    val url: String,
    val bookmarkId: String? = null,
    val fromExternalApp: Boolean = false,
    val requestedAt: Long = 0L,
)
```

- `NavigationRequest`（Intent 由来の抽象）と `OpenRequest`（ブラウザの実状態）を分ける。`IntentRouter` が前者を返し、`BrowserViewModel` が後者に変換して保持する。
- 同一 URL の再要求は無視（`canonicalForCompare`）。

### 5.6 永続化キー一覧（`data/Prefs.kt`）

| ファイル | キー | 内容 | バックアップ |
|---|---|---|---|
| `halfbrowser_prefs` | `settings` | AppSettings JSON | 対象 |
| `halfbrowser_prefs` | `bookmarks` | BookmarkList JSON | 対象 |
| `halfbrowser_prefs` | `bookmarks.bak` | 直前の正常値 | 対象（実装は任意） |
| `halfbrowser_prefs` | `trusted_sites` | TrustedSiteList JSON | 対象 |
| `halfbrowser_secure` | `credential_<id>` | `{"u":...,"p":...}`（暗号化） | **除外**（既存 xml 設定） |

---

## 6. 状態遷移

### 6.1 SSL インタースティシャル（`SslTrustCoordinator`）

```
[Idle]
  │ onReceivedSslError(handler, error)
  ▼
[Inspecting]  ── X509 取得失敗 ──────────────→ [UnknownNoFingerprint]
  │                                              │ 「常に信頼」無効
  │ ピン照合（TrustPolicy.decide）               │
  ├── Trusted ──────────────────────────────→ [AutoProceed] → handler.proceed() → [Idle]
  ├── Unknown ──→ [PendingDialog]（タイマー60s）
  └── Changed ──→ [PendingDialogChanged]（タイマー60s）

[PendingDialog*]
  ├─ 「今回だけ」 ─→ handler.proceed()（保存しない） → [Idle]
  ├─ 「常に信頼」 ─→ ピン保存（上限/期限/IDMISMATCH を再確認）→ handler.proceed() → [Idle]
  ├─ 「戻る」 ─────→ handler.cancel() → stopLoading() → 履歴あれば goBack() → [Idle]
  ├─ タイムアウト ─→ handler.cancel() → [Idle]
  └─ 画面破棄 ─────→ handler.cancel() → [Idle]
```

- **保留単位は `(host, fingerprint)` のキュー**（上限 16）。同一キーのハンドラは積み、ダイアログは1つだけ表示して、決定時に**同一キューを一括 proceed / cancel** する（サブリソースでページが欠けるのを防ぐ。G-03）。別証明書のハンドラは即 `cancel()`。
- 承認後、メインフレームが https なら `reload()` する（POST 中なら「再読み込みしてください」を表示）。
- ハンドラは `AtomicBoolean consumed` で二重呼び出しを防ぐ。
- `[AutoProceed]` でもピンの `lastSeenAt` を更新する（IO、ベストエフォート）。

### 6.2 HTTP 認証（`HttpAuthCoordinator`）

```
[Idle]
  │ onReceivedHttpAuthRequest(handler, host, realm)
  ▼
[Resolving] key = AuthKey(scheme(現在ページ), host, port(URL), realm)
  ├─ vault に秘密なし ───────────────────────→ [Prompt]（理由: 未保存）
  ├─ tracker.autoFillAllowed(key) == false ──→ [Prompt]（理由: 連続失敗）
  ├─ scheme != https ───────────────────────→ [Prompt]（理由: 平文接続。自動入力しない）
  ├─ メインフレームのホスト != host ─────────→ [Prompt]（理由: 別ホスト）
  └─ それ以外 ──→ handler.proceed(u,p) → tracker.onAutoProceed(key) → [AwaitResult]

[AwaitResult]
  ├─ ページロード成功（onPageFinished, 認証後） → tracker.onSuccess(host) → [Idle]
  └─ 同一 key で再要求 ─→ tracker.onFailure(key)
        ├─ failures < 2 → [Prompt]（ユーザー名プリフィル、パスワード空）
        └─ failures >= 2 → [Suppressed]（セッション中、自動入力しない）

[Prompt]
  ├─ 送信 ─→ handler.proceed(u,p)（save チェック時は vault.save）→ [AwaitResult]
  ├─ キャンセル ─→ handler.cancel() → [Idle]（ページは 401 エラー表示）
  └─ タイムアウト 120s ─→ handler.cancel() → [Idle]
```

- `[Prompt]` 中の再要求は**新しい方を `cancel()`**（保留1件の原則）。
- `proceed()` は **Main ディスパッチャ**でのみ呼ぶ。
- `useHttpAuthUsernamePassword()` は**ゲートにしない**（自前保存では戻り値の意味が不定。ADR-0003）。
- 失敗カウンタは「同一ナビゲーション epoch 内の同一キー再要求」でのみ加算し、ロード成功・新規ナビゲーション・手動ログイン成功でリセットする。

### 6.3 ページ／WebView のライフサイクル

```
[NoWebView] → [Creating] → [Loading(url)] → [Loaded(title, canGoBack, canGoForward)]
                    │                ├─ onReceivedError(主フレーム) → [ErrorPage]
                    │                └─ onRenderProcessGone → [RendererGone]
                    └─ onPageFinished
[RendererGone] → destroy() → [Creating]（同じ URL を再ロード）
[NoWebView] ← ViewModel.onCleared()（handler cancel → destroy）
```

- `onRenderProcessGone` は **true を返す**（false だとアプリが落ちる。TECH-RESEARCH §6.2）。
- プロセス死からの復元は `SavedStateHandle` の URL で行う（履歴は失われる。許容）。

---

## 7. セキュリティ設計

### 7.1 脅威と統制

| # | 脅威 | 統制 | 検証 |
|---|---|---|---|
| T1 | 悪意あるページが `javascript:` / `file:` を踏ませる | `UrlNormalizer` でスキーム制限、`shouldOverrideUrlLoading` で再検証、`allowFileAccess=false` | AC-02, AC-37 |
| T2 | 中間者による自己署名証明書のすり替え | TOFU ピン（host + DER SHA-256）。`Changed` で必ず再警告 | AC-15 |
| T3 | ユーザーが「常に信頼」を乱用 | 既定ボタンは「戻る」、危険色、期限切れ等はピン不可、一覧・失効 | AC-16 |
| T4 | 認証情報の平文保存 | Keystore + EncryptedSharedPreferences。`halfbrowser_secure.xml` をバックアップ除外 | AC-23 |
| T5 | 自動入力の無限ループ / アカウントロック | `AuthAttemptTracker`（同一 epoch・同一キーで2回失敗したら自動入力を抑止。ダイアログは常に出す） | AC-24 |
| T6 | 別ホストへの資格情報漏出 | `AuthKey` に scheme+host+port+realm、メインフレーム host 一致要求 | AC-22 |
| T7 | 秘密のログ漏出 | `StoredSecret.toString()` マスク、ログ規約、grep 検査 | AC-27 |
| T8 | `WebView` の危険設定 | `allowFileAccess/ContentAccess=false`、`mixedContent=COMPATIBILITY_MODE`（Chrome 相当。機密性は「資格情報を http に送らない」で担保）、JS ブリッジ無し、`WebContentsDebugging` は debug のみ | AC-37, AC-48, AC-43 |
| T9 | Intent 経由の細工 URL | `IntentRouter` で検証、資格情報入り URL 拒否、長さ上限、`halfbrowser://` は非 BROWSABLE + ルータでも拒否 | AC-29, AC-30 |
| T10 | バックアップからの秘密抽出 | `data_extraction_rules.xml` / `backup_rules.xml` で `halfbrowser_secure.xml` を除外 | AC-23 |
| T11 | レンダラクラッシュによるアプリ停止 | `onRenderProcessGone` → true + 再生成 | AC-38 |
| T12 | WebView のメモリリーク（Activity 参照） | `MutableContextWrapper` + `onCleared` で `destroy()` | AC-39 |

### 7.2 明示的に「やらない」こと

- JS ブリッジ（`addJavascriptInterface`）を**追加しない**。
- `setAllowUniversalAccessFromFileURLs(true)` を**使わない**。
- 認証情報をクリップボードへ自動コピーしない。
- 証明書例外を「設定で全サイト一括許可」にしない。
- 独自 HTTP スタック（OkHttp 等）を追加しない（WebView の通信を迂回しない）。

### 7.3 既知の受容リスク

1. 初回接続時の MITM（TOFU の限界）— ADR-0002 R1
2. 信頼後の MITM — ADR-0002 R2
3. `security-crypto` の非推奨化 — ADR-0003 R1
4. ピン留めショートカットをアプリから削除できない — ADR-0006 R2
5. ヒンジ跨ぎのレイアウト — ADR-0007 R4
6. WebView の SSL 判断保持の可能性（未検証）— ADR-0002 / AC-12b

---

## 8. 多言語

- 既定 `values/` = 英語、`values-ja/` = 日本語。**キー集合・書式指定子・plurals を完全一致**（`StringParityTest`）。
- 接頭辞: `ssl_` / `auth_` / `home_` / `bookmark_` / `browser_` / `settings_` / `menu_` / `error_` / `common_`。
- 想定キー数: 160〜200。1画面あたり 15〜25。
- 言語切替: API 33+ は `LocaleManager`、26–32 は `attachBaseContext` + `recreate()`（ADR-0008）。
- ハードコード検出: 日本語文字を含む `Text("...")` を JVM テストで検出。
- 日付・数値は `Locale` 明示。フィンガープリントは `Locale.ROOT` で hex 化。

### 8.1 主要な文言（抜粋・実装時の正）

| key | 英語 | 日本語 |
|---|---|---|
| `ssl_title` | Certificate problem | 証明書に問題があります |
| `ssl_body` | This site's certificate could not be verified. Only continue if you trust this server. | このサイトの証明書を検証できませんでした。信頼できるサーバーである場合のみ続行してください。 |
| `ssl_fingerprint` | SHA-256 fingerprint | SHA-256 フィンガープリント |
| `ssl_trust_once` | Continue once | 今回だけ続行 |
| `ssl_trust_always` | Always trust this site | このサイトを常に信頼 |
| `ssl_back` | Go back | 戻る |
| `ssl_trust_always_warning` | This permission is stored on this device and is not a substitute for a valid certificate. | この許可は端末内に保存されます。正規の証明書の代わりにはなりません。 |
| `auth_title` | Sign in | 認証が必要です |
| `auth_save` | Save for this site | このサイト用に保存する |
| `auth_insecure_warning` | This connection is not encrypted. Your password may be visible on the network. | この接続は暗号化されていません。パスワードが第三者に読まれる可能性があります。 |
| `auth_auto_suppressed` | Automatic sign-in is paused after repeated failures. | 連続して失敗したため、自動入力を停止しました。 |
| `menu_share` | Share | 共有 |
| `menu_copy_url` | Copy URL | URL をコピー |
| `menu_add_bookmark` | Add bookmark | ブックマークに追加 |
| `menu_edit_bookmark` | Edit bookmark | ブックマークを編集 |
| `menu_reload` | Reload | 再読み込み |
| `menu_desktop_site` | Request desktop site | PC 版サイトを表示 |
| `menu_open_external` | Open in another app | 外部ブラウザで開く |
| `menu_settings` | Settings | 設定 |
| `menu_close` | Close | 閉じる |
| `settings_default_browser` | Default browser | 既定のブラウザ |
| `settings_role_unavailable` | This device doesn't allow apps to request the browser role. Set it in system settings. | この端末ではアプリから既定ブラウザを設定できません。端末の設定から選択してください。 |

---

## 9. 適応 UI

| 幅 | 区分 | グリッド | ツールバー | 余白 |
|---|---|---|---|---|
| <600dp | COMPACT | 自動 2〜4 列（狭幅では 2 列を許す） | アイコン + タイトル | 16dp |
| 600–839dp | MEDIUM | 自動 4〜5 列 | + ホスト表示 | 24dp |
| 840–1199dp | EXPANDED | 自動 5〜7 列、最大幅 1200dp 中央 | + フル URL | 32dp |
| ≥1200dp | EXTRA_LARGE | 自動 6〜8 列、最大幅 1200dp 中央 | 同上 | 32dp |

- 設定の列数（3/4/5/6/8）は自動より優先。狭すぎる場合は警告のみ（勝手に変えない）。
- スケール: `Density(density * uiScale, fontScale * fontScaleSetting)`。合成最大 2.1 倍。
- インセット: `LocalDensity` 差し替えの**外側**で `WindowInsets.safeDrawing` を適用。IME も外側。
- ダイアログは `ScaledTheme` で包む。
- タップ領域: 主要操作は `UnscaledMinTouchTarget`（48dp を実密度で確保）。

---

## 10. テスト計画（要約）

詳細は [ADR-0009](ADR/0009-test-qa-strategy.md)。要点のみ:

| 層 | 対象 | 実行 |
|---|---|---|
| L1 JVM | `UrlNormalizer` / `Codecs` / `TrustPolicy` / `BookmarkIcons` / `GridPolicy` / `WidthBucket` / `AuthKey` / `AuthAttemptTracker` / `IntentRouter` / `ScalePolicy` / 文字列パリティ | `./gradlew testDebugUnitTest`（CI） |
| L2 JVM | Repository（Fake ストア）、`SslTrustCoordinator` の判定（Fake handler）、`HttpAuthCoordinator` の時系列 | 同上 |
| L3 instrumented | `CredentialVault` 往復・暗号化確認、ショートカット対応判定、WebView スモーク | `connectedDebugAndroidTest`（ローカル） |
| L4 手動 | SSL インタースティシャル、認証、ショートカット、適応、言語、署名 APK | `docs/QA-CHECKLIST.md` |

- テスト用 HTTPS サーバー: `tools/qa-server.py`（Python 標準ライブラリ、Basic/Digest、証明書切替、常時401）。
- CI: `testDebugUnitTest` + `lintDebug` のみ。エミュレータは回さない。
- ゲート: 単体全件成功 / **lint 0 errors** / `assembleRelease` 成功 / `apksigner verify` 成功。

---

## 11. リリース手順（要約）

詳細は [ADR-0010](ADR/0010-release-signing-distribution.md) と `docs/RELEASE.md`（実装フェーズで作成）。

```
1. versionName / versionCode 更新（versionCode = major*10000 + minor*100 + patch）
2. ./gradlew clean testDebugUnitTest lintDebug
3. STORE_PASSWORD / KEY_PASSWORD を export（KEYSTORE_PATH は既定 keystore/release.jks）
4. ./gradlew assembleRelease
5. apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
6. aapt2 dump badging ... で versionCode / debuggable を確認
7. sha256sum → SHA256SUMS.txt
8. gh release create v1.0.0 app-release.apk SHA256SUMS.txt --notes-file docs/release-notes/1.0.0.md
```

- keystore とパスワードは `common-rules-document` に保管。リポジトリには**署名証明書の SHA-256 のみ**を記録。
- git commit / push / リリース実行は**メインが行う**（本フェーズでは実施しない）。

---

## 12. リスクと対策

| # | リスク | 影響 | 確率 | 対策 | 担当 |
|---|---|---|---|---|---|
| R1 | WebView が `proceed()` 判断を保持し、証明書変更を検知できない | **高**（セキュリティ） | 低〜中 | AC-12b で実測。Plan B（毎回 cancel→再ロード）を設計済み | TO-EXEC + レビュー |
| R2 | `security-crypto` の将来削除 | 中 | 中 | インターフェース隔離 + 1.1.0 固定 | TO-EXEC |
| R3 | `getX509Certificate` 分岐の実装ミス（26–28 で落ちる） | 高 | 中 | 分岐を1ファイルに閉じ込め、API 26 エミュレータで必須検証 | TO-EXEC |
| R4 | ショートカットの Bitmap がランチャーで表示されない | 中 | 低〜中 | リソースアイコン fallback を用意（未検証 U12） | TO-EXEC |
| R5 | 2.1 倍スケールでレイアウト崩れ | 中 | 高 | 早期に最大スケールでスクリーンショット確認。崩れる画面を設計し直す | TO-EXEC |
| R6 | `attachBaseContext` 方式が 26–32 で破綻 | 中 | 中 | API 26 エミュレータで検証。破綻時は AppCompat 導入の ADR を起票 | TO-EXEC |
| R7 | 認証ハンドラの失効・二重 proceed | 中 | 中 | `AtomicBoolean` + タイムアウト + `onCleared` cancel | TO-EXEC |
| R8 | 文字列パリティの漏れ | 低 | 高 | CI でパリティテストを必須化 | TO-EXEC |
| R9 | lint 0 エラーに到達しない | 低 | 中 | 早期に `lintDebug` を回し、warning も分類して潰す | TO-EXEC |
| R10 | R8 で WebView コールバックが壊れる | 高 | 低 | 既存 keep ルール維持 + release ビルドで L4 抜粋を実施 | TO-EXEC |
| R11 | スコープ膨張（タブ・履歴・DL） | 中 | 中 | 非スコープ表を維持。追加は ADR 起票後 | メイン |
| R12 | エミュレータの WebView が実機と異なる | 中 | 中 | 実機（開発者端末）でも1回実施 | TO-EXEC |

---

## 13. マイルストーン

| M | 内容 | 完了条件 | 目安 |
|---|---|---|---|
| M1 | 基盤: BaseActivity / テーマ / ナビ / `core` 追加関数 / 単体テスト雛形 / 文字列パリティ | `testDebugUnitTest` 緑、`core` に android import 0 | 0.5日 |
| M2 | ホーム + 設定（外観・言語・グリッド）+ ブックマーク CRUD + 編集ダイアログ | AC-19〜AC-21, AC-32, AC-33 | 1.5日 |
| M3 | WebView 基盤 + ツールバー + メニュー 9 項目 + エラー/レンダラ処理 | AC-01〜AC-09, AC-37〜AC-39 | 1.5日 |
| M4 | SSL: インタースティシャル + TOFU + 一覧/失効 + 26–28 分岐 | AC-10〜AC-18 | 2日 |
| M5 | 認証: ダイアログ + Vault + 自動入力 + ループ防止 + 一覧/削除 | AC-22〜AC-27 | 1.5日 |
| M6 | ショートカット + 外部 Intent + 既定ブラウザ導線 | AC-28〜AC-31 | 1.5日 |
| M7 | 適応 UI + スケール + インセット + エミュレータ QA | AC-34〜AC-36 | 1日 |
| M8 | lint 0 / release 署名 / README / PRIVACY / リリースノート | AC-40〜AC-45 | 0.5日 |

- 合計 **約10日**（1人・実装のみ。レビューと手戻りを含まず）。
- 各 M の終わりに `./gradlew testDebugUnitTest lintDebug` を回す（緑でなければ次へ進まない）。

---

## 14. ファイル別作業分担案（TO-EXEC 用）

| WP | 内容 | 主なファイル | 依存 | 対応AC | 推奨担当 |
|---|---|---|---|---|---|
| WP-01 | 基盤・テーマ・ナビ・スケール | `BaseActivity.kt`, `MainActivity.kt`, `ui/theme/*`, `ui/layout/*`, `ui/nav/*`, `core/ScalePolicy.kt` | なし | AC-33, AC-34 | Worker-UI-A |
| WP-02 | core 純粋ロジック追加 + 単体テスト | `core/GridPolicy.kt`, `core/WidthBucket.kt`, `core/AuthKey.kt`, `core/AuthAttemptTracker.kt`, `core/IntentRouter.kt`, `core/OpenRequest.kt`, `core/BookmarkIcons.kt`(書記素), `src/test/**` | なし | AC-02, AC-03, AC-19, AC-24, AC-29, AC-32, AC-34 | Worker-CORE |
| WP-03 | data 層 | `data/Prefs.kt`, `SettingsStore.kt`, `BookmarkRepository.kt`, `TrustedSiteRepository.kt`, `LocaleStore.kt`, `CredentialVault.kt`, `EncryptedPrefsCredentialVault.kt` | WP-02 | AC-03, AC-23, AC-26 | Worker-DATA |
| WP-04 | ホーム画面 | `ui/home/*` | WP-01, WP-03 | AC-19〜AC-21 | Worker-UI-A |
| WP-05 | 設定画面 3 種 | `ui/settings/*` | WP-01, WP-03 | AC-16, AC-27, AC-31, AC-33 | Worker-UI-B |
| WP-06 | WebView 基盤 + クライアント | `browser/WebViewHost.kt`, `HalfBrowserWebViewClient.kt`, `HalfBrowserWebChromeClient.kt`, `BrowserViewModel.kt` | WP-03 | AC-01, AC-05〜AC-09, AC-37〜AC-39 | Worker-BROWSER |
| WP-07 | SSL 信頼 | `browser/SslTrustCoordinator.kt`, `ui/ssl/SslInterstitialDialog.kt` | WP-06, WP-03 | AC-10〜AC-18 | Worker-BROWSER |
| WP-08 | HTTP 認証 | `browser/HttpAuthCoordinator.kt`, `ui/auth/HttpAuthDialog.kt` | WP-06, WP-03 | AC-22〜AC-27 | Worker-BROWSER |
| WP-09 | ショートカット | `shortcut/*` | WP-03 | AC-28 | Worker-PLATFORM |
| WP-10 | 外部 Intent / 既定ブラウザ | `IntentRouter.kt`(配線), `MainActivity.kt`, `ui/settings` の該当節 | WP-06, WP-02 | AC-29〜AC-31 | Worker-PLATFORM |
| WP-11 | QA サーバー + チェックリスト | `tools/qa-server.py`, `docs/QA-CHECKLIST.md` | なし | AC-12, AC-24, AC-25 | Worker-QA |
| WP-12 | リリース整備 | `README.md`, `LICENSE`, `docs/PRIVACY.md`, `docs/RELEASE.md`, `docs/release-notes/*`, `.gitignore` | 全部 | AC-41〜AC-45 | Worker-DOCS |

**編集所有（競合回避）**: WP-01/04 と WP-05 は `ui/` を分ける。`MainActivity.kt` は WP-01 が初期版を作り、WP-10 が `IntentRouter` 配線のために**同じターンで触らない**（WP-10 は WP-06 完了後）。`core/` は WP-02 が独占。`browser/` は WP-06→07→08 の順で直列（同一 Worker 推奨）。

**直列化の理由**: `browser/` は WebView のライフサイクルと状態機械が密結合しており、並列編集は競合とデグレを生む。1 人の Worker が 06→07→08 を順に担当するのが安全。

---

## 15. 実装フェーズへの引き継ぎ事項（TO-EXEC が最初に読む）

1. **既存コードを壊さない**。`core/` の 6 ファイルは「拡張」のみ（`Bookmark.lastOpenedAt` 追加、`glyphFor` の書記素対応、`Codecs.sanitize` の項目追加）。公開シグネチャの変更は避ける。
2. **`MainActivity` のハードコード文字列を最初に撤去**する（`Text("Half-Browser")`）。
3. `AndroidManifest.xml` への追加は `ShortcutPinReceiver`（`exported="false"`）のみ。既存 intent-filter は**変更しない**。
4. `app/build.gradle.kts` への追加は、テスト依存（coroutines-test / androidx.test）と `testInstrumentationRunner` のみ。`security-crypto` を `1.1.0` に更新する。
5. **AC-12b（proceed 保持の実測）を M4 の最初に行う**。結果次第で Plan B に切り替える判断が必要（メインへエスカレーション）。
6. 未検証事項（TECH-RESEARCH §11 の U1〜U14）は、実装時に「未検証」として記録し、勝手に「確認済み」としない。

---

## 16. 参考

- [TECH-RESEARCH.md](TECH-RESEARCH.md) — 一次資料・確信度・落とし穴
- [ADR/0001〜0010](ADR/) — 設計判断と代替案
- [ACCEPTANCE_MATRIX.md](ACCEPTANCE_MATRIX.md) — AC-01〜AC-51（計 56 件）の検証方法
- [IMPLEMENTATION-GAP.md](IMPLEMENTATION-GAP.md) — 現物（AS-IS）との差分 G-01〜G-16
- [TO_FEEDBACK.md](TO_FEEDBACK.md) — 本フェーズの報告・Worker 採否・未決事項
