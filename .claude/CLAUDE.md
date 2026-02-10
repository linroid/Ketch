You are a senior Kotlin Multiplatform library engineer working on "KDown", an open-source Kotlin
Multiplatform downloader library.

## Project Status

The library is **substantially complete** with all core features implemented and working across
Android, JVM/Desktop, iOS, and WebAssembly platforms.

**Note:** The library has not been published yet, so public API breaking changes are allowed.

## Core Features (All Implemented ✅)

1. ✅ Download with progress callbacks (bytes downloaded, total bytes, speed)
2. ✅ Pause / Resume (true resume using HTTP Range; not "restart from 0")
3. ✅ Multi-threaded segmented downloads (N segments using Range requests)
4. ✅ Robust cancellation
5. ✅ Retry with exponential backoff (configurable)
6. ✅ Persist resumable metadata (app restart can resume)
7. ✅ Comprehensive logging system with platform-specific implementations
8. ✅ Kermit integration for structured logging

## Architecture (Implemented ✅)

- ✅ Ktor Client as default HTTP layer via `KtorHttpEngine` implementation
- ✅ Pluggable architecture with `HttpEngine` interface for custom implementations
- ✅ Kotlin coroutines throughout; no blocking IO in common code
- ✅ `FileAccessor` expect/actual abstraction for platform-specific file operations
  - Android/JVM: Uses `RandomAccessFile` with `Dispatchers.IO`
  - iOS: Uses Foundation APIs (`NSFileHandle`, `NSFileManager`)
  - All implementations are thread-safe with Mutex protection
- ✅ Thread-safety ensured for pause/resume/cancel operations

## Implemented Components

### 1. Server Capability Detection (✅ `RangeSupportDetector`)
- Performs HEAD request before downloading
- Detects: content-length, accept-ranges, ETag, Last-Modified
- Falls back to single-connection if ranges not supported

### 2. Segmented Downloads (✅ `SegmentCalculator` + `SegmentDownloader`)
- Splits files into N segments based on total size and connections
- Each segment downloads concurrently using `Range: bytes=start-end`
- Automatic fallback to single connection if server doesn't support ranges

### 3. File Writing (✅ `FileAccessor` expect/actual)
- Platform-specific random-access writes at offsets
- Thread-safe with Mutex protection
- Each segment writes to correct offset in same destination file

### 4. Pause/Resume (✅ `DownloadCoordinator`)
- Pause: stops all segment jobs, persists offsets, transitions to `Paused` state
- Resume: loads metadata, validates server identity (ETag/Last-Modified), continues from last offsets
- Resume validation prevents corrupted downloads
- Local file integrity check on resume: verifies file size matches claimed progress, resets if truncated
- Duplicate download guards: `start()`, `startFromRecord()`, `resume()` check `activeDownloads` to prevent concurrent writes
- State transition guards in `KDown` action lambdas prevent invalid operations (e.g., pause on completed)

### 5. Metadata Persistence (✅ `TaskStore` interface)
- Interface implemented with two storage backends:
  - `InMemoryTaskStore`: for testing and ephemeral downloads
  - `SqliteTaskStore`: SQLite-based persistence (separate module)
- Stores: url, destPath, totalBytes, acceptRanges, etag, lastModified, segment progress

### 6. Error Handling (✅ Sealed `KDownError`)
- Types: `Network`, `Http(code)`, `Disk`, `Unsupported`, `ValidationFailed`, `Canceled`, `Unknown`
- Smart retry: only for transient errors (network issues, 5xx HTTP codes)
- Exponential backoff with configurable retry count and base delay
- I/O exceptions from `FileAccessor` (`writeAt`, `flush`, `preallocate`) classified as `KDownError.Disk`

### 7. Progress Tracking (✅ StateFlow-based)
- Aggregates progress across all segments
- Throttled updates (default: 200ms) to prevent UI spam
- Includes: downloadedBytes, totalBytes, percent, speed

### 8. Logging System (✅ Pluggable `Logger` interface)
- Three logging options:
  1. `Logger.None` (default): Zero-overhead, no logging
  2. `Logger.console()`: Platform-specific console output
  3. `KermitLogger`: Optional Kermit integration (separate module)
- Platform-specific console implementations:
  - JVM: `println` / `System.err`
  - Android: Android `Log` API with tag prefixing ("KDown.*")
  - iOS: `NSLog`
  - WebAssembly: `println` (browser dev tools)
