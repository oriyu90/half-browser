# フェーズ報告書（TO_FEEDBACK）— TO-PLAN

- TASK: Half-Browser 実装計画・ADR・受け入れ条件・技術調査
- PHASE: PLAN-01（タスク確定）
- TO_ID: **TO-PLAN-HB-01**
- 実行方式: **DIRECT_NESTED**（DSH 実測 depth=2 / 同時 2）
- 状態: **PHASE_COMPLETED**（計画成果物は完成。ただし**実装の AS-IS に HIGH 以上の欠陥 8 件**を発見しており、G2 ゲートは `REWORK` を推奨）
- 日付: 2026-10-31

---

## 1. フェーズ目的と範囲

ユーザー要件（9 機能 + 品質要件）を、実装者が迷わず着手できる **計画書 / ADR / 受け入れ条件 / 技術調査** に落とし、メインが G1（計画ゲート）を判定できる状態にする。

- 範囲内: 一次資料調査、設計判断、AC 定義、リスク、WP 分割、ギャップ分析
- 範囲外: コード実装（`app/` は**一切変更していない**）、git commit / push、リリース実行、有料ツール

---

## 2. 起動した Worker と採否

| Worker | 実起動 ID（child_id） | 役割 | model | WP | 状態 | 成果物 | TO の採否 |
|---|---|---|---|---|---|---|---|
| W-RESEARCH | `dd5d84be-75c1-4c27-8546-725fa5d1105e` | プラットフォーム API 一次調査 | 親ルート継承（`deepseek/deepseek-v4.1-flash`、上書き不可のため指定なし） | WP-PLAN-RESEARCH | DELIVERED | [docs/_workers/W-RESEARCH.md](_workers/W-RESEARCH.md) 457 行 | **ACCEPT（条件付き）**。主要 41 事実を TO が一次資料で再検証し一致。10 件の未検証はそのまま「未検証」として採用 |
| W-DESIGN | `fa576c96-aae8-44b5-903e-04756c00025e` | 設計批判・テスト設計 | 同上 | WP-PLAN-DESIGN | DELIVERED | [docs/_workers/W-DESIGN.md](_workers/W-DESIGN.md) 504 行 / 指摘 30 件 / AC 提案 40 件 | **ACCEPT（重要 12 件を採用、3 件は前提誤りとして棄却、5 件は未検証として保留）** |

- モデル指定は本セッションで許可されておらず（`list_subagent_models` で provider 拒否）、`provider`/`model` を渡さず親ルートを継承した。**追加課金・新規有料ツールは無し**。
- Worker に孫を起動させていない（depth 2 上限）。W-DESIGN は W-RESEARCH 完了後に起動（同時 2 制約のため直列化）。

---

## 3. TO による検証（無検証転記の防止）

### 3.1 一次資料の再検証（自分で取得）

`curl` で developer.android.com の本文を取得し、以下を**自分の目で確認**した（`web_fetch` は巨大ナビで truncate されるため不採用）。

| 主張 | 結果 | 出典 |
|---|---|---|
| `onReceivedSslError` は cancel/proceed 必須、公式は「常に cancel」「問うな」 | 一致 | WebViewClient |
| `SslCertificate.getX509Certificate()` は **API 29+** | 一致（W-RESEARCH の指摘が正しい） | SslCertificate |
| `saveState()` の Bundle キー `x509-certificate` | **TO が AOSP ソースで確認**（W-RESEARCH は未検証としていた） | AOSP SslCertificate.java |
| `HttpAuthHandler.proceed` は void・UI スレッド必須 | 一致 | HttpAuthHandler |
| `useHttpAuthUsernamePassword()` は「拒否済みなら false」 | 一致（ただし**自前保存では意味が不定**と判断し、ゲートから外した） | HttpAuthHandler |
| `requestPinShortcut` は前景必須・戻り値は成功でない・拒否時無通知 | 一致 | ShortcutManager |
| `security-crypto` は 1.1.0-beta01 で全 API 非推奨 | 一致（リポジトリは alpha06 = 非推奨化前） | AndroidX release notes |
| 幅クラス 600/840/1200/1600 | 一致（公式は 5 区分。本アプリは 4 区分に丸め） | Compose adaptive guide |
| per-app language の後方互換は API 32 以下で AppCompatActivity 必須 | 一致 | app-languages guide |
| `WebView.getCertificate()`（メイン frame の証明書） | **TO が発見**。事後検証に採用 | WebView reference |

