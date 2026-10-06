# API Reference

The reference for Ketch's Kotlin library. For an overview of the modules, a quick start and the
REST API, see the [developer guide](developers.md).

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
ketch-ftp = { module = "com.linroid.ketch:ftp", version.ref = "ketch" }
ketch-hls = { module = "com.linroid.ketch:hls", version.ref = "ketch" }
ketch-dash = { module = "com.linroid.ketch:dash", version.ref = "ketch" }
ketch-torrent = { module = "com.linroid.ketch:torrent", version.ref = "ketch" }
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
    // Android, iOS and JVM only: SQLite persistence (add ketch.ftp and ketch.torrent the same way)
    androidMain.dependencies { implementation(libs.ketch.sqlite) }
    iosMain.dependencies { implementation(libs.ketch.sqlite) }
    jvmMain.dependencies { implementation(libs.ketch.sqlite) }
  }
}
```

`core`, `hls` and `dash` target Android, iOS, JVM, JavaScript (Node.js) and WasmWasi; `ktor`,
`sqlite`, `ftp` and `torrent` target Android, iOS and JVM. Browser (WasmJs) apps use `remote` to control a daemon
instead of running the engine in-process.

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
  suspend fun start()  // core: restore persisted tasks; remote: connect and sync
  suspend fun download(request: DownloadRequest): DownloadTask
  suspend fun resolve(url: String, properties: Map<String, String> = emptyMap()): ResolvedSource
  suspend fun resolveContent(content: ByteArray, fileName: String? = null): ResolvedSource
  suspend fun status(): KetchStatus
  suspend fun updateConfig(config: DownloadConfig)
  fun close()
  // ... plus backendLabel, torrents, networkInterfaces(), updateNetworkInterfaces()
}
```

Call `start()` once before use; persisted tasks only appear in `tasks` after it. The library
version is available as `KetchApi.VERSION` and `KetchApi.REVISION`.

`resolve(url)` probes a URL (for HTTP, a HEAD request, or a `GET` for its first byte when the
server refuses HEAD) and returns its size, resume support,
suggested file name and, for multi-file sources such as torrents, the selectable `files`.
`resolveContent(bytes, fileName)` does the same for file content the caller already holds, such
as a picked `.torrent` file. Pass the result as `DownloadRequest.resolvedSource` (with its `url`
as the request URL) to skip the probe; `selectedFileIds` picks a subset of `files`.

`KetchApi.download(...)` returns a `DownloadTask` for controlling an
individual download. Tasks expose reactive state and per-task actions:

```kotlin
interface DownloadTask {
  val taskId: String
  val request: DownloadRequest
  val requestState: StateFlow<DownloadRequest>  // request including runtime changes
  val createdAt: Instant
  val state: StateFlow<DownloadState>
  val segments: StateFlow<List<Segment>>
  val queuePosition: StateFlow<Int?>  // 1 = starts next; null when not waiting for a slot

  suspend fun pause()
  suspend fun resume(destination: Destination? = null)
  suspend fun cancel()
  suspend fun setSpeedLimit(limit: SpeedLimit)
  suspend fun setPriority(priority: DownloadPriority)
  suspend fun setConnections(connections: Int)  // 0 = Auto, the configured default
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

`DownloadState.Paused.reason` is a `PauseReason`: `User` for `pause()` (and tasks restored
paused), `Preempted(byTaskId)` for a task that gave its slot to an URGENT one and still waits in
the queue, `Shutdown` for a task that was downloading when its `Ketch` closed (its partial file
is kept and it resumes on the next `start()`), and `WaitingForCondition`. A reason the client
does not know decodes as `User`. `DownloadState.Completed.completedAt` is when the task
finished, in whole milliseconds; it is `null` for tasks that finished before Ketch recorded it.
`queuePosition` counts the tasks that wait for a slot (`Queued`, or `Paused` for `Preempted`)
in the order the queue starts them, priority first, then age.

`KetchStatus.features` lists the optional behaviors an instance supports, from
`KetchFeatures`: `AUTO_CONNECTIONS` (`setConnections(0)`) and `QUEUE_POSITION`. Older servers
send none, so their tasks report no position and `setConnections(0)` on them throws
`UnsupportedOperationException`.

### `library:core`

The in-process download engine. Depends on an `HttpEngine` interface (no HTTP client dependency):

```kotlin
interface HttpEngine {
  suspend fun head(url: String, headers: Map<String, String> = emptyMap()): ServerInfo
  // GET with `Range: bytes=0-0`, used when HEAD is refused (400, 403, 404, 405 or 501)
  suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): ServerInfo
  suspend fun download(url: String, range: LongRange?, headers: Map<String, String> = emptyMap(), onData: suspend (ByteArray) -> Unit)
  fun close()
}
```

`probe` has a default that throws `UnsupportedOperationException`; an engine without it fails
when a server refuses HEAD, as before. `RequestHeaders` holds the header rules engines share:
`Ketch.download` rejects names that are not tokens and values with line breaks or other control
characters, and engines ignore `Host`, `Range`, `Content-Length` and hop-by-hop headers.

`Ketch` implements `KetchApi`. Everything except the `HttpEngine` has a default:

```kotlin
val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  taskStore = createSqliteTaskStore(driverFactory), // default: InMemoryTaskStore()
  config = DownloadConfig(),
  name = "Ketch",                                   // reported by status()
  additionalSources = listOf(FtpDownloadSource(), TorrentDownloadSource()),
  logger = Logger.console(),                        // default: Logger.None
)
ketch.start()
```

The built-in HTTP(S) source is the fallback. `additionalSources` adds other protocols:
`HlsDownloadSource(httpEngine)` from `library:hls` and `DashDownloadSource(httpEngine)` from
`library:dash` add [finite media downloads](media.md), sharing `Ketch`'s HTTP engine.
`FtpDownloadSource` from `library:ftp` and `TorrentDownloadSource` from `library:torrent` (see
[BitTorrent downloads](torrent.md)), or your own `DownloadSource`. The first source whose
`canHandle(url)` matches is used. `fileNameResolver` and `dispatchers` can also be replaced.

### `library:ktor`

Ready-made `HttpEngine` backed by Ktor Client with per-platform engines:

| Platform | Ktor Engine |
|---|---|
| Android | OkHttp |
| iOS | Darwin |
| Desktop | CIO |

On JavaScript and WasmWasi, pass your own `HttpEngine` to `Ketch`.

`KtorHttpEngine` sends `User-Agent: Ketch/<version>` (`KtorHttpEngine.DEFAULT_USER_AGENT`) unless
the request headers name one; pass `userAgent` to change it, or `null` to leave it to the client.
It follows redirects itself, at most 20, and refuses those from HTTPS to HTTP or to other
schemes. A redirect to another scheme, host or port keeps only `User-Agent`, `Accept`,
`Accept-Encoding`, `Accept-Language` and the origin of `Referer`: cookies, `Authorization` and
any other header, which may hold a credential, stay with the origin they were given for. The
engine remembers where a request's redirects led, so a download's segments go to the server that
answered its probe; when that server fails, for example because a signed link expired, the next
request follows the redirects again.

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
  defaultDirectory = null,        // null = platform default, e.g. ~/Downloads on JVM
  maxConnectionsPerDownload = 4,  // default concurrent segments per task
  retryCount = 3,                 // automatic retries after a retryable failure
  retryDelayMs = 1000,            // base delay (exponential backoff)
  progressIntervalMs = 200,       // progress throttle
  saveIntervalMs = 5000,          // how often segment progress is persisted
  bufferSize = 8192,              // FTP read buffer size
  speedLimit = SpeedLimit.kbps(500), // global speed limit (default: Unlimited)
  maxConcurrentDownloads = 2,     // max simultaneous downloads (0 = unlimited)
  maxConnectionsPerHost = 8,      // max simultaneous downloads per host (0 = unlimited)
)
```

The values shown are the defaults, except `speedLimit`. A `null` `defaultDirectory` resolves to
`~/Downloads` on JVM, the app's external `Download` folder on Android and `Downloads` in the app's
Documents folder on iOS. `status().system` reports the folder in use as `downloadDirectory` and
this platform default as `defaultDownloadDirectory`.

`ketch.updateConfig(config)` replaces the configuration at runtime. `speedLimit`,
`maxConcurrentDownloads` and `maxConnectionsPerHost` apply immediately: raising a queue limit
starts queued downloads, lowering one lets running downloads finish. All other fields apply to
downloads that start or resume afterwards; pause and resume a running download to pick them up.
A changed `defaultDirectory` that does not exist or is not a folder is rejected with
`IllegalArgumentException`, and the previous configuration stays in effect.

