# Architecture

This document explains how Ketch is built: the modules and how they depend on each other, the
abstractions they share, what happens to a download from request to finished file, how tasks are
saved, and how the server and the apps use the library. It is for contributors and for integrators
who want to know what happens behind `KetchApi`. For usage, see the [developer guide](developers.md)
and the [API reference](api.md).

## Modules

Ketch is a set of Kotlin Multiplatform modules. The published SDK modules are the ones you add as
dependencies:

| Module | Description | Platforms |
|---|---|---|
| `library:api` | Public API interfaces and models (`KetchApi`, `DownloadTask`, `DownloadState`, etc.) | All |
| `library:torrent-api` | Torrent control contract (`TorrentController`, its snapshots, file pages and commands), for clients of any backend | All |
| `library:core` | In-process download engine: embed downloads directly in your app | Android, iOS, JVM, JS (Node.js), WasmWasi |
| `library:ktor` | Ktor-based `HttpEngine` (the default engine for `core`) | Android, iOS, JVM |
| `library:sqlite` | SQLite-backed `TaskStore` for resuming after a restart | Android, iOS, JVM |
| `library:ftp` | FTP/FTPS `DownloadSource` | Android, iOS, JVM |
| `library:hls` | Finite HLS `DownloadSource` | Android, iOS, JVM, JS (Node.js), WasmWasi |
| `library:dash` | Finite DASH `DownloadSource` | Android, iOS, JVM, JS (Node.js), WasmWasi |
| `library:torrent` | BitTorrent and magnet `DownloadSource` ([details](torrent.md)) | Android, iOS, JVM |
| `library:kermit` | Optional [Kermit](https://github.com/touchlab/Kermit) logging integration | Android, iOS, JVM, JS, WasmJs |
| `library:remote` | Remote client: control a Ketch server from any platform | Android, iOS, JVM, WasmJs |
| `library:endpoints` | REST endpoint and wire model definitions shared by `server` and `remote` | Android, iOS, JVM, WasmJs |

Unpublished modules build the products:

| Module | Description |
|---|---|
| `library:server` | Daemon server with a REST API and server-sent events (JVM; used by the CLI, desktop and Android apps) |
| `library:mcp` | MCP server exposing a `KetchApi` as tools for AI agents (`ketch mcp`) |
| `config` | TOML configuration shared by the apps and the CLI |
| `ai:discover` | LLM-driven resource discovery |
| `updater`, `hooks` | Self-update from GitHub releases; commands and webhooks run on download events |
| [`cli`](../cli/README.md), `app/*` | The command line and the apps |

Choose a backend: **`core`** runs downloads in-process, **`remote`** controls a server. Both
implement `KetchApi`, so your UI code is the same either way.

### Dependency graph

```text
library:api          (public interfaces and models; no protocol types)
  ^
  +--- library:torrent-api (torrent control contract)
  +--- library:core        (download engine, implements KetchApi)
  |      ^
  |      +--- library:ktor     (Ktor-based HttpEngine)
  |      +--- library:sqlite   (SQLite-backed TaskStore)
  |      +--- library:ftp      (FTP/FTPS DownloadSource)
  |      +--- library:hls      (finite HLS DownloadSource)
  |      +--- library:dash     (finite DASH DownloadSource)
  |      +--- library:torrent  (BitTorrent DownloadSource and controller; ktor, torrent-api)
  |      +--- library:server   (REST API + SSE daemon; endpoints, ktor, sqlite)
  |      +--- updater          (GitHub release updates)
  |
  +--- library:endpoints   (REST resources shared by server and remote; torrent-api)
  +--- library:remote      (HTTP + SSE client, implements KetchApi; endpoints, torrent-api)
  +--- library:kermit      (Kermit logging adapter)
  +--- library:mcp         (MCP server exposing a KetchApi as AI agent tools)
  +--- config              (TOML configuration)
         ^
         +--- ai:discover  (LLM-driven resource discovery)
         +--- hooks        (commands and webhooks on download events)

cli          (core, ktor, sqlite, ftp, hls, dash, torrent, server, mcp, remote, config,
              ai:discover, hooks, updater)
app/shared   (Compose Multiplatform UI: config and remote everywhere; core, ktor, sqlite, ftp,
              hls, dash, torrent and ai:discover where the engine runs)
app/desktop  (JVM entry point: shared, the engine modules, server, ai:discover, hooks, updater)
app/android  (Android entry point: shared, the engine modules, server, ai:discover, updater)
app/web      (WasmJs entry point: shared, remote only)
```

`library:api` and `library:core` name no protocol. Torrent types live in `library:torrent-api` and
`library:torrent`, which plug in through `ControlKey`, `Interest` and `DownloadSource.attach`.

## Core abstractions

### KetchApi

The central service interface, in `library:api`. `Ketch` (in-process) and `RemoteKetch` (HTTP and
SSE client) both implement it, so UI and CLI code works the same with either:

```text
KetchApi
  |-- tasks: StateFlow<List<DownloadTask>>
  |-- start() / close()
  |-- download(request): DownloadTask
  |-- resolve(input: ResolveInput): ResolvedSource   (a URL, or file content the caller holds)
  |-- status(): KetchStatus                          (config, system, storage, features)
  |-- updateConfig(config)
  |-- networkInterfaces() / selectNetworkInterfaces(config)
  |-- subscribe(interest, interval): Flow<T>         (segments, stats, connections, ...)
  |-- controller(key): T?                            (optional typed controllers)
```

Two extension points keep the API protocol-neutral:

- **`Interest<T>`** names live data to follow. Interests are serializable, so a remote instance
  sends its server exactly the ones its callers hold, and the server encodes values with the
  interest's `valueSerializer`. Modules add their own, such as `TorrentInterest`.
- **`ControlKey<T>`** looks up an optional controller, such as `TorrentController`, defined by the
  module that owns it.

### Pluggable components

Everything that varies by platform or use is an interface passed to `Ketch`:

| Interface | Purpose | Implementations |
|---|---|---|
| `HttpEngine` | HTTP `HEAD`/`GET` with ranges, proxies | `KtorHttpEngine` (`library:ktor`), `MultiNetworkHttpEngine` (one engine per network) |
| `TaskStore` | Save tasks so they survive a restart | `InMemoryTaskStore`, `SqliteTaskStore` |
| `Logger` | Diagnostic logging | `Logger.None`, `Logger.console()`, `Logger.combine()`, `KermitLogger` |
| `DownloadSource<S>` | One download protocol | `HttpDownloadSource` (built in), `FtpDownloadSource`, `HlsDownloadSource`, `DashDownloadSource`, `TorrentDownloadSource` |
| `FileNameResolver` | Names a file when the source suggests none | `DefaultFileNameResolver` |

### Download sources

A `DownloadSource<S>` handles one protocol. `SourceResolver` routes each URL, or file content, to
the first source whose `canHandle` accepts it: the `additionalSources` in order, then the built-in
HTTP source as the fallback. A source:

- probes a link with `resolve(input, config)`;
- transfers it with `download(context, state)`, starting over when `state` is `null`;
- returns what a later run needs to continue from `store(context)`, a value of its own
  serializable type `S` (`HttpSourceState`, `FtpSourceState`, `TorrentSourceState`, or `Unit` for
  HLS and DASH);
- releases what outlived the download, and optionally deletes its files, in
  `cleanup(context, state, deleteFiles)`.

`DownloadContext` carries everything a run needs: the request, the `FileAccessor`, the segments,
progress and throttle callbacks, the config snapshot, the pre-resolved source, the live selection,
the connection reporter and the transfer watchdog.

`DownloadSource.attach(host)` may return a `SourceExtension`, which adds controllers for
`KetchApi.controller`, answers its own interests for `KetchApi.subscribe`, and hooks into instance
start, task completion and selection changes. The torrent source uses it for seeding and its
controller. See [writing a download source](developers.md#write-your-own-download-source).

### Platform code

Platform-specific code is limited to a few expect/actual declarations:

| Declaration | Android | JVM / iOS | JS / WasmWasi |
|---|---|---|---|
| `createFileAccessor()` | `PathFileAccessor`; `ContentUriFileAccessor` for `content://` URIs | `PathFileAccessor` (okio `FileHandle`) | `PathFileAccessor` |
| `platformFileSystem` | `FileSystem.SYSTEM` | `FileSystem.SYSTEM` | `NodeJsFileSystem` / `WasiFileSystem` |
| `Logger.console()` | Logcat | Timestamped `println` (errors on stderr on the JVM) | Timestamped `println` |
| `KetchDispatchers.Default` | Idle-exiting thread pools | Idle-exiting pools (JVM) / fixed pools (iOS) | `Dispatchers.Default` |

## Threads

Ketch keeps its work off the application's dispatchers, so a busy download never starves the app's
own `Dispatchers.IO` or `Dispatchers.Default`.

### Engine dispatchers

`KetchDispatchers` separates three kinds of work:

| Dispatcher | Runs |
|---|---|
| `main` | Task coordination: scheduling, the queue, state changes |
| `network` | Network transfers |
| `disk` | Blocking file I/O: writes, reads, preallocation |

Every `Ketch` uses `KetchDispatchers.Default` unless given others. It is created once per process
and shared by every instance:

- On Android and the JVM it runs on Ketch's own daemon threads: at most 8 `ketch-network-N` and 8
  `ketch-disk-N` threads, started only when every thread is busy and ended once idle for 30
  seconds, so an idle process holds none.
- iOS keeps two pools of 8 threads. JS and WasmWasi use `Dispatchers.Default`.
- `main` is `network` itself. Each `Ketch` coordinates its tasks one at a time on a serial view of
  its own, so instances never wait for each other.

Pass your own with `Ketch(dispatchers = KetchDispatchers(main, network, disk))`. `Ketch.close()`
closes them once its coroutines have finished, never under them, and never closes
`KetchDispatchers.Default`.

### HTTP and torrent threads

- **HTTP.** The clients `KtorHttpEngine` builds run on their own `ketch-http-N` daemon threads,
  started on demand and ended after 30 seconds idle: up to 64 for OkHttp, which reads each response
  body there while it streams, and up to 8 per CIO client.
- **Torrents.** The torrent engine, peers and hashing run on the `network` dispatcher and file I/O
  on `disk`. On Android and the JVM, peer sockets share one `ketch-torrent-selector` thread that
  waits for readiness.
- **Apps.** The library does not change `Dispatchers.IO`. The Ketch apps and CLI cap it at 16
  threads themselves.

## Download pipeline

A download goes through these stages:

```text
DownloadRequest
  |
  v
[1. Schedule]  Only for a non-Immediate schedule or conditions: DownloadScheduler waits for
  |            AtTime/AfterDelay and every DownloadCondition (Scheduled state), then hands the
  |            task to the queue.
  v
[2. Queue]     DownloadQueue orders tasks by priority, then creation time, and starts them while
  |            maxConcurrentDownloads and the per-host limit allow; the rest stay Queued. URGENT
  |            can preempt a lower-priority running download, which becomes Paused(Preempted),
  |            keeps its place and resumes on its own. Each waiting task reports a queuePosition.
  v
[3. Resolve]   The source comes from, in order: the resolution the task saved
  |            (TaskRecord.resolvedSource), DownloadRequest.resolvedSource, or SourceResolver
  |            followed by DownloadSource.resolve (for HTTP, a HEAD request: size, ranges, ETag,
  |            Last-Modified, Content-Type, published digest).
  v
[4. Name]      A request that names no folder saves in the default directory, or in the folder
  |            there of the first DownloadConfig.categories entry its name, Content-Type and host
  |            match. Names from a server, a link or a FileNameResolver pass through
  |            sanitizeFileName and must stay inside the folder; a file Destination is used as it
  |            is. OutputPathReservations holds every running download's path, so two downloads
  |            never share a file, and the name is deduplicated as `name (n).ext`.
  v
[5. Plan]      For HTTP and FTP, SegmentCalculator splits the file into one segment per effective
  |            connection; one segment without range support. Content of unknown size gets no
  |            segments: it streams to the end on one connection, and every attempt starts over.
  v
[6. Download]  DownloadCoordinator starts a DownloadExecution, which builds a DownloadContext and
  |            calls DownloadSource.download. HttpDownloadSource runs one SegmentDownloader per
  |            segment concurrently; each writes its range at the right offset of the partial
  |            file `<name>.ketch` through the FileAccessor. Every chunk passes the per-task and
  |            global token buckets. A connection-count change resegments the remaining bytes
  |            without losing progress.
  v
[7. Persist]   Every saveIntervalMs, and as the run starts and stops, the segments and the
  |            source's state (DownloadSource.store) are saved to the TaskStore.
  v
[8. Complete]  The partial file is flushed, its checksum verified if there is one, then closed and
               moved to its final name (an existing file there is kept and the download takes
               `name (n).ext`; a file Destination is replaced). The task becomes Completed with
               its path, size and download time (summed over runs, excluding queued and paused
               time). A retryable error retries with backoff from the saved progress; anything
               else, or too many retries, ends in Failed.
```

Sources that write their own files (`managesOwnFileIo`, such as torrents) skip the partial file,
the `FileAccessor` and checksum verification.

## Persistence

A `TaskStore` saves one `TaskRecord` per task. `SqliteTaskStore` keeps them in `ketch.db`; the
default `InMemoryTaskStore` forgets them on exit. The fields that matter for resuming are:

| Field | Holds |
|---|---|
| `request` | The `DownloadRequest`, including later changes such as priority or speed limit |
| `state`, `error` | The `TaskState` to restore into, and the last error |
| `outputPath`, `partialPath` | The final path, and the `<name>.ketch` partial file written until completion |
| `totalBytes`, `segments` | The size and Ketch's byte ranges with their progress |
| `sourceType`, `sourceState` | Which source owns the task, and its `S` state as JSON (`SourceState`) |
| `resolvedSource` | A resolution that describes all the content (`ResolvedSource.METAINFO_KEY`, such as a torrent's metainfo), which cannot go stale |
| `downloadTime`, `completedAt` | Time spent downloading, and when it last finished |
| `control` | `TaskControl`: the file selection generation, sharing intent and the command ledger |

- Only Ketch reads and writes `sourceState`, through `encodeState` and `decodeState`, which ignore
  unknown fields and refuse another source's state. A state that cannot be decoded fails a resume
  with `KetchError.CorruptResumeState` and reaches `cleanup` as `null`.
- A task with progress but no source state restarts from zero: its saved segments are dropped.
- `resolvedSource` is written only by Ketch. Fresh starts and resumes get it as
  `DownloadContext.preResolved`, and task views carry the request without its bulky metadata.
- `SqliteTaskStore` skips, and logs, rows it cannot decode, and moves a corrupt database aside
  instead of failing to start.

## Pause and resume

- **Pause** cancels the execution. The source stops its transfers and the execution saves their
  final progress and state. A queued task can be paused too; it leaves the queue. `Paused.reason`
  says why: `User`, `Preempted` for an `URGENT` task's victim (still queued; resuming changes
  nothing, pausing makes it a `User` pause), `Shutdown` or `AwaitingFileSelection`.
- **Close.** `Ketch.close()` pauses every downloading task for `Shutdown` instead of canceling it.
  Its partial file and `DOWNLOADING` record are kept, so the next `start()` resumes it.
- **Resume** calls the source with its saved state. For HTTP:
  1. A download of unknown size restarts from byte zero without a probe.
  2. Otherwise the server is probed again. An ETag or Last-Modified mismatch fails the task with
     `KetchError.FileChanged`.
  3. A server that no longer supports ranges restarts the download from byte zero on one
     connection.
  4. Otherwise the remaining bytes are resegmented to the current connection count and the local
     file is checked (size against claimed progress); if the check fails, the segments restart
     from zero. The download continues from the saved offsets.

  The local file is the partial file. A task saved before partial files existed has none and keeps
  writing to its final name.

## Concurrency model

- Segments of a download run as sibling coroutines in one `coroutineScope`. A failed segment
  cancels the others, their progress is kept, and the engine retries from the saved offsets.
- Each `FileAccessor` serializes its I/O on a single-parallelism view of the `disk` dispatcher.
  `Mutex`es guard progress aggregation and the queue and coordinator bookkeeping.
- Structured concurrency cleans up on cancel and pause.
- `DownloadQueue` enforces the concurrent-download and per-host limits. Its mutex also guards the
  queue positions: every queue change republishes them before releasing the lock, so two changes
  never interleave. Each task's position is a separate flow, though, so a reader that does not
  hold the lock (a task list snapshot, a UI collector, a stream of SSE frames) can briefly see
  positions from two queue states, such as two tasks at the same position. `RemoteKetch` prefers
  the event stream's positions over a command's response when an event arrived while the command
  was in flight.

## Errors and retries

Every failure is a `KetchError`:

| Type | Retryable | Trigger |
|---|---|---|
| `Network` | Yes | Connection or timeout failures, including stalled transfers |
| `Http(code)` | 5xx and 429 | Non-success HTTP status |
| `Disk` | No | File I/O failures (from the `FileAccessor`) |
| `Unsupported` | No | Server lacks required features, or no source handles the URL |
| `FileChanged` | No | ETag/Last-Modified (or FTP MDTM) mismatch on resume |
| `CorruptResumeState` | No | Saved resume state cannot be decoded |
| `SourceError` | No | Error from a `DownloadSource` |
| `AuthenticationFailed` | No | Credentials rejected (e.g. FTP 530) |
| `ChecksumMismatch` | No | The finished file's digest differs from the expected or published one |
| `Canceled` | No | User-initiated cancellation |
| `Unknown` | No | Unexpected exceptions |

### Retries

Retryable errors are retried up to `retryCount` times in a row, or until the task is paused with
`unlimitedRetries`. An attempt that gets further than every attempt before it starts the count
over. Retry `n` waits `retryDelayMs` doubled `n - 1` times, capped at `maxRetryDelayMs`, then a
random time between half of that and all of it. An HTTP 429 uses the server's `Retry-After` delay
when present and reduces the task's connections (to `RateLimit-Remaining` when given, otherwise by
half).

### Stalled transfers

Transfers never time out on their own: request and socket timeouts are infinite, so a large body
can take as long as it needs. Instead, `TransferWatchdog` watches each HTTP segment request,
unknown-size stream, HLS/DASH part and FTP segment connection:

- A transfer that receives no data for `idleTimeoutMs` (60 s by default), or averages less than
  `minSpeed` bytes per second over 30 s (off by default), is canceled.
- It fails with a retryable `KetchError.Network` whose cause is a `TransferStalledException`, so
  the engine retries it from the saved progress.
- Time spent waiting for a speed limit counts as neither idle nor slow.
- Opening a connection is limited to 30 s (`KtorHttpEngine`'s own clients on the JVM and Android,
  and FTP).

## Speed limiting

A two-level token-bucket algorithm:

```text
DownloadContext.throttle(bytes)   (called by the source before writing each chunk)
  |
  +-- per-task limiter (DelegatingSpeedLimiter -> TokenBucket, or unlimited)
  +-- global limiter   (DelegatingSpeedLimiter -> TokenBucket shared by all tasks)
```

Each chunk takes tokens from the task's bucket and then from the global bucket, so a task runs at
no more than the lower of the two limits. The `DelegatingSpeedLimiter` wrappers let a limit change
or disappear while a download runs. Set them with `DownloadConfig.speedLimit` through
`KetchApi.updateConfig()` (global) or `DownloadTask.setSpeedLimit()` (per task).

## Daemon server

`library:server` exposes any `KetchApi` (usually a `Ketch`) through `KetchServer`, a Ktor CIO
server on port 8642 by default. Endpoint paths are defined once as Ktor resources in
`library:endpoints` (`Api`) and shared with the client. The [REST API](developers.md#rest-api)
lists the routes.

### What it serves

- **REST API** under `/api`: create, list, get, pause, resume, cancel and remove tasks; set a
  task's speed limit, priority, connections and files; `status`, `config`, `network-interfaces`,
  `resolve` and `resolve/content` (resolve uploaded file bytes, such as a `.torrent` file).
- **Event stream**: `GET /api/stream` (servers listing `events.stream`) is one SSE stream per
  client: `hello`, `subscribed`, a `tasks` snapshot, then each task's lifecycle and state changes
  at once, plus what `PUT /api/stream/{id}/subscription` asks for: progress at one interval, the
  segments of the tasks it names, and per interest (under an ID the client gives it) `interest`
  values at its interval, then `interest.end` or `interest.error`. One frame per task and one
  upstream per interest are encoded once for every stream, and a slow stream skips values rather
  than queueing them. See the [plan](plans/event-stream.md).
- **Torrents**: on servers listing `torrent.control`, `/api/torrents` serves the instance's
  `controller(TorrentController)`: capabilities, a task's snapshot, sorted pages of its files, and
  the `selection` and `seeding` commands, guarded by a revision and an idempotency key. A server
  whose instance has no torrent controller installs none of these routes.
- **Live connections**: `GET /api/connections?limit=1..1024` answers one `Interest.Connections`
  snapshot. Hosts and peer addresses never go on the task events.
- **Deprecated streams** for older clients: `/api/events` and `/api/events/{id}` (`task_added`,
  `task_removed`, `state_changed`, `progress`), `/api/connections/events` (at most 16 streams) and
  `/api/torrents/{id}/events`. Task views and events never carry a torrent's metainfo.
- **Health**: `GET /api/health`, without the token, answers `200` once the tasks are restored and
  `503` before. `ketch server` listens first, so a daemon that cannot bind never resumes downloads,
  and calls `markReady()` once `KetchApi.start()` has restored them (`KetchServer(ready = false)`);
  other embedders are ready from the start. Until then the other routes but pairing answer
  `503 starting` (`startupGate`), so no download races the restore.
- **Web UI**: the bundled web app, when it is packaged with the server (the CLI build does).

### Security

- **Auth**: a bearer token (`KetchServer(apiToken = ...)`, `[server] apiToken` in `config.toml`)
  is required on every route except pairing and health, compared in constant time. Ten wrong
  tokens from one address within a minute get `429` until the minute ends.
- **Binding**: `KetchServer` binds to loopback by default. `ketch server` listening beyond loopback
  always has a token (`--token`, `KETCH_API_TOKEN`, `apiToken`, or one it creates in the
  owner-only `api-token` file) unless started with `--no-token`.
- **Folders**: without a token, and with one when `allowedDirectories` is set, callers are kept to
  the download directory and `allowedDirectories` (`DestinationGuard` over core's
  `DestinationPathPolicy`) for new tasks' destinations, resume destinations, the
  `defaultDirectory` of `PUT /api/config` and `deleteFiles=true`. Anything else is
  `403 path_rejected`. With a token and no list they may choose any folder but
  `protectedDirectories` (system folders, the user's application data, the app's own data) and
  hidden entries, outside the download directory; a new download never replaces a file.
- **Request bodies**: JSON only (`415` otherwise), at most 1 MiB, or 32 MiB for a new task and
  16 MiB for uploaded content (`413` beyond).
- **Host check**: without a token, requests must name this machine in `Host` (loopback, an
  interface IP, the host name, `<host>.local`, or `allowedHosts`), which blocks DNS rebinding.
- **Browsers**: without a token, requests from web pages on other origins are refused
  (`CrossOriginGuard`); with one, `corsAllowedHosts` lists the origins that get CORS.
- **Pairing**: with a token and a `PairingApprover`, a device can ask for the token
  (`POST /api/pairing`, then poll `GET /api/pairing/{id}`). The approver decides within two
  minutes; one request per address and four in all may wait. An address whose request was
  denied or left unanswered waits 30 s before asking again, doubling each time, and five such
  requests within 10 minutes pause pairing for 10 minutes (`PairingBackoff`). Web pages are
  refused. The apps' servers pass an approver that asks their owner; `ketch server` takes no
  requests.
- **Discovery**: advertised on the LAN over mDNS as `_ketch._tcp` unless disabled, with TXT
  `token=none|required`, and `pairing=1` when it takes pairing requests.

### Clients

`RemoteKetch` (`library:remote`) is the client counterpart. It implements `KetchApi` by calling
the REST API and reading the event stream (`/api/events` on older servers), to which it sends the
interests its callers hold. It reconnects with exponential backoff and stops when the server
rejects the token. `RemotePairing` asks a server's owner for the token and waits for the answer.

The [browser extension](../app/browser-extension/README.md) is another REST client: it sends
downloads to `POST /api/tasks` and `.torrent` content to `POST /api/resolve/content`.

## Apps: devices

The apps (`app/shared`) show the downloads of Ketch instances, which the UI calls **devices**, all
through `KetchApi`:

1. **Embedded**: an in-process `Ketch` on Android, iOS and desktop. Android and desktop can also
   share it with other devices through a `KetchServer` (Settings → Sharing).
2. **Remote**: another device's app or a `ketch server` daemon, reached with `RemoteKetch`. The
   web app has no embedded instance and only works with remote ones.

### Key design decisions

- **`KetchApi` is the only abstraction the UI needs.** Switching devices is transparent.
- **Lambda injection over expect/actual.** Each platform entry point builds the engine and passes
  it to `InstanceFactory` as `embeddedFactory`, so `app/shared` common code does not depend on
  `library:core`. Android and desktop also pass a `localServerFactory`; common code checks
  `isLocalServerSupported` to show the sharing controls.
- **Devices are places, not settings.** Every device is listed in the sidebar with its live
  presence, and watched devices stay connected, so switching to one is instant. Users add devices
  with a pairing link or QR code, an address, or from those found on the LAN over mDNS. A found
  device that needs a token but takes pairing requests asks its owner instead: both devices show
  the same four digits, and the shared device's app asks in a dialog (`PairingRequests`,
  `PairingApprovalHost`), or from a notification while it is not in front.

### Structure

```text
InstanceManager
  |-- instances: StateFlow<List<InstanceEntry>>   (EmbeddedInstance, RemoteInstance)
  |-- activeInstance: StateFlow<InstanceEntry?>   (the device new downloads go to)
  |-- activeApi: StateFlow<KetchApi>
  |-- deviceScope: StateFlow<DeviceScope>         (Single(deviceId) or All)
  |-- presence: StateFlow<List<DevicePresence>>   (what each device is doing)
  |-- serverState: StateFlow<ServerState>         (Stopped, Running, Failed)
  |
  +-- KeepAlivePolicy(maxWatched, backgroundGrace)
  +-- InstanceFactory(deviceName, embeddedFactory, localServerFactory, applyTorrentSettings, ...)
        |-- createEmbedded(): invokes embeddedFactory (none on web)
        |-- createRemote(RemoteConfig): creates a RemoteKetch
        |-- startServer(api) / stopServer(): invokes localServerFactory
```

Platform entry points:

```kotlin
// Android and desktop: embedded instance plus local server
InstanceManager(
  factory = InstanceFactory(
    deviceName = instanceName,
    embeddedFactory = { Ketch(httpEngine = ..., additionalSources = ...) },
    localServerFactory = { api, pairingRequests ->
      // start KetchServer(api, ...) and return a LocalServerHandle
    },
  ),
  initialRemotes = config.remotes,
  configStore = configStore,
)

// iOS: embedded instance, no local server
InstanceFactory(deviceName = instanceName, embeddedFactory = { Ketch(...) })

// Web (WasmJs): remote instances only
InstanceFactory()
```

### Connections

- The active device is always connected. Remote devices marked as watched (`RemoteConfig.watch`)
  stay connected too, the first `KeepAlivePolicy.maxWatched` of them (5 by default), while the app
  is in front; `backgroundGrace` (10 minutes by default) after it leaves, only the active one stays.
- A device that leaves that set disconnects and gets a fresh, unstarted client, which connects
  again when it is needed.
- A device without a name takes the one it announces in `status()` once connected, and every
  device keeps the system it reports there (`RemoteConfig.os`), which picks its glyph while it is
  offline.

### Switching devices

1. The user picks a device in the sidebar, the rail or the device switcher, or with a shortcut.
2. `InstanceManager.switchTo(device)` makes it active and shows it alone; the device shown before
   stays connected while it is watched.
3. `activeApi` updates, and a device that was not connected starts.
4. The UI recomposes with the new task list.

`showAllDevices()` switches `deviceScope` to `All`, from two devices on: the downloads list shows
every device's tasks with a Device column, while new downloads still go to the active device.

### Constraints

- New downloads go to one active device at a time, unless the user picks another one for them.
  The local server keeps serving the embedded instance whichever device is active.
- The embedded instance cannot be removed.
- Removing the active device switches to the embedded one (or to disconnected on the web).
- Remote devices are saved in the app configuration (`remotes` in `config.toml`, browser storage
  on the web) and restored on the next launch, with the device that was active.
- Sharing starts on launch when `[server] autoStart` is set.

## See also

- [Developer guide](developers.md)
- [API reference](api.md)
- [CLI documentation](../cli/README.md)
- [BitTorrent downloads](torrent.md)
- [Logging](logging.md)
- [Kermit logger](../library/kermit/README.md)