### 3.2 W-DESIGN 指摘の採否（重要どころ）

| 指摘 | 採否 | 理由 |
|---|---|---|
| F-01 `proceed` 保持の可能性（CRITICAL） | **採用** | 公式文言の解釈が2通りあることを TECH-RESEARCH §1.2 に明記。`WebView.getCertificate()` による**事後検証**を ADR-0002 に追加。AC-12b/12c を新設 |
| F-02 ハンドラ1件保持でサブリソースが壊れる（HIGH） | **採用（未検証前提）** | `(host, fingerprint)` キュー + 一括決定 + reload を ADR-0002 に追加。実測は AC-47（U-A） |
| F-03 API 26–28 で指紋が取れない（HIGH） | **採用（現物で確認）** | `BrowserState.kt:92-95` が `x509Certificate` を無分岐で参照 → G-02 |
| F-04 SPKI ピン併用（MEDIUM） | **一部採用** | 信頼キーは要件どおり DER SHA-256 のまま。SPKI は**表示・診断用に併記**（「公開鍵は同一＝更新の可能性」）。信頼判定は変えない |
| F-05 同一ホスト複数証明書（MEDIUM） | **採用** | host+fingerprint を一意に。`TrustPolicy` に List 版を追加 → G-16 / AC-50 |
| F-07 `useHttpAuthUsernamePassword` はゲートにならない（HIGH） | **採用** | ADR-0003 からゲートを削除。**自前 epoch カウンタに一本化** |
| F-08 認証キーに scheme/port が無い（HIGH） | **採用（現物で確認）** | `CredentialStore.keyOf()` が `host|realm` → G-05 |
| F-09 Digest stale の誤検知（MEDIUM） | **採用** | 「同一 epoch 内」条件と、成功・新規ナビ・手動成功でのリセットを ADR-0003 に追加 |
| F-12 静かな資格情報喪失（HIGH） | **採用（現物で確認）** | `CredentialStore` の memory フォールバック + `emptyMap()` 握り潰し → G-06 |
| F-15 `.bak` の穴（HIGH） | **採用** | 復旧モード（読取専用 + 通知）を PLAN/ADR-0004 の実装条件に追加 |
| F-17 ショートカット Bitmap（HIGH） | **採用（緩和付き）** | 実装は 108dp×density（最大432px）。**リソースアイコン fallback** を必須化（AC-28 / U12） |
| F-19 `intent://` の任意コンポーネント起動（CRITICAL） | **採用（現物で確認）** | `HalfBrowserWebViewClient.kt:164-174` に実在 → G-01 / AC-46。**最優先修正** |
| F-22 Dialog に Density が伝播しない（CRITICAL） | **採用（未検証）** | 伝播を前提にせず `ScaledTheme` で明示的に包む方針へ変更（ADR-0007 S4） |
| F-23 2.1 倍で破綻（HIGH） | **一部採用** | 自動列数の下限を 3 → **2** に変更（ADR-0007 / PLAN §9）。スケール仕様自体は要件どおり維持 |
| F-24 インセット二重適用（HIGH） | **採用** | インセットは `LocalDensity` 差し替えの**外側**で適用（PLAN §3.1 / ADR-0007） |
| F-25 言語の二重管理（HIGH） | **採用（現物で確認）** | `LocaleManager` 未使用 → G-17。`Locale.setDefault` の副作用 → G-10 |
| F-26 `onRenderProcessGone` 未実装（HIGH） | **採用（現物で確認）** | → G-08 / AC-38 |
| F-29 未署名 release が成功（HIGH） | **採用（現物で確認）** | → G-07 / AC-41b |
| F-30 `largeHeap=true`（LOW） | **採用** | → G-14 |
| 「現実装は `isForMainFrame` 未確認」「設計とコードが矛盾」など | **棄却（前提誤り）** | 該当箇所は実装済み、または対象コードが存在しない。**Worker が参照した一部ファイル名・行番号は本リポジトリに存在しない**（`BrowserState.kt` 等は存在するが、指摘の一部は別実装を想定したもの）。前提が確認できない指摘は採用しない |
| 「毎回 cancel→自前 UI→再ロード」への全面移行 | **棄却** | POST 本文喪失・二重送信・履歴汚染が致命的（W-DESIGN 自身も単独不採用と結論）。**Plan B（保持が実測された場合の限定適用）** として保持 |

