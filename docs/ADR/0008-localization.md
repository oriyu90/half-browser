# ADR-0008: 多言語対応（日本語 / 英語）とアプリ内言語切替

- 状態: **Accepted**（2026-10-31）
- 決定者: TO-PLAN（HB-PLAN-01）
- 関連: 要件（UI は日本語と英語の完全対応）、要件 11 / TECH-RESEARCH §8 / AC-31, AC-32

## Context

要件は「UI は**日本語と英語の完全対応**（全文字列を resources 化、ハードコード禁止）」であり、開発者は日本語話者、対象読者も日本語ユーザー。既存状態:

- `app/src/main/res/values/strings.xml` は `app_name` のみ（英語）
- `app/src/main/res/values-ja/strings.xml` は `app_name` のみ（日本語）
- `app/build.gradle.kts` に `resourceConfigurations += listOf("en", "ja")`
- `res/xml/locales_config.xml` に en / ja
- アプリは `ComponentActivity` + Compose（**AppCompat 無し**）
- `MainActivity` は文字列をハードコードしている（`Text("Half-Browser")`）＝撤去対象

言語切替の選択肢:

1. `AppCompatDelegate.setApplicationLocales`（AppCompat 必須）
2. `LocaleManager.setApplicationLocales`（API 33+）
3. `attachBaseContext` で `Configuration` を差し替え（全 API）
4. アプリ内で `Resources` を都度生成（非推奨）

## Decision

### 1. 文字列は 100% resources 化する（既定 = 英語、`values-ja` = 日本語）

- `values/strings.xml` を**英語の正**とする（Android の既定ロケール挙動に合わせる）。
- `values-ja/strings.xml` に日本語を置く。**キー集合は完全一致**させる。
- ハードコード禁止の対象:
  - Compose の `Text("...")`、`contentDescription = "..."`、`placeholder = "..."`、Snackbar 文言、Toast、通知、エラーメッセージ
  - `AlertDialog` のボタン
  - 例外メッセージをそのまま UI に出すこと（**禁止**。必ず `stringResource` の汎用文言に写像）
- 数値・日付の書式は `Locale` を明示（フィンガープリントの hex は `Locale.ROOT`、日付は `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(currentLocale)`）。
- 文字列 ID の命名: `<画面>_<要素>`（例 `ssl_title`, `ssl_trust_always`, `auth_save_checkbox`, `settings_ui_scale`）。接頭辞: `ssl_` / `auth_` / `home_` / `bookmark_` / `settings_` / `menu_` / `error_` / `common_`。

### 2. アプリ内言語切替（AppCompat を入れない）

| API | 実装 |
|---|---|
| **33+** | `context.getSystemService(LocaleManager::class.java).setApplicationLocales(LocaleList.forLanguageTags("ja"))`。OS が永続化し、Activity を再生成する。アプリ内設定画面は `getApplicationLocales()` を読んで現在値を表示（**システム設定が真実の源**） |
| **26–32** | `BaseActivity.attachBaseContext(newBase)` で `Configuration` に `LocaleList` を設定した `Context` を返す。設定変更時は保存 → `recreate()` |
| 共通 | `LanguageMode.SYSTEM` は「OS の言語に従う」。33+ では `setApplicationLocales(LocaleList.getEmptyLocaleList())`、26–32 では `attachBaseContext` で何もしない |

```kotlin
// 概念コード
override fun attachBaseContext(newBase: Context) {
    val mode = LocaleStore.readMode(newBase)          // 軽量 SharedPreferences 直読み（DI 不可）
    if (Build.VERSION.SDK_INT >= 33 || mode == LanguageMode.SYSTEM) {
        super.attachBaseContext(newBase); return
    }
    val locale = Locale.forLanguageTag(mode.tag)
    val config = Configuration(newBase.resources.configuration)
    config.setLocales(LocaleList(locale))
    config.setLayoutDirection(locale)
    super.attachBaseContext(newBase.createConfigurationContext(config))
}
```

**注意点（実装で守る）**:

1. `attachBaseContext` は DI グラフ初期化より前に走る。**`LocaleStore` は `SharedPreferences` を直接読む**（Hilt/Koin に依存しない）。既存 `Codecs.decodeSettings` を使って `AppSettings.language` を読む。
2. `applicationContext` の `Resources` は切り替わらない。UI では `stringResource` / `LocalContext.current.getString` を使い、`applicationContext.getString` を UI に使わない。
3. API 33+ では `LocaleManager` と `AppSettings.language` の**二重管理をしない**。設定画面の表示は `getApplicationLocales()` を優先し、`AppSettings` は 26–32 のためのバックアップとする（33+ でも保存するが、起動時は `LocaleManager` を優先）。
4. 言語変更時は `recreate()`。WebView の現在 URL・スクロール位置・ブックマークモードを `SavedStateHandle` に保存して復元する（AC-30）。
5. process death 後は `LocaleManager`（33+）／`AppSettings`（26–32）から復元。
6. `localeConfig` と `resourceConfigurations` は既存設定を維持。`resourceConfigurations` に無い言語の端末では英語にフォールバックする（意図どおり）。

