# ADR-0022: VPN の疎通検証・復帰時再接続・ホーム画面の状態表示（v1.8.0）

- 日付: 2026-11-11 / 状態: 採用（v1.8.0）
- 関連: ADR-0018（アプリ内 WireGuard VPN）、[計画](../plan/v1.8.0-vpn-reliability.md)

## 背景

v1.6.1 の VPN には次の 3 つの問題があった。

1. VPN を接続して VPN 先のサイトを開くとタイムアウトする。
2. スリープ等の後、UI は「接続中」なのに VPN 先へ繋がらない。アプリを開き直すと直る。
3. ホーム画面で VPN の接続状態が分からない。

原因（v1.6.1 のコードを読んだ結論）:

- `VpnState.Connected` は `GoBackend.setState(UP)` の成功、つまり **TUN デバイス作成** だけを
  意味していた。WireGuard のハンドシェイクは非同期で、失敗しても状態は Connected のまま。
  サーバー停止・UDP 遮断・鍵不一致・NAT の失効はすべて「接続中」と表示されていた。
- `PersistentKeepalive` を補完しておらず、アイドル状態のトンネルは NAT マッピングが消える
  と次のパケットが届かない（症状 2 の主因）。
- `MainActivity` に `onStart` の検証が無く、ハンドシェイクの鮮度を読む処理も無かった。
- `vpnAutoConnect` の既定値が `false` で、アプリを開き直しても再接続しなかった。
- タイムアウト時に VPN を疑う案内・再接続の導線が無かった。

## 決定

1. **トンネル状態と疎通を分離する（`VpnState` と `VpnReachability`）。**
   - 疎通は `Backend.getStatistics(tunnel)` の `latestHandshakeEpochMillis` から算出する。
   - `VpnHealthRules.classify`（純粋関数）:
     - ハンドシェイクが 180 秒以内 → `HEALTHY`
     - ハンドシェイクが古い → `STALE`
     - 一度も無く UP から 45 秒未満 → `VERIFYING`、以降 → `UNREACHABLE`
     - 前セッションのハンドシェイク（`connectedAt` より前）は現セッションの証拠にしない。
   - 判定は `core/VpnHealth.kt` に置き、JVM テストで境界条件を固定する。
2. **アプリ復帰時（`MainActivity.onStart`）に必ず検証する。**
   - トンネルが残っている場合: ハンドシェイクを読み、`STALE`/`UNREACHABLE` なら自動再接続。
   - プロセスが作り直された場合: `GoBackend.runningTunnelNames` に残っていれば再 adopt し、
     無ければ再確立する。ただし WireGuard ライブラリのトンネル登録はプロセス内メモリに
     あるため、**プロセス再生成後は `runningTunnelNames` が空**になり adopt は成立しない。
     その場合は永続化したセッション（`VpnHealthStore`）を読み、再確立する（ハンドシェイクも
     やり直せるので望ましい）。
   - 自動再接続は **システム同意が既にある場合のみ**。同意ダイアログを勝手に出さない。
   - ユーザーが明示的に切断した場合は尊重する（`userDisconnected`）。
   - 再接続は 30 秒のクールダウンでループを防ぐ（`AUTO_RECONNECT_MIN_INTERVAL_MS`）。
3. **設定 `vpnAutoReconnect`（既定 ON）を追加する。**
   - 既存の `vpnAutoConnect`（起動時接続、既定 OFF）はそのまま残す。JSON に既定値付きで
     追加するため、既存の設定 JSON・バックアップは読める（`ignoreUnknownKeys` + 既定値）。
4. **バックグラウンドでも検証する（WorkManager `VpnHealthWorker`、15 分周期）。**
   - プロファイルがあり、自動再接続か起動時接続が ON のときだけ登録する。
   - OS の周期制限（最短 15 分）は許容し、即時性は復帰時の検証が担う。
5. **実行時チューニング（`VpnTuning`）— 保存テキストは変更しない。**
   - `PersistentKeepalive` 既定 25 秒（未指定・`0` のとき補正。非ゼロの指定は尊重）。
   - `MTU` 未指定なら 1280 を挿入、1280 超は 1280 に制限（PPPoE/NAT 経由の大きなパケット落ち対策）。
     1280 未満のユーザー指定値はそのまま尊重する（さらに下げると壊れるだけのため）。
   - `DNS` は先頭 3 件まで（システムリゾルバのタイムアウト連鎖を防ぐ）。
   - エディタ・エクスポートはユーザーの原文のまま。