### 3.3 実装の実在確認（本フェーズ中の発見）

- 本フェーズ中に `app/src/` へ実装が追加された（`ui/` `data/` `shortcuts/` ほか、`src/test` 6 ファイル、計 **4,793 行**）。
- TO は指示どおり `app/` を**変更していない**が、**現物を読んで**設計との差分を [IMPLEMENTATION-GAP.md](IMPLEMENTATION-GAP.md) に **G-01〜G-18** として記録した。
- これは計画フェーズの想定外だが、**「計画と現物の不一致を記録する」ことは TO-PLAN の責務**（規約 §1.5 / §15.3）と判断し、隠さず報告する。

---

## 4. 成果物（実在確認済み）

| # | パス | 行数 | 内容 |
|---|---|---|---|
| 1 | `docs/TECH-RESEARCH.md` | 560 | 一次資料・確信度・落とし穴・未検証 U1〜U14 |
| 2 | `docs/ADR/0001-webview-vs-geckoview.md` | 94 | WebView 採用（GeckoView / Custom Tabs / Chromium 比較） |
| 3 | `docs/ADR/0002-self-signed-trust-tofu.md` | 157 | TOFU（host + DER SHA-256）、事後検証、複数ピン、Plan B |
| 4 | `docs/ADR/0003-credential-storage.md` | 165 | EncryptedSharedPreferences、AuthKey、ループ防止 |
| 5 | `docs/ADR/0004-bookmark-persistence-icons.md` | 122 | JSON/SharedPreferences、モノグラム、favicon 不採用 |
| 6 | `docs/ADR/0005-external-url-default-browser.md` | 134 | Intent 検証、ブックマークモード、RoleManager |
| 7 | `docs/ADR/0006-home-screen-shortcuts.md` | 149 | pinned + dynamic、Bitmap、無効化 |
| 8 | `docs/ADR/0007-adaptive-layout-ui-scale.md` | 185 | 幅クラス、列数、LocalDensity、インセット順序 |
| 9 | `docs/ADR/0008-localization.md` | 138 | ja/en、パリティテスト、LocaleManager / attachBaseContext |
| 10 | `docs/ADR/0009-test-qa-strategy.md` | 152 | L1〜L4、qa-server、エミュレータマトリクス |
| 11 | `docs/ADR/0010-release-signing-distribution.md` | 145 | 署名、keystore 管理、GitHub Releases |
| 12 | `docs/PLAN.md` | 598 | 目的・スコープ・アーキ・画面・モデル・状態遷移・セキュリティ・多言語・適応・テスト・リリース・リスク・M・WP |
| 13 | `docs/ACCEPTANCE_MATRIX.md` | 149 | AC-01〜AC-51 + 接尾辞付き（AC-02b/12b/12c/29b/41b、**計 56**） |
| 14 | `docs/IMPLEMENTATION-GAP.md` | 211 | AS-IS/TO-BE 差分 G-01〜G-18（CRITICAL 1 / HIGH 7 / MEDIUM 7 / LOW 3） |
| 15 | `docs/TO_FEEDBACK.md` | 本ファイル | フェーズ報告 |
| — | `docs/_workers/W-RESEARCH.md` | 457 | Worker 一次成果（証跡） |
| — | `docs/_workers/W-DESIGN.md` | 504 | Worker 一次成果（証跡） |

