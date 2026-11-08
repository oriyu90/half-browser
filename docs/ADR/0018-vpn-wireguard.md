# ADR-0018: アプリ内 WireGuard VPN（このアプリの通信のみ）

- 日付: 2026-11-09 / 状態: 採用（v1.6.0）
- 関連: ADR-0003（資格情報の保持）、[計画](../plan/v1.6.0-vpn-js-guard.md)

## 背景

要件は「アプリ内に VPN 機能（WireGuard）を付け、この VPN はこのアプリ内の通信だけに使う」。
アプリ内設定からプロファイルを登録・編集でき、ホーム画面下部から接続／切断／再接続でき、
登録済み VPN 一覧と接続状態が見えること。

## 決定

1. **公式 WireGuard ライブラリ `com.wireguard.android:tunnel`（userspace, wireguard-go）を使う。**
   - ネイティブ `.so`（`libwg-go.so`）が全 ABI に含まれ、root 不要・カーネルモジュール不要。
   - `GoBackend` が `VpnService` を提供し、AAR の Manifest がマージされる。Android 14+ 用に
     `foregroundServiceType="connectedDevice"` の再宣言だけアプリ側 Manifest に追加した。
2. **「このアプリの通信だけ」は allow-list で保証する。**
   - `[Interface]` に `IncludedApplications = <自パッケージ>` を書き加え、ライブラリ経由で
     `Builder.addAllowedApplication` させる。「一度でも呼べば、追加したアプリだけがトンネルを使う」
     （Android 公式ドキュメント）ため、他のアプリは通常回線のままになる。
   - **除外リスト方式は不採用**。`getInstalledPackages()` は Android 11 以降のパッケージ可視性で
     フィルタされ、列挙漏れしたアプリがトンネル内に入る＝fail-open になる。allow-list は
     自分自身の名前だけを必要とし、後からインストールされたアプリも外側に残る fail-closed。
   - ユーザーが貼り付けた設定内の `IncludedApplications` / `ExcludedApplications` は除去する。
     従わないと、他アプリをこのブラウザのトンネルに誘導できる。
   - ライブラリが allow-list を拒否する将来版に備え、解析後の `Config` を自己検証する
     フォールバック（除外リスト）を備える。どちらも受け付けられない場合は接続失敗として扱い、
     「全アプリがトンネル内」になる状態では起動しない。
3. **プロファイルは Keystore 暗号化ストレージに保存する。**
   - 設定テキストは秘密鍵を含むため、`EncryptedSharedPreferences`（AES-256-GCM）で保存し、
     `CredentialStore` と同じくクラウドバックアップ／端末転送の対象外にする。
   - Keystore 不可時はメモリ専用に縮退し、平文で保存しない。
   - バックアップ（`halfbrowser.backup`）には含めない。秘密鍵をファイルに出さない方針。
     書き出しはユーザーが明示操作したときだけ `.conf` として出す。
4. **設定の検証はアプリ側の厳格パーサで行う。**
   - 保存前に `[Interface]` / `[Peer]`、鍵（32B base64 の canonical 形式）、Endpoint（host:port）、
     CIDR、数値範囲を検証し、失敗時は項目ごとのローカライズ済み理由を出す。
   - 「黙って直す」はしない。意図と違うルーティングになり得るため。
   - サイズ・ピア数・リスト長の上限を設け、貼り付けた巨大テキストで UI が止まらないようにする。
5. **ホーム画面のパネルは Scaffold の `bottomBar` に置く。**
   - 下部固定で接続／切断／再接続が 1 タップ。登録一覧は折りたたみ可能。
   - FAB と重ならないよう、コンテンツ側の内側パディングで回避する（重ね配置はしない）。

## 実行時の注意

- 再接続で「切断→即座に起動」すると、`GoBackend` の `stopSelf()` と次回起動が競合し、
  新しいトンネルが即座に破壊されることを実測した。切断後に 600ms 待ってから起動する。
- トンネル名は `Tunnel.NAME_PATTERN`（`[a-zA-Z0-9_=+.-]{1,15}`）に合わせ、プロファイル ID から
  導出する（表示名の自由テキストは使わない）。
- 接続は `Dispatchers.IO` で実行する（DNS 解決とネイティブ受け渡しを含むため）。

## 検証

- JVM テスト: パーサ／命名／スコープ書き換え 42 件（不正設定の拒否、allow-list の idempotent 性、
  ユーザー指定スコープの除去など）。
- エミュレータ（API 34）: インポート→保存→同意→接続で `Device started`＝トンネル Up、
  `ConnectivityService` の VPN ネットワークに自 UID のみが紐付くことを確認。再接続が
  切断→再起動→接続維持になることも確認。
- **未検証（正直な記録）**: エミュレータのユーザーネット（NAT）環境では、`VpnService.protect()`
  された UDP がトンネル内をループしてしまい、ハンドシェイクのパケットがホストに届かない。
  公式 WireGuard アプリでも同一設定で再現したため、エミュレータ環境の制限であり本統合の
  不具合ではない。実機での対向接続確認は残課題（[DEBUG-REPORT](../DEBUG-REPORT.md) §v1.6.0 参照）。
