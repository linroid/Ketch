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

The optional Kermit integration supports Android, JVM, iOS, JavaScript, and WasmJs.
It does not support WASI because Kermit 2.1.0 does not publish a WASI variant. Projects with
a WASI target should add Kermit only to source sets shared by its supported targets.

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
  suspend fun updateConfig(config: DownloadConfig)
  fun close()
  // ... plus backendLabel, version
}
```

`KetchApi.download(...)` returns a `DownloadTask` for controlling an
individual download. Tasks expose reactive state and per-task actions:

```kotlin
interface DownloadTask {
  val taskId: String
  val request: DownloadRequest
  val state: StateFlow<DownloadState>
  val segments: StateFlow<List<Segment>>

  suspend fun pause()
  suspend fun resume(destination: Destination? = null)
  suspend fun cancel()
  suspend fun setSpeedLimit(limit: SpeedLimit)
  suspend fun setPriority(priority: DownloadPriority)
  suspend fun setConnections(connections: Int)
  suspend fun reschedule(
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition> = emptyList(),
  )

  /**
   * Cancels the download and removes it from the task list.
   *
   * @param deleteFiles when `true`, also delete the data this task
   *   wrote to disk (partial bytes, completed file, or a torrent's
   *   save path). Deletion is best-effort — failures are logged
   *   and do not prevent the task record from being removed.
   *   Defaults to `false`.
   */
  suspend fun remove(deleteFiles: Boolean = false)

  /** Suspends until the task reaches a terminal state. */
  suspend fun await(): Result<String>
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

For downloading across multiple interfaces, wrap network-bound engines in
`MultiNetworkHttpEngine`. JVM provides `KtorHttpEngine.forLocalAddress(InetAddress)`;
Android provides `KtorHttpEngine.forNetwork(Network)`. These are extension functions in
`com.linroid.ketch.engine` and must be imported explicitly. See
[multiple network interfaces](multiple-networks.md) for setup and platform limits.

`KetchApi.networkInterfaces()` returns supported interfaces and their current selection on the
download instance. `KetchApi.updateNetworkInterfaces(NetworkInterfaceConfig(interfaceIds))`
changes the selection for subsequent HTTP requests; an empty list restores system-default
routing. `RemoteKetch` forwards these methods through `GET`/`PUT /api/network-interfaces`.
Selection is runtime-only. SDK instances enable these controls with
`KtorHttpEngine.withNetworkInterfaces()` on JVM or
`KtorHttpEngine.withNetworkInterfaces(connectivityManager)` on Android.

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

`ketch.updateConfig(config)` replaces the configuration at runtime. `speedLimit`,
`maxConcurrentDownloads` and `maxConnectionsPerHost` apply immediately: raising a queue limit
starts queued downloads, lowering one lets running downloads finish. All other fields apply to
downloads that start or resume afterwards; pause and resume a running download to pick them up.

The per-host limit counts downloads by URL host (case-insensitive, ignoring user info and port).
Magnet links, `torrent:` identifiers and local files are not counted.

`maxConnectionsPerDownload` and `DownloadRequest.connections` split HTTP(S) and FTP(S)
downloads into parallel ranges when the server supports HTTP Range or FTP REST; otherwise a
single connection is used. BitTorrent treats a per-task connection count as its peer limit.
`bufferSize` sets the FTP read buffer; HTTP buffering is up to the `HttpEngine`.

### Priority & Scheduling

`task.setPriority(priority)` persists the change and updates `task.requestState`, including
for active, paused, and scheduled tasks. LOW, NORMAL, and HIGH determine which queued task
starts next. Changing a queued task to URGENT can immediately pause a lower-priority active
task to make room. The interrupted task is requeued and resumes when a slot opens.
Concurrency and per-host limits still apply; other URGENT tasks cannot be preempted.
Priority changes do not resume manually paused tasks or bypass schedules and conditions.

Priority does not reserve or weight bandwidth among active downloads. Use per-task
`setSpeedLimit` and the global speed limit to control transfer rates.

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
ketch.updateConfig(config.copy(speedLimit = SpeedLimit.kbps(500))) // global
```

A per-task limit applies in addition to the global one, so a task never exceeds the lower of
the two. `setSpeedLimit`, `setConnections` and `setPriority` are persisted for queued, scheduled,
paused and failed tasks and take effect when the task starts or resumes; `reschedule` is
persisted too (conditions are not). `pause()` also works on a queued task: it leaves the queue
until `resume()` is called.

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