- `docs/adr/`（小文字・空）が存在したため、要件どおり **`docs/ADR/`** に正規化した。
- `app/`、`build.gradle.kts`、`AndroidManifest.xml`、`res/` には**本フェーズで一切書き込みをしていない**（作成・更新したのは `docs/` 配下のみ）。実装ファイルの変更は他プロセスによるもので、TO は内容を読んだだけ。

---

## 5. 主要な設計判断（要旨）

1. **WebView 採用**（要件が明示）。公式非推奨の `proceed()` を使うが、補償統制（既定=戻る、期限切れはピン不可、一覧・失効、事後検証）を必須化（ADR-0001/0002）。
2. **信頼の単位 = host + 証明書 DER SHA-256**。ポートは含めない。ホストあたり最大 8 証明書、全体 50。SPKI は表示のみ。
3. **「今回だけ」は保存しない**。ただし WebView 側の保持可能性があるため、`getCertificate()` による事後照合と、失効時の WebView 再生成で担保。
4. **認証キー = scheme+host+port+realm**。`http://` は自動入力しない。ループ防止は自前 epoch カウンタ（`useHttpAuthUsernamePassword` はゲートにしない）。
5. **新規依存を足さない**（幅クラスは `BoxWithConstraints` + 純粋関数）。テスト用の無料 OSS のみ追加。
6. **永続化は SharedPreferences + JSON**（既存 `Codecs`）。単一書き手 + `.bak` + 復旧モード。
7. **ショートカットはユーザー操作時のみ**。成功を断定しない（OS が拒否を通知しない）。
8. **言語は 33+ `LocaleManager` / 26–32 `attachBaseContext`**。文字列パリティを CI ゲートに。
9. **リリースは GitHub Releases のみ**。keystore は `common-rules-document`、リポジトリには証明書 SHA-256 のみ。

---

## 6. 受け入れ条件の達成状況

- **全 56 件が `NOT_TESTED`**。本フェーズは計画のみで、テストを実行していない（実行していないものを PASS と書かない）。
- 実装が既に存在するため、**コードレビューで判定できる AC**（AC-02b, AC-37, AC-46 の静的部）は次フェーズで即判定可能。
- 自動化可能: L1/L2 で 15 件、L3 で 2 件、S で 9 件。**残り 27 件は L4 手動**（SSL・認証・ショートカット・適応・署名）。「CI 緑＝リリース可」ではない。

---

## 7. 未決事項（メインに判断してほしい）

| # | 論点 | 選択肢 | TO の推奨 |
|---|---|---|---|
| Q1 | **G-01（`intent://` 任意コンポーネント起動）を今すぐ直すか** | (a) 即修正（`intent:` を起動しない） (b) リリースまでに修正 | **(a)**。30 分で直り、リスクは CRITICAL。修正までリリース不可 |
| Q2 | `proceed()` 保持の実測（AC-12b）を**出荷必須ゲート**にするか | (a) 必須 (b) 実測できなければ「保持される」前提で実装 | **(a)**。結果で Plan B の要否が決まる |
| Q3 | **`minSdk 26` を維持するか**（26–28 のピンは `saveState` フォールバックで対応可能だが、端末差リスクがある） | (a) 26 維持 + フォールバック (b) minSdk 29 に上げる | **(a)**。要件が 26。ただし API 26 エミュレータでの AC-12 を必須に |
| Q4 | `mixedContentMode` を **COMPATIBILITY_MODE**（現物）で確定してよいか（文書は NEVER_ALLOW だった） | (a) COMPATIBILITY（Chrome 相当） (b) NEVER_ALLOW（厳格） | **(a)**。機密性は「資格情報を http に送らない」で担保（G-09） |
| Q5 | `security-crypto` を **1.1.0 stable に更新**してよいか（非推奨 API のまま） | (a) 1.1.0 に固定 + 隔離 (b) Keystore 直実装へ今すぐ移行 | **(a)**。移行は 1.5〜2 人日。トリガを ADR-0003 に定義済み |
| Q6 | 計画文書と実装の**どちらを正**とするか（現物が先行している） | (a) 文書を TO-BE とし、実装を合わせる (b) 実装に合わせて文書を改訂 | **ケースバイケース**。セキュリティ（G-01/02/05/08）は文書を正、UI 詳細は実装を正として文書を改訂 |
| Q7 | 要件 9 のメニュー 9 項目を AC に分割するか | (a) AC-51 として追加 (b) 現状のまま | **(a)**。追跡性のため |

