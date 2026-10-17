# Developer guide

[![Maven Central](https://img.shields.io/maven-central/v/com.linroid.ketch/core?label=Maven%20Central&logo=apache-maven&logoColor=white)](https://central.sonatype.com/namespace/com.linroid.ketch)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-4c8dec?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Ktor](https://img.shields.io/badge/Ktor-3.6.0-087CFA.svg?logo=ktor&logoColor=white)](https://ktor.io)

This guide is for developers who want to build on Ketch: embed its download engine, control a
Ketch server, talk to the REST API, or add a protocol of their own. It covers the modules, a quick
start, the REST API and a step-by-step guide to writing a download source.

The Ketch apps, the command line and the server all run on one Kotlin Multiplatform library,
published on Maven Central under `com.linroid.ketch`. You can use it to:

- **Embed the download engine** in an Android, iOS, JVM, Node.js or WASI app (`core`).
- **Control a Ketch server** from an Android, iOS or JVM app, or from the browser (`remote`).
- **Talk to a server from any language** over its REST API and server-sent events.
- **Add protocols, storage or logging** by implementing small interfaces.

`Ketch` (the in-process engine) and `RemoteKetch` (the client of a server) implement the same
`KetchApi`, so code written against one works with the other.

> [!NOTE]
> The library is still before 1.0, so its API can change between releases.

## Modules

| Artifact | What it does | Platforms |
|---|---|---|
| `api` | Public interfaces and models: `KetchApi`, `DownloadTask`, `DownloadRequest`, `DownloadState`, `Logger` | All |
| `core` | The download engine: segmented downloads, queue, scheduler, speed limits, resume | Android, iOS, JVM, JS (Node.js), WasmWasi |
| `ktor` | `KtorHttpEngine`, the default HTTP engine for `core` | Android, iOS, JVM |
| `sqlite` | `SqliteTaskStore`, which keeps tasks across restarts | Android, iOS, JVM |
| `ftp` | FTP and FTPS downloads | Android, iOS, JVM |
| `hls` | Finite HLS downloads ([details](media.md)) | Android, iOS, JVM, JS (Node.js), WasmWasi |
| `dash` | Finite DASH downloads ([details](media.md)) | Android, iOS, JVM, JS (Node.js), WasmWasi |
| `torrent` | BitTorrent and magnet downloads ([details](torrent.md)) | Android, iOS, JVM |
| `torrent-api` | `TorrentController`: torrent snapshots, file pages and commands, through `ketch.controller(TorrentController)` on any backend | All |
| `remote` | `RemoteKetch`, a `KetchApi` that controls a Ketch server | Android, iOS, JVM, WasmJs |
| `endpoints` | REST resource and wire model definitions shared by the server and `remote` | Android, iOS, JVM, WasmJs |
| `kermit` | `KermitLogger`, an optional [Kermit](https://github.com/touchlab/Kermit) logging backend | Android, iOS, JVM, JS, WasmJs |

The server (`library:server`) and the MCP server (`library:mcp`) are JVM modules used by the
command line and the apps; they are not published. [Installation](api.md#installation) shows the
version catalog setup and which source sets each module goes in.

## Quick start

Add the engine and an HTTP engine:

```kotlin
// build.gradle.kts
dependencies {
  implementation("com.linroid.ketch:core:<latest-version>")
  implementation("com.linroid.ketch:ktor:<latest-version>")
}
```

Create a `Ketch`, start it once, and download:

```kotlin
val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  config = DownloadConfig(
    maxConnectionsPerDownload = 4,
    maxConcurrentDownloads = 3,
  ),
)
ketch.start() // restores saved tasks

val task = ketch.download(
  DownloadRequest(
    url = "https://example.com/large-file.zip",
    destination = Destination("/path/to/downloads/"), // trailing slash: a folder
  )
)

task.state.collect { state ->
  when (state) {
    is DownloadState.Downloading -> {
      val p = state.progress
      println("${(p.percent * 100).toInt()}%  ${p.bytesPerSecond / 1024} KB/s")
    }
    is DownloadState.Completed -> println("Done: ${state.outputPath}")
    is DownloadState.Failed -> println("Error: ${state.error.message}")
    else -> Unit
  }
}
```

From here:

- Every task can be paused, resumed, canceled or removed, and its speed limit, connections,
  priority and schedule changed while it runs.
- Tasks are kept in memory by default. Pass `taskStore = createSqliteTaskStore(driverFactory)`
  from `sqlite` to resume them after a restart.
- Add `FtpDownloadSource()` or `TorrentDownloadSource()` to `additionalSources` for FTP or
  BitTorrent, and `HlsDownloadSource(httpEngine)` or `DashDownloadSource(httpEngine)` for
  [finite streaming media](media.md), sharing the engine you pass to `Ketch`.

The [API reference](api.md) covers configuration, priorities and scheduling, speed limits,
checksums, errors and logging. [Multiple networks](multiple-networks.md) shows how to spread
downloads over several network interfaces.

## Control a Ketch server

Run a server with `ketch server` ([CLI](../cli/README.md#server)) or turn on sharing in the apps,
then connect to it with `RemoteKetch` from `remote`. It mirrors the server's tasks and follows
their changes as they happen:

```kotlin
val api: KetchApi = RemoteKetch(
  host = "192.168.1.20",
  port = 8642,
  apiToken = "my-secret", // the server's token, if it has one
)
api.start() // connects and keeps reconnecting
api.download(DownloadRequest(url = "https://example.com/file.iso"))
```

`RemoteKetch` works in the browser too (WasmJs), where `core` does not run; the Ketch web app is
built this way. See [RemoteKetch](api.md#remoteketch) for its options.

## REST API

A Ketch server answers JSON over HTTP on port 8642, under `/api`. If the server has a token, send
it as `Authorization: Bearer <token>`.

```bash
curl -X POST http://localhost:8642/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"url": "https://example.com/file.zip", "priority": "HIGH"}'
```

### Tasks and settings

| Method and path | What it does |
|---|---|
| `GET /api/status` | Name, version, uptime, configuration, download folder, free space and supported `features` |
| `PUT /api/config` | Replace the `DownloadConfig`; fields the body leaves out keep their current values |
| `GET`, `PUT /api/network-interfaces` | List the network interfaces, choose the ones to use |
| `POST /api/resolve` | Probe a URL (`{"url": ..., "properties": {...}}`): size, file name, resume support, a torrent's files |
| `POST /api/resolve/content?fileName=` | Probe file content sent as the request body, such as a `.torrent` file (up to 16 MiB) |
| `GET /api/tasks` | List the tasks |
| `POST /api/tasks` | Add a download; the body is a `DownloadRequest` |
| `GET /api/tasks/{id}` | One task |
| `POST /api/tasks/{id}/pause`, `/resume`, `/cancel` | Control a task |
| `PUT /api/tasks/{id}/speed-limit`, `/priority`, `/connections` | Change a task while it runs; `connections` 0 means Auto |
| `PUT /api/tasks/{id}/files` | Choose a task's files, at any time; the body is `{"fileIds": [...]}` |
| `DELETE /api/tasks/{id}?deleteFiles=true` | Remove a task, and its files if asked |
| `GET /api/health` | `200 {"status":"ready"}` once the server has restored its saved tasks, `503 {"status":"starting"}` before; no token needed. Until it is ready, the other endpoints but pairing answer `503 starting` |

### Live updates

| Method and path | What it does |
|---|---|
| `GET /api/stream` | The event stream, on servers listing `events.stream`: one SSE stream per client with `hello`, `subscribed`, a `tasks` snapshot, then every task's lifecycle and state changes, plus what its subscription asks for |
| `PUT /api/stream/{id}/subscription` | Choose what the stream also sends: progress at an interval, the segments of named tasks, and interests such as stats, connections or torrent snapshots |
| `GET /api/connections?limit=` | A snapshot of the live network connections (HTTP hosts, FTP servers, torrent peers), at most `limit` (1..1024, default 256), on servers listing `net.activeConnections` |
| `GET /api/countries` | The status of the IP to country database that names connections' `country`, on servers listing `net.countries` |
| `POST /api/countries/download` | Starts downloading that database (DB-IP Lite, about 5 MB) on the server and answers its status |
| `GET /api/events`, `/api/events/{id}` | Deprecated: SSE `task_added`, `task_removed`, `state_changed` and `progress` events, for older clients |
| `GET /api/connections/events?limit=` | Deprecated: SSE `snapshot` events of the live connections, at most 16 streams at once |

The event stream is described in the [event stream plan](plans/event-stream.md).

### Torrents

These exist on servers listing `torrent.control`:

| Method and path | What it does |
|---|---|
| `GET /api/torrents/capabilities` | What the server's torrent controller can do |
| `GET /api/torrents/{id}` | A torrent task's snapshot |
| `GET /api/torrents/{id}/files?limit=&cursor=&sort=&desc=true` | A page of its files, up to 1000, sorted `torrent`, `name`, `size`, `extension` or `selected` |
| `PUT /api/torrents/{id}/selection`, `/seeding` | Choose its files, or start or stop seeding it, guarded by a revision and an idempotency key |
| `GET /api/torrents/{id}/events` | Deprecated: SSE `snapshot` events of it; the event stream carries them |

### Pairing

| Method and path | What it does |
|---|---|
| `POST /api/pairing`, `GET`, `DELETE /api/pairing/{id}` | Ask the server's owner for the token, poll for the answer, withdraw; no token needed |

Pairing only works on servers whose owner can answer, such as the apps' (mDNS TXT `pairing=1`).
`RemotePairing` in `remote` sends a request and waits for the answer.

### Notes

- The routes are defined once as Ktor resources in `endpoints` (`Api`), with the wire models in
  `com.linroid.ketch.endpoints.model`.
- Without a token, the server only answers requests addressed to this machine and refuses web
  pages on other origins. See [the CLI's server options](../cli/README.md#server) for hosts, CORS
  and mDNS.
- Refused calls answer with an `ErrorResponse` (`{"error": ..., "message": ...}`).

## Extend Ketch

Every part that varies by platform or use is an interface you can replace:

| Interface | Replace it to | Built in |
|---|---|---|
| `DownloadSource<S>` | Add a protocol | HTTP(S); `FtpDownloadSource`, `HlsDownloadSource`, `DashDownloadSource`, `TorrentDownloadSource` |
| `HttpEngine` | Use another HTTP client | `KtorHttpEngine`, `MultiNetworkHttpEngine` |
| `TaskStore` | Keep tasks somewhere else | `InMemoryTaskStore`, `SqliteTaskStore` |
| `Logger` | Send logs to your backend | `Logger.console()`, `Logger.combine()`, `KermitLogger` |
| `FileNameResolver` | Name files when the source suggests no name | `DefaultFileNameResolver` |

To give AI agents control of a `KetchApi`, run `ketch mcp`
([MCP server](../cli/README.md#mcp-server)). The [architecture](architecture.md) shows how the
parts fit together.

## Write your own download source

A `DownloadSource` teaches Ketch a new kind of link. Ketch keeps doing everything around the
transfer: the queue, retries, speed limits, file naming, partial files, progress, persistence and
checksums. Your source only has to answer three questions:

1. **Is this link mine?** `canHandle(input)`.
2. **What is behind it?** `resolve(input, config)` returns its size and name.
3. **How do I fetch it?** `download(context, state)` writes the bytes.

To resume later, a source can also save a small state object of its own, `S`.

### The interface

These are the members of `DownloadSource<S>` (`com.linroid.ketch.core.engine`), with their
defaults:

```kotlin
interface DownloadSource<S : Any> {
  val type: String                              // unique, such as "http" or "torrent"
  val stateSerializer: KSerializer<S>           // encodes S; Unit.serializer() for none

  fun canHandle(input: ResolveInput): Boolean
  suspend fun resolve(
    input: ResolveInput,
    config: DownloadConfig = DownloadConfig.Default,
  ): ResolvedSource
  suspend fun download(context: DownloadContext, state: S?) // null: start from the beginning
  suspend fun store(context: DownloadContext): S? = null     // null: keep the last saved state
  // The default deletes the file Ketch opened when deleteFiles is true.
  suspend fun cleanup(context: DownloadContext, state: S?, deleteFiles: Boolean)

  // Optional
  val previousTypes: Set<String> get() = emptySet()  // old names of `type`, for saved tasks
  val features: Set<String> get() = emptySet()       // added to KetchStatus.features
  val managesOwnFileIo: Boolean get() = false        // true: the source writes its own files
  suspend fun planSelection(request: SelectionRequest<S>): SelectionPlan // sources with files
  fun attach(host: SourceHost): SourceExtension? = null // controllers and lifecycle hooks
  fun close() {}
}
```

### How Ketch drives a source

1. **Routing.** Ketch asks each source in `additionalSources`, in order, then the built-in HTTP
   source, whether it `canHandle` the link. The first that says yes owns the task.
2. **Resolving.** For a new download Ketch calls `resolve` itself, unless the request already
   carries a `resolvedSource`. It uses the result to pick the file name, category folder and size,
   then hands it to the download as `context.preResolved`.
3. **Downloading.** Ketch calls `download(context, null)` for a fresh start. The source writes
   through `context.fileAccessor`; Ketch moves the partial file to its final name, and verifies a
   checksum, after `download` returns.
4. **Saving state.** Ketch calls `store(context)` as each run starts (before any byte is written),
   every `saveIntervalMs`, and once more as the run stops. It saves what you return as JSON with
   the task (`TaskRecord.sourceState`), next to the segments in `context.segments`.
5. **Pausing.** A pause cancels the coroutine running `download`. Let the cancellation through.
6. **Resuming and retrying.** Ketch calls `download(context, state)` with the decoded state: the
   one the run started from, or on a retry the latest one stored. Check that the content has not
   changed, then continue from `context.segments`. A state that cannot be decoded fails the task
   with `KetchError.CorruptResumeState` before your code runs. On a resume `context.preResolved`
   is usually `null`, so ask the server again if you need fresh details.
7. **Removing.** `cleanup(context, state, deleteFiles)` runs after the download stopped. The
   default deletes the file Ketch opened, which suits a source that writes one file.

### Example: files behind your app's API

Suppose your backend serves files as `vault://<id>` links: you ask it about a file, and it answers
with the file's name, size, a version that changes whenever the content does, and a short-lived
signed HTTPS link. This source resolves those links, downloads the signed URL through Ketch's
`HttpEngine`, and resumes after a restart only if the version is unchanged.

```kotlin
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.engine.ConnectionSpec
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.file.sanitizeFileName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** Your backend: describes a file and signs a short-lived download link for it. */
interface VaultApi {
  suspend fun describe(fileId: String): VaultFile
}

data class VaultFile(
  val name: String,
  val size: Long,        // -1 when unknown
  val version: String,   // changes whenever the content does
  val signedUrl: String, // plain HTTPS, expires after a few minutes
  val host: String,      // the host of signedUrl, for the connections view
)

/**
 * What a later run needs to continue. Tasks save it as JSON, so later versions of your app must
 * still read it: add fields with defaults, never rename or retype them.
 */
@Serializable
data class VaultState(val version: String)

class VaultDownloadSource(
  private val api: VaultApi,
  private val http: HttpEngine,
) : DownloadSource<VaultState> {
  override val type: String = "vault"
  override val stateSerializer: KSerializer<VaultState> = VaultState.serializer()

  override fun canHandle(input: ResolveInput): Boolean =
    input is ResolveInput.Url && input.url.startsWith("vault://")

  override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
    val url = (input as ResolveInput.Url).url
    val file = api.describe(url.removePrefix("vault://"))
    return ResolvedSource(
      url = url,
      sourceType = type,
      totalBytes = file.size,
      supportsResume = file.size >= 0,
      suggestedFileName = sanitizeFileName(file.name), // never trust a server's name as is
      maxSegments = 1,
      metadata = mapOf(VERSION to file.version),
    )
  }

  override suspend fun download(context: DownloadContext, state: VaultState?) {
    // Ask again on every run: the signed link from an earlier run may have expired.
    val file = api.describe(context.url.removePrefix("vault://"))
    if (state != null && state.version != file.version) {
      throw KetchError.FileChanged("version ${state.version} is now ${file.version}")
    }
    if (file.size < 0) {
      throw KetchError.SourceError(type, detail = "The server did not report the file's size")
    }

    // A fresh start is one segment for the whole file; a resume continues Ketch's saved one.
    var segment = context.segments.value.singleOrNull()
      ?.takeIf { state != null && it.end == file.size - 1 }
      ?: Segment(index = 0, start = 0, end = file.size - 1)
    context.segments.value = listOf(segment)
    context.onProgress(segment.downloadedBytes, file.size)
    if (segment.isComplete) return

    val engine = http.withProxy(context.config.proxy) // honors DownloadConfig.proxy
    val connection = context.connections.open(
      ConnectionSpec(source = type, host = file.host, port = 443, protocol = "HTTP", secure = true),
    )
    try {
      // The watchdog fails a stalled transfer with a retryable KetchError.Network.
      context.watchdog.watch { monitor ->
        val range = segment.currentOffset..segment.end
        engine.download(file.signedUrl, range) { chunk ->
          monitor.received(chunk.size)
          connection.received(chunk.size)
          monitor.throttled { context.throttle(chunk.size) } // speed limits, before writing
          context.fileAccessor.writeAt(segment.currentOffset, chunk)
          segment = segment.copy(downloadedBytes = segment.downloadedBytes + chunk.size)
          context.segments.value = listOf(segment)
          context.onProgress(segment.downloadedBytes, file.size)
        }
      }
    } finally {
      connection.close()
    }
  }

  // Ketch resolved the file before a fresh start, so preResolved holds the version the bytes
  // come from. On a resume it is null, and returning null keeps the state saved before.
  override suspend fun store(context: DownloadContext): VaultState? =
    context.preResolved?.metadata?.get(VERSION)?.let(::VaultState)

  private companion object {
    const val VERSION = "version"
  }
}
```

Register it, sharing the HTTP engine with `Ketch`:

```kotlin
val http = KtorHttpEngine()
val ketch = Ketch(
  httpEngine = http,
  taskStore = createSqliteTaskStore(driverFactory),
  additionalSources = listOf(VaultDownloadSource(vaultApi, http)),
)
ketch.start()
ketch.download(DownloadRequest(url = "vault://reports/2026-q3.pdf"))
```

### Rules of thumb

- **Errors.** Throw a `KetchError`. `Network` and HTTP 5xx or 429 are retried; `FileChanged`,
  `SourceError`, `AuthenticationFailed` and `Unsupported` fail the task. `HttpEngine` already
  throws `Network` and `Http`. Never swallow `CancellationException`.
- **Progress.** Call `context.onProgress(downloaded, total)` and keep `context.segments` current:
  Ketch saves the segments and shows them in the apps.
- **Speed limits.** Call `context.throttle(bytes)` before writing each chunk. Inside a watchdog,
  wrap it in `monitor.throttled { }` so waiting for the limit does not count as a stall.
- **Settings.** Read defaults from `context.config` (a snapshot taken when the run started), not
  from your constructor. Segmented sources size their transfers with
  `context.effectiveConnections()` and can follow `context.maxConnections` as it changes.
- **Unknown sizes.** When `totalBytes` is `-1`, either stream from offset zero with no segments
  (Ketch records the final size) or throw `KetchError.SourceError`, as the example does.
- **File names.** Pass any name from a response or a URL through `sanitizeFileName()` before
  returning it as `suggestedFileName`. Ketch sanitizes it again before saving into a folder.
- **State.** Keep `S` small and backward compatible: add fields with defaults only; unknown fields
  are ignored. Use `Unit` (`Unit.serializer()`) when there is nothing to save, as the HLS and DASH
  sources do. Large descriptions of the whole content belong in
  `ResolvedSource.metadata[ResolvedSource.METAINFO_KEY]`: Ketch keeps such a resolution with the
  task (`TaskRecord.resolvedSource`) and passes it back as `context.preResolved` on every run.
- **Content input.** If your source reads files the caller holds (as the torrent source reads
  `.torrent` files), accept `ResolveInput.Content` in `canHandle` and `resolve`, and return
  everything `download` needs in the result, since Ketch will not resolve `url` again.
- **Renames.** If you change `type`, list the old name in `previousTypes` so saved tasks still find
  your source.
- **Files of its own.** Sources that write their own files, as torrents do, set
  `managesOwnFileIo = true` (Ketch then opens no `FileAccessor` and refuses checksums) and
  override `cleanup` to delete them from `state`.
- **Choosing files.** Sources with several files implement `planSelection`, follow
  `context.selection` while running and confirm each revision with
  `context.acknowledgeSelection(revision)`, and list `KetchFeatures.FILE_SELECTION` in
  `features`.
- **Extras.** `attach(host)` returns a `SourceExtension` that can offer controllers for
  `KetchApi.controller`, answer its own `Interest`s for `KetchApi.subscribe`, and hook into task
  start, completion and selection changes. The torrent source uses it for seeding.
- **Testing.** `DownloadContext` has a public constructor, so a unit test can call `download` with
  a fake `HttpEngine` and an in-memory `FileAccessor`, then check the bytes, segments and
  `store` result.

## How a download works

1. **Schedule and queue.** Wait for the task's schedule and conditions, then for a free slot by
   priority within the concurrency and per-host limits.
2. **Resolve.** Pick the source and ask it about the download (for HTTP, a `HEAD` request, or a
   `GET` of the first byte when the server refuses `HEAD`): its size, range support, ETag and
   Last-Modified. Choose the file name and folder.
3. **Plan.** With range support, split the file into one segment per connection; otherwise use a
   single connection.
4. **Download.** Each segment downloads its byte range at once and writes at its own offset in a
   `<name>.ketch` partial file. Changing the connection count splits or merges the remaining
   ranges as it runs.
5. **Throttle.** Token buckets hold each download to its own speed limit and all of them to the
   global one.
6. **Persist.** Segment progress and the source's state are saved to the `TaskStore`, so pause and
   resume work across restarts.
7. **Complete.** Verify a checksum if there is one, then move the partial file to its final name.
8. **Resume.** Check that the file on the server is unchanged and the local file is intact, then
   continue. A server without range support, or content of unknown size, starts over.

The [architecture](architecture.md#download-pipeline) describes each step in detail.

## Further reading

- [API reference](api.md): installation, `KetchApi`, configuration, errors and logging
- [Architecture](architecture.md): modules, the download pipeline, the server and the apps
- [Logging](logging.md): log format, levels and what never gets logged
- [BitTorrent](torrent.md): torrent support, configuration and verification
- [Multiple networks](multiple-networks.md): downloading over several network interfaces
- [AI discovery](ai-discovery.md): the `ai:discover` module and its providers
- [Code style](development/code-style.md) and [testing](development/testing.md): for
  contributors
