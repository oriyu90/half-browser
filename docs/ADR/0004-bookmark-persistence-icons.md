# ADR-0004: ブックマーク永続化とモノグラムアイコン生成

- 状態: **Accepted**（2026-10-31）
- 決定者: TO-PLAN（HB-PLAN-01）
- 関連: 要件 5, 7 / ADR-0006 / AC-19〜AC-21

## Context

ホーム画面はブックマークをアイコン付きタイルでアプリのようにグリッド表示する（要件 5）。必要になるのは:

1. ブックマークの永続化（追加・編集・削除・並び順）
2. タイルに出すアイコン（**オフラインで生成**するモノグラム。決定的な色 + 1〜2文字グリフ。ユーザー編集可）
3. 長押しで編集・削除・ホーム画面ショートカット追加

既存実装には以下がある（**そのまま活用する**）:

- `Models.kt`: `Bookmark(id, title, url, glyph, colorSeed, createdAt, updatedAt)`、`BookmarkList(version=1, items)`
- `Codecs.kt`: kotlinx.serialization による encode/decode。`ignoreUnknownKeys=true`、破損時は空リストへフォールバック。`validateWebUrl` を通らない URL は捨てる
- `BookmarkIcons.kt`: 10色パレット、`paletteIndexFor`（URL の FNV 風ハッシュ）、`glyphFor`、`shortcutId` / `bookmarkIdFromShortcut`、`shortcutLabel`

永続化の候補は SharedPreferences + JSON、DataStore(Proto/Preferences)、Room。アイコンの候補は favicon 取得、モノグラム生成、手動画像アップロード。

## Decision

### 1. 永続化は SharedPreferences + JSON を維持する

- `BookmarkRepository`（新規）が単一の書き手となり、`Mutex` で直列化する。読み書きは IO ディスパッチャ。
- 保存形式は既存の `Codecs.encodeBookmarks()`（`BookmarkList` の JSON）をそのまま使う。
- 変更は `apply()` ではなく **`commit()`** を使う（ブックマーク追加は「保存された」とユーザーに即座に伝える操作であり、プロセス即死での消失を避ける）。件数は高々数百件・数十 KB なので同期書き込みのコストは無視できる。
- メモリ上に `StateFlow<List<Bookmark>>` を持ち、UI はそれを購読する（SharedPreferences の `OnSharedPreferenceChangeListener` は使わない＝単一書き手なので不要）。
- **スキーマ進化**: `BookmarkList.version` は据え置き（1）。フィールド追加は既定値付きで行い、`ignoreUnknownKeys=true` により前方互換を保つ。破壊的変更が必要になったら `version=2` + 明示的マイグレーションを追加する。

**上限と整合性**:

| 項目 | 方針 |
|---|---|
| 最大件数 | 500 件（超過時は追加を拒否し、Snackbar で通知） |
| `id` | `UUID.randomUUID().toString()` |
| 重複 URL | 追加時に `UrlNormalizer.canonicalForCompare()` で既存と比較し、**重複するなら新規作成せず既存のタイトルを更新**して通知 |
| タイトル空 | `BookmarkIcons.normalizedTitleOrFallback()` でホスト名にフォールバック |
| URL 検証 | `UrlNormalizer.validateWebUrl()` を通らないものは保存しない（`javascript:` / `file:` / `data:` を弾く） |

### 2. アイコンはモノグラムを Canvas で決定的に生成する（favicon は取得しない）

- 色: `BookmarkIcons.colorFor(url, colorSeed)`。`colorSeed` が 0..9 ならユーザー指定、それ以外は URL のハッシュから決定（**同じ URL は常に同じ色**）。
- グリフ: `BookmarkIcons.glyphFor(title, url, userGlyph)`。ユーザー指定 > タイトル先頭の英数字 > ホスト先頭 > `•`。
- 描画: Compose 側は `Box` + `Text` で描く（タイル）。ショートカット用は `Bitmap` に同じ規則で描く（ADR-0006）。**同一の色・グリフ関数を共有**し、見た目を一致させる。
- 追加修正（実装フェーズで必要）: 現行 `glyphFor` は `source.firstOrNull { it.isLetterOrDigit() }` で**サロゲートペア（絵文字）を分割し得る**。`BreakIterator.getCharacterInstance()` で書記素単位に取り出すよう修正する。日本語の「日」は 1 コードポイントなので現行でも動くが、絵文字タイトルで豆腐になる。

### 3. favicon を取得しない理由（決定の核）