- Levels: verbose, debug, info, warn, error
- Lazy evaluation via lambda parameters for zero cost when disabled
- Internal `KDownLogger` wrapper for consistent tag formatting: `[Tag] message`
- Comprehensive logging coverage:
  - KDown lifecycle (init, start, pause, resume, cancel)
  - Server detection and capabilities
  - Segment operations (start, completion, progress)
  - HTTP requests and errors
  - Retry attempts
- Kermit integration available via `library/kermit` module
- See LOGGING.md for detailed usage examples

### 9. Testing (✅ Comprehensive test suite)
- Unit tests for: SegmentCalculator, SegmentDownloader, InMemoryMetadataStore
- Model tests for all data classes
- State transition tests

### 10. Documentation (✅ Complete)
- README.md: quickstart, features, configuration, error handling
- LOGGING.md: detailed logging guide
- CLI example: demonstrates pause/resume functionality
- Multiple platform examples: Compose Multiplatform, Desktop, Android, iOS, WebAssembly

## Current Limitations

1. ⚠️ WebAssembly file writes limited by browser APIs (basic support only)
2. ⚠️ iOS support is best-effort via expect/actual (functional but not extensively tested)

## Roadmap

Planned features that should be considered in architecture decisions:

1. **Speed Limit** - Bandwidth throttling per task or globally
2. **Queue Management** - Download queue with priority, concurrency limits, and scheduling
3. **Scheduled Downloads** - Timer-based or condition-based download scheduling
4. **Web App** - Browser-based download manager UI
5. **Torrent Support** - BitTorrent protocol as a pluggable download source
6. **Media Downloads** - Download web media (like yt-dlp), with a pluggable downloader
   architecture to support different media sources and extractors
7. **Daemon Server** - Run KDown as a background service/daemon with an API, supporting
   switching between local and remote backends (e.g., control a remote KDown instance
   from a mobile app or web UI)

## Usage Examples

### Basic Setup with Logging

```kotlin
// Option 1: No logging (default, zero overhead)
val kdown = KDown(httpEngine = KtorHttpEngine())

// Option 2: Console logging (development/debugging)
val kdown = KDown(
  httpEngine = KtorHttpEngine(),
  logger = Logger.console()
)

// Option 3: Kermit structured logging (recommended for production)
val kdown = KDown(
  httpEngine = KtorHttpEngine(),
  logger = KermitLogger(minSeverity = Severity.Info)
)
```

See LOGGING.md for detailed logging guide with example output.

## Development Guidelines

When working on this project:

### Code Quality
- Favor simple correctness over micro-optimizations
- Keep public APIs minimal and well-documented with KDoc
- Mark internal implementation details with `internal` modifier
- Use sealed classes for closed type hierarchies

### Architecture Patterns
- All core logic lives in `commonMain` using expect/actual for platform differences
- Avoid platform-specific APIs in common code
- Use dependency injection for pluggable components (HttpEngine, TaskStore, Logger)
- Prefer composition over inheritance
- Package structure: root (KDown, DownloadConfig, DownloadRequest, DownloadProgress, DownloadState), `task/` (DownloadTask, TaskStore, InMemoryTaskStore, TaskRecord, TaskState), `segment/` (Segment, SegmentCalculator, SegmentDownloader), `engine/` (HttpEngine, DownloadCoordinator, RangeSupportDetector, ServerInfo), `file/` (FileAccessor, FileNameResolver, DefaultFileNameResolver, PathSerializer), `log/` (Logger, KDownLogger), `error/` (KDownError)

### Testing
- Add unit tests for new features in `commonTest`
- Test segment calculation edge cases (file sizes, connection counts)
- Test metadata serialization/deserialization
- Test state transitions (Idle → Downloading → Paused → Downloading → Completed)
- Mock HTTP responses for testing resume logic

### Logging
- Use `KDownLogger` for all internal logging (automatically prefixes tags)
- Pass component tag as first parameter: `KDownLogger.i("KDown") { "message" }`
- Log at appropriate levels:
  - `verbose`: Detailed flow information (segment-level progress, byte details)
  - `debug`: Important state changes (server detection, metadata operations, segment start/completion)
  - `info`: User-facing events (download started/paused/resumed/completed, server capabilities)
  - `warn`: Recoverable errors, retries, validation warnings
  - `error`: Fatal errors (download failures, network errors)
- Include context in log messages (task ID, segment index, URLs, byte ranges)
- Use lazy lambdas for message construction: `logger.d { "expensive $computation" }`
  - Messages are only computed if logging is enabled
  - This ensures zero overhead when using `Logger.None` (default)
- Common component tags: "KDown", "Coordinator", "SegmentDownloader", "RangeDetector", "KtorHttpEngine"
