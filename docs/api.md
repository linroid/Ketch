# API Reference

## Installation

Add the dependencies to your `build.gradle.kts`:

```kotlin
// Version catalog (gradle/libs.versions.toml)
[versions]
ketch = "<latest-version>"

[libraries]
ketch-core = { module = "com.linroid.ketch:core", version.ref = "ketch" }
ketch-ktor = { module = "com.linroid.ketch:ktor", version.ref = "ketch" }
ketch-sqlite = { module = "com.linroid.ketch:sqlite", version.ref = "ketch" }
ketch-kermit = { module = "com.linroid.ketch:kermit", version.ref = "ketch" }
ketch-remote = { module = "com.linroid.ketch:remote", version.ref = "ketch" }
```

```kotlin
// build.gradle.kts
kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(libs.ketch.core)  // Download engine
      implementation(libs.ketch.ktor)  // HTTP engine (required by core)
    }
    // Optional modules
    commonMain.dependencies {
      implementation(libs.ketch.kermit)  // Kermit logging
      implementation(libs.ketch.remote)  // Remote client for daemon server
    }
    // SQLite persistence (not available on WasmJs)
    androidMain.dependencies { implementation(libs.ketch.sqlite) }
    iosMain.dependencies { implementation(libs.ketch.sqlite) }
    jvmMain.dependencies { implementation(libs.ketch.sqlite) }
  }
}
```

Or without a version catalog:

```kotlin
dependencies {
  implementation("com.linroid.ketch:core:<latest-version>")
  implementation("com.linroid.ketch:ktor:<latest-version>")
}
```

## Modules

### `library:api`

The public API surface. Both `library:core` and `library:remote` implement the `KetchApi` interface,
so UI code works identically regardless of backend:

```kotlin
interface KetchApi {
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
  maxConnectionsPerDownload = 4,  // max concurrent segments per task
  retryCount = 3,                 // retries per segment
  retryDelayMs = 1000,            // base delay (exponential backoff)
  progressIntervalMs = 200,       // progress throttle
  bufferSize = 8192,              // read buffer size
  speedLimit = SpeedLimit.kbps(500), // global speed limit
  maxConcurrentDownloads = 3,     // max simultaneous downloads (0 = unlimited)
  maxConnectionsPerHost = 4,      // per-host limit (0 = unlimited)
)
```

### Priority & Scheduling

```kotlin
// High-priority download
ketch.download(
  DownloadRequest(
    url = "https://example.com/urgent.zip",
    directory = "/downloads",
    priority = DownloadPriority.URGENT  // preempts lower-priority tasks
  )
)

// Scheduled download
ketch.download(
  DownloadRequest(
    url = "https://example.com/file.zip",
    directory = "/downloads",
    schedule = DownloadSchedule.AtTime(startAt),
    conditions = listOf(wifiOnlyCondition)
  )
)

// Speed limiting
task.setSpeedLimit(SpeedLimit.mbps(1))        // per-task
ketch.setGlobalSpeedLimit(SpeedLimit.kbps(500)) // global
```

## Error Handling

All errors are modeled as a sealed class `KetchError`:

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

Ketch provides pluggable logging with zero overhead when disabled (default).

```kotlin
// No logging (default)
Ketch(httpEngine = KtorHttpEngine())

// Console logging (development)
Ketch(httpEngine = KtorHttpEngine(), logger = Logger.console())

// Kermit structured logging (production)
Ketch(httpEngine = KtorHttpEngine(), logger = KermitLogger(minSeverity = Severity.Debug))
```

See [Logging](logging.md) for detailed documentation.
