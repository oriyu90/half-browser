# ADR-0019: 悪意あるページ JavaScript への防御

- 日付: 2026-11-09 / 状態: 採用（v1.6.0）
- 関連: ADR-0014（WebView 互換）、[計画](../plan/v1.6.0-vpn-js-guard.md)

## 背景

訪問した（あるいは広告・iframe 経由で混入した）ページの JavaScript が攻撃に使えるのは、
「JS を実行できる」こと自体ではなく次の点である。

1. **ダイアログ連打**（`alert`/`confirm`/`prompt` の無限ループ）でブラウザを操作不能にする。
2. **遷移の乗っ取り**（`javascript:` への遷移、承認なき外部サイトへのリダイレクト）。
3. **ポップアップ連打**（`window.open` のループ）。
4. **退出妨害**（`beforeunload` の再登録ループでタブを離れられなくする）。
5. **アプリ自身の注入スクリプトの置き換え**（互換シム・通知ブリッジの改竄）。

従来、JS ダイアログは WebView 標準ダイアログに任せており、送信元（origin）も表示されず、
連打の制限もなかった。

## 決定

二層防御を入れた。どちらか一方では不十分だからである。

### 第 1 層: ページ内シム（document-start 注入）

- `core/JsGuard.kt` の `JS` を `WebViewCompat.addDocumentStartJavaScript` で最優先（全ページスクリプトより前）に注入。
  - `alert`/`confirm`/`prompt` をレート制限（最大 12 回、250ms 間隔）。
  - `beforeunload` リスナー登録を 2 回まで（超過は無視）。
  - `window.open` はユーザー操作なしをブロック。
  - `Location.assign/replace` への `javascript:`  URL をブロック。
  - 自分のグローバル（`__hbJsGuard` など）を non-writable 化し、ページが置き換えられないようにする。
  - カウンタ `__hbJsGuard.read()` を公開（診断用、読み取り専用）。
- ラッパーは `toString()` を native らしく見せ、ページの機能検出で分岐させない。

### 第 2 層: ネイティブ（WebChromeClient）

- `onJsAlert` / `onJsConfirm` / `onJsPrompt` をアプリ側ダイアログに置換。
  - 送信元 origin を必ず表示（ページがブラウザや他サイトを装えない）。
  - ダイアログ文は長さ制限＋制御文字除去（ページ指定テキストのレンダリング安全性）。
  - `DialogBudget`（Kotlin 版の同ポリシー）でページ内シムを迂回しても同じ上限が効く。
- `onJsBeforeUnload`: 2 回までは WebView の確認、3 回目以降はアプリが自動で「離れる」を選択
  （ユーザーをページに閉じ込めない）。
- `shouldOverrideUrlLoading`（既存）でクリック起点の `javascript:` 等を従来通り拒否。

### 設定

- 「悪意あるスクリプトから保護する」トグル（設定→セキュリティ、既定 ON）。
  ダイアログを多用する正当なサイト向けに解除できる。OFF 時は WebView 標準動作。

## 互換性への配慮

- 完全ブロックはしない。`alert` は上限まで出る。ページの正当な動作は壊さない。
- WebView 標準ダイアログを置換するため、既存ページの `alert` 表示は変わるが機能は同じ。

## 検証

- JVM テスト: `DialogBudget` の上限・間隔・リセット、シムとネイティブの上限一致、シムの自己完結性
  （ネットワーク・eval・ブリッジ呼び出しを含まないこと）。
- エミュレータ実測（`tools/vpn-test/attack.html`）:
  - alert 20 連打 → 12 表示・8 自動ブロックでループ終了。
  - `window.open` 5 連打 → 5 件すべてブロック。
  - `beforeunload` 5 登録 → 3 件ブロック。離脱は確認ダイアログ経由で可能。
  - `location = 'javascript:...'` は実行されるが、その `alert` は予算でブロックされる
    （下記の限界を参照）。
- **限界（正直な記録）**: `location = 'javascript:...'` や `location.href = 'javascript:...'` の
  代入は、シムで止められない（`Location` のプロパティは上書き不可）。実行されたスクリプトの
  ダイアログは予算で止まるが、ページ内 DOM 操作そのものは止まらない。クリック起点の
  `javascript:` リンクは `shouldOverrideUrlLoading` で従来通りブロックされる。
- **未実装**: スクリプト経由のクロスサイト遷移（承認なき外部サイトへのリダイレクト）に対する
  確認ダイアログ。WebView の遷移リクエストには「ユーザー操作の有無」を確実に付与できないため、
  リンククリックまで確認対象にすると通常の閲覧が壊れる。ユーザー操作の有無を確実に得られる
  経路（例: ページ内シムからの報告）ができた時点で実装する。
