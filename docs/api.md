# API reference

This is the reference for Ketch's Kotlin library: how to install it, the `KetchApi` and
`DownloadTask` interfaces, configuration, checksums, errors and logging. It is for developers who
embed the download engine or control a Ketch server from Kotlin. For an overview of the modules, a
quick start and the REST API, start with the [developer guide](developers.md).

## Installation

Ketch is published on Maven Central under `com.linroid.ketch`. Add the modules you need to your
version catalog:

```toml
# gradle/libs.versions.toml
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
ketch-webdav = { module = "com.linroid.ketch:webdav", version.ref = "ketch" }
ketch-torrent = { module = "com.linroid.ketch:torrent", version.ref = "ketch" }
ketch-torrent-api = { module = "com.linroid.ketch:torrent-api", version.ref = "ketch" }
```

Then add them to the source sets whose targets they support:

```kotlin
// build.gradle.kts
kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(libs.ketch.core)    // download engine
      implementation(libs.ketch.ktor)    // HTTP engine for core
      implementation(libs.ketch.kermit)  // optional: Kermit logging
      implementation(libs.ketch.remote)  // optional: client of a Ketch server
    }
    // Android, iOS and JVM only. Add ketch.ftp and ketch.torrent the same way.
    androidMain.dependencies { implementation(libs.ketch.sqlite) }
    iosMain.dependencies { implementation(libs.ketch.sqlite) }
    jvmMain.dependencies { implementation(libs.ketch.sqlite) }
  }
}
```

Without a version catalog:

```kotlin
dependencies {
  implementation("com.linroid.ketch:core:<latest-version>")
  implementation("com.linroid.ketch:ktor:<latest-version>")
}
```

### Platform support

| Modules | Targets |
|---|---|
| `api`, `torrent-api` | Android, iOS, JVM, JS, WasmJs, WasmWasi |
| `core`, `hls`, `dash`, `webdav` | Android, iOS, JVM, JS (Node.js), WasmWasi |
| `ktor`, `sqlite`, `ftp`, `torrent` | Android, iOS, JVM |
| `remote`, `endpoints` | Android, iOS, JVM, WasmJs |
| `kermit` | Android, iOS, JVM, JS, WasmJs |

- Browser (WasmJs) apps cannot run the engine. They use `remote` to control a Ketch server.
- On JS and WasmWasi, `core` has file I/O but `ktor` ships no `HttpEngine`, so pass your own.
- `torrent-api` is the torrent control contract that both `torrent` and `remote` implement. It has
  the targets of `api`, so any client can inspect and command torrents.
- Kermit publishes no WASI variant. Projects with a WASI target should add `kermit` only to source
  sets shared by its supported targets.

## A quick tour

Create a `Ketch`, start it once, add a download and follow its state:

```kotlin
val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  taskStore = createSqliteTaskStore(driverFactory), // default: InMemoryTaskStore()
  additionalSources = listOf(FtpDownloadSource(), TorrentDownloadSource()),
  logger = Logger.console(),                        // default: Logger.None
)
ketch.start() // restores saved tasks

val task = ketch.download(
  DownloadRequest(
    url = "https://example.com/large-file.zip",
    destination = Destination("/downloads/"), // trailing slash: a folder
  )
)

task.state.collect { state ->
  when (state) {
    is DownloadState.Downloading -> println("${(state.progress.percent * 100).toInt()}%")
    is DownloadState.Completed -> println("Saved to ${state.outputPath}")
    is DownloadState.Failed -> println("Failed: ${state.error.message}")
    else -> Unit
  }
}
```

