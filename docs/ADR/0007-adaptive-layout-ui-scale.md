# ADR-0007: 適応レイアウトと UI スケール

- 状態: **Accepted**（2026-10-31）
- 決定者: TO-PLAN（HB-PLAN-01）
- 関連: 要件 8 / TECH-RESEARCH §7 / AC-34〜AC-36

## Context

要件 8 は:

- スマホ縦横・タブレット・折りたたみに対応する
- コンパクト／ミディアム／拡張のウィンドウ幅で**グリッド列数とレイアウトを切り替える**
- 設定で「UI スケール 0.8〜1.4」「文字スケール 0.8〜1.5」「グリッド列数 自動/3/4/5/6/8」を調整できる
- edge-to-edge、インセット、カットアウトに対応する

既存 `AppSettings` には `uiScale` / `fontScale` / `gridColumns` と範囲定数があり、`Codecs.sanitize` が範囲外を丸める。`gridColumns = 0` は「自動」。

技術的な選択肢:

- ウィンドウサイズクラス: `androidx.compose.material3:material3-window-size-class` の `calculateWindowSizeClass(activity)`、または `BoxWithConstraints` の `maxWidth`。
- UI スケール: `LocalDensity` 差し替え、`Modifier.scale`、sp/dp を全部掛け算。
- インセット: `Scaffold` の `contentWindowInsets`、`Modifier.windowInsetsPadding`、`fitsSystemWindows`。

## Decision

### 1. ウィンドウ幅は `BoxWithConstraints` + 純粋関数で判定する（新規依存を足さない）

```kotlin
enum class WidthBucket { COMPACT, MEDIUM, EXPANDED, EXTRA_LARGE }

// 純粋関数（JVM テスト対象）
fun bucketFor(widthDp: Float): WidthBucket = when {
    widthDp < 600f -> WidthBucket.COMPACT
    widthDp < 840f -> WidthBucket.MEDIUM
    widthDp < 1200f -> WidthBucket.EXPANDED
    else -> WidthBucket.EXTRA_LARGE
}
```

- 公式の 5 区分（compact/medium/expanded/large/extra-large）を **4 区分に丸める**（large と extra-large は同じ扱い）。理由: 本アプリのレイアウトは「1列/2列/グリッド+余白」の3段階で足り、5段階にしても差分が出ない。
- `material3-window-size-class` を**追加しない**理由: (a) 依存を増やすと Compose BOM との整合検証が必要、(b) 判定が純粋関数になり JVM 単体テストで境界値（599.9/600/839.9/840/1199.9/1200）を検証できる、(c) `BoxWithConstraints` は画面ルートではウィンドウ幅と一致する。
- 判定は**画面ルートで 1 回**行い、`CompositionLocal`（`LocalWidthBucket`）で配る。各コンポーネントが個別に `BoxWithConstraints` を持つと、ネストした制約で誤判定する。

### 2. 列数は「幅から算出」を基本に、設定で上書きできる

```kotlin
// 純粋関数（JVM テスト対象）
object GridPolicy {
    const val MIN_TILE_DP = 104f
    fun autoColumns(widthDp: Float, horizontalPaddingDp: Float = 32f): Int {
        val usable = (widthDp - horizontalPaddingDp).coerceAtLeast(MIN_TILE_DP)
        // 自動は 2..8。狭い画面（uiScale 1.4 の 360dp 端末など）で 3 列固定にすると
        // 1 タイル 75dp まで潰れるため、2 列を許す（G-23 / W-DESIGN F-23）。
        return (usable / MIN_TILE_DP).toInt().coerceIn(2, 8)
    }
    fun columns(widthDp: Float, setting: Int): Int =
        if (setting in AppSettings.ALLOWED_GRID_COLUMNS && setting != 0) setting
        else autoColumns(widthDp)
}
```

- `autoColumns` は **2〜8** にクランプする。要件の固定選択肢は 3/4/5/6/8 だが、「自動」は狭い画面で 2 列を許す（uiScale 1.4 の 360dp 端末では論理幅 257dp になり、3 列固定だと 1 タイル 75dp まで潰れる）。2 列でも 1 タイル 112dp を確保できる。
- 設定値 3/4/5/6/8 は**そのまま使う**（ユーザーの明示意思を尊重）。ただし画面幅が狭すぎて 1 タイル 72dp を割る場合のみ、警告アイコンを出す（列数を勝手に変えない）。
- タイルの最小タップ領域は 48dp 以上（`MIN_TILE_DP` 104 なら余裕）。

