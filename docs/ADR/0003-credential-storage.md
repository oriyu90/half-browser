# ADR-0003: HTTP 認証情報の保存（Keystore + EncryptedSharedPreferences）と自動入力ループ対策

- 状態: **Accepted**（2026-10-31）
- 決定者: TO-PLAN（HB-PLAN-01）
- 関連: 要件 3, 10 / TECH-RESEARCH §3 / ADR-0002 / AC-22〜AC-27

## Context

要件 3 は、HTTP Basic / Digest 認証で保護されたページに対して:

1. ユーザー名／パスワードを入力でき、「保存」できる
2. 保存済みなら自動入力される
3. **Android Keystore + EncryptedSharedPreferences で暗号化保存**する
4. 設定から一覧・削除できる
5. 自動認証の無限ループを防ぐ（同一ホスト/レルムで連続失敗したら手動入力に切り替える）

を求める。技術的な制約は:

- `onReceivedHttpAuthRequest(WebView, HttpAuthHandler, host, realm)` は**既定でキャンセル**。`proceed(user, pass)` は **UI スレッド必須**（`HttpAuthHandler` は `Handler` のサブクラス）（TECH-RESEARCH §3.1）。
- `useHttpAuthUsernamePassword()` は「保存済み資格情報が**以前拒否された**なら false」を返す、プラットフォーム標準のループ防止信号（§3.1）。
- `androidx.security:security-crypto` は **1.1.0-beta01 で全 API 非推奨**（§3.4）。ただし要件が EncryptedSharedPreferences を明示している。
- 既存 `backup_rules.xml` / `data_extraction_rules.xml` は `halfbrowser_secure.xml` をバックアップから除外済み。

## Decision

### 1. 保存層は `CredentialVault` インターフェースで隔離する

```kotlin
interface CredentialVault {
    suspend fun list(): List<CredentialMeta>                  // 秘密を含まない
    suspend fun secretFor(key: AuthKey): StoredSecret?        // username + password
    suspend fun save(key: AuthKey, username: String, password: String): Result<Unit>
    suspend fun delete(id: String)
    suspend fun deleteAll()
}
```

- 実装 `EncryptedPrefsCredentialVault` が `EncryptedSharedPreferences`（`MasterKey` AES256-GCM）を使う。
- **`@Suppress("DEPRECATION")` を許すのはこの実装ファイル1つだけ**。他コードはインターフェース越しに触る。
- 依存は `androidx.security:security-crypto` を **`1.1.0`（stable）に更新**する。現行 `1.1.0-alpha06` は 2023 年のビルドで、非推奨化前の alpha。

### 2. 保存ファイルとキー設計

- ファイル名: **`halfbrowser_secure`**（`SharedPreferences` の実ファイルは `halfbrowser_secure.xml`）。既存のバックアップ除外設定と一致させる。
- キー: `credential_<id>`（`id` は `AuthKey` の SHA-256 先頭16バイト hex）。値は JSON `{"u":"...","p":"..."}`。
- **ユーザー名も暗号化対象**にする（ユーザー名は PII であり、平文インデックスを作らないため）。一覧表示時に復号する（件数は高々数十件で、起動時1回のコストは無視できる）。
- `AuthKey`:

```kotlin
data class AuthKey(val scheme: String, val host: String, val port: Int, val realm: String)
```

  - `host` は小文字化。`port` は URL から（既定 80/443 を明示値に正規化）。
  - `realm` は空文字を許容（サーバーが realm を返さない場合がある）。
  - **スキームとポートを含める**理由: `http://host` と `https://host` は別の資格情報であり、ポートが違えば別サービスである可能性が高い。ADR-0002 のピン（ホスト単位）とは**意図的に粒度を変える**（資格情報はより慎重に）。

### 3. 自動入力の状態機械（無限ループ対策）

```
onReceivedHttpAuthRequest(host, realm)
  ├─ handler を保留（1件のみ）。既に保留中なら新規は cancel()
  ├─ key = AuthKey(scheme, host, port, realm)
  ├─ vault.secretFor(key) が無い        → 手動入力ダイアログ
  ├─ tracker.autoFillAllowed(key) == false → 手動入力ダイアログ（理由を表示）
  ├─ scheme != https ───────────────────────→ 手動入力（自動入力しない。警告表示）
  └─ それ以外 → 自動 proceed(user, pass) + tracker.onAutoProceed(key)
```

