You are a senior Kotlin Multiplatform library engineer working on "KDown", an open-source Kotlin
Multiplatform downloader library.

## Project Status

The library is **substantially complete** with all core features implemented and working across
Android, JVM/Desktop, iOS, and WebAssembly platforms.

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

### 5. Metadata Persistence (✅ `MetadataStore` interface)
- Interface implemented with two storage backends:
  - `InMemoryMetadataStore`: for testing and ephemeral downloads
  - `JsonMetadataStore`: file-based persistence using kotlinx-io
- Stores: url, destPath, totalBytes, acceptRanges, etag, lastModified, segment progress

### 6. Error Handling (✅ Sealed `KDownError`)
- Types: `Network`, `Http(code)`, `Disk`, `Unsupported`, `ValidationFailed`, `Canceled`, `Unknown`
- Smart retry: only for transient errors (network issues, 5xx HTTP codes)
- Exponential backoff with configurable retry count and base delay

### 7. Progress Tracking (✅ StateFlow-based)
- Aggregates progress across all segments
- Throttled updates (default: 200ms) to prevent UI spam
- Includes: downloadedBytes, totalBytes, percent, speed

### 8. Logging System (✅ Pluggable `Logger` interface)
- Platform-specific console implementations:
  - JVM: `println` / `System.err`
  - Android: Android `Log` API with tag prefixing
  - iOS: `NSLog`
- Levels: verbose, debug, info, warn, error
- Default: `Logger.None` (no logging)
- Kermit integration available via `library/kermit` module

### 9. Testing (✅ Comprehensive test suite)
- Unit tests for: SegmentCalculator, SegmentDownloader, InMemoryMetadataStore
- Model tests for all data classes
- State transition tests

### 10. Documentation (✅ Complete)
- README.md: quickstart, features, configuration, error handling
- LOGGING.md: detailed logging guide
- CLI example: demonstrates pause/resume functionality
- Multiple platform examples: Compose Multiplatform, Desktop, Android, iOS, WebAssembly

## Current Limitations & Future Enhancements

Known limitations (documented in README.md):
1. ❌ No download queue/scheduler (one `download()` call = one task at a time)
2. ❌ No bandwidth throttling
3. ⚠️ WebAssembly file writes limited by browser APIs (basic support only)
4. ⚠️ iOS support is best-effort via expect/actual (functional but not extensively tested)

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
- Use dependency injection for pluggable components (HttpEngine, MetadataStore, Logger)
- Prefer composition over inheritance

### Testing
- Add unit tests for new features in `commonTest`
- Test segment calculation edge cases (file sizes, connection counts)
- Test metadata serialization/deserialization
- Test state transitions (Idle → Downloading → Paused → Downloading → Completed)
- Mock HTTP responses for testing resume logic

### Logging
- Use `KDownLogger.logger` for all internal logging
- Log at appropriate levels:
  - `verbose`: Detailed flow information
  - `debug`: Important state changes
  - `info`: User-facing events (download started, completed)
  - `warn`: Recoverable errors, retries
  - `error`: Fatal errors
- Include context in log messages (task ID, segment index, etc.)
