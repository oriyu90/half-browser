# ADR-0001: レンダリングエンジンに Android System WebView を採用する

- 状態: **Accepted**（2026-10-31）
- 決定者: TO-PLAN（HB-PLAN-01）
- 関連: 要件 1, 2, 10 / TECH-RESEARCH §1, §6 / ADR-0002

## Context

Half-Browser は「Android 向け Chromium 互換（Android System WebView ベース）ブラウザ」と定義されている。一方で要件 2 は、自己署名証明書の HTTPS に対して**ブラウザ標準の警告の代わりにアプリ独自のインタースティシャルを出し、ユーザーが例外を許可できる**ことを求める。この2つは、エンジン選定の際に強い制約になる。

検討すべき選択肢は次の4つ。

1. **Android System WebView**（`android.webkit.WebView` + `androidx.webkit`）
2. **GeckoView**（Mozilla の独立エンジン）
3. **Custom Tabs**（`androidx.browser`）
4. **Chromium の自前ビルド埋め込み**

判断材料は、要件充足（特に証明書例外）、APK サイズ、実装コスト、セキュリティ更新の追随、ライセンス、配布制約である。

## Decision

**Android System WebView を採用する。** 具体的には:

- `WebView` を単一 `Activity`（Compose ホスト）内の `AndroidView` で保持する。
- `WebViewClient` を継承し、`onReceivedSslError` / `onReceivedHttpAuthRequest` / `shouldOverrideUrlLoading` / `onRenderProcessGone` / `onReceivedError` を実装する。
- 依存は既存の `androidx.webkit:webkit:1.17.0` を使い、`WebViewFeature` で機能可否を判定する。
- エンジンのバージョンは OS／Play ストア経由で更新される（アプリは同梱しない）。

### 決定理由

| 観点 | WebView | 理由 |
|---|---|---|
| 要件 2（証明書例外） | **可** | `onReceivedSslError` + `SslErrorHandler.proceed()` で例外的に続行できる。Custom Tabs では**不可能**（ホストアプリにコールバックが無い） |
| 要件 3（HTTP 認証） | **可** | `onReceivedHttpAuthRequest` で資格情報を差し込める。Custom Tabs では不可能 |
| APK サイズ | 最小 | エンジン同梱が無い |
| セキュリティ更新 | 最良 | WebView は Play ストアで独立更新される |
| 実装コスト | 低 | 既に scaffold 済み（`app/build.gradle.kts` に `androidx.webkit` がある） |
| ライセンス | MIT と両立 | WebView は OS コンポーネント。アプリ側の MIT ライセンスと衝突しない |

## Consequences

### 得られるもの

- 要件 2・3 を**プラットフォーム API の範囲で**実現できる唯一の現実的な選択肢。
- APK が小さく、エンジンの脆弱性修正が OS 更新に追随する。
- Compose との統合が容易（`AndroidView`）。

### 受け入れる制約（重要・隠さない）

1. **証明書例外は「公式に非推奨の操作」である。** `WebViewClient#onReceivedSslError` の公式ドキュメントは「常に `cancel()` を呼べ」「ユーザーに SSL エラーを問うな」と明記している（TECH-RESEARCH §1.3）。本アプリはこれを**意図的に逸脱**する。補償統制は ADR-0002 に定義する。
2. **通信層に介入できない。** 独自の証明書ストア検証、ピン留め（network security config の domain-config を動的変更する等）、プロキシ認証、HTTP/3 の制御はできない。信頼判断は `onReceivedSslError` の事後通知のみ。
3. **PDF をネイティブ表示できない。** リンク先が PDF の場合、v1 では「外部アプリで開く」導線に倒す（非スコープ）。
4. **拡張機能・広告ブロック・リーダーモードは無い。** `shouldInterceptRequest` による簡易ブロックは技術的には可能だが、v1 では実装しない。
5. **レンダラプロセスのクラッシュを自前で処理する必要がある。** `onRenderProcessGone` で `true` を返さないとアプリが落ちる（TECH-RESEARCH §6.2）。AC-38 で検証。
6. **WebView のバージョン差で挙動が変わる。** 端末ごとに WebView が異なるため、`WebViewFeature` と `Build.VERSION` の両方で分岐する。特に `SslCertificate.getX509Certificate()` は API 29+ のみ（TECH-RESEARCH §2.1）。
7. **`file://` を無効化すると、ローカル HTML を開く手段が `WebViewAssetLoader` に限定される。** v1 ではローカル HTML を使わないため問題にならないが、将来のオフラインページ実装時に効く（ADR-0004）。

### リスクと mitigation

| リスク | 影響 | 対策 |
|---|---|---|
| 公式非推奨 API の将来削除 | 証明書例外が実装できなくなる | `TrustPolicy` / `SslTrustCoordinator` をインターフェース化し、エンジン差し替え時に他機能への波及を防ぐ |
| 端末 WebView のバージョン差 | 一部端末で証明書取得が失敗 | 失敗時は「常に信頼」を無効化し「今回だけ」のみ許可（安全側） |
| WebView のメモリ使用 | 低 RAM 端末で OOM | `onTrimMemory` で `WebView.clearCache` はしない（UX 劣化）。`largeHeap` は既存設定を維持。`destroy()` を確実に呼ぶ |

## Alternatives

### A. GeckoView

- **利点**: エンジンをアプリに同梱するため挙動が端末非依存。`GeckoSession.ContentDelegate.onCertificateError` で証明書例外を**公式にサポート**しており、公式見解との衝突が無い。拡張機能も使える。
- **欠点**: APK が数十 MB 増える。エンジン更新をアプリのリリースサイクルに載せる必要がある（セキュリティ更新の遅延リスク）。`GeckoView` は Compose との統合例が少なく、日本語ドキュメントも薄い。Maven 依存の追加とビルド時間増。
- **不採用理由**: 要件は「Android System WebView ベース」と明記されており、**要件そのものが WebView を指定している**。加えて APK サイズと更新責任の増大が見合わない。

### B. Custom Tabs（`androidx.browser`）

- **利点**: 実装が最も簡単。Chrome の UI を借りられる。
- **欠点**: **ホストアプリに SSL エラー／HTTP 認証のコールバックが無い。** 要件 2・3 を根本的に満たせない。Cookie や履歴の共有もできない。
- **不採用理由**: 要件充足不可（決定的）。

### C. Chromium の自前ビルド埋め込み

- **利点**: 完全な制御。証明書処理も自由。
- **欠点**: ビルドに数時間〜数十時間、成果物が 100MB 超、ライセンス遵守（BSD + 通知）と脆弱性追随の継続コストが個人プロジェクトの範囲を超える。
- **不採用理由**: 維持不可能。MIT の小規模アプリとしての配布方針に反する。

### D. 外部ブラウザへ投げるだけ（`Intent.ACTION_VIEW`）

- **利点**: 実装ゼロ。
- **欠点**: 要件 2・3・5・7 がすべて成立しない。
- **不採用理由**: 製品要件を満たさない。

## Status

**Accepted**。将来 GeckoView へ移行する場合、`BrowserEngine` 抽象（`loadUrl` / `goBack` / `canGoBack` / `evaluateJavascript` 無し / 証明書コールバック）を設けて差し替え可能にする。ただし v1 では抽象化のための過剰な間接層は作らない（YAGNI）。判断が変わった時点で新しい ADR を起票する。
