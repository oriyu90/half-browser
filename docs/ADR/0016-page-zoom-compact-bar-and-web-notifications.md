# 0016 — ページズーム、コンパクトバー、サイト権限、Web 通知

- 日付: 2026-11-01
- 状態: 採用
- 対象: 1.5.0

## 背景

1.4.0 の時点で、アプリ内の UI スケールは Compose の `Density` だけを変えており、
WebView に渡していたのは `textZoom`（文字だけ）でした。ユーザーから
「UI スケールを変えたら Web ページの表示サイズにも反映してほしい」という要求があり、
あわせてブックマーク起動時の上部バーの圧縮、Shift+Enter の取りこぼし、Web 通知、
サイト権限の一括管理、設定のタブ化、ブックマーク切替の要望が上がりました。

## 決定

### 1. Web ページへの反映は「文字ズーム（折り返し）」に限定する

エミュレータ（API 34 / Android System WebView 113）で 4 方式を実測しました。

| 方式 | 実測結果 |
|---|---|
| `WebView.setInitialScale(140)` | `<meta name="viewport" content="width=device-width, initial-scale=1">` があるページでは**無効**。DSH もこの meta を持つ。 |
| `meta viewport` の `initial-scale` を JS で書き換え | 読み込み後の変更は**反映されない**（`visualViewport.scale` は 1.00 のまま）。 |
| `documentElement.style.zoom = 1.4` | 文字は折り返し、横スクロールも消える。しかし `100dvh` 系の固定レイアウトで**ページがスクロールしなくなる**（DSH で `scrollY` が 0 のまま、入力バーに到達不能）。 |
| `WebView.zoomBy()`（ページスケール） | `visualViewport.scale=1.4` になり拡大はするが、レイアウト幅が変わらないため横スクロールが必要。さらに文書遷移のたびにリセットされ、再適用のタイミングが端末・ページ依存で不安定だった。 |
| `WebSettings.textZoom`（文字ズーム） | 文字が拡大し、Chromium が**折り返し再レイアウト**する。`100vh` も固定要素もそのまま。DSH でスクロール・入力バーとも正常（実測）。 |

採用は文字ズーム。UI スケール × 文字スケールを `textZoom` に掛ける（50〜200% にクランプ）。
トレードオフは「画像やボタンは拡大されない」ことだが、レイアウトを壊さず横スクロールも出ない。
ページ全体を拡大する方式は、上表のとおり DSH を壊すか横スクロールを強いるため採用しない。

### 2. 上部バーはブックマーク起動時だけ 1 段 44dp

`NavigationRequest.Open.bookmarkId` が解決できたときだけコンパクト表示にします。
タイトルとドメインは 1 つの `Text` に `AnnotatedString` で並べ、ドメインだけ小さく
`onSurfaceVariant` にします。左はホームと再読み込み、右はブックマーク切替（☰）と ⋮。
外部リンクから開いた場合は従来の 2 段バーを維持します（ドメインを読み取りやすくするため）。

### 3. Web 通知は JS ポリフィル + `WebMessageListener`

Android WebView の `PermissionRequest.RESOURCE_*` は AUDIO / VIDEO / MIDI / PROTECTED_MEDIA の
4 種類だけで、通知リソースは存在しません（`android.jar` のスタブで確認）。したがって
ネイティブの通知許可フローは無く、`new Notification()` は参照エラーになります。

`addDocumentStartJavaScript` で spec に沿った `Notification`（`permission` getter、
`requestPermission()`、コンストラクタ、`close()`）を注入し、`WebMessageListener`
（`addJavascriptInterface` ではない）でアプリへ転送します。受理条件は「メインフレーム」
「http(s) の origin」「その origin に通知の許可がある」の 3 つで、ページが自分の許可を
決めることはできません。`ServiceWorkerRegistration.showNotification` も同じ経路に接続します。

バックグラウンド配信は Google の push 基盤を使えないため、**ページを生かす**方向で解きます。
設定「Web通知を即時に受け取る」が ON のときだけ `KeepAliveService`（`specialUse` の
フォアグラウンドサービス、常駐通知あり）を起動し、`onStop` の `pauseTimers()` を抑止します。
プロセス終了後の配信は不可能であることを設定画面と README に明記します。

### 4. サイト権限は origin 単位の単一ストア

`SitePermissionRepository` が `scheme://host[:port]` をキーに `notifications` / `camera` /
`microphone` / `location` の可否を JSON で保存します。行が無い＝毎回確認（既定）。
`onPermissionRequest` と `onGeolocationPermissionsShowPrompt` は保存済みの判断を先に引き、
未決定のときだけダイアログを出します。`WebPermissionRequest` に `kinds` を持たせ、
ダイアログの回答をそのまま保存します。設定の「サイトの権限」タブから 1 項目ずつ反転、
origin 単位のリセット、全消去ができます。

### 5. 設定は 7 タブ

表示 / 閲覧 / サイトの権限 / 通知 / セキュリティ / バックアップ / このアプリ。
`ScrollableTabRow` + `SettingsTab` enum で、各タブは既存の `SettingsCard` の集合です。
タブ選択は `rememberSaveable` で保持します。

### 6. Shift+Enter は「アプリ側の欠陥なし」を実測で確認し、ガードだけ入れる

`MainActivity` にキー横取りは無く、WebView にもキーリスナーはありません（コード監査）。
`adb shell input keycombination 59 66`（Shift + Enter）で textarea に `keydown` が
`shiftKey: true` で届き、改行が挿入されることを実測しました。残る取りこぼし経路は IME が
修飾キーを落とす場合なので、Shift の押下を自前で追跡し、`Enter` に修飾が無いときだけ
そのイベントインスタンスの `shiftKey` を `true` に差し替える小さなガードを注入します
（キャプチャ段階・document start なのでページのハンドラより先に動く）。入力や送信は
一切変更しません。

## 影響

- `AppSettings` に `backgroundWebNotifications`（既定 false）を追加。既存データはそのまま読めます。
- `AndroidManifest.xml` に `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` と
  `KeepAliveService` を追加。
- 文字列は日英両方を追加（`values/strings.xml`, `values-ja/strings.xml`）。

## 未解決・制約

- Web ページ側は文字だけが大きくなる（画像・ボタンは元のサイズ）。
- 通知の即時配信はプロセス生存中のみ。FCM を導入しない限り Chrome と同等にはならない。
- サイト権限はバックアップに含めていない（origin は端末ごとに意味が変わるため）。