| 観点 | favicon 取得 | モノグラム |
|---|---|---|
| プライバシー | タイル表示のたびに各サイトへリクエストが飛ぶ（**訪問していないブックマークのホストに接続**する）。DNS 漏れ・トラッキングの温床 | 通信ゼロ |
| オフライン | 取得失敗時に空白 or 汎用アイコン | 常に描ける |
| 決定性 | サイト側の変更でアイコンが変わる。キャッシュ無効化の管理が必要 | 入力（URL/タイトル）だけで決まる |
| 保存容量 | 画像キャッシュ（数百 KB〜） | 0（描画のみ） |
| 実装 | HTTP クライアント + 画像デコード + キャッシュ + 失敗時 fallback | Canvas 描画のみ |
| 見た目 | サイトのブランドが出る | 統一感があり「アプリのアイコン」に見える（要件の「アプリのように」に合致） |

要件が「**オフラインで生成するモノグラム**」と明示していることも決定を後押しする。将来 favicon をオプション提供する場合も、**明示的オプトイン**かつキャッシュ付きに限る（本 ADR の範囲外）。

### 4. ユーザー編集

- 長押しメニュー →「編集」でタイトル・URL・グリフ（1〜2文字）・色（10色パレット）を変更できる。
- グリフは**書記素 2 個まで**に制限（`takeGraphemes(2)`）。空にすると自動導出に戻る。
- 色は `colorSeed = -1`（自動）または 0..9。

## Consequences

### 利点

- 追加依存ゼロ（DataStore / Room / 画像ライブラリを足さない）。APK サイズとビルド時間に優しい。
- オフラインで完結し、ブックマークの存在を外部に漏らさない。
- 既存の `Codecs` / `BookmarkIcons` がそのまま使え、JVM 単体テストが書きやすい（Android 依存が無い）。
- JSON なので、将来のエクスポート／インポート（v1.1）が容易。

### 欠点・リスク

| # | リスク | 対策 |
|---|---|---|
| R1 | `SharedPreferences` はマルチプロセス非対応。将来 `:remote` プロセスを足すと壊れる | 単一プロセス前提を明記。プロセス追加時は DataStore/Room へ移行（ADR 再起票） |
| R2 | 全件書き換えのため、500 件で約 100KB の JSON を毎回書く | 実測して問題ない範囲。気になる場合は差分書き込みではなく DataStore へ移行する判断材料にする（AC-21 で 500 件の追加・起動時間を計測） |
| R3 | `commit()` は UI スレッドをブロックし得る | 必ず IO ディスパッチャで呼ぶ。UI は楽観更新 + 失敗時ロールバック |
| R4 | 破損 JSON で全消失 | `Codecs.decodeBookmarks` は空リストへフォールバックする（既存）。**全消失を防ぐ**ため、書き込み前に旧値を `bookmarks.bak` に退避し、デコード失敗時に .bak を試す（実装フェーズで追加） |
| R5 | モノグラムはサイトの識別性が弱い（同じ頭文字で混同） | 色が URL ハッシュで分散するため実用上は区別可能。ユーザーがグリフ・色を編集できる |

### 実装上の帰結

- `BookmarkRepository` は `suspend` API とし、`StateFlow` を公開する。
- 追加・更新・削除は「メモリ更新 → commit → 失敗なら復元」の順（UI を待たせない）。
- ショートカット同期（ADR-0006）は Repository の変更を購読して行うが、**動的ショートカットの更新失敗でブックマーク操作を失敗させない**（ベストエフォート）。

## Alternatives

### A. Room

- 利点: クエリ・整合性・マイグレーション・大量件数に強い。`Flow` 統合。
- 欠点: KSP プラグインと依存追加、ビルド時間増、スキーマ JSON の管理。500 件・単一テーブルには過剰。
- 不採用（v1）。件数が数千になる／検索が必要になったら再検討。

### B. DataStore(Preferences / Proto)

- 利点: 非同期・原子的・単一書き手の保証。SharedPreferences の `commit()` ブロックが無い。
- 欠点: 依存追加（`androidx.datastore`）。Proto は protobuf プラグインも要る。既存 `Codecs`（JSON）を活かすなら Preferences DataStore に JSON 文字列を入れる形になり、**SharedPreferences と大差ない**。
- 判断: 将来の移行先としては有力だが、v1 では依存を増やさない。`BookmarkRepository` の背後を差し替え可能にしておく。

### C. favicon を取得してキャッシュ

- 利点: 見た目がリッチ。
- 欠点: プライバシー（未訪問ホストへの接続）、キャッシュ管理、失敗時 UX、オフライン要件。
- 不採用（§3）。将来オプトインで検討。

### D. ユーザーが画像を選んでアイコンにする

- 利点: 完全なカスタマイズ。
- 欠点: 画像の保存・縮小・バックアップ・著作権。要件は「モノグラム（ユーザー編集可）」であり、画像アップロードは要求されていない。
- 不採用。

## Status

**Accepted**。`glyphFor` の書記素対応と `.bak` 退避は実装フェーズの必須修正項目として PLAN の WP に含める。
