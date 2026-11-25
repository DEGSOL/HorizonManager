# Hovq 0.6.1

[Русский](../../README.md) · [English](README.en.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · [Italiano](README.it.md) · [Español](README.es.md) · **日本語** · [한국어](README.ko.md) · [简体中文](README.zh.md) · [Қазақша](README.kk.md) · [Татарча](README.tt.md)

Hovq は、専用の VPS に [Horizon](https://github.com/Thysrael/Horizon) をインストールして管理し、ニュースダイジェストを読むための Android アプリです。Horizon はサーバーで記事を収集して重複を取り除き、選択した AI モデルで評価と翻訳を行い、ダイジェストを作成します。

[署名済み APK をダウンロードしてリリースノートを読む](https://github.com/DEGSOL/HorizonManager/releases/latest)

## 主な機能

- RSS、Telegram、Reddit、Hacker News のソース、評価プロファイル、カバー画像、オンデマンドの情報補足。
- 手動、曜日指定、または設定時刻からの等間隔実行。スケジュールは VPS 上で動作します。
- 検索、フィルター、未読記事を備えたフィード、お気に入り、アーカイブ、ダウンロード済みダイジェストのオフライン閲覧。
- 操作ログと、端末およびサーバーの設定の暗号化バックアップ。

## インストールと最初のダイジェスト

**インストールに必要なのは、Ubuntu Server 24.04 LTS または Debian 12 が動作し、SSH で接続できる自分の VPS です。** Hovq から接続し、インストールを確認します。Horizon、エージェント、必要な依存パッケージはアプリが自動的にインストールします。

1. Android 8.0 以降に APK をインストールします。Ubuntu Server 24.04 LTS または Debian 12、systemd、SSH が動作し、OS のリポジトリ、GitHub、PyPI にアクセスできる VPS を用意します。インストーラーは、対応アーキテクチャ、最低 432 MiB の RAM、1.5 GiB の空き容量を確認します。収集や処理には追加のリソースが必要な場合があります。
2. Hovq でサーバーを追加し、SSH パスワードまたは秘密鍵を指定して、VPS 事業者の管理画面でフィンガープリントを確認します。VPS の `127.0.0.1:18777` への SSH 転送を許可してください。インストールを確認し、必要なら sudo のパスワードを入力します。既存のエージェントにも接続できます。
3. AI 設定でプロバイダー、モデル、API キーを設定します。OpenRouter と UnoRouter のモデル一覧はアプリで取得できます。ソースを追加し、処理プロファイルを選んで収集を開始します。
4. フィードでダイジェストを開きます。ニュース設定でスケジュールを、AI 設定でダイジェストの言語を選択できます。インターフェースとダイジェストは上記のすべての言語に対応しています。

## 通知とアップデート

**設定 → インターフェース → 通知**で新しいダイジェストの通知を有効にし、Android で許可します。Hovq は SSH または設定済みの HTTPS で VPS への直接接続を維持します。監視中は Android がサイレントなサービス通知を表示し、完成時には別の通知を表示します。接続が切れると Hovq は再接続して新しい号を確認します。

同じ証明書で署名された Hovq 0.4.5〜0.6.0 には上書きインストールできます。0.4.4 以前では新規インストールが必要です。既存の VPS では、Hovq からエージェントを 0.1.21 に更新します。オンデマンドで補足する設定でも、表現の異なる記事が同じ出来事を扱っているか確認します。各プロファイルの重複排除設定は引き続き適用されます。

## ソースからビルド

JDK 17 または 21、Android SDK 36、Build Tools 36.0.0 を使用します。SDK の場所は `ANDROID_HOME` または `android/local.properties` で指定します。

```bash
cd android
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

署名済みのビルドでは、次の環境変数を設定します

`HM_RELEASE_KEYSTORE`, `HM_RELEASE_KEY_ALIAS`, `HM_RELEASE_STORE_PASSWORD`, `HM_RELEASE_KEY_PASSWORD`

[このリリースのアプリとエージェントのソース](https://github.com/DEGSOL/HorizonManager/tree/v0.6.1) · [アーキテクチャと API](https://github.com/DEGSOL/HorizonManager/blob/v0.6.1/docs/ARCHITECTURE.md) · [テスト](https://github.com/DEGSOL/HorizonManager/blob/v0.6.1/docs/TESTING.md)