6. **ホーム画面に状態バッジを置く。**
   - 緑は **ハンドシェイク成立時のみ**。確認中は琥珀色、「応答なし」は警告色。
   - タップで詳細シート（プロファイル・疎通・最終ハンドシェイク・最終確認・
     自動再接続スイッチ・今すぐ再接続）。
7. **タイムアウト時は VPN を疑う案内と再接続ボタンを出す。**
   - `onReceivedError` の `ERROR_TIMED_OUT` を `ErrorKind.TIMEOUT` として区別し、
     VPN 接続中なら「VPN を再接続」を併設する。
8. **状態履歴は秘密情報ゼロで保存する（`VpnHealthStore`）。**
   - `halfbrowser_vpn_health`（平文 SharedPreferences）に、プロファイル ID・接続時刻・
     最終ハンドシェイク時刻・最終確認時刻・判定・最終自動再接続時刻のみ。
   - 鍵・アドレス・DNS・エンドポイントは保存しない。バックアップ／端末転送から除外。

## OS 起点の停止を検出する

`VpnState.Connected` は「TUN が上がっている」だけを意味するため、次のいずれかで
**アプリが知らないままトンネルが消える**ことがある。

- システム設定から切断された、別の VPN アプリに切り替えられた
- VpnService がプロセスごと停止された
- ライブラリの DOWN コールバックが失われた

対策は 2 段構え。

1. `onTunnelState(DOWN)` は、そのトンネルがまだ `activeTunnel` である場合
   （＝こちらから切断を要求していない場合）に限り、`activeTunnel` を解放して
   `Disconnected` に遷移させる。以前は「UP でなければ何もしない」だったため、
   外部からの停止が握り潰され、パネルが「接続中」を表示し続けていた。
2. 5 秒周期のポーリングは、ハンドシェイク時刻を読む前に `runningTunnelNames` で
   生存確認する。消えていれば `Disconnected` に落とす（コールバックが来ない環境の保険）。

## 並行性

- `VpnController` は `mutex` で接続・切断・再接続を直列化し、加えて **世代番号** を持つ。
  古い世代の非同期結果は新しい状態を上書きしない（遅い接続が、ユーザーの切断後に
  トンネルを「復活」させない）。
- `bringUp` は `setState` の前に `activeTunnel` を公開する（GoBackend が同期的に
  `onStateChange` を呼ぶ場合に UP を取りこぼさないため）。失敗時は必ず取り消す。
- `Backend.getStatistics` は IO ディスパッチャ上でのみ呼ぶ。
- 接続前は必ず既存トンネルを DOWN にして待つ（プロセス外のトンネルも含む）。

## 検証

- JVM テスト 198 件 / 失敗 0（うち新規: `VpnHealthTest` 13、`VpnTuningTest` 10、
  `VpnStatusRulesTest` 6）。lint 0 errors。日英キー一致 457/457。
- エミュレータ（API 34）:
  - 接続直後は「確認中」、45 秒後に「接続してから応答なし」へ遷移（ハンドシェイク不可の環境）。
  - ホームのバッジと詳細シートが状態を表示。日英両方で表示崩れなし。
  - ホームへ戻って再開 → `UNREACHABLE on resume; reconnecting` で DOWN→UP（約 1.2 秒）。
  - プロセス再起動（force-stop）→ 起動時にトンネルが無いことを検出し自動再接続。
  - VPN 接続中の `ERR_NAME_NOT_RESOLVED` で VPN ヒントと「VPN を再接続」が表示され、
    押下で DOWN→UP。
  - WorkManager の周期ジョブ登録を `dumpsys jobscheduler` で確認。
  - **OS 起点の停止**: システム設定の VPN 画面から「切断」→ アプリが DOWN を検出して
    `Disconnected` に落ち、復帰時に自動再接続（DOWN→UP）することを確認。
  - **二重再接続の抑止**: プロセス再生成直後に復帰処理とバックグラウンド Worker が
    同時に再接続を決めても、DOWN→UP は 1 回だけであることをログで確認。
  - ホームのバッジが「VPN · reconnecting…」（自動復旧が有効で応答なし）を表示することを確認
    （以前は到達不能だった状態）。
- **未検証（正直な記録）**: エミュレータのユーザーネットでは WireGuard ハンドシェイクが
  成立しない（v1.6.0 と同じ制約）。`HEALTHY`（緑）への遷移は JVM テストと
  ハンドシェイク時刻の注入でのみ確認しており、実サーバーでの緑表示は実機で要確認。