The per-host limit counts downloads by URL host (case-insensitive, ignoring user info and port).
Magnet links, `torrent:` identifiers and local files are not counted.

`maxConnectionsPerDownload` and `DownloadRequest.connections` split HTTP(S) and FTP(S)
downloads into parallel ranges when the server supports HTTP Range or FTP REST; otherwise a
single connection is used. BitTorrent treats a per-task connection count as its peer limit.
A count of 0 (Auto) uses the configured default of the run: `maxConnectionsPerDownload`, or a
torrent's own peer limit; `setConnections(0)` returns a running task to it.
`bufferSize` sets the FTP read buffer; HTTP buffering is up to the `HttpEngine`.

### Priority & Scheduling

`task.setPriority(priority)` persists the change and updates `task.requestState`, including
for active, paused, and scheduled tasks. LOW, NORMAL, and HIGH determine which queued task
starts next. Changing a queued task to URGENT can immediately pause a lower-priority active
task to make room. The interrupted task becomes `Paused` with `PauseReason.Preempted`, keeps
its place in the queue and resumes on its own when a slot opens.
Concurrency and per-host limits still apply; other URGENT tasks cannot be preempted.
Priority changes do not resume manually paused tasks or bypass schedules and conditions.

Priority does not reserve or weight bandwidth among active downloads. Use per-task
`setSpeedLimit` and the global speed limit to control transfer rates.

```kotlin
// High-priority download
ketch.download(
  DownloadRequest(
    url = "https://example.com/urgent.zip",
    destination = Destination("/downloads/"),  // trailing slash: a directory
    priority = DownloadPriority.URGENT,  // preempts lower-priority tasks
  )
)

// Scheduled download
ketch.download(
  DownloadRequest(
    url = "https://example.com/file.zip",
    destination = Destination("/downloads/file.zip"),  // full path, used as-is
    schedule = DownloadSchedule.AtTime(startAt),
    conditions = listOf(DownloadCondition.Test(wifiConnected)),  // Flow<Boolean>
  )
)

// Speed limiting
task.setSpeedLimit(SpeedLimit.mbps(1))        // per-task
ketch.updateConfig(config.copy(speedLimit = SpeedLimit.kbps(500))) // global
```

A per-task limit applies in addition to the global one, so a task never exceeds the lower of
the two. `setSpeedLimit`, `setConnections` and `setPriority` are persisted for queued, scheduled,
paused and failed tasks and take effect when the task starts or resumes; `reschedule` is
persisted too (conditions are not). `pause()` also works on a queued or preempted task: it
leaves the queue until `resume()` is called.

## Error Handling

All errors are modeled as a sealed class `KetchError`:

| Type | Retryable | Description |
|---|---|---|
| `Network` | Yes | Connection / timeout failures |
| `Http(code)` | 5xx and 429 | Non-success HTTP status |
| `Disk` | No | File I/O failures |
| `Unsupported` | No | Server or source doesn't support a required feature |
| `FileChanged` | No | ETag / Last-Modified (or FTP MDTM) mismatch on resume |
| `CorruptResumeState` | No | Persisted resume state cannot be read |
| `SourceError` | No | Error from a pluggable download source |
| `AuthenticationFailed` | No | Credentials rejected, e.g. FTP 530 |
| `Canceled` | No | Download was canceled |
| `Unknown` | No | Unexpected errors |

`KetchError.isRetryable` tells which errors Ketch retries automatically, up to
`DownloadConfig.retryCount` times with exponential backoff. On HTTP 429 it also honors
`Retry-After` and reduces the task's connections.

## Logging

Ketch provides pluggable logging with zero overhead when disabled (default).

```kotlin
// No logging (default)
Ketch(httpEngine = KtorHttpEngine())

// Console logging (development)
Ketch(httpEngine = KtorHttpEngine(), logger = Logger.console())

// Kermit structured logging (production)
Ketch(httpEngine = KtorHttpEngine(), logger = KermitLogger(minSeverity = Severity.Debug))

// Several backends at once, each with its own level
Ketch(httpEngine = KtorHttpEngine(), logger = Logger.combine(Logger.console(LogLevel.INFO), other))
```

`Logger` and `LogLevel` live in `com.linroid.ketch.api.log` (`library:api`).

See [Logging](logging.md) for detailed documentation.
