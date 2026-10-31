# ADR-0012: ファイル添付・カメラ・位置情報・全画面の扱い

- 状態: Accepted（2026-11-01）
- 関連: PLAN-v1.1 §3.1 / ADR-0001（WebView 採用）

## Context

v1.0.1 では次が未実装だった（実測）:

- `<input type=file>` をタップしてもファイル選択が開かない（`onShowFileChooser` 未実装）。
- カメラ・マイクは `onPermissionRequest` で無条件拒否。
- 位置情報は `onGeolocationPermissionsShowPrompt` 未実装で、ページ側のコールバックが返らない。
- 動画の全画面（`onShowCustomView`）が未実装。

一般的なブラウザとして、ユーザーが明示的に許可した範囲でこれらを動作させる必要がある。

## Decision

1. **ファイル選択**: `WebChromeClient.onShowFileChooser` を実装し、`FileChooserParams.createIntent()`
   で SAF を起動する。`MODE_OPEN_MULTIPLE` / `MODE_SAVE` は `createIntent()` と `parseResult()` に委ねる。
   結果は `ValueCallback<Array<Uri>>` に返す。保留は 1 件のみ。
2. **カメラ撮影**: 画像 accept のときだけ「ファイル / カメラ」を選ばせる。カメラは
   `ACTION_IMAGE_CAPTURE` + FileProvider（`cacheDir/camera/`）で一時ファイルを作り、その Uri を返す。
3. **Web 権限**: カメラ・マイクは `onPermissionRequest` を保留し、origin を表示する Compose ダイアログで
   許可/拒否を選ばせる。許可時のみ Android の実行時権限を要求し、すべて揃ったら `request.grant()`。
   拒否・未許可は `request.deny()`。許可は永続化しない（セッション中のみ）。
4. **位置情報**: `onGeolocationPermissionsShowPrompt` を保留し、同様に確認。許可時のみ
   `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` を要求し、`callback.invoke(origin, true, false)`。
5. **全画面動画**: `onShowCustomView` の View をフルスクリーン Dialog の `FrameLayout` に載せ、
   システムバーを隠す。`onHideCustomView` と戻る操作で remove + `onCustomViewHidden()`。
6. **新規ウィンドウ**: 単一タブ設計を維持し、`setSupportMultipleWindows(false)` のまま同一 WebView で
   開く（実測で `target=_blank` は同一タブ遷移）。複数タブは別 ADR の範囲。

## Consequences

- ファイル選択は SAF のためストレージ権限不要。カメラは `CAMERA`、マイクは `RECORD_AUDIO`、
  位置情報は `ACCESS_*_LOCATION` を Manifest に追加する。
- Web 権限は「ページ origin を見せて都度確認」する。許可を永続化しないため、次回また確認になる
  （安全側。必要になればサイト単位の永続許可を別 ADR で検討）。
- カメラ撮影の一時ファイルは `cacheDir` に置き、結果返却後に削除する。
- 全画面のカスタム View は Dialog の破棄時に必ず remove し、`WebChromeClient` のコールバックを返す。
- `target=_blank` は新規タブではなく同一タブで開く。UI にその旨は出さないが、単一タブであることは
  README / 設計文書に明記済み。