The same code works against a server: replace `Ketch(...)` with `RemoteKetch(host = ...)` (see
[RemoteKetch](#remoteketch)).

## KetchApi

`KetchApi` (`library:api`) is the service interface. `Ketch` (`library:core`) implements it
in-process, and `RemoteKetch` (`library:remote`) implements it over HTTP and server-sent events,
so UI code works the same with either.

```kotlin
interface KetchApi {
  val backendLabel: String                    // "Core" or "Remote · host:port"
  val tasks: StateFlow<List<DownloadTask>>

  suspend fun start()                         // core: restore saved tasks; remote: connect
  fun close()
  suspend fun shutdown(timeout: Duration = 5.seconds) // close() and wait for it to finish

  suspend fun download(request: DownloadRequest): DownloadTask
  suspend fun resolve(input: ResolveInput): ResolvedSource
  suspend fun status(): KetchStatus
  suspend fun updateConfig(config: DownloadConfig)
  suspend fun networkInterfaces(): NetworkInterfaces
  suspend fun selectNetworkInterfaces(config: NetworkInterfaceConfig): NetworkInterfaces

  fun <T> subscribe(interest: Interest<T>, interval: Duration = Interest.DEFAULT_INTERVAL): Flow<T>
  fun <T : Any> controller(key: ControlKey<T>): T?
}
```

- Call `start()` once before use. Saved tasks only appear in `tasks` after it.
- Before the process exits, call `shutdown()` rather than `close()`. It closes the instance and
  waits, five seconds by default, while running downloads save where they stopped and sources such
  as BitTorrent tell their trackers they stopped. `close()` does the same in the background.
- `KetchApi.VERSION` and `KetchApi.REVISION` give the library version and its git revision.
- Implementing `KetchApi` or `DownloadTask` yourself requires opting in to
  `KetchImplementationApi`.

### Resolve a link before downloading

`resolve` probes a download without starting it. It answers with a `ResolvedSource`: the size
(`-1` when unknown), whether it can resume, a suggested file name, the content type, a checksum
the server publishes and, for sources with several files or variants such as torrents, the
selectable `files` and their `selectionMode`.

```kotlin
val preview = ketch.resolve("https://example.com/file.iso")          // a URL
val torrent = ketch.resolve(pickedBytes, fileName = "ubuntu.torrent") // content you hold

ketch.download(
  DownloadRequest(
    url = torrent.url,
    resolvedSource = torrent,                   // skips probing again
    selectedFileIds = setOf(torrent.files.first().id),
  )
)
```

- Both calls are extensions that build a `ResolveInput` for `KetchApi.resolve`:
  `ResolveInput.Url(url, headers)`, which the instance fetches as it would a download, or
  `ResolveInput.Content(bytes, fileName)`, file content the caller holds. Remote instances accept
  content up to `ResolveInput.Content.MAX_CONTENT_BYTES` (16 MiB).
- An HTTP probe is a `HEAD` request, or a `GET` for the first byte when the server refuses `HEAD`.
- `resolve` runs until it has an answer; a magnet link, for example, waits for peers. There is no
  timeout, so cancel the call (for example with `withTimeout`) if you need one.
- It throws `KetchError.Unsupported` when no source handles the URL or recognizes the content,
  with a `NoDownloadSourceException` as its cause; a source that takes the input but lacks
  something it needs, such as a usable proxy, throws it with a cause that says what.
- To download content you resolved, pass the result as `DownloadRequest.resolvedSource`, with its
  `url` as the request URL. The result does not keep the bytes.

### Choose files

`selectedFileIds` picks which of `ResolvedSource.files` to download:

- `FileSelectionMode.MULTIPLE` takes any subset. An empty set downloads every file.
- `FileSelectionMode.SINGLE` takes exactly one ID, and defaults to the first file. The HLS and
  DASH sources offer their qualities this way, best first ([media](media.md#choose-the-quality)).
- `ResolvedSource.defaultSelection()` returns what an empty selection downloads.
- An ID that `ResolvedSource.files` does not list is refused: `download` throws
  `IllegalArgumentException` when the request carries the resolution, and otherwise the download
  fails with a `KetchError.SourceError` whose `detail` names it.

With `awaitFileSelection = true` and no `selectedFileIds`, a download with several files or
variants pauses as `Paused(AwaitingFileSelection)` once they are known. It holds no download slot
until `selectFiles` chooses, and `resume()` downloads the default selection instead.

## DownloadTask

`download(...)` returns a `DownloadTask`, which exposes the download's state and the commands
that change it:

```kotlin
interface DownloadTask {
  val taskId: String
  val request: DownloadRequest
  val requestState: StateFlow<DownloadRequest> // the request, including changes made later
  val createdAt: Instant
  val state: StateFlow<DownloadState>
  val segments: StateFlow<List<Segment>>
  val queuePosition: StateFlow<Int?>           // 1 = starts next; null when not waiting
  val outputPath: String?                      // where it saves, once chosen; null from remote

  suspend fun pause()
  suspend fun resume(destination: Destination? = null)
  suspend fun cancel()
  suspend fun remove(deleteFiles: Boolean = false)
  suspend fun await(): Result<String>          // output path, or the KetchError

  suspend fun setSpeedLimit(limit: SpeedLimit)
  suspend fun setPriority(priority: DownloadPriority)
  suspend fun setConnections(connections: Int) // 0 = Auto, the configured default
  suspend fun selectFiles(fileIds: Set<String>)
  suspend fun reschedule(
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition> = emptyList(),
  )
}
```

### Commands

- **`pause()`** keeps the progress. A queued or preempted task leaves the queue until `resume()`.
- **`resume(destination)`** continues a paused or failed task. A preempted task already waits and
  is left as it is. Pass a `destination` when the target moved, such as an Android document URI
  that changed while naming the same file.
- **`remove(deleteFiles)`** cancels the download and forgets the task. With `deleteFiles = true`
  it also deletes the partial or finished files, best-effort: failures are logged and the task is
  removed anyway.
- **`setSpeedLimit`, `setConnections`, `setPriority`** are saved for queued, scheduled, paused and
  failed tasks and apply when the task starts or resumes; a running task applies them at once.
  `setConnections` throws `IllegalArgumentException` for a negative count, and
  `UnsupportedOperationException` for `0` on servers that do not list `AUTO_CONNECTIONS`.
- **`reschedule`** pauses the task and starts it again per the new schedule and conditions. The
  schedule is saved; the conditions are not. Remote tasks cannot be rescheduled.

### Changing files while a task runs

`selectFiles(ids)` changes which files, or which variant, a download fetches. It works in every
state except canceled:

- A running torrent switches without reconnecting its peers. A source that cannot confirm the
  change live runs again through resume.
- Newly chosen files are downloaded. Unchosen ones stop downloading but stay on disk.
- A completed task that gains files downloads them and completes again; one that only drops files
  just changes its size.
- A task waiting for a selection starts.

It throws:

| Exception | When |
|---|---|
| `IllegalArgumentException` | The set is empty, has several IDs for a `SINGLE` choice, names an unknown ID, or exceeds `MAX_SELECTED_FILES` (100,000) or `MAX_FILE_ID_LENGTH` (128) |
| `IllegalStateException` | The file list is not known yet, the task was canceled, or a file Ketch does not own is in the way |
| `UnsupportedOperationException` | The task has no files to choose, or the backend does not list `FILE_SELECTION` |

See [choosing torrent files](torrent.md#choosing-files) for the torrent specifics.

## Download states

`DownloadState` is a sealed class. A task usually moves through `Scheduled`, `Queued`,
`Downloading` and `Completed`, and may pass through `Paused` and `Failed` (both resumable) or end
`Canceled`.

| State | Meaning |
|---|---|
| `Scheduled(schedule)` | Waiting for a `DownloadSchedule` time or its `DownloadCondition`s |
| `Queued` | Waiting for a free download slot |
| `Downloading(progress)` | Transferring; `progress` has `downloadedBytes`, `totalBytes` (0 when unknown), `bytesPerSecond` and `percent` (`0f..1f`) |
| `Paused(progress, reason)` | Paused for a `PauseReason` |
| `Completed(...)` | Finished; see below |
| `Failed(error)` | Failed with a `KetchError`; unknown error types read as `KetchError.Unknown` |
| `Canceled` | Canceled for good |

`isTerminal` is true for `Completed`, `Failed` and `Canceled`; `isActive` is true for
`Downloading`.

### Why a task is paused

| `PauseReason` | Cause | What happens next |
|---|---|---|
| `User` | `pause()`, or a task restored paused | Waits for `resume()` |
| `Preempted(byTaskId)` | Gave its slot to an `URGENT` task | Stays queued and resumes on its own; `pause()` turns it into a `User` pause |
| `Shutdown` | Was downloading when its `Ketch` closed | Keeps its partial file and resumes on the next `start()` |
| `AwaitingFileSelection` | Added with `awaitFileSelection` and several files | Starts on `selectFiles`; `resume()` downloads the default selection |
| `WaitingForCondition` | Waiting for its conditions | Resumes on its own |

A reason a client does not know decodes as `User`.

### Completed tasks

`DownloadState.Completed` reports:

- `outputPath`: the saved file, or the folder of a multi-file download.
- `totalBytes`: the size.
- `downloadTime`: time spent downloading over every run, excluding time scheduled, queued or
  paused.
- `completedAt`: when the task last finished, in whole milliseconds.
- `sharing`: `true` while the source shares the content with others, as a seeding torrent does.
  Not saved.
- `checksum`: the digest the file was verified against (see [checksums](#checksums)). Not kept
  across restarts.

Fields are `null` when unknown, for example for tasks finished by older versions.

### Queue position

`queuePosition` counts the tasks that wait for a slot (`Queued`, or `Paused` for `Preempted`) in
the order the queue starts them: priority first, then age. It is `null` for tasks that do not
wait, including a queued task that already holds a slot. A task held back by
`maxConnectionsPerHost` keeps its place while later ones start. The position is a separate flow
from `state`, so the two can briefly disagree.

## Follow live data with `subscribe`

`subscribe(interest, interval)` follows an `Interest` while the flow is collected. Interests are
serializable, so a remote instance sends its server only the ones its callers hold. `interval`
(default 1 s) is the most often updates come; with several callers the shortest wins.

```kotlin
ketch.subscribe(Interest.Stats).collect { stats ->
  println("${stats.downloading} downloading at ${stats.downloadBps} B/s")
}
```

The built-in interests are nested in `Interest`:

| Interest | Reports | Notes |
|---|---|---|
| `Segments(taskIds)` | `Map<String, List<Segment>>` for up to 1024 tasks | Also updates `DownloadTask.segments`. A remote instance sends state and progress anyway, but segments (one per file for a torrent) only while followed |
| `Stats` | `KetchStats`: totals of the downloading tasks | |
| `Connections(limit)` | `ActiveConnections` snapshots of every task's live connections (HTTP requests, FTP transfers, media fetches, torrent peers) | Needs `ACTIVE_CONNECTIONS`. `limit` is 1..1024 (default 256); `total` and the rates count every connection. A snapshot comes at once, then one per change |

Other modules define their own, such as `TorrentInterest(taskId)` in `library:torrent-api`.

- A flow ends when what it follows is gone, as a removed torrent's does after its `null`.
- An instance that does not know an interest fails it with `UnsupportedOperationException` when
  collected.
- Each interest names a `valueSerializer`, which servers encode what they report with. Any
  interest whose type `StreamSerializers` (`library:endpoints`) names reaches a remote instance
  with no extra code on either side.
- On a remote instance, interests other than segments and stats end with the failure when the
  connection drops. Collect them again to resume.

## Controllers and features

### Optional controllers

`controller(key)` returns a typed controller that a backend offers beyond the download API, or
`null`. `library:api` defines only the `ControlKey` lookup; the module that defines a controller
also defines its key, usually as the controller's companion object.

```kotlin
val torrents: TorrentController? = ketch.controller(TorrentController)
```

Check the feature the module names in `status().features` first: a remote controller fails
against servers that lack it.

### The torrent controller

`library:torrent-api` holds the torrent control contract. It has the targets of `library:api`,
so browser and remote clients use it without linking the torrent engine.

`ketch.controller(TorrentController)` returns a `TorrentController`, or `null` without a torrent
source. It offers:

- `capabilities()` and `snapshot(taskId)`.
- `files(...)`: a task's files a page at a time, sorted by a `TorrentFileOrder` (each naming a
  `FileOrder`), optionally descending.
- `select(...)` and `setSeeding(...)`, each guarded by a `TorrentCommandContext` (an idempotency
  key and the expected `TorrentRevision`). Failures throw `TorrentCommandException` with a
  `TorrentCommandError`.

Live snapshots come from `subscribe(TorrentInterest(taskId))`. `RemoteKetch` returns a controller
that fails closed against servers without `TorrentFeatures.CONTROL`. See the
[control contract](design/torrent-control-contract.md#version-1-runtime).

`library:api` also holds the file orders every client shares: `FileOrder`, `NaturalOrder`,
`fileExtension()` and `sortedByFileOrder()`.

### Feature flags

`status().features` lists the optional behaviors an instance supports. Gate client features on
these rather than on version numbers: older servers send fewer.

| `KetchFeatures` constant | Wire name | Enables |
|---|---|---|
| `REQUEST_ID` | `task.requestId` | `DownloadRequest.requestId` deduplication |
| `AUTO_CONNECTIONS` | `task.autoConnections` | `setConnections(0)` |
| `QUEUE_POSITION` | `task.queuePosition` | `DownloadTask.queuePosition` |
| `PROXY` | `http.proxy` | Proxies other than `Proxy.System` |
| `CATEGORY_FOLDERS` | `download.categories` | `DownloadConfig.categories` |
| `TRANSFER_TIMEOUTS` | `download.timeouts` | `idleTimeoutMs`, `minSpeed`, `unlimitedRetries` |
| `CHECKSUM` | `download.checksum` | `DownloadRequest.checksum` |
| `ACTIVE_CONNECTIONS` | `net.activeConnections` | `Interest.Connections` |
| `FILE_SELECTION` | `download.fileSelection` | `selectFiles` (listed by sources that choose files) |
| `AWAIT_FILE_SELECTION` | `download.awaitFileSelection` | `awaitFileSelection` |
| `FINITE_HLS`, `FINITE_DASH` | `hls.finite`, `dash.finite` | The HLS and DASH sources; `FINITE_MEDIA` (`media.finite`) is listed when both are |

Modules beside `library:api` name their own: the torrent source lists `TorrentFeatures.CONTROL`
(`torrent.control`). On servers without `QUEUE_POSITION` tasks report no position, and without
`AUTO_CONNECTIONS` `setConnections(0)` throws `UnsupportedOperationException`.

## The Ketch engine (`library:core`)

`Ketch` implements `KetchApi` in-process. Everything except the `HttpEngine` has a default:

```kotlin
val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  taskStore = InMemoryTaskStore(),             // or createSqliteTaskStore(driverFactory)
  config = DownloadConfig(),
  name = "Ketch",                              // reported by status()
  fileNameResolver = DefaultFileNameResolver(),
  additionalSources = emptyList(),
  logger = Logger.None,
  dispatchers = KetchDispatchers.Default,
)
```

### Download sources

The built-in HTTP(S) source is the fallback. `additionalSources` adds other protocols, checked in
order before HTTP; the first source whose `canHandle` accepts the URL is used:

- `FtpDownloadSource()` from `library:ftp`.
- `TorrentDownloadSource()` from `library:torrent` (see [BitTorrent downloads](torrent.md)).
- `HlsDownloadSource(httpEngine)` from `library:hls` and `DashDownloadSource(httpEngine)` from
  `library:dash`, for [finite streaming media](media.md). Pass them the engine you give `Ketch`.
  Register them before HTTP, which `additionalSources` does.
- `MetalinkDownloadSource(httpEngine)` from `library:metalink`, which downloads what a
  [Metalink](metalink.md) lists from all its mirrors at once. Pass it the engine you give `Ketch`.
- Your own `DownloadSource` (see [writing a download source](developers.md#write-your-own-download-source)).

### Dispatchers

`dispatchers` defaults to `KetchDispatchers.Default`, created once per process and shared by every
instance. On Android and the JVM it runs on Ketch's own daemon threads, which start only when
needed and end after 30 seconds idle. See [architecture](architecture.md#threads) for details.
Pass your own `KetchDispatchers(main, network, disk)` to control threading; `Ketch.close()`
closes them once its coroutines have finished.

### HttpEngine

`library:core` depends on an `HttpEngine` interface, not on an HTTP client:

```kotlin
interface HttpEngine {
  suspend fun head(url: String, headers: Map<String, String> = emptyMap()): ServerInfo

  // GET with `Range: bytes=0-0`, used when HEAD is refused (400, 403, 404, 405, 500 or 501).
  // The default throws UnsupportedOperationException, keeping the HEAD error.
  suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): ServerInfo

  suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String> = emptyMap(),
    onData: suspend (ByteArray) -> Unit,
  )

  // Streams a whole resource and returns its final URL after redirects (manifests use it).
  suspend fun downloadResource(
    url: String,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ): String

  val supportsProxies: Boolean get() = false
  fun withProxy(proxy: Proxy): HttpEngine // default: this for Proxy.System, else refuses
  fun close()
}
```

- Engines that follow redirects must override `downloadResource` to return the final URL.
- An engine that can choose proxies overrides `withProxy` and reports `supportsProxies`. The
  default refuses any proxy but `Proxy.System` with `KetchError.Unsupported`, so a download never
  silently skips its proxy. `Ketch` lists `http.proxy` only when its engine supports proxies, and
  otherwise refuses other proxies in `updateConfig` and `download` with
  `UnsupportedOperationException`.
- `RequestHeaders` holds the header rules engines share: `Ketch.download` rejects header names that
  are not tokens and values with line breaks or other control characters, and engines drop `Host`,
  `Range`, `Content-Length` and hop-by-hop headers.
- `ServerInfo.publishedChecksum` reads `Repr-Digest` and `Digest` headers for custom engines.

## HTTP engine (`library:ktor`)

`KtorHttpEngine` is the ready-made `HttpEngine`, backed by Ktor Client:

| Platform | Ktor engine |
|---|---|
| Android | OkHttp |
| iOS | Darwin |
| JVM | CIO; OkHttp for requests that go through a proxy |

### Headers and redirects

- It sends `User-Agent: Ketch/<version>` (`KtorHttpEngine.DEFAULT_USER_AGENT`) unless the request
  names one. Pass `userAgent` to change it, or `null` to leave it to the client.
- It follows redirects itself, at most 20, and refuses those from HTTPS to HTTP or to other
  schemes.
- A redirect to another scheme, host or port keeps only `User-Agent`, `Accept`,
  `Accept-Encoding`, `Accept-Language` and the origin of `Referer`. Cookies, `Authorization` and
  any other header that may hold a credential stay with the origin they were given for.
- It remembers where a request's redirects led, so a download's segments go to the server that
  answered its probe. When that server fails, for example because a signed link expired, the next
  request follows the redirects again.

### Proxies

`KtorHttpEngine` applies `DownloadConfig.proxy` and `DownloadRequest.proxy` (see
[proxies](proxy.md)). Every request, and every hop of a redirect, goes directly or through an HTTP
or SOCKS5 proxy as the setting says for its URL. An engine built around an `HttpClient` of your
own only follows the system's proxy settings, in the client's own way.

### Multiple network interfaces

To spread downloads across interfaces, wrap network-bound engines in `MultiNetworkHttpEngine`. The
factories are extension functions in `com.linroid.ketch.engine`; import them explicitly:

- JVM: `KtorHttpEngine.forLocalAddress(InetAddress)`.
- Android: `KtorHttpEngine.forNetwork(Network)`.

To let users pick interfaces at runtime, build the engine with
`KtorHttpEngine.withNetworkInterfaces()` (JVM) or
`KtorHttpEngine.withNetworkInterfaces(connectivityManager)` (Android). Then:

- `networkInterfaces()` lists the supported interfaces and the current selection.
- `selectNetworkInterfaces(NetworkInterfaceConfig(interfaceIds))` changes the selection for later
  HTTP requests; requests in flight keep theirs. An empty list restores default routing.
- The selection is not saved across restarts. `RemoteKetch` forwards both calls to
  `GET`/`PUT /api/network-interfaces`.

See [multiple network interfaces](multiple-networks.md) for setup and platform limits.

## RemoteKetch

`RemoteKetch` (`library:remote`) is a `KetchApi` that controls a Ketch server over HTTP and
server-sent events:

```kotlin
val api = RemoteKetch(
  host = "192.168.1.20",
  port = 8642,                 // default
  apiToken = "my-secret",      // the server's token, if it has one
  secure = false,              // true for https
  progressInterval = 1.seconds, // how often running tasks' progress comes; null for none
)
api.start() // connects, loads the tasks and keeps reconnecting
```

- Set `api.foreground = false` while nothing is shown; the server then sends only lifecycle and
  state changes.
- It reconnects with exponential backoff, and stops when the server rejects the token.
- Calls the server refuses throw `RemoteApiException` with the HTTP status, the error code and
  message, and `Retry-After`, unless `KetchApi` documents another exception for the case.
- It refuses requests that need features the server lacks, such as a proxy without `http.proxy`
  or a checksum without `download.checksum`, instead of letting the server ignore them.

## Configuration

`DownloadConfig` holds the instance-wide settings. These are the defaults, except `speedLimit`:

```kotlin
DownloadConfig(
  defaultDirectory = null,           // null = platform default, e.g. ~/Downloads on JVM
  maxConnectionsPerDownload = 4,     // default concurrent segments per task
  retryCount = 3,                    // retries in a row without progress before failing
  retryDelayMs = 1_000,              // first retry delay, doubled for each further retry
  unlimitedRetries = false,          // true retries until the task is paused or canceled
  maxRetryDelayMs = 60_000,          // retry delay cap; each delay is randomized from half to all
  idleTimeoutMs = 60_000,            // retry a connection that receives no data this long (0 = off)
  minSpeed = 0,                      // bytes/s a connection must average over 30 s (0 = off)
  progressIntervalMs = 200,          // progress throttle
  saveIntervalMs = 5_000,            // how often progress and source state are saved
  bufferSize = 8 * 1024,             // FTP read buffer size
  speedLimit = SpeedLimit.kbps(500), // global speed limit (default: SpeedLimit.Unlimited)
  maxConcurrentDownloads = 4,        // simultaneous downloads (0 = unlimited)
  maxConnectionsPerHost = 16,        // simultaneous downloads per host (0 = unlimited)
  proxy = Proxy.System,              // HTTP(S) proxy: System, Direct or Proxy.Manual(url)
  categories = emptyList(),          // folders inside defaultDirectory, by type or site
)
```

### Changing settings at runtime

`ketch.updateConfig(config)` replaces the configuration:

- `speedLimit`, `maxConcurrentDownloads` and `maxConnectionsPerHost` apply immediately. Raising a
  queue limit starts queued downloads; lowering one lets running downloads finish.
- All other fields apply to downloads that start or resume afterwards. Pause and resume a running
  download to pick them up.
- A changed `defaultDirectory` that does not exist or is not a folder is rejected with
  `IllegalArgumentException`, and the previous configuration stays in effect.

### Folders

A `null` `defaultDirectory` resolves to `~/Downloads` on the JVM, the app's external `Download`
folder on Android, and `Downloads` in the app's Documents folder on iOS. `status().system`
reports the folder in use as `downloadDirectory` and the platform default as
`defaultDownloadDirectory`.

`Destination` says where one download is saved: a file name (`file.zip`), a full file path, a
folder ending in `/` (`/downloads/`), or an Android content URI. A full file path is used as it
is.

### Connections and limits

- `maxConnectionsPerDownload` and `DownloadRequest.connections` split HTTP(S) and FTP(S)
  downloads into parallel ranges when the server supports HTTP Range or FTP `REST`; otherwise one
  connection is used. BitTorrent treats a per-task count as its peer limit.
- A count of `0` (Auto) uses the run's default: `maxConnectionsPerDownload`, or a torrent's own
  peer limit. `setConnections(0)` returns a running task to it.
- The per-host limit counts downloads, not connections, by URL host (case-insensitive, ignoring
  user info and port). Magnet links, `torrent:` identifiers and local files are not counted. With
  the defaults, the global limit of four downloads applies before the per-host limit of sixteen.
- `bufferSize` sets the FTP read buffer; HTTP buffering is up to the `HttpEngine`.

### Category folders

`categories` sorts downloads that do not choose a folder into folders inside `defaultDirectory`:

```kotlin
DownloadConfig(
  categories = listOf(
    DownloadCategory(
      folder = "Video",
      extensions = listOf("mp4", "mkv"),
      mimeTypes = listOf("video/*"),
    ),
    DownloadCategory(folder = "Software/GitHub", hosts = listOf("github.com")),
  ),
)
```

A request whose `destination` is `null` or a bare file name is saved in the `folder` of the first
category it matches, or in `defaultDirectory` when it matches none. A destination naming a folder
or a full path is used as it is. Matching happens when the download starts, against the name it
is saved under, the server's `Content-Type` (`ResolvedSource.contentType`) and the request host.

- A category matches when the file's type matches (its name ends with one of `extensions`, such
  as `tar.gz`, or its media type is one of `mimeTypes`, where `video/*` covers a whole kind) and,
  when `hosts` is set, its host is one of them or a subdomain. Rules ignore case.
- A category with no rules matches nothing. The first match wins, so order matters.
- `folder` is relative to `defaultDirectory`, with `/` between nested folders. An absolute folder,
  a drive or `..` is rejected with `IllegalArgumentException`. Each name is made safe like a
  server's file name, and folders are created as the download starts, including inside an Android
  `content://` tree.
- Magnet links have no host. A torrent matches by its name, which for a multi-file torrent is
  usually a folder name without an extension.
- `DownloadConfig.categoryFor(fileName, contentType, host)` tells which category a download would
  go to. Instances that support categories list `CATEGORY_FOLDERS`.
- `RemoteKetch.updateConfig` sends the categories to the server. A `PUT /api/config` body without
  a `categories` field, as older clients send, keeps the server's categories.

## Priority, scheduling, speed limits and proxies

```kotlin
// Jump the queue
ketch.download(
  DownloadRequest(
    url = "https://example.com/urgent.zip",
    destination = Destination("/downloads/"),
    priority = DownloadPriority.URGENT, // may preempt a lower-priority download
  )
)

// Start later, only on Wi-Fi
ketch.download(
  DownloadRequest(
    url = "https://example.com/file.zip",
    destination = Destination("/downloads/file.zip"),
    schedule = DownloadSchedule.AtTime(startAt), // or AfterDelay(30.minutes)
    conditions = listOf(DownloadCondition.Test(wifiConnected)), // a Flow<Boolean>
  )
)

// Speed limits
task.setSpeedLimit(SpeedLimit.mbps(1))                              // one task
ketch.updateConfig(config.copy(speedLimit = SpeedLimit.kbps(500))) // every task

// Proxies: one for every download, and one for a single download
ketch.updateConfig(config.copy(proxy = Proxy.Manual("http://proxy.lan:3128")))
ketch.download(
  DownloadRequest(
    url = "https://example.com/file.zip",
    proxy = Proxy.Manual("socks5://user:secret@127.0.0.1:1080", bypass = listOf("*.lan")),
  )
)
```

### Priority

- `LOW`, `NORMAL` and `HIGH` decide which queued task starts next.
- Changing a task to `URGENT` can pause a lower-priority running task to make room. That task
  becomes `Paused(Preempted)`, keeps its place in the queue and resumes on its own when a slot
  opens. Concurrency and per-host limits still apply, and other `URGENT` tasks are never
  preempted.
- `setPriority` is saved and updates `requestState` in any state. It does not resume manually
  paused tasks or bypass schedules and conditions.
- Priority does not reserve bandwidth. Use speed limits to control transfer rates.

### Speed limits

A per-task limit applies on top of the global one, so a task never exceeds the lower of the two.
Both can change while downloads run.

### Schedules and conditions

`DownloadSchedule` is `Immediate`, `AtTime(Instant)` or `AfterDelay(Duration)`.
`DownloadCondition.Test(flow)` holds a task until a `Flow<Boolean>` emits `true`; a task starts
once all its conditions hold. The schedule is saved with the task, but conditions are not: their
flows cannot be stored.

### Proxies

`Proxy` is `System` (default), `Direct` or `Manual(url, bypass)`, where the URL is `http://` or
`socks5://` with optional credentials. It applies to HTTP(S), HLS and DASH; FTP and BitTorrent
ignore it, and loopback hosts always go directly. `DownloadRequest.proxy` replaces the global
setting for one download. See [proxies](proxy.md).

## Checksums

Ask Ketch to verify a download against a known digest:

```kotlin
ketch.download(
  DownloadRequest(
    url = "https://example.com/file.iso",
    checksum = Checksum.parse(
      "sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    ),
  )
)
```

`Checksum(algorithm, hex)` takes `ChecksumAlgorithm.MD5`, `SHA_1`, `SHA_256` or `SHA_512`.
`Checksum.parse` reads:

- `<algorithm>:<hex>`, such as `sha256:…` or `SHA-256:…`.
- Base64 values as HTTP digest headers write them, such as `sha-256=:…:`.
- Bare hex digests, whose length names the algorithm.

JSON carries a checksum as the text `"sha256:<hex>"`. The hashes are pure Kotlin
(`com.linroid.ketch.core.digest`), so every engine target can verify.

### What happens on completion

1. Once the last byte is flushed, Ketch reads the file back and hashes it. The task keeps
   reporting `Downloading` with every byte downloaded meanwhile; pausing or canceling stops the
   hashing.
2. A match completes the task with `Completed.checksum`.
3. A difference fails it with `KetchError.ChecksumMismatch(algorithm, expected, actual,
   published)`, which is not retried. The file is deleted and the progress discarded, so it never
   looks complete and `resume()` downloads it again.

A completed task is never hashed again.

### Digests the server publishes

Without a checksum of its own, an HTTP download is checked against the digest its server
publishes for the whole file: the strongest supported entry of `Repr-Digest` (RFC 9530), or else
of a legacy `Digest` (RFC 3230), from a `HEAD` or `200` response and never a ranged one. A
response with a `Content-Encoding` other than `identity` publishes nothing, as its digest covers
the encoded bytes, and `Content-Digest` is never used. A mismatch fails the same way, with
`published = true`. `ResolvedSource.checksum` and `ServerInfo.checksum` hold the published digest.

### Limits

- Checksums apply to downloads Ketch writes as one file: HTTP, FTP, HLS and DASH. `download`
  refuses one for sources that write their own files, such as torrents, which verify their pieces
  themselves.
- Instances list `CHECKSUM`. `RemoteKetch` refuses a checksum for servers without it.
- Ketch reads the file back with `FileAccessor.readAt`. Accessors without it skip published
  digests and fail a requested checksum with `KetchError.Unsupported`.

## Errors

Every failure is a `KetchError`, a sealed class that extends `Exception`:

| Type | Retried | Meaning |
|---|---|---|
| `Network` | Yes | Connection or timeout failure, including a stalled transfer |
| `Http(code)` | 5xx and 429 | Non-success HTTP status; carries `retryAfterSeconds` and `rateLimitRemaining` |
| `Disk` | No | File I/O failure |
| `Unsupported` | No | Something required is unsupported, such as ranges or the content's format, or no source handles the URL |
| `FileChanged(reason)` | No | The file changed on the server since the download started (ETag, Last-Modified or FTP MDTM) |
| `CorruptResumeState` | No | The saved resume state could not be read |
| `SourceError(sourceType)` | No | A download source failed; `detail` is safe to show |
| `AuthenticationFailed(sourceType)` | No | Credentials rejected, such as FTP 530 |
| `ChecksumMismatch` | No | The finished file's digest differs from the expected or published one |
| `Canceled` | No | The download was canceled |
| `Unknown` | No | Anything else |

`KetchError.isRetryable` tells which errors Ketch retries on its own: `retryCount` times in a row,
with exponential backoff and jitter, the count starting over whenever an attempt gets further than
every attempt before it. On HTTP 429 it waits for `Retry-After` when given and reduces the task's
connections. `KetchError.tolerantSerializer()` reads unknown error types as `Unknown`.

## Logging

Logging is pluggable and costs nothing when off, the default:

```kotlin
// No logging (default)
Ketch(httpEngine = KtorHttpEngine())

// Console logging, for development
Ketch(httpEngine = KtorHttpEngine(), logger = Logger.console())

// Kermit, from library:kermit
Ketch(httpEngine = KtorHttpEngine(), logger = KermitLogger(minSeverity = Severity.Debug))

// Several backends at once, each with its own level
Ketch(httpEngine = KtorHttpEngine(), logger = Logger.combine(Logger.console(LogLevel.INFO), other))
```

`Logger` and `LogLevel` live in `com.linroid.ketch.api.log` (`library:api`). See
[logging](logging.md) for the format, levels and what never gets logged.

## See also

- [Developer guide](developers.md): modules, quick start, REST API and custom sources
- [Architecture](architecture.md): how the engine, server and apps fit together
- [BitTorrent downloads](torrent.md), [media downloads](media.md), [proxies](proxy.md)
- [Multiple network interfaces](multiple-networks.md)