### 3. 文字列パリティテスト（JVM、AC-32）

`StringParityTest`（`app/src/test/`）:

1. `src/main/res/values/strings.xml` と `src/main/res/values-ja/strings.xml` を `javax.xml.parsers.DocumentBuilder` で読む。
2. **キー集合の完全一致**を検証（片方にしか無い ID があれば fail）。
3. **フォーマット指定子の多重集合一致**を検証（`%1$s`, `%2$d`, `%d` など。順序・個数の食い違いは実行時 `IllegalFormatException` の原因）。
4. `<plurals>` の `quantity` 集合一致。
5. 空文字列・空白のみの値を fail。
6. 値に `<` `&` などの生記号が無いこと（XML エスケープ漏れ）。
7. **ハードコード検出（簡易）**: `src/main/java/**/*.kt` を走査し、`Text("...")` / `contentDescription = "..."` の直後に**日本語文字（ひらがな・カタカナ・漢字）**が含まれるものを fail。英語のハードコードは誤検知が多いため、日本語のみを機械検出対象とし、英語はコードレビューで担保する（現実的な線引き）。

- テストの作業ディレクトリは Gradle の unit test 実行時のモジュールディレクトリ（`app/`）。`File("src/main/res/...")` で解決できる。解決できない場合は `System.getProperty("user.dir")` をログに出して fail（黙って skip しない）。

### 4. 翻訳品質

- 日本語は「です・ます」調で統一。技術用語（Cookie、JavaScript、SHA-256、Basic 認証）はカタカナ／原語のまま。
- 英語は sentence case、ボタンは動詞（"Add", "Cancel", "Trust always"）。
- ラベルの長さ: 英語は日本語より 1.5〜2 倍長くなる。ボタンは `maxLines=2` を許容し、`ellipsis` で切れないか AC-33 で目視確認。
- アクセシビリティ: アイコンのみのボタンに `contentDescription` を必ず付ける（日英両方）。

## Consequences

### 利点

- 要件の「完全対応」を**テストで強制**できる（パリティテストが無いと必ず抜ける）。
- AppCompat を入れないため、依存と APK サイズを増やさない。
- API 33+ では OS の per-app language 設定と統合され、ユーザーの混乱が少ない。

### 欠点・リスク

| # | リスク | 対策 |
|---|---|---|
| R1 | 26–32 の `attachBaseContext` 方式は Application context に効かない | UI は Activity context を使う規約 + レビュー観点 |
| R2 | 33+ と 26–32 で挙動が異なる（再生成のタイミング、システム設定との同期） | 両方のエミュレータで AC-31 を実施 |
| R3 | `recreate()` で WebView が失われる | URL/スクロール/モードを `SavedStateHandle` に保存して復元 |
| R4 | 文字列パリティテストは「キーと書式」しか見ない。翻訳の意味の正しさは保証しない | レビューで日本語ネイティブ（開発者本人）が確認 |
| R5 | ハードコード検出が日本語のみ | 英語は lint の `HardcodedText`（Compose では効かない）に頼れないため、コードレビュー必須。将来はカスタム lint を検討 |

### 実装上の帰結

- `BaseActivity`（`ComponentActivity` を継承）+ `MainActivity : BaseActivity`。
- `LocaleStore`（軽量、`SharedPreferences` 直読み）を `core` に置く。
- `SettingsScreen` の言語選択は 3 択（システム / 日本語 / English）。切替時に 33+ は `LocaleManager`、26–32 は保存 + `recreate()`。
- `MainActivity` のハードコード文字列を撤去する（実装フェーズの最初のコミット）。

## Alternatives

### A. AppCompat を導入して `AppCompatDelegate.setApplicationLocales`

- 利点: 26–32 でも公式の後方互換 API が使える。実装が一本化される。
- 欠点: `AppCompatActivity` + AppCompat テーマへの移行が必要（現行テーマは `android:Theme.Material.*`）。依存と APK サイズ増。Compose のみのアプリに AppCompat を足すのは冗長。
- 不採用。ただし 26–32 の実装が破綻した場合の**フォールバック候補**として記録。

### B. 言語切替を提供せず OS 設定のみ

- 利点: 実装ゼロ。
- 欠点: 要件（アプリ内言語切替の示唆）と、`locales_config` を置いている意図に反する。26–32 では per-app language の UI が無い。
- 不採用。

### C. アプリ内で `Resources` を都度生成して `stringResource` を差し替え

- 利点: Activity 再生成が不要。
- 欠点: Compose の `stringResource` は `LocalContext` に依存するため、Context ラッパーを全階層に配る必要があり、`pluralStringResource` や `LocalConfiguration` の整合も崩れる。
- 不採用。

## Status

**Accepted**。文字列キーの総数は実装完了時に 150〜200 を見込む。パリティテストを CI の必須ゲートにする。
