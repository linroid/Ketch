# Ketch Architecture

This document describes the internal architecture of Ketch, covering module boundaries,
core abstractions, the download pipeline, and the example app's multi-backend design.

## Module Dependency Graph

```
library:api          (public interfaces and models, no dependencies)
  ^
  |
library:core         (download engine, implements KetchApi)
  ^       ^
  |       |
  |    library:ktor  (Ktor-based HttpEngine)
  |
  +--- library:sqlite   (SQLite-backed TaskStore)
  +--- library:kermit   (Kermit logging adapter)
  +--- library:remote   (HTTP+SSE client, implements KetchApi)
  +--- library:server   (Ktor REST API + SSE daemon)

cli                  (JVM CLI, depends on core + server + ktor + sqlite)
app/shared           (Compose Multiplatform UI, depends on core + remote + ktor)
app/desktop          (JVM entry point, depends on shared + server)
app/android          (Android entry point, depends on shared)
app/web              (WasmJs entry point, depends on shared)
```

## Modules

Ketch is split into published SDK modules that you add as dependencies:

| Module | Description | Platforms |
|---|---|---|
| `library:api` | Public API interfaces and models (`KetchApi`, `DownloadTask`, `DownloadState`, etc.) | All |
| `library:core` | In-process download engine -- embed downloads directly in your app | All |
| `library:ktor` | Ktor-based `HttpEngine` implementation (required by `core`) | All |
| `library:sqlite` | SQLite-backed `TaskStore` for persistent resume | Android, iOS, Desktop |
| `library:kermit` | Optional [Kermit](https://github.com/touchlab/Kermit) logging integration | All |
| `library:remote` | Remote client -- control a Ketch daemon server from any platform | All |
| `server` | Daemon server with REST API and SSE events (not an SDK; standalone service) | Desktop |
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
  |-- download(request): DownloadTask
  |-- setGlobalSpeedLimit(limit)
  |-- resolveUrl(url): ResolvedUrl
  |-- close()
```

### Pluggable Components

Ketch uses interface-based dependency injection for all platform-varying or
user-swappable components:

| Interface | Purpose | Implementations |
|---|---|---|
| `HttpEngine` | HTTP HEAD/GET with range support | `KtorHttpEngine` (library:ktor) |
| `TaskStore` | Persist task metadata for resume | `InMemoryTaskStore`, `SqliteTaskStore` |
| `Logger` | Diagnostic logging | `Logger.None`, `Logger.console()`, `KermitLogger` |
| `DownloadSource` | Protocol-level download handling | `HttpDownloadSource` (built-in) |

### Expect/Actual

Platform-specific code is minimized to a few expect/actual declarations:

| Declaratiozn       | Android/JVM | iOS | WasmJs |
|--------------------|---|---|---|
| `FileAccessor`     | `RandomAccessFile` | `NSFileHandle` | Stub (throws) |
| `Logger.console()` | Logcat | NSLog | println |

All platforms use `Dispatchers.IO` for file operations.

## Download Pipeline

A download progresses through these stages:

```
DownloadRequest
  |
  v
[1. Queue]  DownloadQueue checks concurrency limits and priority.
  |         If slots are full, task enters Queued state.
  |         URGENT priority can preempt lower-priority active downloads.
  |
  v
[2. Schedule]  DownloadScheduler handles delayed/conditional starts.
  |            Immediate (default), AtTime, AfterDelay, or DownloadCondition.
  |
  v
[3. Resolve]  SourceResolver finds the right DownloadSource for the URL.
  |           HttpDownloadSource sends HEAD to get size, range support,
  |           ETag, Last-Modified (ServerInfo).
  |
  v
[4. Plan]  SegmentCalculator splits the file into N segments based on
  |        total size and maxConnections. Single segment if no range support.
  |
  v
[5. Download]  DownloadCoordinator launches SegmentDownloaders in
  |            supervisorScope. Each segment downloads its byte range
  |            and writes to the correct file offset via FileAccessor.
  |            SpeedLimiter (token bucket) throttles bandwidth.
  |
  v
[6. Persist]  Segment progress is saved to TaskStore at regular intervals
  |           (segmentSaveIntervalMs) for crash recovery.
  |
  v
[7. Complete]  All segments finished -> Completed state with file path.
               On failure -> retry with exponential backoff, or Failed state.
```

### Pause / Resume

- **Pause**: Cancels active segment coroutines, saves progress to TaskStore.
- **Resume**: Validates server identity (ETag/Last-Modified match) and local file
  integrity (file size vs. claimed progress). If valid, resumes from saved offsets.
  If server changed, restarts from scratch.

### Concurrency Model

- `supervisorScope` for segment downloads -- one segment failure doesn't cancel others.
- `Mutex` protects shared state (file writes, progress aggregation).
- Structured concurrency ensures cleanup on cancel/pause.
- `DownloadQueue` enforces global and per-host connection limits.

## Error Classification

All errors are modeled as sealed class `KetchError`:

| Type | Retryable | Trigger |
|---|---|---|
| `Network` | Yes | Connection/timeout failures |
| `Http(code)` | 5xx only | Non-success HTTP status |
| `Disk` | No | File I/O failures (from `FileAccessor`) |
| `Unsupported` | No | Server lacks required features |
| `ValidationFailed` | No | ETag/Last-Modified mismatch on resume |
| `SourceError` | No | Error from a pluggable `DownloadSource` |
| `Canceled` | No | User-initiated cancellation |
| `Unknown` | No | Unexpected exceptions |

Retryable errors trigger exponential backoff up to `retryCount` attempts.

## Speed Limiting

Two-level token-bucket algorithm:

```
Global SpeedLimiter (shared across all tasks)
  |
  +-- Per-task DelegatingSpeedLimiter
        |-- own bucket (per-task limit)
        |-- delegates to global bucket
        |-- tokens consumed = min(task budget, global budget)
```

Set via `KetchApi.setGlobalSpeedLimit()` (global) or `DownloadTask.setSpeedLimit()`
(per-task). Both can be changed at runtime.

## Daemon Server

The server module (`library:server`) wraps a `Ketch` instance with a Ktor HTTP server:

- **REST API**: Create, list, pause, resume, cancel downloads
- **SSE**: Real-time event stream for state changes and progress updates
- **Auth**: Optional bearer token via `KetchServerConfig.apiToken`
- **CORS**: Configurable allowed origins for browser clients

`RemoteKetch` (`library:remote`) is the client counterpart -- it implements `KetchApi`
by calling the REST API and subscribing to SSE events. Auto-reconnects with exponential
backoff on disconnection.

## Example App: Multi-Backend Design

The example app (`app/shared`) supports three backend modes, all through `KetchApi`:

1. **Embedded** (default) -- in-process `Ketch`, works on all platforms
2. **Remote** -- connects to an existing daemon via `RemoteKetch`
3. **Local server** -- starts `KetchServer` in-process (JVM/Desktop only)

### Key design decisions

- **`KetchApi` is the only abstraction the UI needs.** Backend switching is transparent.
- **Lambda injection over expect/actual.** Local server support is JVM-only. Instead of
  expect/actual across 4 platforms, the JVM entry point injects a `localServerFactory`
  lambda. CommonMain checks `lambda != null` to gate UI visibility.
- **Backend list model.** Users manage a list of configured backends (like bookmarks).
  The embedded backend is always present. Remote servers can be added/removed.

### Architecture

```
BackendManager
  |-- backends: StateFlow<List<BackendEntry>>
  |-- activeBackend: StateFlow<BackendEntry>
  |-- activeApi: StateFlow<KetchApi>
  |
  +-- BackendFactory(localServerFactory: ((LocalServer) -> LocalServerHandle)?)
        |-- create(BackendConfig) -> KetchApi
        |-- Embedded: creates Ketch directly
        |-- Remote: creates RemoteKetch
        |-- LocalServer: invokes lambda (JVM only)
```

Platform entry points:

```kotlin
// Desktop (JVM) -- provides local server support
BackendManager(BackendFactory(localServerFactory = { config ->
  // start KetchServer, return LocalServerHandle
}))

// iOS, Android, WasmJs -- no local server
BackendManager(BackendFactory())
```

### Backend switching flow

1. User selects a backend in the selector sheet
2. `BackendManager.switchTo(id)` closes the old `KetchApi`
3. `BackendFactory.create()` builds the new one
4. `activeApi` StateFlow updates, UI recomposes with new task list

### Constraints

- Only one active backend at a time
- Embedded backend cannot be removed
- Removing the active backend auto-switches to embedded
- Backend list is not persisted (resets on app restart)

## See Also

- [CLI Documentation](../cli/README.md)
- [Logging](logging.md)
- [Kermit Logger](../library/kermit/README.md)
