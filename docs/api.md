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
ketch-torrent-api = { module = "com.linroid.ketch:torrent-api", version.ref = "ketch" }
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
`sqlite`, `ftp` and `torrent` target Android, iOS and JVM. Browser (WasmJs) apps use `remote` to
control a daemon instead of running the engine in-process. `torrent-api`, the torrent controller
contract that `torrent` and `remote` expose, has the targets of `api`, so any client can inspect
and command torrents (`ketch.controller(TorrentController)`).

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
  suspend fun resolve(input: ResolveInput): ResolvedSource  // ResolveInput.Url or .Content
  suspend fun status(): KetchStatus
  suspend fun updateConfig(config: DownloadConfig)
  fun <T> subscribe(interest: Interest<T>, interval: Duration = 1.seconds): Flow<T>
  fun close()
  // ... plus backendLabel, controller(), networkInterfaces(), selectNetworkInterfaces()
}
```

Call `start()` once before use; persisted tasks only appear in `tasks` after it. The library
version is available as `KetchApi.VERSION` and `KetchApi.REVISION`.

`resolve(url)` probes a URL (for HTTP, a HEAD request, or a `GET` for its first byte when the server
refuses HEAD) and returns its size, resume support, suggested file name and, for sources with
several files or variants such as torrents, the selectable `files` and their `selectionMode`:
`MULTIPLE` for any subset, `SINGLE` for exactly one variant (reserved for HLS quality; no built-in
source offers it yet). `resolve(bytes, fileName)` does the same for file content the caller already
holds, such as a picked `.torrent` file. Both are extensions building a `ResolveInput` (`Url(url,
headers)` or `Content(bytes, fileName)`) for `KetchApi.resolve`, the one function backends
implement; a URL is fetched by the instance, as a download's is, while content comes from the
caller. Pass the result as `DownloadRequest.resolvedSource` (with its `url` as the request URL) to
skip the probe; `selectedFileIds` picks a subset of `files`, or one of them for a `SINGLE` choice
(empty downloads the source's `defaultSelection()`: every file, or the first variant). With
`awaitFileSelection = true` and no `selectedFileIds`, a download with several files or variants
waits once they are known, as `Paused(AwaitingFileSelection)` holding no download slot, until
`selectFiles` chooses; `resume()` downloads the default instead.

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
  suspend fun selectFiles(fileIds: Set<String>)  // several files or variants: change them
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

`selectFiles(ids)` changes which files or variant of a download with several the task
downloads, in every state but canceled: any subset for `MULTIPLE`, exactly one ID for `SINGLE`.
A running source that applies the change live, as a torrent does without reconnecting, keeps
going; one that does not confirm it runs again through resume. Newly chosen files are downloaded
and unchosen ones stop downloading but stay on disk, and a completed task that gains files
downloads them and completes again. It throws `IllegalArgumentException` for an empty set, several
IDs for a `SINGLE` choice or an ID the download does not have, `IllegalStateException` while the
file list is not known or after `cancel()`, and `UnsupportedOperationException` for tasks without
files or backends that cannot change them (see [torrents](torrent.md#choosing-files)).

`DownloadState.Paused.reason` is a `PauseReason`: `User` for `pause()` (and tasks restored
paused), `Preempted(byTaskId)` for a task that gave its slot to an URGENT one and still waits in
the queue, `Shutdown` for a task that was downloading when its `Ketch` closed (its partial file
is kept and it resumes on the next `start()`), `AwaitingFileSelection` for a download that waits
for `selectFiles`, and `WaitingForCondition`. A reason the client does not know decodes as
`User`. `DownloadState.Completed.completedAt` is when the task last finished, in whole
milliseconds; it is `null` for tasks that finished before Ketch recorded it.
`DownloadState.Completed.sharing` is `true` while its source shares the finished content with
others, as a seeding torrent does. `DownloadState.Completed.checksum` is the checksum the file was
verified against (see [checksums](#checksums)), or `null`.
`queuePosition` counts the tasks that wait for a slot (`Queued`, or `Paused` for `Preempted`)
in the order the queue starts them, priority first, then age.

`KetchStatus.features` lists the optional behaviors an instance supports, from
`KetchFeatures`: `AUTO_CONNECTIONS` (`setConnections(0)`), `QUEUE_POSITION`, `REQUEST_ID`,
`PROXY`, `CATEGORY_FOLDERS`, `ACTIVE_CONNECTIONS`, `CHECKSUM` (`download.checksum`), the media
sources' `FINITE_HLS` and
`FINITE_DASH`, `FILE_SELECTION`
(`download.fileSelection`, `selectFiles`) and `AWAIT_FILE_SELECTION`
(`download.awaitFileSelection`, `awaitFileSelection`), listed by the sources that choose files
(the torrent source today). Modules beside `library:api` name their own: the torrent source lists
`TorrentFeatures.CONTROL` (`torrent.control`, in `library:torrent-api`).
Older servers send fewer, so
their tasks report no position and `setConnections(0)` on them throws
`UnsupportedOperationException`.

`KetchApi.subscribe(interest, interval)` follows an `Interest` while the flow is collected and
reports what it names. Interests are serializable, so a remote instance sends its server the
ones its callers hold and receives only those; `interval` (default 1 s) is how often updates come
at most, the shortest of the callers' winning. The built-in ones are nested in `Interest`:

- `Interest.Segments(taskIds)`: the named tasks' segments, by task ID, which also update on
  `DownloadTask.segments`. A remote instance sends a task's lifecycle, state changes and progress
  anyway, but its segments (one per file for a torrent) only while followed.
- `Interest.Stats`: `KetchStats`, the totals of the tasks that are downloading.
- `Interest.Connections(limit)`: `ActiveConnections` snapshots of every task's live network
  connections (HTTP requests, FTP transfers, media fetches, torrent peers), once a second while
  something changes, on instances listing `ACTIVE_CONNECTIONS`. `limit` (1..1024, default 256)
  caps the connections a snapshot holds; `total` and the rates count all of them. Without the
  feature, collecting it throws `UnsupportedOperationException`.

Modules define their own, such as `TorrentInterest` in `library:torrent-api`; an instance fails
the interests it does not know with `UnsupportedOperationException` when collected. A flow ends
when what it follows is gone, as a removed torrent's does after its `null`.

Each interest names its `valueSerializer`, which a server encodes what it reports with, so any
interest whose type `StreamSerializers` (`library:endpoints`) names reaches a remote instance
without code of its own on either side. A remote instance's interests other than segments and
stats end with the failure when the connection drops, so collect them again to resume.

`RemoteKetch(progressInterval = 1.seconds)` sets how often every downloading task's progress
comes; set `foreground = false` while nothing is shown, and the server sends only state changes.

`KetchApi.controller(key)` returns an optional typed controller a backend offers beyond the download
API, or `null`; `library:api` defines only the `ControlKey` lookup, and the module that defines a
controller defines its key. `library:api` also holds the file orders every client shares:
`FileOrder`, `NaturalOrder`, `fileExtension()` and `sortedByFileOrder()`.

### `library:torrent-api`

The torrent control contract, with the targets of `library:api`, so browser and remote clients
use it without linking the torrent engine. `ketch.controller(TorrentController)` is a
`TorrentController`, or `null` without a torrent source: capabilities, a task's snapshot, its
files a page at a time (`files`, sorted by `TorrentFileOrder`, which names a
`FileOrder`, and optionally descending), and the `select` and `setSeeding` commands, each guarded
by a `TorrentCommandContext` (an idempotency key and the expected revision). Failures throw
`TorrentCommandException` with a `TorrentCommandError`. `RemoteKetch` returns one that fails closed
against servers without `TorrentFeatures.CONTROL`. A task's snapshots, as they change, come from
`subscribe(TorrentInterest(taskId))`. See the
[control contract](design/torrent-control-contract.md#version-1-runtime).

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
`canHandle` accepts the URL is used. `fileNameResolver` and `dispatchers` can also be replaced;
`dispatchers` defaults to `KetchDispatchers.Default`, which every instance in the process shares
(see [architecture](architecture.md#expectactual)).

### `library:ktor`

Ready-made `HttpEngine` backed by Ktor Client with per-platform engines:

| Platform | Ktor Engine |
|---|---|
| Android | OkHttp |
| iOS | Darwin |
| Desktop | CIO; OkHttp through a proxy |

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

`KtorHttpEngine` applies `DownloadConfig.proxy` and `DownloadRequest.proxy` (see
[proxies](proxy.md)): every request, and every hop of a redirect, goes directly or through an
HTTP or SOCKS5 proxy as the setting says for its URL. An engine built around an `HttpClient` of
your own only follows the system's proxy settings, in the client's own way. `Ketch` lists the
`http.proxy` feature only when its engine reports `HttpEngine.supportsProxies`, and otherwise
refuses other proxies in `updateConfig` and `download` with `UnsupportedOperationException`.

For downloading across multiple interfaces, wrap network-bound engines in
`MultiNetworkHttpEngine`. JVM provides `KtorHttpEngine.forLocalAddress(InetAddress)`;
Android provides `KtorHttpEngine.forNetwork(Network)`. These are extension functions in
`com.linroid.ketch.engine` and must be imported explicitly. See
[multiple network interfaces](multiple-networks.md) for setup and platform limits.

`KetchApi.networkInterfaces()` returns supported interfaces and their current selection on the
download instance. `KetchApi.selectNetworkInterfaces(NetworkInterfaceConfig(interfaceIds))`
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
  retryCount = 3,                 // retries in a row without progress before failing
  retryDelayMs = 1000,            // first retry delay, doubled for each further retry
  unlimitedRetries = false,       // true retries until the task is paused or canceled
  maxRetryDelayMs = 60_000,       // retry delay cap; each delay is randomized from half to all
  idleTimeoutMs = 60_000,         // retry a connection that receives no data this long (0 = off)
  minSpeed = 0,                   // bytes/s a connection must average over 30 s (0 = off)
  progressIntervalMs = 200,       // progress throttle
  saveIntervalMs = 5000,          // how often segment progress is persisted
  bufferSize = 8192,              // FTP read buffer size
  speedLimit = SpeedLimit.kbps(500), // global speed limit (default: Unlimited)
  maxConcurrentDownloads = 4,     // max simultaneous downloads (0 = unlimited)
  maxConnectionsPerHost = 16,     // max simultaneous downloads per host (0 = unlimited)
  proxy = Proxy.System,           // HTTP(S) proxy: System, Direct or Proxy.Manual(url)
  categories = emptyList(),       // folders inside defaultDirectory, by type or site
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

The per-host limit counts downloads by URL host (case-insensitive, ignoring user info and port),
not their individual connections. With the defaults, the global limit of four downloads applies
before the per-host limit of sixteen. Explicit values in an existing configuration are kept;
omitted fields use the current defaults.
Magnet links, `torrent:` identifiers and local files are not counted.

`maxConnectionsPerDownload` and `DownloadRequest.connections` split HTTP(S) and FTP(S)
downloads into parallel ranges when the server supports HTTP Range or FTP REST; otherwise a
single connection is used. BitTorrent treats a per-task connection count as its peer limit.
A count of 0 (Auto) uses the configured default of the run: `maxConnectionsPerDownload`, or a
torrent's own peer limit; `setConnections(0)` returns a running task to it.
`bufferSize` sets the FTP read buffer; HTTP buffering is up to the `HttpEngine`.

### Category folders

`categories` sorts the downloads that do not choose a folder into folders inside
`defaultDirectory`. A request whose `destination` is `null` or a bare file name is saved in the
`folder` of the first `DownloadCategory` it matches, and in `defaultDirectory` when it matches
none; a destination naming a folder or a full path is used as it is. Matching happens when a
download starts, against the name it is saved under, the `Content-Type` the server reported
(`ResolvedSource.contentType`) and the host of the request URL:

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

- A category matches when the file's type matches (its name ends with one of `extensions`, such
  as `tar.gz`, or its media type is one of `mimeTypes`, where `video/*` covers a whole kind) and,
  when `hosts` is set, its host is one of them or a subdomain of one. Rules ignore case.
- A category with no rules matches nothing. Order matters: the first match wins.
- `folder` is relative to `defaultDirectory`, `/` separating nested folders; an absolute folder,
  a drive or `..` is rejected with `IllegalArgumentException`. Each folder name is made safe like
  a server's file name, and folders are created as the download starts, including inside an
  Android `content://` tree.
- Magnet links have no host, and a torrent matches by its name, which for a torrent of several
  files is usually a folder name without an extension.
- `DownloadConfig.categoryFor(fileName, contentType, host)` tells which category a download
  would go to, as the apps' add sheet shows it. Instances that support category folders list
  `KetchFeatures.CATEGORY_FOLDERS` in `status().features`.
- `RemoteKetch.updateConfig` sends the categories to the server. A `PUT /api/config` body
  without a `categories` field, as clients older than category folders send it, keeps the
  server's categories rather than clearing them.

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

// Proxies: one for every download, and one for a single download
ketch.updateConfig(config.copy(proxy = Proxy.Manual("http://proxy.lan:3128")))
ketch.download(
  DownloadRequest(
    url = "https://example.com/file.zip",
    proxy = Proxy.Manual("socks5://user:secret@127.0.0.1:1080", bypass = listOf("*.lan")),
  )
)
```

### Checksums

```kotlin
ketch.download(
  DownloadRequest(
    url = "https://example.com/file.iso",
    checksum = Checksum.parse("sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"),
  )
)
```

`DownloadRequest.checksum` is the digest the file must have: `Checksum(algorithm, hex)` with
`ChecksumAlgorithm.MD5`, `SHA_1`, `SHA_256` or `SHA_512`. `Checksum.parse` reads
`<algorithm>:<hex>` (`sha256:…`, `SHA-256:…`), base64 values (`sha-256=:…:`, as HTTP digest
headers write them) and bare hex digests, whose length names the algorithm; JSON carries it as
the text `"sha256:<hex>"`. The hash functions are pure Kotlin (`com.linroid.ketch.core.digest`),
so every engine target verifies.

Once the last byte is flushed, Ketch reads the file back and hashes it before the task completes;
the task keeps reporting `Downloading` with every byte downloaded meanwhile, and pausing or
cancelling stops the hashing. A match completes the task with `Completed.checksum`; a difference
fails it with `KetchError.ChecksumMismatch(algorithm, expected, actual, published)`, which is not
retried. The mismatching file is deleted and the task's progress discarded, so it never stays
where it looks complete and `resume()` downloads it again. A completed task is never hashed
again.

Without a checksum of its own, an HTTP download is checked against the digest its server
publishes for the whole file: the strongest supported entry of `Repr-Digest` (RFC 9530), else of
a legacy `Digest` (RFC 3230) from a `HEAD` or `200` response, never from a ranged one. A response
with a `Content-Encoding` other than `identity` publishes nothing, as its digest covers the
encoded bytes, and `Content-Digest` is never used. A mismatch against it fails the same way, with
`published = true`. `ResolvedSource.checksum` and `ServerInfo.checksum` hold the published digest
(`ServerInfo.publishedChecksum` reads the headers for custom `HttpEngine`s).

Checksums apply to downloads Ketch writes as one file: HTTP, FTP, HLS and DASH. `download`
refuses one for sources that write their own files, such as torrents, which verify their pieces
themselves. Instances list `KetchFeatures.CHECKSUM`; `RemoteKetch` refuses a checksum for servers
without it, which would ignore it. `FileAccessor.readAt` reads the file back; accessors without
it skip published digests and fail a requested checksum with `KetchError.Unsupported`.

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
| `ChecksumMismatch` | No | The finished file's digest differs from the expected or published one |
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
