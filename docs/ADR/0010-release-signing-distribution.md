# ADR-0010: リリース・署名・配布

- 状態: **Accepted**（2026-10-31）
- 決定者: TO-PLAN（HB-PLAN-01）
- 関連: 要件 11 / ADR-0009 / AC-41〜AC-45

## Context

- リポジトリ: `oriyu90/half-browser`（public 予定、MIT）
- パッケージ: `com.yukiorita.halfbrowser`
- 配布先の希望: GitHub Releases（Web 公開）。Google Play での配信は要件に無い。
- 既存 `app/build.gradle.kts` は既に:
  - `signingConfigs.create("release")` で `KEYSTORE_PATH` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` を環境変数から読む
  - `STORE_PASSWORD` と `KEY_PASSWORD` の両方が設定されている場合のみ `release` に署名設定を適用
  - `isMinifyEnabled = true` / `isShrinkResources = true` / `proguardFiles(...)`
  - `keystore/` ディレクトリは存在するが空
- `tools/` も空。

決めるべきこと: バージョニング、keystore の管理、署名検証の自動化、配布物の形式、秘密の取り扱い、Web 公開物。

## Decision

### 1. バージョニング

- `versionName`: セマンティックバージョニング `MAJOR.MINOR.PATCH`（初期 `1.0.0`）。
- `versionCode`: 単調増加の整数。**`MAJOR * 10000 + MINOR * 100 + PATCH`**（例 `1.0.0` → `10000`、`1.2.3` → `10203`）。現在の `versionCode = 1` は初回リリース時に `10000` へ更新する。
- プリリリースは `versionName = "1.1.0-beta01"`、`versionCode` は安定版と同値にせず `+50`（例 `10150`）して**安定版が必ず上になる**ようにする。

### 2. keystore と秘密の管理

| 項目 | 方針 |
|---|---|
| keystore ファイル | `keystore/release.jks`。**リポジトリにコミットしない**（`.gitignore` に `keystore/*.jks`, `*.keystore`）。 |
| パスワード | 環境変数 `STORE_PASSWORD` / `KEY_PASSWORD`。**コード・Gradle ファイル・ドキュメントに書かない**。 |
| 正本の保管 | **`common-rules-document`（別管理の秘密管理文書）**に、keystore のパス・エイリアス・パスワード・作成日・SHA-256 を記録する。本リポジトリには**署名証明書の SHA-256 のみ**を `docs/RELEASE.md` に記載する（公開情報であり、検証に必要）。 |
| バックアップ | keystore は**最低2箇所**（暗号化したクラウドストレージ + オフラインメディア）。紛失すると**同一 applicationId での更新が不可能**になる。 |
| CI | v1 では**リリースビルドを CI に載せない**（秘密を GitHub Secrets に置く運用は、個人プロジェクトでは漏洩面が増える）。ローカルで `assembleRelease` し、生成物を手動でアップロードする。 |
| 漏洩時 | ただちに keystore をローテーションし、**applicationId を変更**（`com.yukiorita.halfbrowser2` 等）して新規アプリとして配布。旧アプリの更新は打ち切る旨を README に告知。 |

### 3. リリース手順（`docs/RELEASE.md` に詳細を置く）

```bash
# 1. 事前確認
./gradlew clean testDebugUnitTest lintDebug          # 全件成功・lint 0 errors
./gradlew assembleRelease                             # STORE_PASSWORD / KEY_PASSWORD を export 済みであること

# 2. 署名検証（必須）
$ANDROID_HOME/build-tools/<ver>/apksigner verify --verbose --print-certs \
    app/build/outputs/apk/release/app-release.apk
#   → "Verified using v2 scheme: true" / "v3 scheme: true" を確認
#   → 証明書 SHA-256 が docs/RELEASE.md の記録と一致することを確認

# 3. 成果物とチェックサム
sha256sum app-release.apk > SHA256SUMS.txt

# 4. GitHub Releases
gh release create v1.0.0 app-release.apk SHA256SUMS.txt \
   --title "Half-Browser 1.0.0" --notes-file docs/release-notes/1.0.0.md
```

- リリースノートは**日本語 + 英語**の併記（対象読者に合わせる）。
- 添付物: `app-release.apk`、`SHA256SUMS.txt`、任意で `mapping.txt`（R8 の難読化マップ。クラッシュ解析用。**公開すると内部構造が露出するため、v1 では添付しない**）。
- 署名スキーム: AGP の既定（v1/v2/v3）。`minSdk 26` なので v1 は必須ではないが、AGP が付ける場合はそのまま。

### 4. 配布とインストール

- GitHub Releases の APK を**サイドロード**する。ユーザーは「提供元不明のアプリ」の許可が必要（README に手順を書く）。
- Google Play は**使わない**。理由: (a) 開発者アカウントと審査のコスト、(b) **自己署名証明書の `proceed()` は Play のポリシー審査で説明を求められる可能性が高い**（セキュリティ機能の回避に見えるため）。要件は GitHub Releases であり、審査リスクを負う理由が無い。
- F-Droid も v1 では対象外（再現可能ビルドとメタデータの要件がある）。将来検討。

### 5. Web 公開物

| 物 | 場所 | 内容 |
|---|---|---|
| README | リポジトリ直下 | 概要、スクリーンショット、インストール手順、**自己署名証明書の信頼に関する警告**、既定ブラウザの OS 制約の説明 |
| プライバシーポリシー | `docs/PRIVACY.md` + GitHub Pages | 収集する情報が**無い**こと（解析 SDK・広告・クラッシュレポータを入れない）、認証情報は端末内で暗号化、外部送信なし |
| リリースノート | `docs/release-notes/<version>.md` | 日英併記 |
| ライセンス | `LICENSE`（MIT） | 開発者: 折田悠希 / Yuki_Orita |

- GitHub Pages は `docs/` をそのまま公開しない（設計文書が混ざる）。`site/` ディレクトリを作り、そこだけを Pages のソースにする（実装フェーズ）。

### 6. リリース前チェックリスト（AC-41〜AC-45）

1. `./gradlew testDebugUnitTest` 全件成功
2. `./gradlew lintDebug` **0 errors**
3. `./gradlew assembleRelease` 成功
4. `apksigner verify --print-certs` 成功、SHA-256 が記録と一致
5. `aapt2 dump badging` で `package: name='com.yukiorita.halfbrowser' versionCode='10000' versionName='1.0.0'`、`application-debuggable` が**付かない**こと
6. release APK を実機/エミュレータにインストールし、起動・ページ表示・SSL インタースティシャル・認証・ショートカットを確認（L4 の抜粋）
7. `docs/PRIVACY.md` と README の警告文が最新
8. `SHA256SUMS.txt` を生成し、Release に添付

## Consequences

### 利点

- 秘密がリポジトリに入らない（既存の環境変数方式を維持）。
- 署名検証を**手順として必須化**し、証明書 SHA-256 を公開記録と照合できる。
- Play の審査リスクを避け、要件どおり GitHub Releases で完結する。

### 欠点・リスク

| # | リスク | 対策 |
|---|---|---|
| R1 | keystore 紛失 = 更新不能 | 2箇所バックアップ、`common-rules-document` に記録、README に「更新には同一鍵が必要」と明記 |
| R2 | サイドロードの心理的障壁 | README に手順とスクリーンショット、SHA-256 の検証方法を記載 |
| R3 | 難読化で WebView のコールバックが壊れる | 既存 `proguard-rules.pro` の keep 設定を維持し、release ビルドで L4 の主要項目を再確認（AC-43） |
| R4 | `versionCode` の単調増加を破る | リリースチェックリストで `aapt2 dump badging` を確認 |
| R5 | リリースノートの日英併記漏れ | テンプレートを `docs/release-notes/TEMPLATE.md` に置く（実装フェーズ） |

### 実装上の帰結

- `docs/RELEASE.md`（手順 + 証明書 SHA-256 の記録）、`docs/PRIVACY.md`、`LICENSE`、`README.md`、`site/` を実装フェーズで追加。
- `.gitignore` に `keystore/*.jks` / `*.keystore` / `local.properties` / `build/` / `.gradle/` を追加（現状 `.gitignore` が無い）。
- `app/build.gradle.kts` の署名条件（両方の環境変数が揃った時のみ署名）は**そのまま維持**。誤って未署名の release APK を配布しないよう、チェックリストで `apksigner verify` を必須にする。

## Alternatives

### A. GitHub Actions で署名して自動リリース

- 利点: 再現性・省力化。
- 欠点: keystore とパスワードを GitHub Secrets に置く必要があり、リポジトリ侵害時の影響が大きい。個人プロジェクトでは手動の方が統制しやすい。
- 不採用（v1）。将来、CI の権限を絞ったうえで再検討。

### B. Play App Signing を使う

- 利点: 鍵管理を Google に委任できる。
- 欠点: Play 配信が前提。要件外。審査リスク。
- 不採用。

### C. デバッグ署名で配布

- 利点: 簡単。
- 欠点: **更新時に署名が変わり、上書きインストールできない**。セキュリティ上も不適切。
- 不採用（論外）。

### D. APK ではなく AAB で配布

- 利点: Play 向け。
- 欠点: サイドロードに不向き（`bundletool` が必要）。GitHub Releases の読者層に合わない。
- 不採用。

## Status

**Accepted**。配布は GitHub Releases の APK のみ。Play 配信は将来の別 ADR とする。
