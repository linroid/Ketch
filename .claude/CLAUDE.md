You are a senior Kotlin Multiplatform library engineer working on "KDown", an open-source Kotlin
Multiplatform download manager library.

## Project Status

The library is **substantially complete** with all core features implemented and working across
Android, JVM/Desktop, iOS, and WebAssembly platforms.

**Note:** The library has not been published yet, so public API breaking changes are allowed.

## Module Structure

```
library/
  api/        # Public API interfaces and models -- published SDK module
  core/       # In-process download engine -- published SDK module
  ktor/       # Ktor-based HttpEngine implementation -- published SDK module
  kermit/     # Optional Kermit logging integration -- published SDK module
  sqlite/     # SQLite-backed TaskStore (Android, iOS, JVM only) -- published SDK module
  remote/     # Remote KDownApi client (HTTP + SSE) -- published SDK module
server/       # Ktor-based daemon server with REST API and SSE events
app/
  shared/     # Shared Compose Multiplatform UI (supports Core + Remote backends)
  android/    # Android app
  desktop/    # Desktop (JVM) app
  web/        # Wasm browser app
  ios/        # Native iOS app (Xcode project, consumes shared module)
cli/          # JVM CLI entry point
```

## Package Structure

### `library:api` (public API)
- `com.linroid.kdown.api` -- `KDownApi`, `DownloadTask`, `DownloadRequest`, `DownloadState`,
  `DownloadProgress`, `Segment`, `KDownError`, `SpeedLimit`, `DownloadPriority`,
  `DownloadSchedule`, `DownloadCondition`, `KDownVersion`

### `library:core` (implementation)
- `com.linroid.kdown.core` -- `KDown` (implements `KDownApi`), `DownloadConfig`, `QueueConfig`
- `com.linroid.kdown.core.engine` -- `HttpEngine`, `DownloadCoordinator`, `RangeSupportDetector`,
  `ServerInfo`, `DownloadSource`, `HttpDownloadSource`, `SourceResolver`, `SourceInfo`,
  `SourceResumeState`, `DownloadContext`, `DownloadScheduler`, `ScheduleManager`,
  `SpeedLimiter`, `TokenBucket`, `DelegatingSpeedLimiter`
- `com.linroid.kdown.core.segment` -- `SegmentCalculator`, `SegmentDownloader`
- `com.linroid.kdown.core.file` -- `FileAccessor` (expect/actual), `FileNameResolver`,
  `DefaultFileNameResolver`, `PathSerializer`
- `com.linroid.kdown.core.log` -- `Logger`, `KDownLogger`
- `com.linroid.kdown.core.task` -- `DownloadTaskImpl`, `TaskStore`, `InMemoryTaskStore`,
  `TaskRecord`, `TaskState`

### `library:remote`
- `com.linroid.kdown.remote` -- `RemoteKDown` (implements `KDownApi`), `RemoteDownloadTask`,
  `ConnectionState`, `WireModels`, `WireMapper`

## Implemented Features

### Core Download Engine
- Multi-platform: Android (minSdk 26), JVM 11+, iOS (iosArm64, iosSimulatorArm64), WasmJs
- Segmented downloads with concurrent HTTP Range requests
- Pause / Resume with server identity validation (ETag, Last-Modified)
- File integrity check on resume (validates local file size vs. claimed progress)
- Retry with exponential backoff for transient errors
- Persistent task metadata via `TaskStore` interface
- Duplicate download guards in `start()`, `startFromRecord()`, `resume()`

### Queue Management (`DownloadScheduler`)
- Configurable concurrent download slots (`QueueConfig.maxConcurrentDownloads`)
- Per-host connection limits (`QueueConfig.maxConnectionsPerHost`)
- Priority-based ordering (`DownloadPriority`: LOW, NORMAL, HIGH, URGENT)
- URGENT preemption: pauses lowest-priority active download to make room

### Speed Limiting
- Global speed limit via `DownloadConfig.speedLimit` or `KDownApi.setGlobalSpeedLimit()`
- Per-task speed limit via `DownloadRequest.speedLimit` or `DownloadTask.setSpeedLimit()`
- Token-bucket algorithm (`TokenBucket`) with delegating wrapper

