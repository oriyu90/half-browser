# 独立レビューへの対応（Review Response）

- 対象: [REVIEW_REPORT.md](REVIEW_REPORT.md) の指摘 25 件（CRITICAL 0 / HIGH 6 / MEDIUM 11 / LOW 6 / INFO 2）
- 対応日: 2026-10-31
- 対応後の検証: 単体テスト **57 件 / 失敗 0**、`lintDebug` **Error 0**、署名付きリリース APK を実機で再検証。

| ID | 重大度 | 対応 | 内容 |
|---|---|---|---|
| R-01 | HIGH | **修正** | `intent://` を一切起動しない。`S.browser_fallback_url` のみ `validateWebUrl` を通して読み込む。任意コンポーネント・extras の経路を削除。 |
| R-02 | HIGH | **修正** | `AuthAttemptTracker`（純粋クラス）を導入。同一キーで最大 2 回の自動入力、`onPageCommitVisible` でホスト単位にリセット。単体テスト 4 件。 |
| R-03 | HIGH | **修正** | `onPageFinished` で `WebView.certificate` をピンと再照合。不一致ならピン失効＋読み込み停止＋エラーペイン。失効時は WebView を再生成。 |
| R-04 | HIGH | **修正/判断** | `largeHeap` を削除。`usesCleartextTraffic` は http ページを開くブラウザ要件のため維持し、コメントと設計文書に根拠を明記。 |
| R-05 | HIGH | **一部修正** | 認証試行・グリッド・資格情報キー・信頼のポート/ピン可否の単体テストを追加（13 件増、計 57 件）。instrumented テスト基盤は未導入（残課題）。 |
| R-06 | HIGH | **一部修正** | `saveState()` Bundle の既知キー→全 Bundle 走査→X.509 パースの二段フォールバック。取得不能時は「常に信頼」を無効化し説明を表示。API 26 実機は未検証。 |
| R-07 | MEDIUM | **修正** | SSL ダイアログはメインフレーム URL と一致する場合のみ。同一ホストのサブリソースは silent cancel（ダイアログ置換・スパムを排除）。 |
| R-08 | MEDIUM | **修正** | `CredentialStore.save()` の戻り値を確認し、失敗時は `auth_save_failed` を表示。成功トーストの無条件表示を削除。 |
| R-09 | MEDIUM | **受容（記録）** | 復号失敗時の退避・通知（VaultState）は未実装。保存失敗は通知する。破損時は空扱いで起動する安全側。v1.1 候補として設計評価に記載。 |
| R-10 | MEDIUM | **修正・実測** | 環境変数なしの `:app:packageRelease` が `SigningConfig "release" is missing required property "storePassword"` で失敗することを実行確認。 |
| R-11 | MEDIUM | **修正** | 自パッケージを除外したうえで `Intent.createChooser` + `EXTRA_EXCLUDE_COMPONENTS`。特定パッケージへの強制をやめ、ユーザーの選択に委ねる。 |
| R-12 | MEDIUM | **修正** | `bookmarkId` は URL の canonical 一致を確認した場合のみ採用。外部からの名前偽装を排除。 |
| R-13 | MEDIUM | **修正** | セキュリティ設定（`allowFileAccess`/`allowContentAccess`/ポップアップ/混合コンテンツ）を先頭で個別に `runCatching`。1 つの失敗で後続を止めない。 |
| R-14 | MEDIUM | **修正** | Basic 認証の host/realm を制御文字除去＋長さ上限（120/200）で表示。 |
| R-15 | MEDIUM | **修正** | `Changed`（証明書変更）時の「常に信頼」に二段確認ダイアログを追加。 |
| R-16 | MEDIUM | **修正** | 外部委譲は `tel/mailto/sms/smsto/geo` のみ。`data:`/`javascript:`/`about:`/`blob:`/`file:` は拒否。委譲 URL も `validateWebUrl` 通過が必要。 |
| R-17 | MEDIUM | **一部修正** | 上記の純粋ロジックテストを追加。Compose UI テストは未追加（残課題）。 |
| R-18 | LOW | **修正** | `Locale.setDefault` を削除し、`Configuration` のみで言語を適用。 |
| R-19 | LOW | **受容** | `onRenderProcessGone` は実装済み。lint の警告は実装を認識しない誤検知のため、コード変更なし。 |
| R-20 | LOW | **受容** | `debuggingConfigured` は release で false。実害なし。 |
| R-21 | LOW | **延期** | 依存更新は別コミット。v1.0.0 は実績のある組み合わせで固定。 |
| R-22 | LOW | **受容** | アプリ内言語切替は動作。OS の per-app 言語との二重管理は設計評価に記載。 |
| R-23 | LOW | **修正** | `intent://` の parse 自体を廃止し、fallback は `Uri.getQueryParameter` で取得。 |
| R-24 | INFO | **修正** | `IMPLEMENTATION-GAP.md` に 2026-10-31 の解決追記。本文書と設計評価を追加。 |
| R-25 | INFO | **維持** | https のみ保存・返却の設計を維持。 |

## 再検証

| 項目 | 結果 |
|---|---|
| `:app:testDebugUnitTest` | 57 件 / 失敗 0 |
| `:app:lintDebug` | Error 0 / Warning 63 |
| `:app:assembleRelease`（署名あり） | 成功、`apksigner verify` v2 |
| 実機（API 34） | 初回信頼 → 認証保存 → 再起動 → 自動表示、失効 → 再警告、ショートカット、タブレット/横向き、日英切替、release で FATAL 0 |
| `:app:packageRelease`（署名なし） | 期待どおり失敗 |

## 残課題（v1.1 候補）

- API 26–28 実機での証明書 DER 取得の実測
- instrumented / Compose UI テストの導入
- Keystore 破損時の退避・通知（VaultState）
- ファイル選択・クライアント証明書
- レンダラ強制終了の実地試験
