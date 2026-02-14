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

## Installation

Add the dependencies to your `build.gradle.kts`:

```kotlin
// Version catalog (gradle/libs.versions.toml)
[versions]
kdown = "<latest-version>"

[libraries]
kdown-core = { module = "com.linroid.kdown:core", version.ref = "kdown" }
kdown-ktor = { module = "com.linroid.kdown:ktor", version.ref = "kdown" }
kdown-sqlite = { module = "com.linroid.kdown:sqlite", version.ref = "kdown" }
kdown-kermit = { module = "com.linroid.kdown:kermit", version.ref = "kdown" }
kdown-remote = { module = "com.linroid.kdown:remote", version.ref = "kdown" }
```

```kotlin
// build.gradle.kts
kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(libs.kdown.core)  // Download engine
      implementation(libs.kdown.ktor)  // HTTP engine (required by core)
    }
    // Optional modules
    commonMain.dependencies {
      implementation(libs.kdown.kermit)  // Kermit logging
      implementation(libs.kdown.remote)  // Remote client for daemon server
    }
    // SQLite persistence (not available on WasmJs)
    androidMain.dependencies { implementation(libs.kdown.sqlite) }
    iosMain.dependencies { implementation(libs.kdown.sqlite) }
    jvmMain.dependencies { implementation(libs.kdown.sqlite) }
  }
}
```

Or without a version catalog:

```kotlin
dependencies {
  implementation("com.linroid.kdown:core:<latest-version>")
  implementation("com.linroid.kdown:ktor:<latest-version>")
}
```

## Quick Start

```kotlin
// 1. Create a KDown instance
val kdown = KDown(
  httpEngine = KtorHttpEngine(),
  taskStore = createSqliteTaskStore(driverFactory),
  config = DownloadConfig(
    maxConnections = 4,
    retryCount = 3,
    queueConfig = QueueConfig(maxConcurrentDownloads = 3)
  )
)

// 2. Start a download
val task = kdown.download(
  DownloadRequest(
    url = "https://example.com/large-file.zip",
    directory = "/path/to/downloads",
    connections = 4
  )
)

// 3. Observe progress
launch {
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
}

// 4. Control the download
task.pause()
task.resume()
task.cancel()

// 5. Or just await the result
val result: Result<String> = task.await()
```

## Modules

KDown is split into published SDK modules that you add as dependencies:

| Module | Description | Platforms |
|---|---|---|
| `library:api` | Public API interfaces and models (`KDownApi`, `DownloadTask`, `DownloadState`, etc.) | All |
| `library:core` | In-process download engine -- embed downloads directly in your app | All |
| `library:ktor` | Ktor-based `HttpEngine` implementation (required by `core`) | All |
| `library:sqlite` | SQLite-backed `TaskStore` for persistent resume | Android, iOS, Desktop |
| `library:kermit` | Optional [Kermit](https://github.com/touchlab/Kermit) logging integration | All |
| `library:remote` | Remote client -- control a KDown daemon server from any platform | All |
| `server` | Daemon server with REST API and SSE events (not an SDK; standalone service) | Desktop |
| [`cli`](cli/README.md) | Command-line interface for downloads and running the daemon | Desktop |

Choose your backend: use **`core`** for in-process downloads, or **`remote`** to control a daemon server. Both implement the same `KDownApi` interface, so your UI code works identically.

### `library:api`

The public API surface. Both `library:core` and `library:remote` implement the `KDownApi` interface,
so UI code works identically regardless of backend:

```kotlin
interface KDownApi {
  val tasks: StateFlow<List<DownloadTask>>
  suspend fun download(request: DownloadRequest): DownloadTask
  suspend fun setGlobalSpeedLimit(limit: SpeedLimit)
  fun close()
  // ... plus backendLabel, version
}
```

### `library:core`

The in-process download engine. Depends on an `HttpEngine` interface (no HTTP client dependency):

```kotlin
interface HttpEngine {
  suspend fun head(url: String, headers: Map<String, String> = emptyMap()): ServerInfo
  suspend fun download(url: String, range: LongRange?, headers: Map<String, String> = emptyMap(), onData: suspend (ByteArray) -> Unit)
  fun close()
}
```

### `library:ktor`

Ready-made `HttpEngine` backed by Ktor Client with per-platform engines:

| Platform | Ktor Engine |
|---|---|
| Android | OkHttp |
| iOS | Darwin |
| Desktop | CIO |
| WasmJs | Js |

## Configuration

```kotlin
DownloadConfig(
  maxConnections = 4,             // max concurrent segments per task
  retryCount = 3,                 // retries per segment
  retryDelayMs = 1000,            // base delay (exponential backoff)
  progressUpdateIntervalMs = 200, // progress throttle
  bufferSize = 8192,              // read buffer size
  speedLimit = SpeedLimit.kbps(500), // global speed limit
  queueConfig = QueueConfig(
    maxConcurrentDownloads = 3,   // max simultaneous downloads
    maxConnectionsPerHost = 4,    // per-host limit
    autoStart = true              // auto-start queued tasks
  )
)
```

### Priority & Scheduling

```kotlin
// High-priority download
kdown.download(
  DownloadRequest(
    url = "https://example.com/urgent.zip",
    directory = "/downloads",
    priority = DownloadPriority.URGENT  // preempts lower-priority tasks
  )
)

// Scheduled download
kdown.download(
  DownloadRequest(
    url = "https://example.com/file.zip",
    directory = "/downloads",
    schedule = DownloadSchedule.AtTime(startAt),
    conditions = listOf(wifiOnlyCondition)
  )
)

// Speed limiting
task.setSpeedLimit(SpeedLimit.mbps(1))        // per-task
kdown.setGlobalSpeedLimit(SpeedLimit.kbps(500)) // global
```

## Error Handling

All errors are modeled as a sealed class `KDownError`:

| Type | Retryable | Description |
|---|---|---|
| `Network` | Yes | Connection / timeout failures |
| `Http(code)` | 5xx only | Non-success HTTP status |
| `Disk` | No | File I/O failures |
| `Unsupported` | No | Server doesn't support required features |
| `ValidationFailed` | No | ETag / Last-Modified mismatch on resume |
| `SourceError` | No | Error from a pluggable download source |
| `Canceled` | No | Download was canceled |
| `Unknown` | No | Unexpected errors |

## Logging

KDown provides pluggable logging with zero overhead when disabled (default).

```kotlin
// No logging (default)
KDown(httpEngine = KtorHttpEngine())

// Console logging (development)
KDown(httpEngine = KtorHttpEngine(), logger = Logger.console())

// Kermit structured logging (production)
KDown(httpEngine = KtorHttpEngine(), logger = KermitLogger(minSeverity = Severity.Debug))
```

See [Logging](docs/logging.md) for detailed documentation.

## How It Works

1. **Resolve** -- Query the download source (HEAD request for HTTP) to get size, range support, identity headers
2. **Plan** -- If ranges are supported, split the file into N segments; otherwise use a single connection
3. **Queue** -- If max concurrent downloads reached, queue with priority ordering
4. **Download** -- Each segment downloads its byte range concurrently and writes to the correct file offset
5. **Throttle** -- Token-bucket speed limiter controls bandwidth per task and globally
6. **Persist** -- Segment progress is saved to `TaskStore` so pause/resume works across restarts
7. **Resume** -- On resume, validates server identity (ETag/Last-Modified) and file integrity, then continues

## Daemon Server

Run KDown as a background service and control it remotely:

```kotlin
// Server side (Desktop)
val kdown = KDown(httpEngine = KtorHttpEngine())
val server = KDownServer(kdown)
server.start()  // REST API + SSE on port 8642

// Client side (any platform)
val remote = RemoteKDown(baseUrl = "http://localhost:8642")
val task = remote.download(DownloadRequest(url = "...", directory = "..."))
task.state.collect { /* real-time updates via SSE */ }
```

Or start the daemon from the CLI:

```bash
# Start with defaults
kdown server

# With a TOML config file
kdown server --config /path/to/config.toml

# Generate a default config file
kdown server --generate-config
```

See the [CLI documentation](cli/README.md) for all commands, flags, and config file reference.

## Platform Support

| Feature | Android | Desktop | iOS | WasmJs |
|---|---|---|---|---|
| Segmented downloads | Yes | Yes | Yes | Remote only* |
| Pause / Resume | Yes | Yes | Yes | Remote only* |
| SQLite persistence | Yes | Yes | Yes | No |
| Console logging | Logcat | stdout/stderr | NSLog | println |
| Daemon server | -- | Yes | -- | -- |

\*WasmJs: Local file I/O is not supported. Use `RemoteKDown` to control a daemon server from the browser.

## CLI

KDown includes a native CLI for downloading files and running the daemon server.

### Install

```bash
curl -fsSL https://raw.githubusercontent.com/linroid/KDown/main/install.sh | bash
```

Options via environment variables:

```bash
# Install a specific version
curl -fsSL https://raw.githubusercontent.com/linroid/KDown/main/install.sh | KDOWN_VERSION=0.1.0 bash

# Install to a custom directory
curl -fsSL https://raw.githubusercontent.com/linroid/KDown/main/install.sh | KDOWN_INSTALL=~/.local/bin bash
```

Supported platforms: **macOS** (arm64), **Linux** (x64, arm64), **Windows** (x64).

### Usage

```bash
# Download a file
kdown https://example.com/file.zip

# Start the daemon server
kdown server

# Run from source (for development)
./gradlew :cli:run --args="https://example.com/file.zip"
```

See the [CLI documentation](cli/README.md) for all commands, flags, and config file reference.

## Contributing

Contributions are welcome! Please open an issue to discuss your idea before submitting a PR.
See the [code style rules](.claude/rules/code-style.md) for formatting guidelines.

## License

Apache-2.0

---

*Built with [Claude Code](https://claude.ai/claude-code) by Anthropic.*