### 3. レイアウトの切替（コンポーネント別）

| 要素 | COMPACT | MEDIUM | EXPANDED / EXTRA_LARGE |
|---|---|---|---|
| ホームのグリッド | 3〜4 列 | 4〜5 列 | 5〜8 列、最大幅 1200dp で中央寄せ |
| ツールバー | アイコン + タイトル（1行、ellipsis） | アイコン + タイトル + URL ホスト | アイコン + タイトル + フル URL、メニューは常時テキスト併記 |
| 設定画面 | 1 ペイン | 1 ペイン（最大 720dp） | 2 ペイン（左カテゴリ / 右詳細） |
| ダイアログ（SSL/認証） | 全幅 - 32dp | 最大 480dp | 最大 560dp |
| 余白 | 16dp | 24dp | 32dp |

- 「2 ペイン設定」は v1 では**実装しない**（1 ペインを最大 720dp で中央寄せ）。理由: 設定項目が 7 セクション程度で、2 ペインにする情報量が無い。将来の拡張として記録。

### 4. UI スケールは `LocalDensity` の差し替えで行う（インセットの外側）

```kotlin
// Activity の setContent 内、テーマ適用の直後
val base = LocalDensity.current
CompositionLocalProvider(
    LocalDensity provides Density(
        density = base.density * settings.uiScale,
        fontScale = base.fontScale * settings.fontScale,
    )
) {
    AppNavHost()   // Scaffold はこの内側
}
```

**順序の規約（重要）**:

```
WindowCompat.enableEdgeToEdge(window)
  └─ Theme
      └─ CompositionLocalProvider(LocalDensity = scaled)   ← ここから内側がスケール対象
          └─ Scaffold(contentWindowInsets = WindowInsets.safeDrawing)  ← インセットは scaled で計算される
              └─ content
```

- 上記だとインセットも `uiScale` 倍になる。これを避けるため、**インセットの適用だけは `LocalDensity` 差し替えの外側**で行う:

```
Theme
  └─ Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing))   ← 実密度で計算
      └─ CompositionLocalProvider(LocalDensity = scaled)
          └─ AppNavHost()   ← Scaffold(contentWindowInsets = WindowInsets(0))
```

- これにより、ステータスバー・ナビゲーションバー・カットアウトの余白は**端末の実寸**のまま、コンテンツだけがスケールする。
- IME は `Modifier.imePadding()` を**外側**（実密度）に置く。

### 5. スケールの副作用への対処

| # | 副作用 | 対処 |
|---|---|---|
| S1 | 0.8 倍でタップ領域が 48dp を割る | タップ領域は `Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)` を**スケール前の dp** で保証…ただし dp は scaled density で px 化されるため、実 px は 38dp 相当になる。**対策**: 重要な操作（ツールバーの戻る/メニュー、ダイアログのボタン）は `LocalDensity` を**元に戻した**スコープで 48dp を確保する `UnscaledMinTouchTarget` を用意する |
| S2 | 文字が最大 2.1 倍（1.4 × 1.5） | 2.1 倍を AC-34 の必須テストケースにする。`Text` は `maxLines` + `overflow = Ellipsis`、ツールバーは 2 行まで許容 |
| S3 | インセット二重適用 | §4 の順序規約。コードレビュー観点 |
| S4 | Dialog / Popup の継承 | **伝播を前提にしない**。`ScaledTheme`（テーマ + `LocalDensity` 差し替え）でダイアログ内容を明示的に包む。伝播の有無は実装依存で未検証（TECH-RESEARCH §7.2 S4） |
| S5 | Canvas / Bitmap 生成の二重スケール | アイコン Bitmap は Compose 外の純粋関数で生成（ADR-0006） |
| S6 | スクリーンショットテストの差異 | Preview は `uiScale = 1f` 固定のバリアントを用意 |

### 6. edge-to-edge / カットアウト / 折りたたみ

