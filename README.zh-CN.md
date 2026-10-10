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
  <b>简体中文</b> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="README.fr.md">Français</a>
</p>

<p align="center">
  <b>适用于所有设备的快速开源下载管理器。</b><br>
  macOS · Windows · Linux · Android · iOS · Web · 命令行<br>
  可嵌入自有应用的 Kotlin Multiplatform 库。
</p>

<p align="center">

[![最新版本](https://img.shields.io/github/v/release/linroid/Ketch?include_prereleases&filter=v*&label=Download&logo=github)](https://github.com/linroid/Ketch/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/com.linroid.ketch/core?label=Maven%20Central&logo=apache-maven&logoColor=white)](https://central.sonatype.com/namespace/com.linroid.ketch)
[![网页应用](https://img.shields.io/badge/Web_app-open-4F5DE4.svg?logo=webassembly&logoColor=white)](https://linroid.com/Ketch/)
[![Android](https://img.shields.io/badge/Android-8.0+-3DDC84.svg?logo=android&logoColor=white)](https://github.com/linroid/Ketch/releases/latest)
[![iOS](https://img.shields.io/badge/iOS-18+-000000.svg?logo=apple&logoColor=white)](app/ios/)
[![桌面端](https://img.shields.io/badge/Desktop-macOS_|_Windows_|_Linux-DB380E.svg)](https://github.com/linroid/Ketch/releases/latest)
[![许可证](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

</p>

<p align="center">
  <a href="#download"><b>下载</b></a> ·
  <a href="https://www.bilibili.com/video/BV1pLHb6GEFT"><b>观看演示</b></a> ·
  <a href="#features"><b>功能</b></a> ·
  <a href="#getting-started"><b>快速上手</b></a> ·
  <a href="docs/developers.md"><b>开发者指南</b></a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="art/showcase-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="art/showcase-light.png">
    <img
      alt="Ketch 桌面端、Android、iOS 和命令行：下载列表中的实时连接通道、Android 上的设备、iOS 上的下载连接以及终端中的 ketch"
      src="art/showcase-light.png" width="100%">
  </picture>
</p>

<p align="center">
  <a href="https://www.bilibili.com/video/BV1pLHb6GEFT"><b>▶ 观看 Ketch 实际演示</b></a>
</p>

Ketch 将每个下载任务拆分为多个并行连接，并实时显示各连接的进度。
网页链接、FTP 服务器、种子和磁力链接都能在同一个应用中下载。
配对笔记本电脑、手机和家庭服务器后，即可从其中任意一台设备查看和控制所有设备上的下载任务。

**正在开发自己的应用？** 将 Ketch 应用使用的下载引擎嵌入其中，即可获得并行下载、暂停与继续、
队列和限速功能。Kotlin Multiplatform 库已发布到 Maven Central。
[开始集成 Ketch →](docs/developers.md#quick-start)

> [!WARNING]
> 🚧 Ketch 正在积极开发中，目前仅提供候选发布版本。使用中可能遇到问题，欢迎
> [反馈](https://github.com/linroid/Ketch/issues)。

<a id="download"></a>

## 下载

| 平台 | 获取方式 |
|---|---|
| macOS（Apple 芯片、Intel） | [最新版本][release]中的 `.dmg` |
| Windows（x64、ARM64） | [最新版本][release]中的 `.msi` 或免安装便携版 `.zip`（[便携版说明](docs/updates.md#the-portable-windows-app)） |
| Linux（x64、ARM64） | [最新版本][release]中的 `.deb` |
| Android 8.0+ | [最新版本][release]中的 `.apk` |
| iOS 18+ | 使用 Xcode 从源码构建（[`app/ios`](app/ios/)） |
| 网页版 | [linroid.com/Ketch](https://linroid.com/Ketch/)，用于控制其他设备上运行的 Ketch |
| 浏览器扩展 | Chrome、Edge、Brave、Firefox 等：[最新版本][release]中的 `.zip`（[安装方法](app/browser-extension/README.md#installing)） |
| 命令行和服务器 | macOS、Linux 和 Windows：[安装脚本](#run-ketch-on-a-server)或[最新版本][release] |

桌面应用自带运行时，命令行工具则是单个原生可执行文件，两者均无需安装 Java。
它们都能从本仓库发布的版本更新：桌面应用通过「设置 → 关于」，命令行通过 `ketch update`
（[更新说明](docs/updates.md)）。

[release]: https://github.com/linroid/Ketch/releases/latest

<a id="features"></a>

## 功能

### 下载更快

- **并行连接** — 将文件分段并同时下载。「连接」标签页以独立通道显示每个连接及其速度，
  下载过程中也能增加或减少连接数。
- **同时使用多个网络** — 将一个下载任务分配到 Wi-Fi、以太网，以及 Android 上的移动数据网络
  （[工作原理](docs/multiple-networks.md)）。
- **暂停与继续，重启后也不例外** — 继续下载前会检查服务器上的文件是否发生变化，
  并自动重试失败的连接。

### 支持多种链接

- **HTTP 和 HTTPS**，浏览器扩展发送下载任务时会附带页面的 Cookie 和来源信息。
- **FTP 和 FTPS**，支持并行连接与断点续传。
- **BitTorrent 和磁力链接** — 纯 Kotlin 引擎支持 v1、v2 和混合种子，可选择要下载的文件
  （[详情](docs/torrent.md)）。
- **HLS（`.m3u8`）和 DASH（`.mpd`）** — 将有限时长、未加密的流保存为一个媒体文件。
  HLS 主播放列表会选择带宽最高的版本（[支持范围与限制](docs/media.md)）。
- Ketch 可注册为系统中磁力链接和 `.torrent` 文件的打开方式，
  在浏览器或文件管理器中单击即可交给 Ketch。

### 一个应用管理所有设备

- **扫码配对** — 在要共享的设备上打开「设置 → 共享」，选择「允许其他设备」，
  然后用手机扫码或复制配对链接。也可在「在网络中查找」中选择设备，
  确认两边显示相同的四位数字后允许连接。
- **侧边栏列出每台设备**，实时显示速度和运行状态。使用快捷键切换设备，
  或打开「所有设备」，在同一张表格中查看全部下载任务。
- **将下载任务交给合适的设备** — 向任意设备添加链接、将链接拖到侧边栏的设备上，
  或将下载任务发送或移动到另一台设备，无需切换当前设备。
- **在 NAS 或服务器上无界面运行** — `ketch server` 运行同一个引擎，内置 REST API 和网页应用，
  其他 Ketch 应用可在局域网中发现它。

### 掌控带宽

- **速度模式** — 使用「全速」，用「低速模式」为视频通话留出带宽，
  或使用「自动」按每周计划在两者之间切换。
- **单个下载任务控制** — 下载过程中可调整限速、连接数和优先级。
  「紧急」会暂停优先级较低的任务，让当前任务立即开始。
- **灵活的队列** — 限制同时运行的下载任务数及每个网站的并发数，也可指定稍后的开始时间。

### 贴合日常使用

- **浏览器扩展**支持 Chrome、Edge、Brave、Firefox 等浏览器：接管下载和磁力链接，
  并在右键菜单中提供「使用 Ketch 下载」，可发送到本机或其他设备
  （[详情](app/browser-extension/README.md)）。
- **粘贴即可下载** — 粘贴链接即可添加任务，支持撤销；也可让 Ketch 提示下载已复制的链接。
- **桌面端键盘操作** — `⌘K`（Windows 和 Linux 上为 `Ctrl+K`）打开命令面板，
  快捷键列表列出所有按键组合。
- **下载完成即可打开** — 直接从列表打开文件、在文件夹中显示，或将文件拖出。
- **融入各个平台** — 桌面端支持菜单栏或托盘、通知、Dock 和任务栏进度；
  Android 和 iOS 26 上可在后台继续下载。
- **下载时保持唤醒** — 桌面版和 Android 版在下载进行时防止系统自动进入睡眠，屏幕仍可关闭
  （**设置 → 通用**）。
- **外观与语言随心选择** — 浅色与深色主题、四种强调色，支持 English、简体中文、繁體中文、
  日本語、한국어、Español、Português (Brasil)、Deutsch 和 Français
  （[参与翻译](docs/development/localization.md)）。

### 用 AI 查找下载资源（预览）

- **搜索** — 描述所需内容，例如「最新的 Ubuntu Server ISO」，AI 智能体会搜索网页、
  检查链接并对找到的下载资源排序。下载失败时，也可用「查找其他来源」执行相同的搜索。
  通过追问缩小范围，丢弃不需要的结果，或从历史记录继续之前的搜索。
  除非已获许可，搜索功能会在打开网站前询问。使用自己的模型服务：OpenAI、Anthropic、
  Gemini、Ollama 或任意兼容 OpenAI 的服务。桌面端、Android 应用和 `ketch ai-discover`
  均可使用（[配置方法](docs/ai-discovery.md)）。
- **MCP 服务器** — `ketch mcp` 让 AI 助手通过
  [Model Context Protocol](cli/README.md#mcp-server) 启动、查看和管理下载任务。

<a id="getting-started"></a>

## 快速上手

1. 从[下载](#download)部分安装 Ketch 并打开。手机上的欢迎页面会询问下载保存位置。
2. 添加下载任务：粘贴链接（`⌘V` 或 `Ctrl+V`），将链接或 `.torrent` 文件拖到窗口中，
   或选择「添加」。
3. 如需用手机控制电脑，在电脑上打开「设置 → 共享」，选择「允许其他设备」，
   然后用手机相机扫描二维码。
4. 安装[浏览器扩展](app/browser-extension/README.md)，将浏览器的下载任务发送到 Ketch。

<a id="run-ketch-on-a-server"></a>

### 在服务器上运行 Ketch

在 macOS 或 Linux 上安装命令行工具（Windows 请下载[最新版本][release]中的 `.zip`）：

```bash
curl -fsSL https://raw.githubusercontent.com/linroid/Ketch/main/install.sh | bash
```

启动服务器，REST API 和网页应用将运行在 8642 端口：

```bash
ketch server
```

然后在应用的「设备 → 添加设备」中添加它，或在浏览器中打开 `http://<服务器地址>:8642`。
命令行也可直接下载：

```bash
ketch https://example.com/file.zip
```

在[配置文件](cli/README.md#configuration-file)中设置访问码、端口及其他选项；
[命令行文档](cli/README.md)列出了所有命令。

## 路线图

- **v1.0.0 前的分发准备** — Windows 版本签名、macOS 版本签名与公证、浏览器扩展商店上架，
  以及安装和更新流程验证（[计划](docs/plans/v1-distribution.md)）
- **Metalink** — 同时从多个镜像下载，并校验校验和
- **WebDAV** — 从 WebDAV 服务器下载，支持断点续传
- **更多 HLS 和 DASH 格式** — 选择画质、合并独立音视频轨道、处理 AES-128 HLS 加密；
  目前已支持有限时长、未加密的单流下载
- **媒体提取** — 保存网页中的媒体内容
- **资源嗅探** — 查找网页上可下载的文件
- **校验和** — 根据用户提供或服务器发布的哈希值校验下载文件
- **代理** — 通过 HTTP、SOCKS5 或系统代理下载，支持绕过列表
- **更快的分段下载** — 先完成的连接接手较慢连接剩余的部分，避免等待最慢的连接
- **停滞检测和重试设置** — 连接停止传输数据时重新连接，可设置超时和重试次数，包括无限重试
- **让未完成文件一目了然** — 下载时使用临时文件名，完成后再改为正式名称
- **分类文件夹** — 按自定义规则，将视频、音乐、文档和压缩包保存到各自的文件夹
- **随时选择种子文件** — 磁力链接的详情获取后再选择文件，下载中也可调整选择
- **下载完成后** — 队列清空后可选择退出 Ketch、让电脑睡眠或关机
- **自动化钩子** — 下载完成或失败时执行命令或调用 webhook
- **控制运行中设备的命令行** — `ketch` 和 AI 智能体可添加、列出、暂停和查看 Ketch 应用
  或服务器中的下载任务，无需启动第二个引擎
- **Docker 镜像** — 为 x64 和 ARM 的 NAS 与家庭服务器提供官方镜像，支持健康检查和固定种子端口
- **设备间传输** — 「发送到」和「移动到」携带已下载的数据，让另一台设备继续下载，
  无需从头开始（[计划](docs/plans/task-transfer.md)）
- **辅助设备** — 让其他设备使用各自的网络连接下载同一文件的部分内容，运行中可随时加入或退出
  （[提案](docs/design/multi-instance-downloads.md)）

## 开发者指南

**将 Ketch 嵌入应用。** Kotlin Multiplatform 库提供与 Ketch 应用相同的下载引擎，
可搭配自己的界面和应用逻辑。从 Maven Central 的 `com.linroid.ketch` 下添加所需模块。

Android、iOS 或 JVM 应用可通过 `core` 和 `ktor` 在进程内运行引擎；
Node.js 和 WASI 可使用 `core` 搭配自定义 HTTP 引擎。
也可使用 `remote`，通过同一个 `KetchApi` 从 Android、iOS、JVM 或浏览器控制 Ketch 服务器。

- [开发者指南](docs/developers.md) — 模块、快速入门、REST API 和扩展 Ketch
- [API 参考](docs/api.md) — 安装、配置、优先级、错误和日志
- [架构](docs/architecture.md) — 下载流程和应用结构

## 文档

以下详细文档目前使用英文。

- [命令行](cli/README.md) — 下载、服务器、MCP、AI 搜索和配置文件
- [浏览器扩展](app/browser-extension/README.md) — 配置、下载接管、权限和隐私
- [BitTorrent](docs/torrent.md) — 种子和磁力链接的支持范围与限制
- [多个网络](docs/multiple-networks.md) — 跨网络接口分配下载任务
- [AI 搜索](docs/ai-discovery.md) — 服务商、密钥、网页搜索、网页访问、对话和历史记录
- [日志](docs/logging.md) — 日志位置及问题报告所需的附件

## 参与贡献

欢迎贡献！提交 PR 前，请先创建 issue 讨论想法。
开发规范参见[代码风格](docs/development/code-style.md)、
[测试规则](docs/development/testing.md)和[本地化指南](docs/development/localization.md)；
翻译也通过 PR 提交。

编程智能体应先阅读 [AGENTS.md](AGENTS.md)，这是所有智能体和编辑器的共享指南。
如果工具不会自动加载，请要求它在修改前阅读 `AGENTS.md` 及其链接的规则。
共享指南应保存在这些文件中，各工具的入口文件仅引用它们。

## 许可证

Apache-2.0
