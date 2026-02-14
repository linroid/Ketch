# API Reference

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

## Modules

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

See [Logging](logging.md) for detailed documentation.