### Download Scheduling (`ScheduleManager`)
- `DownloadSchedule.Immediate`, `AtTime(Instant)`, `AfterDelay(Duration)`
- `DownloadCondition` interface for user-defined conditions (e.g., WiFi-only)
- Reschedule support via `DownloadTask.reschedule()`

### Pluggable Download Sources (`DownloadSource`)
- `SourceResolver` routes URLs to the appropriate source
- `HttpDownloadSource` is the built-in HTTP/HTTPS implementation
- Additional sources registered via `KDown(additionalSources = listOf(...))`
- Each source defines: `canHandle()`, `resolve()`, `download()`, `resume()`

### Daemon Server (`server/`)
- Ktor-based REST API: create, list, pause, resume, cancel downloads
- SSE event stream for real-time state updates
- Remote backend (`RemoteKDown`) communicates via HTTP + SSE
- Auto-reconnection with exponential backoff

### Logging System
- `Logger.None` (default, zero overhead), `Logger.console()`, `KermitLogger`
- Platform-specific console: Logcat (Android), NSLog (iOS), println/stderr (JVM), println (Wasm)
- Lazy lambda evaluation for zero cost when disabled

### Error Handling (sealed `KDownError`)
- `Network` (retryable), `Http(code)` (5xx retryable), `Disk`, `Unsupported`,
  `ValidationFailed`, `Canceled`, `SourceError`, `Unknown`
- I/O exceptions from `FileAccessor` classified as `KDownError.Disk`

## Architecture Patterns

### Dual Backend via `KDownApi`
- `KDownApi` is the service interface (in `library:api`)
- `KDown` (core) is the in-process implementation
- `RemoteKDown` (remote) communicates with a daemon server over HTTP + SSE
- UI code works identically regardless of backend

### Pluggable Components
- `HttpEngine` interface for custom HTTP clients (default: Ktor)
- `TaskStore` interface for persistence (InMemoryTaskStore, SqliteTaskStore)
- `DownloadSource` interface for protocol-level extensibility
- `Logger` interface for logging backends

### Expect/Actual for Platform Code
- `FileAccessor`: Android/JVM uses `RandomAccessFile`, iOS uses `NSFileHandle`
- Console logger: platform-specific implementations
- All implementations use Mutex for thread-safety

### Coroutine-Based Concurrency
- `supervisorScope` for segment downloads (one failure doesn't cancel others)
- Structured concurrency for cleanup on cancel/pause
- `Dispatchers.IO` for file operations

## Development Guidelines

### Code Quality
- 2-space indentation, max 100 char lines (see `.editorconfig`)
- No star imports, no trailing commas
- Favor simple correctness over micro-optimizations
- Keep public APIs minimal with KDoc
- Mark internal implementation with `internal` modifier

### Architecture
- All core logic in `commonMain` using expect/actual for platform differences
- Dependency injection for pluggable components
- Prefer composition over inheritance
- Public API types live in `library:api`, implementations in `library:core`

### Testing
- Unit tests in `commonTest` for segment math, state transitions, serialization
- Mock `HttpEngine` for testing without network
- Test edge cases: 0-byte files, 1-byte files, uneven segment splits

### Logging
- Use `KDownLogger` for all internal logging
- Tags: "KDown", "Coordinator", "SegmentDownloader", "RangeDetector", "KtorHttpEngine",
  "Scheduler", "ScheduleManager", "SourceResolver"
- Levels: verbose (segment detail), debug (state changes), info (user events),
  warn (retries), error (fatal)
- Use lazy lambdas: `logger.d { "expensive $computation" }`

## Current Limitations

1. WasmJs: Local file I/O not supported (`FileAccessor` is a stub that throws
   `UnsupportedOperationException`). Use `RemoteKDown` for browser-based downloads.
2. iOS support is best-effort via expect/actual (iosArm64 + iosSimulatorArm64)
3. `library:sqlite` does not support WasmJs -- use `InMemoryTaskStore` on that platform

## Roadmap

Planned features not yet implemented:

1. **Web App** - Browser-based download manager UI
2. **Torrent Support** - BitTorrent protocol as a pluggable `DownloadSource`
3. **Media Downloads** - Web media extraction (like yt-dlp) as a pluggable `DownloadSource`
