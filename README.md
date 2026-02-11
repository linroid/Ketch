# KDown

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-4c8dec?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Ktor](https://img.shields.io/badge/Ktor-3.4.0-087CFA.svg?logo=ktor&logoColor=white)](https://ktor.io)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Android](https://img.shields.io/badge/Android-26+-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![iOS](https://img.shields.io/badge/iOS-supported-000000.svg?logo=apple&logoColor=white)](https://developer.apple.com)
[![JVM](https://img.shields.io/badge/JVM-11+-DB380E.svg?logo=openjdk&logoColor=white)](https://openjdk.org)
[![Built with Claude Code](https://img.shields.io/badge/Built_with-Claude_Code-6b48ff.svg?logo=anthropic&logoColor=white)](https://claude.ai/claude-code)

A Kotlin Multiplatform download manager with segmented downloads, pause/resume, queue management, speed limiting, and scheduling -- for Android, JVM, iOS, and WebAssembly.

> **WIP:** This project is under active development. APIs may change. Contributions and feedback are welcome!

## Features

- **Multi-platform** -- Android, iOS, JVM/Desktop, and WebAssembly (WasmJs)
- **Segmented downloads** -- Split files into N concurrent segments using HTTP Range requests
- **Pause / Resume** -- True resume using byte ranges, with ETag/Last-Modified validation
- **Queue management** -- Priority-based queue with configurable concurrency limits and per-host throttling
- **Speed limiting** -- Global and per-task bandwidth throttling via token-bucket algorithm
- **Scheduling** -- Start downloads at a specific time, after a delay, or when conditions are met
- **Download conditions** -- User-defined conditions (e.g., WiFi-only) that gate download start
- **Pluggable sources** -- Extensible `DownloadSource` interface for custom protocols (HTTP built-in)
- **Persistent resume** -- Task metadata survives app restarts via pluggable `TaskStore`
- **Progress tracking** -- Aggregated progress across segments via `StateFlow`, with download speed
- **Retry with backoff** -- Configurable exponential backoff for transient errors
- **Daemon server** -- Run KDown as a background service with REST API and SSE events
- **Remote control** -- Control a daemon server from any client via `RemoteKDown`
- **Pluggable HTTP engine** -- Ships with Ktor; bring your own `HttpEngine` if needed

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
| `library:sqlite` | SQLite-backed `TaskStore` for persistent resume | Android, iOS, JVM |
| `library:kermit` | Optional [Kermit](https://github.com/touchlab/Kermit) logging integration | All |
| `library:remote` | Remote client -- control a KDown daemon server from any platform | All |
| `server` | Daemon server with REST API and SSE events (not an SDK; standalone service) | JVM |

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
| JVM | CIO |
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

See [LOGGING.md](LOGGING.md) for detailed documentation.

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
// Server side (JVM)
val kdown = KDown(httpEngine = KtorHttpEngine())
val server = KDownServer(kdown)
server.start()  // REST API + SSE on port 8642

// Client side (any platform)
val remote = RemoteKDown(baseUrl = "http://localhost:8642")
val task = remote.download(DownloadRequest(url = "...", directory = "..."))
task.state.collect { /* real-time updates via SSE */ }
```

## Platform Support

| Feature | Android | JVM | iOS | WasmJs |
|---|---|---|---|---|
| Segmented downloads | Yes | Yes | Yes | Remote only* |
| Pause / Resume | Yes | Yes | Yes | Remote only* |
| SQLite persistence | Yes | Yes | Yes | No |
| Console logging | Logcat | stdout/stderr | NSLog | println |
| Daemon server | -- | Yes | -- | -- |

\*WasmJs: Local file I/O is not supported. Use `RemoteKDown` to control a daemon server from the browser.

## Building

```bash
# Build all modules
./gradlew build

# Run CLI
./gradlew :cli:run --args="https://example.com/file.zip"

# Run desktop app
./gradlew :app:desktop:run
```

## Contributing

Contributions are welcome! Please open an issue to discuss your idea before submitting a PR.
See the [code style rules](.claude/rules/code-style.md) for formatting guidelines.

## License

Apache-2.0

---

*Built with [Claude Code](https://claude.ai/claude-code) by Anthropic.*
