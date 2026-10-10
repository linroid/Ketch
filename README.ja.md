<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="art/icon-white.svg">
    <source media="(prefers-color-scheme: light)" srcset="art/icon-app.svg">
    <img alt="Ketch" src="art/icon-app.svg" height="128">
  </picture>
</p>

<h1 align="center">Ketch</h1>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="README.es.md">Español</a> ·
  <b>日本語</b> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="README.fr.md">Français</a>
</p>

<p align="center">
  <b>すべてのデバイスで使える、高速でオープンソースのダウンロードマネージャー。</b><br>
  macOS · Windows · Linux · Android · iOS · Web · コマンドライン<br>
  自分のアプリに組み込める Kotlin Multiplatform ライブラリ。
</p>

<p align="center">

[![最新リリース](https://img.shields.io/github/v/release/linroid/Ketch?include_prereleases&filter=v*&label=Download&logo=github)](https://github.com/linroid/Ketch/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/com.linroid.ketch/core?label=Maven%20Central&logo=apache-maven&logoColor=white)](https://central.sonatype.com/namespace/com.linroid.ketch)
[![Web アプリ](https://img.shields.io/badge/Web_app-open-4F5DE4.svg?logo=webassembly&logoColor=white)](https://linroid.com/Ketch/)
[![Android](https://img.shields.io/badge/Android-8.0+-3DDC84.svg?logo=android&logoColor=white)](https://github.com/linroid/Ketch/releases/latest)
[![iOS](https://img.shields.io/badge/iOS-18+-000000.svg?logo=apple&logoColor=white)](app/ios/)
[![デスクトップ](https://img.shields.io/badge/Desktop-macOS_|_Windows_|_Linux-DB380E.svg)](https://github.com/linroid/Ketch/releases/latest)
[![ライセンス](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

</p>

<p align="center">
  <a href="#download"><b>ダウンロード</b></a> ·
  <a href="https://youtu.be/l8RCUYQfRrc"><b>デモを見る</b></a> ·
  <a href="#features"><b>機能</b></a> ·
  <a href="#getting-started"><b>使い方</b></a> ·
  <a href="docs/developers.md"><b>開発者向け</b></a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="art/showcase-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="art/showcase-light.png">
    <img
      alt="デスクトップ、Android、iOS、コマンドラインの Ketch：接続ごとの進捗を表示するダウンロード一覧、Android のデバイス一覧、iOS の接続一覧、ターミナルの ketch"
      src="art/showcase-light.png" width="100%">
  </picture>
</p>

<p align="center">
  <a href="https://youtu.be/l8RCUYQfRrc"><b>▶ Ketch の動作を見る</b></a>
</p>

Ketch はダウンロードを複数の並列接続に分割し、それぞれの進捗をリアルタイムで表示します。
Web リンク、FTP サーバー、トレント、マグネットリンクをひとつのアプリで扱えます。
ノート PC、スマートフォン、ホームサーバーをペアリングすれば、どのデバイスからでも
すべてのデバイスのダウンロードを確認・操作できます。

**自分のアプリを開発していますか？** Ketch のアプリと同じダウンロードエンジンを組み込めます。
並列ダウンロード、一時停止と再開、キュー、速度制限に対応した Kotlin Multiplatform ライブラリを
Maven Central で公開しています。[Ketch の組み込みを始める →](docs/developers.md#quick-start)

> [!WARNING]
> 🚧 Ketch は開発中で、現在はリリース候補版のみを公開しています。不具合が見つかる可能性があります。
> 気づいた点は [issue でお知らせください](https://github.com/linroid/Ketch/issues)。

<a id="download"></a>

## ダウンロード

| プラットフォーム | 入手方法 |
|---|---|
| macOS（Apple silicon、Intel） | [最新リリース][release]の `.dmg` |
| Windows（x64、ARM64） | [最新リリース][release]の `.msi`、またはインストール不要のポータブル版 `.zip`（[ポータブル版について](docs/updates.md#the-portable-windows-app)） |
| Linux（x64、ARM64） | [最新リリース][release]の `.deb` |
| Android 8.0+ | [最新リリース][release]の `.apk` |
| iOS 18+ | Xcode でソースからビルド（[`app/ios`](app/ios/)） |
| Web | [linroid.com/Ketch](https://linroid.com/Ketch/) から別のデバイスで動作する Ketch を操作 |
| ブラウザ拡張機能 | Chrome、Edge、Brave、Firefox など：[最新リリース][release]の `.zip`（[インストール方法](app/browser-extension/README.md#installing)） |
| コマンドラインとサーバー | macOS、Linux、Windows：[インストールスクリプト](#run-ketch-on-a-server)または[最新リリース][release] |
| Docker | x64 と ARM64 の NAS・ホームサーバー：`ghcr.io/linroid/ketch`（[ガイド](docs/docker.md)、TrueNAS と fnOS の手順付き） |

デスクトップアプリはランタイムを同梱し、コマンドラインツールは単体のネイティブバイナリなので、
どちらも Java のインストールは不要です。どちらもこのリポジトリのリリースから更新できます。
デスクトップは「設定 → 情報」、コマンドラインは `ketch update` を使います
（[更新の仕組み](docs/updates.md)）。

[release]: https://github.com/linroid/Ketch/releases/latest

<a id="features"></a>

## 機能

### より速いダウンロード

- **並列接続** — ファイルを範囲ごとに分割して同時にダウンロードします。「接続」タブでは
  各接続の進捗と速度を個別に表示し、ダウンロード中でも接続数を増減できます。
- **複数のネットワークを同時に利用** — Wi-Fi、Ethernet、Android ではモバイルデータ通信にも
  ひとつのダウンロードを分散できます（[仕組み](docs/multiple-networks.md)）。
- **プロキシ** — システムのプロキシ、ユーザー名とパスワード付きの HTTP・SOCKS5 プロキシ、
  またはプロキシなしでダウンロード。指定したホストには直接接続し、ダウンロードごとにも
  設定できます（[詳細](docs/proxy.md)）。
- **再起動後も一時停止と再開** — 再開前にサーバー上のファイルが変わっていないか確認し、
  失敗した接続は自動で再試行します。

### さまざまなリンクに対応

- **HTTP と HTTPS** — ブラウザ拡張機能から送る場合は、ページの Cookie とリファラーを引き継ぎます。
- **FTP と FTPS** — 並列接続と再開に対応します。
- **BitTorrent とマグネットリンク** — 純粋な Kotlin 製のエンジンで v1、v2、ハイブリッドの
  トレントに対応し、ダウンロードするファイルを選べます（[詳細](docs/torrent.md)）。
- **HLS（`.m3u8`）と DASH（`.mpd`）** — 有限長で暗号化されていないストリームを
  ひとつのメディアファイルに保存します。HLS マスタープレイリストでは帯域幅が最大の
  バリアントを選びます（[対応範囲と制限](docs/media.md)）。
- マグネットリンクと `.torrent` ファイルを開くアプリとして Ketch を登録できます。
  ブラウザやファイルマネージャーでクリックすれば Ketch で開きます。

### すべてのデバイスをひとつのアプリで

- **QR コードでペアリング** — 共有するデバイスで「設定 → 共有」を開き、
  「別のデバイスを許可」を選びます。スマートフォンでコードを読み取るか、ペアリングリンクを
  コピーしてください。「ネットワーク上で探す」から選び、両方に同じ 4 桁の数字が表示されている
  ことを確認して接続を許可することもできます。
- **サイドバーにすべてのデバイスを表示** — 速度と状態をリアルタイムで確認できます。
  キー操作で切り替えたり、「すべてのデバイス」で全ダウンロードをひとつの表に表示したりできます。
- **ダウンロード先のデバイスを選択** — デバイスを切り替えずに、任意のデバイスへリンクを追加したり、
  サイドバーのデバイスにドロップしたり、ダウンロードを別のデバイスへ送信・移動したりできます。
- **NAS やサーバーでヘッドレス実行** — `ketch server`（[Docker イメージ](docs/docker.md)もあり）は
  REST API と Web アプリを内蔵した同じエンジンを実行します。アプリからネットワーク上のサーバーを
  見つけられます。
- **ターミナルから操作** — `ketch add`・`list`・`pause`・`resume`・`watch` で、Ketch アプリや
  サーバーのダウンロードをターミナルやスクリプトから操作できます。`watch` は JSON Lines を出力します
  （[CLI](cli/README.md#work-on-a-running-ketch)）。

### 帯域幅をコントロール

- **速度モード** — 「フルスピード」、ビデオ通話用に帯域を残す「低速モード」、
  週間スケジュールに従って両者を切り替える「自動」を選べます。
- **ダウンロードごとの設定** — 実行中に速度制限、接続数、優先度を変更できます。
  「緊急」は優先度の低いダウンロードを一時停止して、すぐに開始します。
- **柔軟なキュー** — 全体やサイトごとの同時ダウンロード数を制限し、開始時刻も指定できます。

### 普段の使い方に合わせて

- **ブラウザ拡張機能** — Chrome、Edge、Brave、Firefox などのダウンロードとマグネットリンクを
  引き継ぎ、コンテキストメニューに「Ketch でダウンロード」を追加します。
  この PC にも別のデバイスにも送れます（[詳細](app/browser-extension/README.md)）。
- **貼り付けてダウンロード** — リンクを貼り付けて追加でき、取り消しにも対応します。
  コピーしたリンクのダウンロードを Ketch から提案することもできます。
- **デスクトップではキーボードで操作** — `⌘K`（Windows と Linux は `Ctrl+K`）で
  コマンドパレットを開けます。ショートカット一覧ですべてのキー操作を確認できます。
- **カテゴリ別フォルダー** — ファイルの種類や Web サイトに応じて、動画、音楽、文書、アーカイブを
  ダウンロードフォルダー内の専用フォルダーに保存。「設定 → ダウンロード」でおすすめを使うか、
  独自のルールを設定できます。
- **完了したらすぐに開く** — 一覧から直接ファイルを開く、保存先フォルダーを表示する、
  ファイルを外へドラッグする、といった操作ができます。
- **各プラットフォームに自然に統合** — デスクトップのメニューバーやトレイ、通知、Dock と
  タスクバーの進捗表示に対応。Android と iOS 26 ではバックグラウンドでもダウンロードを続けます。
- **ダウンロード中はスリープしない** — デスクトップ版と Android 版は、ダウンロード中にシステムが
  自動でスリープしないようにします。画面はオフになることがあります（「設定 → 一般」）。
- **好みの外観と言語** — ライト・ダークテーマと 7 色のアクセントカラーを用意。
  English、简体中文、繁體中文、日本語、한국어、Español、Português (Brasil)、Deutsch、
  Français に対応します（[翻訳への参加](docs/development/localization.md)）。

### AI でダウンロードを探す（プレビュー）

- **ディスカバー** — 「最新の Ubuntu Server ISO」のように欲しいものを伝えると、AI エージェントが
  Web を検索し、リンクを確認して候補を順位付けします。失敗したダウンロードの「別のソースを探す」
  でも同じように検索できます。追加メッセージで絞り込み、不要な結果を除外し、履歴から以前の検索を
  再開できます。許可済みでなければ、サイトを開く前に確認します。OpenAI、Anthropic、Gemini、
  Ollama、OpenAI 互換サービスなど、自分のモデルサービスを利用できます。
  デスクトップ、Android、`ketch ai-discover` で使えます（[設定方法](docs/ai-discovery.md)）。
- **MCP サーバー** — `ketch mcp` を使うと、AI アシスタントが
  [Model Context Protocol](cli/README.md#mcp-server) を通じて Ketch アプリやサーバーのダウンロードを
  開始・確認・管理できます。

<a id="getting-started"></a>

## 使い方

1. [ダウンロード](#download)から Ketch をインストールして開きます。
   スマートフォンでは初回の案内画面で保存先を指定します。
2. リンクを貼り付ける（`⌘V` または `Ctrl+V`）、リンクや `.torrent` ファイルをウィンドウに
   ドロップする、または「追加」を選んでダウンロードを追加します。
3. スマートフォンから PC を操作するには、PC で「設定 → 共有」を開いて
   「別のデバイスを許可」を選び、スマートフォンのカメラで QR コードを読み取ります。
4. [ブラウザ拡張機能](app/browser-extension/README.md)をインストールして、
   ブラウザのダウンロードを Ketch に送ります。

<a id="run-ketch-on-a-server"></a>

### サーバーで Ketch を実行する

macOS または Linux にコマンドラインツールをインストールします
（Windows は[最新リリース][release]の `.zip` をダウンロードしてください）。

```bash
curl -fsSL https://raw.githubusercontent.com/linroid/Ketch/main/install.sh | bash
```

REST API と Web アプリをポート 8642 で提供するサーバーを起動します。

```bash
ketch server
```

アプリの「デバイス → デバイスを追加」で追加するか、ブラウザで
`http://<サーバーのアドレス>:8642` を開きます。コマンドラインから直接ダウンロードすることもできます。

```bash
ketch https://example.com/file.zip
```

アクセスコード、ポートなどは[設定ファイル](cli/README.md#configuration-file)で指定します。
すべてのコマンドは [CLI ドキュメント](cli/README.md)を参照してください。

NAS など Docker が動く環境では、代わりにイメージを起動します。設定と TrueNAS・fnOS での手順は
[Docker ガイド](docs/docker.md)を参照してください。

```bash
docker run -d --name ketch --restart unless-stopped -e PUID=1000 -e PGID=1000 \
  -v /srv/ketch:/config -v /srv/downloads:/downloads \
  -p 8642:8642 -p 16881:16881 -p 16881:16881/udp ghcr.io/linroid/ketch
```

## ロードマップ

- **v1.0.0 までの配布準備** — Windows 版の署名、macOS 版の署名と公証、ブラウザストアでの
  拡張機能の公開、インストールと更新手順の検証（[計画](docs/plans/v1-distribution.md)）
- **Metalink** — チェックサム検証付きの複数ミラーからの同時ダウンロード
- **WebDAV** — 再開に対応した WebDAV サーバーからのダウンロード
- **HLS と DASH の対応拡大** — 品質選択、独立した音声・映像トラックの結合、AES-128 HLS 暗号化への
  対応。有限長で暗号化されていない単一ストリームのダウンロードにはすでに対応しています
- **メディア抽出** — Web ページのメディアを保存
- **リソース検出** — Web ページ内のダウンロード可能なファイルを検出
- **チェックサム** — 指定したハッシュ値やサーバーが公開するハッシュ値でダウンロードを検証
- **分割ダウンロードの高速化** — 先に完了した接続が遅い接続の残りを引き継ぎ、待ち時間を短縮
- **停止検出と再試行設定** — データが届かなくなった接続を再接続し、タイムアウトや再試行回数を設定。
  無制限の再試行にも対応
- **未完了ファイルを区別** — 一時的な名前で保存し、完了してから正式なファイル名に変更
- **トレントのファイルをいつでも選択** — マグネットリンクの詳細取得後に選び、ダウンロード中も変更
- **ダウンロード完了後の動作** — キューが空になったら Ketch の終了、スリープ、シャットダウンを選択可能に
- **自動化フック** — 完了時や失敗時にコマンド実行や webhook 呼び出し
- **デバイス間転送** — 送信・移動時にダウンロード済みデータも渡し、別のデバイスで最初からやり直さずに再開
  （[計画](docs/plans/task-transfer.md)）
- **補助デバイス** — 他のデバイスがそれぞれの回線で同じファイルの一部をダウンロード。
  実行中の参加・離脱に対応（[提案](docs/design/multi-instance-downloads.md)）

## 開発者向け

**Ketch を自分のアプリに組み込みましょう。** Kotlin Multiplatform ライブラリを使えば、
Ketch アプリと同じエンジンに独自の UI とアプリロジックを組み合わせられます。
Maven Central の `com.linroid.ketch` から必要なモジュールを追加してください。

Android、iOS、JVM アプリ内では `core` と `ktor` でエンジンを実行できます。
Node.js と WASI では `core` に独自の HTTP エンジンを組み合わせます。
また、`remote` を使えば、Android、iOS、JVM、ブラウザから共通の `KetchApi` で
Ketch サーバーを操作できます。

- [開発者ガイド](docs/developers.md) — モジュール、クイックスタート、REST API、機能拡張
- [API リファレンス](docs/api.md) — インストール、設定、優先度、エラー、ログ
- [アーキテクチャ](docs/architecture.md) — ダウンロードの処理フローとアプリの構造

## ドキュメント

以下の詳細ドキュメントは英語です。

- [コマンドライン](cli/README.md) — ダウンロード、サーバー、MCP、AI 検索、設定ファイル
- [ブラウザ拡張機能](app/browser-extension/README.md) — 設定、ダウンロードの取り込み、権限、プライバシー
- [BitTorrent](docs/torrent.md) — トレントとマグネットリンクの対応範囲と制限
- [複数ネットワーク](docs/multiple-networks.md) — 複数のネットワークインターフェースへの分散
- [AI 検索](docs/ai-discovery.md) — プロバイダー、キー、Web 検索、ページへのアクセス、チャット、履歴
- [ログ](docs/logging.md) — 保存先と不具合報告に添付する情報

## 貢献する

貢献を歓迎します。PR を送る前に、issue でアイデアを相談してください。
開発時は[コードスタイル](docs/development/code-style.md)、
[テスト規則](docs/development/testing.md)、[ローカライズガイド](docs/development/localization.md)を
参照してください。翻訳も PR で受け付けています。

コーディングエージェントは、すべてのエージェントとエディタの共通指針である
[AGENTS.md](AGENTS.md)から読み始めてください。自動で読み込まないツールには、変更前に
`AGENTS.md` とリンク先の規則を読むよう指示してください。共通の指針はこれらのファイルに置き、
ツール固有の入口ファイルは参照だけを記載します。

## ライセンス

Apache-2.0