---

## 8. リスク（残存）

| # | リスク | 影響 | 次フェーズの扱い |
|---|---|---|---|
| R1 | WebView が `proceed` を保持し、証明書変更を検知できない | 高 | AC-12b で実測。事後検証で緩和済み |
| R2 | `security-crypto` の将来削除 | 中 | インターフェース隔離済み |
| R3 | API 26–28 の Bundle キー依存 | 中 | 2段フォールバック + AC-12 |
| R4 | サブリソース SSL の実挙動が不明 | 中 | AC-47 |
| R5 | ショートカット Bitmap のランチャー互換 | 中 | AC-28 + fallback |
| R6 | 2.1 倍スケールのレイアウト崩れ | 中 | AC-34 |
| R7 | 実装が計画から逸脱したまま進む | 中 | GAP 文書を TO-EXEC の必須入力にする |

---

## 9. 推奨ゲート判定

- **G1（計画）: `GO`** — 計画・ADR・AC・調査・WP 分割が揃い、相互参照が通っている。
- **G2（作業）: `REWORK`** — 実装に CRITICAL 1 / HIGH 7。特に G-01（`intent://`）と G-02（API 26–28 の指紋）は修正必須。修正後、AC-12b を実施してから G3 へ。
- **G3（レビュー）: 未実施** — 本フェーズでは独立レビューを起動していない（TO-PLAN の範囲外）。TO-REVIEW の起動を推奨。

---

## 10. 次フェーズへの引き継ぎ

**必読（この順）**:
1. [IMPLEMENTATION-GAP.md](IMPLEMENTATION-GAP.md) — 修正必須リスト（G-01〜G-18）
2. [PLAN.md](PLAN.md) §6（状態遷移） / §14（WP 分担）
3. [ACCEPTANCE_MATRIX.md](ACCEPTANCE_MATRIX.md) — AC-46〜AC-50 が今回追加
4. [ADR/0002](ADR/0002-self-signed-trust-tofu.md) / [ADR/0003](ADR/0003-credential-storage.md) — 変更点

**固定した判断（変更する場合は ADR 再起票）**:
- 信頼単位 = host + DER SHA-256（ポート含めず、ホストあたり最大 8）
- 認証キー = scheme+host+port+realm、http は自動入力しない
- 新規 UI 依存を追加しない（幅クラスは自前関数）
- ショートカットはユーザー操作時のみ、成功を断定しない
- リリースは GitHub Releases、Play 配信はしない

**注意すべき依存関係**:
- G-05（AuthKey 変更）は**保存データのマイグレーション**を伴う。既存 `host|realm` は `https` として読み替え、`http` へは引き継がない。
- G-02 の修正は `TrustPolicy` / `BrowserState` の両方に触る。単体テストを先に書く。
- G-01 の修正は `shouldOverrideUrlLoading` のテスト（L1 の `IntentRouter` 相当）とセットで。

**禁止・要承認**:
- git commit / push / リリース実行は**メイン**が行う。
- 新規の有料ツール・外部サービス・Play 配信は、ユーザー承認なしに開始しない。