- `WindowCompat.enableEdgeToEdge(window)` を `onCreate` で必ず呼ぶ（Android 15 以降は自動だが、26〜34 でも同じ見た目にするため）。
- `android:windowLayoutInDisplayCutoutMode=shortEdges` は既存テーマで設定済み。
- 折りたたみの**ヒンジは考慮しない**（`androidx.window` を追加しない）。理由: 幅ベースのレイアウトで実用上破綻せず、ヒンジ API の追加依存とテストコストに見合わない。**制約として明記**し、二つ折りの内側画面でコンテンツがヒンジを跨ぐ可能性を README に書く。
- `configChanges` に `orientation|screenSize|screenLayout|smallestScreenSize|density|layoutDirection|uiMode|navigation|colorMode|fontScale` が既に宣言されているため、回転・ダークモード・フォントスケール変更で Activity は再生成されない（WebView が生き残る）。**`locale` は含まれていない**ため、言語変更時は再生成される（ADR-0008 の `recreate()` と整合）。

## Consequences

### 利点

- 追加依存ゼロ。判定ロジックが純粋関数で、JVM テストで境界値を固められる。
- WebView が回転で死なない（既存 manifest の `configChanges` を活かす）。
- スケールとインセットの相互作用を「順序」で解決し、実装のばらつきを防ぐ。

### 欠点・リスク

| # | リスク | 対策 |
|---|---|---|
| R1 | `BoxWithConstraints` は「利用可能領域」であり、マルチウィンドウでは正しく縮むが、`calculateWindowSizeClass` と厳密には一致しない | ルート 1 箇所で評価し、テストで代表幅を確認 |
| R2 | 0.8 倍時のタップ領域不足 | `UnscaledMinTouchTarget`（§5 S1） |
| R3 | 2.1 倍でレイアウト崩れ | AC-34 で必須検証。崩れる画面は設計を直す |
| R4 | ヒンジ跨ぎ | 既知の制約として文書化。v1.1 で `androidx.window` 導入を検討 |
| R5 | 設定変更の即時反映（再起動不要） | `AppSettings` の `StateFlow` を `collectAsStateWithLifecycle` で読む。プレビューで確認 |

### 実装上の帰結

- `LocalWidthBucket`（`compositionLocalOf`）と `WidthBucket` / `GridPolicy` は `ui/layout` 配下の純粋関数 + CompositionLocal。
- `AppSettings.uiScale` / `fontScale` の変更は**即時**に反映（Activity 再生成不要）。`Codecs.sanitize` の丸めを経由する。
- `SettingsScreen` のスライダーは 0.05 刻み、現在値を `%` 表示（例「120%」）。文字列は resources。

## Alternatives

### A. `material3-window-size-class` を使う

- 利点: 公式推奨。将来のブレークポイント変更に追随。
- 欠点: 依存追加、`calculateWindowSizeClass(activity)` が Activity を要求しプレビューで使いにくい、5 区分を 4 に丸める処理が別途必要。
- 不採用（v1）。ただし `WidthBucket` の閾値は公式値（600/840/1200）に合わせてあり、後で差し替え可能。

### B. `Modifier.scale()` で全体を拡大

- 利点: 実装1行。
- 欠点: レイアウトの制約が変わらず、はみ出し・クリッピングが起きる。タップ座標もずれる。
- 不採用。

### C. sp/dp を手動で掛け算

- 利点: 副作用が局所的。
- 欠点: 全コンポーネントに適用漏れが出る。Compose の `TextUnit`/`Dp` を網羅するのは非現実的。
- 不採用。

### D. `fitsSystemWindows` / `WindowCompat.setDecorFitsSystemWindows(true)`

- 利点: インセットを考えなくてよい。
- 欠点: edge-to-edge の要件に反する。Android 15 以降は強制的に edge-to-edge になるため、いずれ破綻する。
- 不採用。

## Status

**Accepted**。`UnscaledMinTouchTarget` の実装可否は実装フェーズで検証し、複雑すぎる場合は「0.8 倍時は 48dp 未満になることを許容し、最小スケールを 0.85 に上げる」代替へ切り替える（その場合 ADR を改訂）。
