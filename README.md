# KDown

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-4c8dec?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Ktor](https://img.shields.io/badge/Ktor-3.4.0-087CFA.svg?logo=ktor&logoColor=white)](https://ktor.io)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Android](https://img.shields.io/badge/Android-24+-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![iOS](https://img.shields.io/badge/iOS-supported-000000.svg?logo=apple&logoColor=white)](https://developer.apple.com)
[![JVM](https://img.shields.io/badge/JVM-11+-DB380E.svg?logo=openjdk&logoColor=white)](https://openjdk.org)
[![Built with Claude Code](https://img.shields.io/badge/Built_with-Claude_Code-6b48ff.svg?logo=anthropic&logoColor=white)](https://claude.ai/claude-code)

A Kotlin Multiplatform download library with pause/resume, multi-threaded segmented downloads, and progress tracking.

> **WIP:** This project is under active development. APIs may change and some features are not yet complete. Contributions and feedback are welcome!

## Features

- **Multi-platform** - Android, iOS, JVM/Desktop, and WebAssembly (Wasm)
- **Segmented downloads** - Split files into N concurrent segments using HTTP Range requests for faster downloads
- **Pause / Resume** - True resume using HTTP Range headers, not restart-from-zero
- **Persistent resume** - Metadata is persisted so downloads survive app restarts
- **Resume validation** - ETag / Last-Modified checks ensure the remote file hasn't changed
- **Progress tracking** - Aggregated progress across all segments via `StateFlow`, with download speed
- **Retry with backoff** - Configurable exponential backoff for transient network errors and 5xx responses
- **Cancellation** - Robust coroutine-based cancellation
- **Pluggable HTTP engine** - Ships with a Ktor adapter; bring your own `HttpEngine` if needed

## Architecture

```
library/
  core/       # Platform-agnostic download engine (commonMain)
  ktor/       # Ktor-based HttpEngine implementation
  kermit/     # Optional Kermit logging integration
examples/
  cli/        # JVM CLI sample
  app/        # Compose Multiplatform app
  desktopApp/ # Desktop (JVM) app
  androidApp/ # Android app
  webApp/     # Wasm browser app
```

Key internal components:

| Class | Role |
|---|---|
| `KDown` | Main entry point |
| `DownloadCoordinator` | Orchestrates segment jobs, manages state |
| `SegmentDownloader` | Downloads a single byte-range segment |
| `RangeSupportDetector` | Probes server for Range/ETag/content-length |
| `FileAccessor` | expect/actual abstraction for random-access file writes |
| `JsonMetadataStore` | File-based metadata persistence (kotlinx-io) |

## Quick Start

```kotlin
// 1. Create a KDown instance
val kdown = KDown(
  httpEngine = KtorHttpEngine(),
  metadataStore = JsonMetadataStore(metadataDir),
  config = DownloadConfig(
    maxConnections = 4,
    retryCount = 3,
    retryDelayMs = 1000
  )
)

// 2. Start a download
val task = kdown.download(
  DownloadRequest(
    url = "https://example.com/large-file.zip",
    destPath = "/path/to/output.zip",
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

// 4. Pause / Resume / Cancel
task.pause()
val resumed = kdown.resume(task.taskId)
task.cancel()

// 5. Or just await the result
val result = task.await() // Result<String>
```

## Logging

KDown provides pluggable logging support. By default, logging is disabled for zero overhead.

### Built-in Console Logger

Use the platform-specific console logger for quick debugging:

```kotlin
val kdown = KDown(
  httpEngine = KtorHttpEngine(),
  logger = Logger.console()  // Logs to Logcat (Android), NSLog (iOS), stdout/stderr (JVM)
)
```

### Structured Logging with Kermit

For production use, integrate with [Kermit](https://github.com/touchlab/Kermit) for structured, multi-platform logging:

```kotlin
// Add dependency: implementation("com.linroid.kdown:kermit:1.0.0")

val kdown = KDown(
  httpEngine = KtorHttpEngine(),
  logger = KermitLogger(
    minSeverity = Severity.Debug,
    tag = "MyApp"
  )
)
```

### Custom Logger

Implement the `Logger` interface to integrate with your own logging framework:

```kotlin
class MyLogger : Logger {
  override fun v(message: () -> String) { /* verbose log */ }
  override fun d(message: () -> String) { /* debug log */ }
  override fun i(message: () -> String) { /* info log */ }
  override fun w(message: () -> String, throwable: Throwable?) { /* warning log */ }
  override fun e(message: () -> String, throwable: Throwable?) { /* error log */ }
}

val kdown = KDown(httpEngine = KtorHttpEngine(), logger = MyLogger())
```

**Log Levels:**
- **Verbose**: Detailed diagnostics (segment-level progress)
- **Debug**: Internal operations (server detection, metadata save/load)
- **Info**: User-facing events (download start/complete, server capabilities)
- **Warn**: Recoverable errors (retry attempts, validation warnings)
- **Error**: Fatal failures (download failures, network errors)

See [LOGGING.md](LOGGING.md) for detailed logging documentation.

## Modules

### `library:core`

The platform-agnostic download engine. No HTTP client dependency -- just the `HttpEngine` interface:

```kotlin
interface HttpEngine {
  suspend fun head(url: String): ServerInfo
  suspend fun download(url: String, range: LongRange?, onData: suspend (ByteArray) -> Unit)
  fun close()
}
```

### `library:ktor`

A ready-made `HttpEngine` backed by Ktor Client with per-platform engines:

| Platform | Ktor Engine |
|---|---|
| Android | OkHttp |
| iOS | Darwin |
| JVM | CIO |
| Wasm/JS | Js |

### `library:kermit`

Optional [Kermit](https://github.com/touchlab/Kermit) integration for production-grade structured logging across all platforms.

## Configuration

```kotlin
DownloadConfig(
  maxConnections = 4,           // max concurrent segments
  retryCount = 3,               // retries per segment
  retryDelayMs = 1000,          // base delay (exponential backoff)
  progressUpdateIntervalMs = 200, // progress throttle
  bufferSize = 8192             // read buffer size
)
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
| `Canceled` | No | Download was canceled |
| `Unknown` | No | Unexpected errors |

## How It Works

1. **Probe** -- HEAD request to get `Content-Length`, `Accept-Ranges`, `ETag`, `Last-Modified`
2. **Plan** -- If ranges are supported, split the file into N segments; otherwise fall back to a single connection
3. **Download** -- Each segment downloads its byte range concurrently and writes to the correct file offset
4. **Persist** -- Segment progress is saved to `MetadataStore` so pause/resume works across restarts
5. **Resume** -- On resume, validates server identity (ETag/Last-Modified), then continues from last offsets

## Current Limitations

- No download queue/scheduler (one `download()` call = one task)
- No bandwidth throttling
- WebAssembly file writes are limited by browser APIs
- iOS support is best-effort via expect/actual

## Building

```bash
# Build all
./gradlew build

# Run CLI example
./gradlew :examples:cli:run --args="https://example.com/file.zip"

# Run desktop example
./gradlew :examples:desktopApp:run
```

## License

Apache-2.0

---

*This project was built with [Claude Code](https://claude.ai/claude-code) by Anthropic.*