`AuthAttemptTracker`（**純粋クラス・JVM テスト対象**）:

| 事象 | 遷移 |
|---|---|
| `onAutoProceed(key)` | `attempts[key] += 1` |
| `onAuthSucceeded(url)`（メインフレームのロード完了） | 同一 host の `attempts` を 0 にリセット |
| `onAuthFailed(key)`（自動 proceed 直後に同一 key で再要求） | `failures[key] += 1` |
| `autoFillAllowed(key)` | `failures[key] < 2` かつ `!suppressed.contains(key)` |
| `failures[key] >= 2` | `suppressed += key`（セッション中は自動入力を禁止） |

- **閾値は 2**（1回目の失敗は打ち間違いや保存値の古さを許容、2回目で自動を止める）。
- 「セッション中」= アプリプロセス生存中。永続化しない（再起動で復活してよい。ユーザーが明示的に再開する操作だから）。
- 失敗検知は「自動 proceed の直後、**同一ナビゲーション epoch 内**に**同一 key** で再び `onReceivedHttpAuthRequest` が来た」ことをもって行う。成功ロード・新規ナビゲーション・手動ログイン成功でカウンタをリセットする。
- **`useHttpAuthUsernamePassword()` はゲートに使わない。** この API は「WebView／WebViewDatabase 側に保存された資格情報が拒否済みか」を返すものであり、本アプリのように EncryptedSharedPreferences へ自前保存する構成では戻り値の意味が不定（常に true/false になり得る）。ゲートにすると**自動入力が永久に無効化される**危険があるため、補助ログに留める（TECH-RESEARCH §3.1、W-DESIGN F-07）。
- `suppressed` のキーには**ポートとスキームを含む**（別ポートの別サービスを巻き添えにしない）。

### 4. ダイアログの仕様

| 項目 | 仕様 |
|---|---|
| 表示内容 | ホスト、レルム（空なら「既定」）、ユーザー名、パスワード（マスク、表示切替）、「保存する」チェック |
| 「保存する」の既定 | **ON**（要件 3 の「保存できる」を素直に満たす）。ただし `http://` の場合は **OFF** かつ警告文を表示 |
| 平文警告 | `http://` のとき「この接続は暗号化されていません。パスワードが第三者に読まれ得ます」 |
| ボタン | 「送信」「キャンセル」 |
| タイムアウト | 120 秒で自動 `cancel()`（保留ハンドラの解放） |
| 二重送信防止 | `AtomicBoolean` で handler を一度だけ消費。送信後は即ボタン無効化 |
| 失敗時 | ダイアログを閉じずに開いたまま、エラー文言を表示（再入力可能） |

### 5. 秘密の取り扱い

- **ログ禁止**: `CredentialMeta` / `StoredSecret` / `AuthKey` を `Log.*` / `println` / クラッシュレポータに渡さない。レビュー観点として AC-27 で grep 検査する（`grep -rn "CredentialMeta\|StoredSecret" --include=*.kt | grep -i log`）。
- `CredentialMeta` は既存の data class（`username` を含む）なので、`toString()` に秘密が入らないよう**ラッパー型 `StoredSecret` を別に用意**し、`CredentialMeta` をログに渡さない規約を CODEOWNERS 的に守る。将来 `@Sensitive` 相当の lint を追加する余地を残す。
- 画面のスクリーンショット防止は**しない**（ユーザーが自分のパスワードを見せる操作を妨げない）。ただし `FLAG_SECURE` は付けない代わりに、パスワード欄の既定はマスク。
- バックアップ: `halfbrowser_secure.xml` を cloud-backup / device-transfer の両方から除外（既存設定を維持）。**DPC や adb backup は対象外**（Android 12+ では adb backup は無効）。

### 6. 鍵が使えなくなった場合のフェイルセーフ

`MasterKey` の生成・復号は次の例外を投げ得る: `GeneralSecurityException`, `IOException`, `KeyStoreException`, `InvalidProtocolBufferException`（Tink）。

