# KDown

[![Maven Central](https://img.shields.io/maven-central/v/com.linroid.kdown/core?label=Maven%20Central&logo=apache-maven&logoColor=white)](https://central.sonatype.com/namespace/com.linroid.kdown)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-4c8dec?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Ktor](https://img.shields.io/badge/Ktor-3.4.0-087CFA.svg?logo=ktor&logoColor=white)](https://ktor.io)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Android](https://img.shields.io/badge/Android-26+-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![iOS](https://img.shields.io/badge/iOS-supported-000000.svg?logo=apple&logoColor=white)](https://developer.apple.com)
[![Desktop](https://img.shields.io/badge/Desktop-JVM_11+-DB380E.svg?logo=openjdk&logoColor=white)](https://openjdk.org)
[![Web](https://img.shields.io/badge/Web-WasmJs-E4A125.svg?logo=webassembly&logoColor=white)](https://kotlinlang.org/docs/wasm-overview.html)
[![Built with Claude Code](https://img.shields.io/badge/Built_with-Claude_Code-6b48ff.svg?logo=anthropic&logoColor=white)](https://claude.ai/claude-code)

A full-featured Kotlin Multiplatform download manager — run locally, remotely, or embedded in your app. Supports Android, iOS, Desktop, and Web.

- **Embed it** — Add downloads to your Android, iOS, or Desktop app with a simple API
- **Run it as a daemon** — Self-hosted download server with REST API and real-time SSE events
- **Control it remotely** — Manage a daemon from any client (mobile app, web UI, CLI, or AI agent)
- **Extend it** — Pluggable architecture for custom protocols (FTP, BitTorrent, HLS, and more on the roadmap)

> [!WARNING]
> 🚧 **Work in Progress** — This project is under active development. APIs may change. Contributions and feedback are welcome!

## Features

- **Multi-platform** `✅` -- Works on Android, iOS, Desktop, and Web
- **Segmented downloads** `✅` -- Accelerate downloads by splitting files into multiple parallel connections
- **Pause / Resume** `✅` -- Pause and pick up where you left off, even after restarting your app
- **Queue management** `✅` -- Manage multiple downloads with priorities and concurrency limits
- **Speed limiting** `✅` -- Control bandwidth usage per task or globally
- **Scheduling** `✅` -- Schedule downloads for a specific time, after a delay, or based on conditions
- **Automatic retry** `✅` -- Automatically retry failed downloads with smart backoff
- **Daemon server** `✅` -- Run as a background service with REST API and real-time events
- **Remote control** `✅` -- Manage a remote server from any client (mobile, desktop, web, or CLI)
- **Pluggable architecture** `✅` -- Swap out HTTP engines, storage backends, and download sources
- **FTP/FTPS** `🔜` -- Download from FTP servers
- **BitTorrent** `🔜` -- Peer-to-peer file sharing
- **Magnet links** `🔜` -- Start BitTorrent downloads from magnet links
- **HLS streaming** `🔜` -- Download and save HTTP Live Streaming videos
- **Resource sniffer** `🔜` -- Detect downloadable files from web pages
- **Media downloads** `🔜` -- Extract and download media from websites (like yt-dlp)
- **Browser extension** `🔜` -- Intercept and manage downloads directly from your browser
- **AI integration** `🔜` -- Control downloads via AI agents using MCP

## Getting Started

### Embed in Your App

Add the SDK to your Kotlin Multiplatform project:

```kotlin
// build.gradle.kts
dependencies {
  implementation("com.linroid.kdown:core:<latest-version>")
  implementation("com.linroid.kdown:ktor:<latest-version>")
}
```

Start downloading:

```kotlin
val kdown = KDown(
  httpEngine = KtorHttpEngine(),
  config = DownloadConfig(
    maxConnections = 4,
    queueConfig = QueueConfig(maxConcurrentDownloads = 3)
  )
)

val task = kdown.download(
  DownloadRequest(
    url = "https://example.com/large-file.zip",
    directory = "/path/to/downloads",
  )
)

// Observe progress
task.state.collect { state ->
  when (state) {
    is DownloadState.Downloading -> {
      val p = state.progress
      println("${(p.percent * 100).toInt()}%  ${p.bytesPerSecond / 1024} KB/s")
    }
    is DownloadState.Completed -> println("Done: ${state.filePath}")
    is DownloadState.Failed -> println("Error: ${state.error}")
    else -> {}
  }
}
```

See [Installation](docs/api.md) for version catalog setup, optional modules (SQLite persistence, Kermit logging, remote client), and the full API reference.

### Desktop App

Download the latest desktop app from [GitHub Releases](https://github.com/linroid/KDown/releases/latest):

| Platform | Format |
|---|---|
| macOS (arm64) | `.dmg` |
| Linux (x64, arm64) | `.deb` |
| Windows (x64, arm64) | `.msi` |

### CLI / Server

Install the native CLI to run KDown as a daemon on your server:

```bash
curl -fsSL https://raw.githubusercontent.com/linroid/KDown/main/install.sh | bash
```

Then start the daemon:

```bash
# Start the server with REST API + web UI on port 8642
kdown server

# Download a file directly
kdown https://example.com/file.zip

# Use a TOML config file
kdown server --config /path/to/config.toml
```

Supported platforms: **macOS** (arm64), **Linux** (x64, arm64), **Windows** (x64). See the [CLI documentation](cli/README.md) for all commands, flags, and config file reference.

## How It Works

1. **Resolve** -- Query the download source (HEAD request for HTTP) to get size, range support, identity headers
2. **Plan** -- If ranges are supported, split the file into N segments; otherwise use a single connection
3. **Queue** -- If max concurrent downloads reached, queue with priority ordering
4. **Download** -- Each segment downloads its byte range concurrently and writes to the correct file offset
5. **Throttle** -- Token-bucket speed limiter controls bandwidth per task and globally
6. **Persist** -- Segment progress is saved to `TaskStore` so pause/resume works across restarts
7. **Resume** -- On resume, validates server identity (ETag/Last-Modified) and file integrity, then continues

## Documentation

- [Architecture](docs/architecture.md) -- Modules, dependency graph, download pipeline, and multi-backend design
- [API Reference](docs/api.md) -- Installation, module interfaces, configuration, error handling, and logging
- [Logging](docs/logging.md) -- Logging system and configuration
- [CLI](cli/README.md) -- Command-line interface for downloads and running the daemon

## Contributing

Contributions are welcome! Please open an issue to discuss your idea before submitting a PR.
See the [code style rules](.claude/rules/code-style.md) for formatting guidelines.

## License

Apache-2.0

---

*Built with [Claude Code](https://claude.ai/claude-code) by Anthropic.*
