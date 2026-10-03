<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="art/icon-white.svg">
    <source media="(prefers-color-scheme: light)" srcset="art/icon-app.svg">
    <img alt="Ketch" src="art/icon-app.svg" height="128">
  </picture>
</p>

<h1 align="center">Ketch</h1>

<p align="center">
  <b>A fast, open-source download manager for every device you own.</b><br>
  macOS · Windows · Linux · Android · iOS · Web · Command line
</p>

<p align="center">

[![Latest release](https://img.shields.io/github/v/release/linroid/Ketch?include_prereleases&label=Download&logo=github)](https://github.com/linroid/Ketch/releases/latest)
[![Web app](https://img.shields.io/badge/Web_app-open-4F5DE4.svg?logo=webassembly&logoColor=white)](https://linroid.com/Ketch/)
[![Android](https://img.shields.io/badge/Android-8.0+-3DDC84.svg?logo=android&logoColor=white)](https://github.com/linroid/Ketch/releases/latest)
[![iOS](https://img.shields.io/badge/iOS-18+-000000.svg?logo=apple&logoColor=white)](app/ios/)
[![Desktop](https://img.shields.io/badge/Desktop-macOS_|_Windows_|_Linux-DB380E.svg)](https://github.com/linroid/Ketch/releases/latest)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

</p>

<p align="center">
  <a href="#download"><b>Download</b></a> ·
  <a href="#features"><b>Features</b></a> ·
  <a href="#getting-started"><b>Getting started</b></a> ·
  <a href="docs/developers.md"><b>For developers</b></a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="art/showcase-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="art/showcase-light.png">
    <img alt="Ketch on the desktop, Android, iOS and the command line: the downloads table with each download split into live connection lanes, the devices on Android, a download's connections on iOS, and ketch in a terminal" src="art/showcase-light.png" width="100%">
  </picture>
</p>

Ketch splits every download across parallel connections, and shows each one as a live lane. It
takes web links, FTP servers, torrents and magnet links in one place. Pair your laptop, phone and
home server, and you can watch and control the downloads on all of them from any one of them.

> [!WARNING]
> 🚧 Ketch is in active development and only has release candidates so far. Expect rough edges,
> and please [report what you find](https://github.com/linroid/Ketch/issues).

## Download

| Platform | Get it |
|---|---|
| macOS (Apple silicon, Intel) | `.dmg` from the [latest release][release] |
| Windows (x64, ARM64) | `.msi` from the [latest release][release] |
| Linux (x64, ARM64) | `.deb` from the [latest release][release] |
| Android 8.0+ | `.apk` from the [latest release][release] |
| iOS 18+ | Build from source with Xcode ([`app/ios`](app/ios/)) |
| Web | [linroid.com/Ketch](https://linroid.com/Ketch/), to control Ketch running on another device |
| Browser extension | Chrome, Edge, Brave, Firefox and others: `.zip` from the [latest release][release] ([how to install](app/browser-extension/README.md#installing)) |
| Command line and server | macOS, Linux and Windows: [install script](#run-ketch-on-a-server) or the [latest release][release] |

The desktop apps bring their own runtime, and the command line is a single native binary: neither
needs Java. Both update themselves from the releases here: the desktop app from Settings → About,
the command line with `ketch update` ([how updates work](docs/updates.md)).

[release]: https://github.com/linroid/Ketch/releases/latest

## Features

### Faster downloads

- **Parallel connections** — Ketch splits a file into ranges and downloads them at once. The
  Connections tab shows every connection as a lane with its own speed, and you can add or remove
  connections while the download runs.
- **Several networks at once** — Spread one download over Wi-Fi, Ethernet and, on Android,
  cellular data ([how it works](docs/multiple-networks.md)).
- **Pause and resume, even after a restart** — Ketch checks that the file on the server has not
  changed before it continues, and retries failed connections on its own.

### Every kind of link

- **HTTP and HTTPS**, with the cookies and referrer of the page when the browser extension sends
  the download.
- **FTP and FTPS**, with parallel connections and resume.
- **BitTorrent and magnet links** — v1, v2 and hybrid torrents, with a choice of files, in an
  engine written in pure Kotlin ([details](docs/torrent.md)).
- Ketch can open magnet links and `.torrent` files for your system, so a click in the browser or
  the file manager lands in Ketch.

### All your devices in one app

- **Pair by QR code** — On the device to share, open **Settings → Sharing** and choose
  **Allow another device**; scan the code with your phone, or copy the pairing link. Or pick
  the device under **Find on network** and allow it there, once both show the same four digits.
- **Every device in the sidebar** with its live speed and health. Switch between them with a
  keystroke, or open **All devices** to list every download in one table.
- **Send downloads where they belong** — Add a link to any device, drop it on a device in the
  sidebar, or send or move a download to another device, without switching.
- **Headless on a NAS or server** — `ketch server` runs the same engine with a REST API and the
  web app built in, and the apps find it on your network.

### In control of your bandwidth

- **Speed modes** — Full speed, **Slow lane** to leave room for a video call, or **Auto**, which
  switches between them on a weekly schedule.
- **Per-download control** — Speed limits, connection counts and priorities you can change while
  a download runs. **Urgent** pauses a less important download to start right away.
- **A queue that behaves** — Limit how many downloads run at once and per site, and start a
  download later, at a time you pick.

### Built for how you work

- **Browser extension** for Chrome, Edge, Brave, Firefox and other browsers: it takes over
  downloads and magnet links and adds **Download with Ketch** to the context menu, for this
  computer or another device ([details](app/browser-extension/README.md)).
- **Paste to download** — Paste a link to add it, with Undo, or let Ketch suggest the links you
  copy.
- **Keyboard first** on the desktop: `⌘K` (`Ctrl+K` on Windows and Linux) opens the command
  palette, and the shortcut sheet lists every key.
- **Finished means openable** — Open a file, show it in its folder or drag it out, straight from
  the list.
- **At home on each platform** — The menu bar or tray, notifications, Dock and taskbar progress
  on the desktop; downloads that keep running in the background on Android and on iOS 26.
- **Your look, your language** — Light and dark themes with four accent colors, in English,
  简体中文, 繁體中文, 日本語, 한국어, Español, Português (Brasil), Deutsch and Français
  ([translating](docs/development/localization.md)).

### Find downloads with AI (preview)

- **Discover** — Describe what you want, such as "the latest Ubuntu Server ISO", and an AI agent
  searches the web, checks the links and ranks the downloads it finds. A failed download can
  **Find another source** the same way. Bring your own model: OpenAI, Anthropic, Gemini, Ollama
  or any OpenAI-compatible service. In the desktop and Android apps, and with
  `ketch ai-discover` ([setup](docs/ai-discovery.md)).
- **MCP server** — `ketch mcp` lets AI assistants start, watch and manage your downloads through
  the [Model Context Protocol](cli/README.md#mcp-server).

## Getting started

1. Install Ketch from [Download](#download) and open it. On phones, a few welcome screens ask
   where downloads go.
2. Add a download: paste a link (`⌘V`, or `Ctrl+V`), drop a link or a `.torrent` file on the
   window, or choose **Add**.
3. To control this computer from your phone, open **Settings → Sharing**, choose
   **Allow another device** and scan the QR code with the phone's camera.
4. Install the [browser extension](app/browser-extension/README.md) to send the browser's
   downloads to Ketch.

### Run Ketch on a server

Install the command line on macOS or Linux (on Windows, take the `.zip` from the
[latest release][release]):

```bash
curl -fsSL https://raw.githubusercontent.com/linroid/Ketch/main/install.sh | bash
```

Start the server, with its REST API and web app on port 8642:

```bash
ketch server
```

Then add it in the apps under **Devices → Add device**, or open `http://<server address>:8642` in
a browser. The command line also downloads on its own:

```bash
ketch https://example.com/file.zip
```

Set an access code, the port and other options in a [config file](cli/README.md#configuration-file);
the [CLI documentation](cli/README.md) lists every command.

## Roadmap

- **Metalink** — Downloads from several mirrors at once, with checksums
- **WebDAV** — Download from WebDAV servers, with resume
- **HLS and DASH** — Download and merge HTTP Live Streaming and MPEG-DASH videos, choosing the
  quality and its matching audio
- **Media extraction** — Save the media of web pages
- **Resource sniffer** — Find the downloadable files on a web page
- **Checksums** — Check a download against a hash you provide, or one the server publishes
- **Proxy** — Download through an HTTP or SOCKS5 proxy or the system proxy, with a bypass list
- **Faster segmented downloads** — Connections that finish early take over the rest of the slower
  ones, so a download no longer waits on its slowest connection
- **Stall detection and retry settings** — Reconnect when a connection stops sending data, and
  choose timeouts and how often to retry, unlimited included
- **Unfinished files look unfinished** — Downloads are written under a temporary name and get
  their real name once complete
- **Category folders** — Save videos, music, documents and archives to folders of their own, by
  rules you set
- **Torrent files, chosen any time** — Pick the files of a magnet link once its details arrive,
  and change the selection while it downloads
- **Keep awake** — Keep the computer from sleeping while downloads run, and optionally sleep or
  shut down when they finish
- **Automation hooks** — Run a command or call a webhook when a download finishes or fails
- **Command line for running devices** — `ketch` and AI agents add, list, pause and watch the
  downloads of the Ketch app or a server, instead of starting a second engine
- **Docker image** — An official image for NAS and home servers on x64 and ARM, with a health
  check and a fixed torrent port
- **Transfers between devices** — Send to and Move to carry what is already downloaded, so the
  other device continues instead of starting over ([plan](docs/plans/task-transfer.md))
- **Helper devices** — Let your other devices download parts of the same file over their own
  connection, added or removed while it runs ([proposal](docs/design/multi-instance-downloads.md))

## For developers

Everything the apps do is built on Ketch's Kotlin Multiplatform library, which you can use in your
own app: embed the download engine on Android, iOS, the JVM, Node.js or WASI, or control a Ketch
server from Android, iOS, the JVM or the browser, through the same `KetchApi`.

- [Developer guide](docs/developers.md) — Modules, a quick start, the REST API and extending Ketch
- [API reference](docs/api.md) — Installation, configuration, priorities, errors and logging
- [Architecture](docs/architecture.md) — The download pipeline and how the apps are put together

## Documentation

- [Command line](cli/README.md) — Downloads, the server, MCP, AI discovery and the config file
- [Browser extension](app/browser-extension/README.md) — Setup, capturing, permissions and
  privacy
- [BitTorrent](docs/torrent.md) — Torrent and magnet support and its limits
- [Multiple networks](docs/multiple-networks.md) — Spreading downloads across network interfaces
- [AI discovery](docs/ai-discovery.md) — Providers, keys, web search and environment variables
- [Logging](docs/logging.md) — Where the logs are, and what to attach to a bug report

## Contributing

Contributions are welcome! Please open an issue to discuss your idea before submitting a PR.
See the [code style rules](docs/development/code-style.md),
[testing rules](docs/development/testing.md) and [localization guide](docs/development/localization.md)
for development guidelines; translations come in by pull request.

Coding agents should start with [AGENTS.md](AGENTS.md), the shared instructions for all agents
and editors. If your tool does not load it automatically, ask it to read `AGENTS.md` and its linked
rules before making changes. Keep shared instructions in these files; tool-specific entry points
should only reference them.

## License

Apache-2.0