- 起動時または保存時にこれらを捕捉したら、**`halfbrowser_secure` を全消去**し、`CredentialVault` を「空・使用不可」状態にする。
- UI には「保存済みの認証情報を読み出せませんでした。端末のロック解除方法を変更した可能性があります。再度保存してください」と表示。
- **クラッシュさせない**（要件 10 のクラッシュ安全性）。AC-26。

## Consequences

### 利点

- 要件 3 を文字通り満たす（Keystore + EncryptedSharedPreferences）。
- 非推奨 API の影響を1ファイルに閉じ込め、将来の Keystore 直実装へ無痛で移行できる。
- ループ防止が「プラットフォーム信号 + アプリ内カウンタ」の二重になり、片方が壊れても無限ループしない。
- 資格情報のスコープが `scheme+host+port+realm` と狭く、別サービスへの漏出が起きにくい。

### 欠点・リスク

| # | リスク | 対策 |
|---|---|---|
| R1 | `security-crypto` は非推奨。将来削除される可能性 | インターフェース隔離 + 1.1.0 固定 + 移行 ADR の余地 |
| R2 | **Digest は平文パスワードを保存せざるを得ない**（ハッシュ計算に必要） | 保存は暗号化。`http://` では保存既定 OFF |
| R3 | 端末のロック解除方法変更で鍵が無効化される | §6 のフェイルセーフ（全消去 + 再入力促し） |
| R4 | ユーザーが「保存」を押した資格情報が、別の（同名ホストの）サービスに送られる | キーにポートを含める。`onReceivedHttpAuthRequest` の host は**認証を要求したホスト**であり、トップレベル URL と異なる場合がある（サブリソース）。**メインフレームのホストと一致しない場合は自動入力をしない**（追加防御） |
| R5 | 自動入力によりユーザーが「どの資格情報が送られたか」を意識しない | 自動送信時にツールバーへ鍵アイコンを表示し、タップで「このサイトに資格情報を送信しました」と確認できるようにする（v1 ではログのみ、UI は v1.1） |

### 実装上の帰結

- `HttpAuthHandler` の保持は `SslTrustCoordinator` と同じ `ViewModel` スコープで管理し、`onCleared` で `cancel()`。
- `proceed()` は必ず `Dispatchers.Main.immediate` 上で呼ぶ（`HttpAuthHandler` が `Handler` のため）。`withContext(Dispatchers.IO)` の中で呼ばない。
- 保存（暗号化）は IO ディスパッチャ、ダイアログ表示は Main。

## Alternatives

### A. 保存しない（毎回入力）

- 利点: リスクゼロ、実装最小。
- 欠点: 要件 3 の「保存」「自動入力」を満たさない。
- 不採用。

### B. Android Keystore 直接 + 自前 AES-GCM ラッパ

- 利点: 非推奨 API を使わない。Google の推奨方向。依存削減（Tink を落とせる）。
- 欠点: **要件が EncryptedSharedPreferences を明示**している。自前実装は IV 管理・AAD・鍵ローテーション・マイグレーションを自分で背負うことになり、脆弱性を作り込むリスクが高い。
- 判断: **v1 では不採用**。ただし `CredentialVault` の別実装として温存し、`security-crypto` の削除アナウンスが出た時点で切替える（ADR-0003 の将来改訂）。

### C. `WebViewDatabase` の HTTP 認証キャッシュに任せる

- 利点: 実装ゼロに近い。
- 欠点: 保存内容を一覧・削除できず（`clearHttpAuthUsernamePassword()` は全消去のみ）、要件 3 の「一覧・削除」を満たさない。暗号化の保証も不明。
- 不採用。ただし**併用はする**（`useHttpAuthUsernamePassword()` の信号として）。

### D. 生体認証（BiometricPrompt）で保存を保護

- 利点: 端末を他人が触った場合の防御。
- 欠点: 認証のたびに生体認証を要求すると、ブラウザの自動入力が実用にならない。要件にも無い。
- 不採用（将来オプションとして検討）。

## Status

**Accepted**。`security-crypto` の非推奨化は既知の負債として記録し、四半期ごとにリリースノートを確認する。
