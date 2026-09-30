# Ketch Architecture

This document describes the internal architecture of Ketch, covering module boundaries,
core abstractions, the download pipeline, and the example app's multi-instance design.

## Module Dependency Graph

```
library:api          (public interfaces and models, no dependencies)
  ^
  +--- library:core       (download engine, implements KetchApi)
  |      ^
  |      +--- library:ktor      (Ktor-based HttpEngine)
  |      +--- library:sqlite    (SQLite-backed TaskStore)
  |      +--- library:ftp       (FTP/FTPS DownloadSource)
  |      +--- library:torrent   (BitTorrent DownloadSource, also uses ktor)
  |      +--- library:server    (Ktor REST API + SSE daemon, also uses endpoints)
  |
  +--- library:endpoints  (REST resource definitions shared by server and remote)
  +--- library:remote     (HTTP+SSE client, implements KetchApi, uses endpoints)
  +--- library:kermit     (Kermit logging adapter)
  +--- library:mcp        (MCP server exposing a KetchApi as AI agent tools)
  +--- config             (TOML configuration)
         ^
         +--- ai:discover  (LLM-driven resource discovery)

cli                  (JVM CLI: core, ktor, sqlite, ftp, torrent, server, mcp, config, ai:discover)
app/shared           (Compose Multiplatform UI: config + remote on every platform; core, ktor,
                      ftp, torrent on Android, iOS and desktop)
app/desktop          (JVM entry point: shared, core, ktor, ftp, torrent, sqlite, server,
                      ai:discover)
app/android          (Android entry point: same modules as app/desktop)
app/web              (WasmJs entry point: shared, remote-only)
```

## Modules

Ketch is split into published SDK modules that you add as dependencies:

| Module | Description | Platforms |
|---|---|---|
| `library:api` | Public API interfaces and models (`KetchApi`, `DownloadTask`, `DownloadState`, etc.) | All |
| `library:core` | In-process download engine -- embed downloads directly in your app | Android, iOS, Desktop, JS (Node.js), WasmWasi |
| `library:ktor` | Ktor-based `HttpEngine` implementation (the default engine for `core`) | Android, iOS, Desktop |
| `library:sqlite` | SQLite-backed `TaskStore` for persistent resume | Android, iOS, Desktop |
| `library:ftp` | FTP/FTPS `DownloadSource` | Android, iOS, Desktop |
| `library:torrent` | BitTorrent and magnet `DownloadSource` ([details](torrent.md)) | Android, iOS, Desktop |
| `library:kermit` | Optional [Kermit](https://github.com/touchlab/Kermit) logging integration | Android, iOS, Desktop, JS, WasmJs |
| `library:remote` | Remote client -- control a Ketch daemon server from any platform | Android, iOS, Desktop, WasmJs |
| `library:endpoints` | REST endpoint and wire model definitions shared by `server` and `remote` | Android, iOS, Desktop, WasmJs |
| `library:server` | Daemon server with REST API and SSE events (not an SDK; standalone service) | Desktop (JVM), embedded by the Android app |
| `library:mcp` | MCP server exposing a `KetchApi` as tools for AI agents (`ketch mcp`) | Desktop (JVM) |
| [`cli`](../cli/README.md) | Command-line interface for downloads and running the daemon | Desktop |

Choose your backend: use **`core`** for in-process downloads, or **`remote`** to control a daemon
server. Both implement the same `KetchApi` interface, so your UI code works identically.

## Core Abstractions

### KetchApi

The central service interface, defined in `library:api`. Both `Ketch` (in-process) and
`RemoteKetch` (HTTP+SSE client) implement it, so UI and CLI code works identically
regardless of backend.

```
KetchApi
  |-- tasks: StateFlow<List<DownloadTask>>
  |-- start()
  |-- download(request): DownloadTask
  |-- resolve(url, properties): ResolvedSource
  |-- resolveContent(content, fileName): ResolvedSource
  |-- status(): KetchStatus
  |-- updateConfig(config)
  |-- networkInterfaces() / updateNetworkInterfaces(config)
  |-- close()
```

### Pluggable Components

Ketch uses interface-based dependency injection for all platform-varying or
user-swappable components:

| Interface | Purpose | Implementations |
|---|---|---|
| `HttpEngine` | HTTP HEAD/GET with range support | `KtorHttpEngine` (library:ktor), `MultiNetworkHttpEngine` (one engine per network) |
| `TaskStore` | Persist task metadata for resume | `InMemoryTaskStore`, `SqliteTaskStore` |
| `Logger` | Diagnostic logging | `Logger.None`, `Logger.console()`, `Logger.combine()`, `KermitLogger` |
| `DownloadSource` | Protocol-level download handling | `HttpDownloadSource` (built-in), `FtpDownloadSource`, `TorrentDownloadSource` |
| `FileNameResolver` | File name when the source suggests none | `DefaultFileNameResolver` |

### Expect/Actual

Platform-specific code is minimized to a few expect/actual declarations:

| Declaration | Android | JVM / iOS | JS / WasmWasi |
|---|---|---|---|
| `createFileAccessor()` | `PathFileAccessor`; `ContentUriFileAccessor` for `content://` URIs | `PathFileAccessor` (okio `FileHandle`) | `PathFileAccessor` |
| `platformFileSystem` | `FileSystem.SYSTEM` | `FileSystem.SYSTEM` | `NodeJsFileSystem` / `WasiFileSystem` |
| `Logger.console()` | Logcat | Timestamped `println` (errors on stderr on JVM) | Timestamped `println` |
| `KetchDispatchers()` | Dedicated thread pools | Dedicated thread pools | `Dispatchers.Default` |

`KetchDispatchers` separates task coordination (`main`, single-threaded), network transfers
(`network`) and file I/O (`io`). Pass your own dispatchers with `Ketch(dispatchers = ...)`.

## Download Pipeline

A download progresses through these stages:

```
DownloadRequest
  |
  v
[1. Schedule]  Only for a non-Immediate schedule or conditions: DownloadScheduler
  |            waits for AtTime/AfterDelay and every DownloadCondition (Scheduled
  |            state), then hands the task to the queue.
  |
  v
[2. Queue]  DownloadQueue orders tasks by priority, then creation time, and starts
  |         them while maxConcurrentDownloads and the per-host limit allow; the
  |         rest stay Queued. URGENT can preempt a lower-priority active download,
  |         which is paused and requeued.
  |
  v
[3. Resolve]  SourceResolver finds the right DownloadSource for the URL, unless
  |           DownloadRequest.resolvedSource already names it. HttpDownloadSource
  |           sends HEAD to get size, range support, ETag, Last-Modified
  |           (ServerInfo). The output path is resolved against the default
  |           directory and deduplicated.
  |
  v
[4. Plan]  SegmentCalculator splits the file into N segments, N being the task's
  |        effective connection count. Single segment if no range support.
  |
  v
[5. Download]  DownloadCoordinator starts a DownloadExecution, which calls the
  |            source. HttpDownloadSource runs one SegmentDownloader per segment
  |            concurrently; each downloads its byte range and writes to the
  |            correct file offset via FileAccessor. Every chunk is throttled by
  |            the per-task and global token buckets. A connection-count change
  |            resegments the remaining bytes without losing progress.
  |
  v
[6. Persist]  Segment progress is saved to TaskStore at regular intervals
  |           (saveIntervalMs) for crash recovery.
  |
  v
[7. Complete]  All segments finished -> Completed state with file path.
               On a retryable error -> retry with exponential backoff, keeping
               segment progress (from byte zero without range support).
               Otherwise, or after retryCount retries -> Failed state.
```

### Pause / Resume

- **Pause**: Cancels the execution. The source stops its segments and the execution saves
  their final progress to TaskStore. A queued task can be paused too; it leaves the queue.
- **Resume**: Probes the server again. An ETag or Last-Modified mismatch fails the task with
  `KetchError.FileChanged`. A server that no longer supports ranges restarts the download from
  byte zero on one connection. Otherwise the remaining bytes are resegmented to the current
  connection count and the local file is checked (file size vs. claimed progress); if the
  check fails, the segments restart from zero. The download continues from the saved offsets.

### Concurrency Model

- Segments of a download run as sibling coroutines in a `coroutineScope`: a failed segment
  cancels the others, their progress is kept, and the engine retries from the saved offsets.
- Writes to a file are serialized on a single-parallelism view of the I/O dispatcher; `Mutex`
  guards progress aggregation and queue and coordinator bookkeeping.
- Structured concurrency ensures cleanup on cancel/pause.
- `DownloadQueue` enforces the concurrent-download limit and the per-host download limit.

## Error Classification

All errors are modeled as sealed class `KetchError`:

| Type | Retryable | Trigger |
|---|---|---|
| `Network` | Yes | Connection/timeout failures |
| `Http(code)` | 5xx and 429 | Non-success HTTP status |
| `Disk` | No | File I/O failures (from `FileAccessor`) |
| `Unsupported` | No | Server lacks required features, or no source handles the URL |
| `FileChanged` | No | ETag/Last-Modified (or FTP MDTM) mismatch on resume |
| `CorruptResumeState` | No | Persisted resume state cannot be parsed |
| `SourceError` | No | Error from a pluggable `DownloadSource` |
| `AuthenticationFailed` | No | Credentials rejected (e.g. FTP 530) |
| `Canceled` | No | User-initiated cancellation |
| `Unknown` | No | Unexpected exceptions |

Retryable errors are retried up to `retryCount` times with exponential backoff from
`retryDelayMs`. An HTTP 429 uses the server's `Retry-After` delay when present and reduces
the task's connections (to `RateLimit-Remaining` when given, otherwise by half).

## Speed Limiting

Two-level token-bucket algorithm:

```
DownloadContext.throttle(bytes)   (called by the source before writing each chunk)
  |
  +-- per-task limiter (DelegatingSpeedLimiter -> TokenBucket, or unlimited)
  +-- global limiter   (DelegatingSpeedLimiter -> TokenBucket shared by all tasks)
```

Each chunk acquires tokens from the task's bucket and then from the global bucket, so a task
runs at no more than the lower of the two limits. The `DelegatingSpeedLimiter` wrappers let a
limit be changed or removed while a download is running.

Set via `DownloadConfig.speedLimit` with `KetchApi.updateConfig()` (global) or
`DownloadTask.setSpeedLimit()` (per-task). Both can be changed at runtime.

## Daemon Server

The server module (`library:server`) exposes any `KetchApi` (usually a `Ketch`) through
`KetchServer`, a Ktor CIO server on port 8642 by default. Endpoint paths are defined once as Ktor
resources in `library:endpoints` (`Api`) and shared with the client:

- **REST API** under `/api`: create, list, get, pause, resume, cancel and remove tasks; set a
  task's speed limit, priority and connections; `status`, `config`, `network-interfaces`,
  `resolve` and `resolve/content` (resolve uploaded file bytes, such as a `.torrent` file)
- **SSE**: `/api/events` (all tasks) and `/api/events/{id}` stream `task_added`,
  `task_removed`, `state_changed` and `progress` events
- **Auth**: Optional bearer token (`KetchServer(apiToken = ...)`, `[server] apiToken` in
  `config.toml`) required on every API route
- **Host check**: Without a token, requests must name this machine in `Host` (loopback, an
  interface IP, the host name or `<host>.local`, or `allowedHosts`), which blocks DNS rebinding
- **Browsers**: Without a token, requests from web pages on other origins are refused
  (`CrossOriginGuard`); with one, `corsAllowedHosts` lists the origins that get CORS
- **Discovery**: Advertised on the LAN over mDNS as `_ketch._tcp` unless disabled
- **Web UI**: Serves the bundled web app when it is packaged with the server (the CLI build does)

`RemoteKetch` (`library:remote`) is the client counterpart -- it implements `KetchApi`
by calling the REST API and subscribing to SSE events. Auto-reconnects with exponential
backoff on disconnection, and stops retrying when the server rejects the API token. The
[browser extension](../app/browser-extension/README.md) is another REST client: it sends
downloads to `POST /api/tasks` and `.torrent` content to `POST /api/resolve/content`.

## Example App: Multi-Instance Design

The example app (`app/shared`) works with Ketch instances, all through `KetchApi`:

1. **Embedded** (default) -- in-process `Ketch` on Android, iOS and desktop. Android and
   desktop can also start a `KetchServer` that exposes it to other devices.
2. **Remote** -- connects to a daemon or another device's app via `RemoteKetch`. The web app
   has no embedded instance and only works with remote ones.

### Key design decisions

- **`KetchApi` is the only abstraction the UI needs.** Instance switching is transparent.
- **Lambda injection over expect/actual.** Each platform entry point wires the engine and passes
  it to `InstanceFactory` as `embeddedFactory`, so `app/shared` commonMain does not depend on
  `library:core`. Android and desktop also pass a `localServerFactory`; commonMain checks
  `isLocalServerSupported` to show the server controls.
- **Instance list model.** Users manage a list of instances (like bookmarks). The embedded
  instance is always present when the platform has one. Remote servers can be added, found on
  the LAN over mDNS, and removed.

### Architecture

```
InstanceManager
  |-- instances: StateFlow<List<InstanceEntry>>   (EmbeddedInstance, RemoteInstance)
  |-- activeInstance: StateFlow<InstanceEntry?>
  |-- activeApi: StateFlow<KetchApi>
  |-- serverState: StateFlow<ServerState>         (Stopped, Running, Failed)
  |
  +-- InstanceFactory(deviceName, embeddedFactory, localServerFactory, applyTorrentSettings)
        |-- createEmbedded(): invokes embeddedFactory (null on web)
        |-- createRemote(RemoteConfig): creates RemoteKetch
        |-- startServer(api) / stopServer(): invokes localServerFactory
```

Platform entry points:

```kotlin
// Android and desktop -- embedded instance plus local server
InstanceManager(
  factory = InstanceFactory(
    deviceName = instanceName,
    embeddedFactory = { Ketch(httpEngine = ..., additionalSources = ...) },
    localServerFactory = { api -> /* start KetchServer(api), return LocalServerHandle */ },
  ),
  initialRemotes = config.remotes,
  configStore = configStore,
)

// iOS -- embedded instance, no local server
InstanceFactory(deviceName = instanceName, embeddedFactory = { Ketch(...) })

// Web (WasmJs) -- remote instances only
InstanceFactory()
```

### Instance switching flow

1. User selects an instance in the selector sheet
2. `InstanceManager.switchTo(instance)` closes the previous `RemoteKetch`, if any (the embedded
   instance stays alive)
3. `activeApi` StateFlow updates and the new instance's `start()` runs
4. UI recomposes with the new task list

### Constraints

- Only one active instance at a time; the local server keeps serving the embedded instance
  whichever instance is active
- Embedded instance cannot be removed
- Removing the active instance switches to the embedded one (or to disconnected on web)
- Remote servers are saved in the app configuration (`remotes` in `config.toml`, browser
  storage on web) and restored on the next launch
- The local server starts on launch when `[server] autoStart` is set

## See Also

- [API Reference](api.md)
- [CLI Documentation](../cli/README.md)
- [BitTorrent downloads](torrent.md)
- [Logging](logging.md)
- [Kermit Logger](../library/kermit/README.md)
