# ADR-0021: Always-on VPN の復元（v1.7.1）

- 日付: 2026-11-10（v1.7.1）
- 状態: 採用
- 関連: ADR-0018（アプリ内 WireGuard VPN）、ADR-0020（QR コード取り込み）

## 文脈

v1.7.0 まで、Android の「Always-on VPN」（起動時／接続イベントでシステムが
`VpnService` を自動起動する機能）を有効にしても、Half-Browser のトンネルは実際には
立ち上がらなかった。WireGuard の `GoBackend` は、システム起動パスでは
`setAlwaysOnCallback` に登録されたコールバックを呼び出してアプリ側に復元を委ねるが、
Half-Browser はこのコールバックを一度も登録していなかった。結果として：

- Always-on を有効 + ロックダウン（「VPN なしの接続をブロック」）を有効にすると、
  再起動後に端末全体がオフラインになる（トンネルが無いまま VPN ネットワークだけが
  存在する）。
- ロックダウンなしでは「保護されている」と誤表示されるだけで、実際のトラフィックは
  トンネルを通らない。

これは接続の正しさに関わる欠陥であり、v1.7.1 で修正する。

## 決定

1. **`GoBackend.setAlwaysOnCallback` を `HalfBrowserApplication` で登録する。**
   コールバックは `VpnController.onAlwaysOnTriggered()` を呼ぶ。登録はアプリケーション
   生成時に行い、`AppContainer` が `settings` を `VpnController` に渡せるよう依存を
   先に組み立てる。
2. **復元ロジックは純粋関数 `VpnAlwaysOn.decide()` に切り出す。** I/O を持たず単体
   テスト可能にする。入力は (a) コントローラが既に active か、(b) 設定で選択中の
   プロファイル ID、(c) 現在見えるプロファイル一覧、(d) 暗号化ストアが永続化可能か。
   - 既に active → 何もしない（`Stop`）。
   - 選択プロファイルが一覧に存在 → `Connect`（復元）。
   - ストアが読めなかった（空 + 非永続）→ `Retry`（Keystore がアンロックされるのを待つ）。
   - それ以外 → `Stop`。
3. **Keystore 未アンロックへの対処としてリトライ／バックオフを持つ。**
   `RETRY_DELAYS_MS = {0, 5s, 15s, 30s, 60s}`（合計約 2 分）で再読み込みを試み、
   それでも読めなければ諦める（次のアプリ起動時の in-app 自動接続に委ねる）。
   システムがサービスを生かし続けるため、待つだけで十分。
4. **Always-on は in-app の「自動接続」スイッチとは独立に扱う。** システムが
   Always-on を有効化したことが権威であり、アプリ側の自動接続設定が OFF でも復元する。
   逆に Always-on が無効な場合は従来どおり in-app の自動接続に従う。
5. **復元対象は Settings で選択中のプロファイル。** 復元時に `connect(profile)` を
   呼び、`VpnScope` による allow-list 書き換えや同意確認など既存の接続経路を再利用する。
   接続不能（同意が必要など）の場合は失敗状態として記録し、システム側の再試行に委ねる。

## 代替案

- **起動時に in-app 自動接続だけに頼る**（不採用）: システムの Always-on はアプリの
  UI を経由せずサービスを起動するため、アプリ側の自動接続コードが走らない。Always-on
  有効ユーザーでは機能しない。
- **Always-on コールバック内で直接 `connect()` を呼ぶ**（不採用）: Keystore 未アンロック
  でプロファイルが読めない場合に失敗が確定し、ロックダウン有効時に端末がオフラインの
  ままになる。リトライで対処する。
- **ロックダウンを強制オフにする**（不採用）: ユーザーの選択を奪う。正しく復元すれば
  ロックダウンは安全に使える。

## 検証

- JVM: `VpnAlwaysOnTest`（決定表 10 ケース）+ 既存 173 件 = **176 件 / 失敗 0**。
- lint: `lintDebug` Error 0。
- 署名付き release ビルド（R8）: 成功。証明書 SHA-256 `36153aed…22a0f37`（v1.6.1 と同一鍵）。
- エミュレータ（API 36.1、Medium_Phone_API_36.1）:
  - Always-on を有効化 → 再起動 → `dumpsys connectivity` に `VPN CONNECTED`（Half-Browser、
    `Uids: {10224-10224, 20224-20224}`）が自動復元されることを確認。
  - 実 WireGuard 対向（colima 上の Docker WG サーバー、UDP 51820 を gRPC ポートフォワーダで
    ホストへ公開）との実 E2E ハンドシェイクを確認: `wg show` の `latest handshake` が
    非ゼロ、転送カウンタが増加、WebView で `http://10.8.0.1:8000` が
    `HALF-BROWSER-TUNNEL-E2E-OK` を表示。
  - 許可リスト外の UID（shell, uid 2000）からの `10.8.0.1:8000` 到達はタイムアウト
    （他アプリがトンネル外に留まることを確認）。
  - FATAL EXCEPTION 0 件。
- 16KB ページサイズ整合（ELF + zip）、minSdk 26、4 ABI、MTU フォールバック（1280）、
  R8 ルール、QR 取り込み経路、`onRevoke` 処理、再接続の 600ms ティアダウン待機は
  v1.7.0 の検証を継承し変更なし。

## 未検証・残課題

- 実機（非エミュレータ）での Always-on 復元とハンドシェイク確認は引き続き残課題。
  エミュレータのユーザーネットワーク環境では UDP が NAT され、colima の gRPC
  ポートフォワーダを介してのみ到達できた（公式 WireGuard アプリでも同等の制約）。
- Keystore 未アンロック時のリトライ挙動（再起動直後）はロジック上は対応済みだが、
  実機での実測は未実施。