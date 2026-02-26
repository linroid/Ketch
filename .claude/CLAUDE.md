You are a senior Kotlin Multiplatform library engineer working on "Ketch", an open-source Kotlin
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
  ftp/        # FTP/FTPS DownloadSource (Android, iOS, JVM only) -- published SDK module
  torrent/    # BitTorrent/Magnet DownloadSource (Android, JVM only) -- published SDK module
  kermit/     # Optional Kermit logging integration -- published SDK module
  sqlite/     # SQLite-backed TaskStore (Android, iOS, JVM only) -- published SDK module
  remote/     # Remote KetchApi client (HTTP + SSE) -- published SDK module
  endpoints/  # Shared REST API endpoint definitions (Ktor Resources)
config/       # Multiplatform TOML-based configuration (server, download, remotes)
server/       # Ktor-based daemon server with REST API and SSE events
ai/
  discover/   # LLM agent-driven resource discovery (JVM only, Koog framework)
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
- `com.linroid.ketch.api` -- `KetchApi`, `DownloadTask`, `DownloadRequest`, `DownloadState`,
  `DownloadProgress`, `Segment`, `KetchError`, `SpeedLimit`, `DownloadPriority`,
  `DownloadSchedule`, `DownloadCondition`, `KetchVersion`, `ResolvedSource`, `SourceFile`,
  `FileSelectionMode`
- `com.linroid.ketch.api.config` -- `DownloadConfig`

### `library:core` (implementation)
- `com.linroid.ketch.core` -- `Ketch` (implements `KetchApi`)
- `com.linroid.ketch.core.engine` -- `HttpEngine`, `DownloadCoordinator`, `RangeSupportDetector`,
  `ServerInfo`, `DownloadSource`, `HttpDownloadSource`, `SourceResolver`, `SourceInfo`,
  `SourceResumeState`, `DownloadContext`, `DownloadQueue`, `DownloadScheduler`,
  `SpeedLimiter`, `TokenBucket`, `DelegatingSpeedLimiter`
- `com.linroid.ketch.core.segment` -- `SegmentCalculator`, `SegmentDownloader`
- `com.linroid.ketch.core.file` -- `FileAccessor`, `PathFileAccessor`, `NoOpFileAccessor`,
  `PlatformFileSystem` (expect/actual), `FileNameResolver`, `DefaultFileNameResolver`,
  `PathSerializer`
- `com.linroid.ketch.core.log` -- `Logger`, `KetchLogger`
- `com.linroid.ketch.core.task` -- `RealDownloadTask`, `TaskStore`, `InMemoryTaskStore`,
  `TaskRecord`, `TaskState`

### `library:ftp`
- `com.linroid.ketch.ftp` -- `FtpDownloadSource` (implements `DownloadSource`), `FtpClient`,
  `RealFtpClient`, `FtpUrl`, `FtpReply`, `FtpError`, `FtpResumeState`, `TlsUpgrade`

### `library:torrent`
- `com.linroid.ketch.torrent` -- `TorrentDownloadSource` (implements `DownloadSource`),
  `TorrentEngine`, `TorrentSession`, `TorrentConfig`, `TorrentMetadata`,
  `TorrentResumeState`, `MagnetUri`, `InfoHash`, `Bencode`, `Sha1`

### `library:endpoints`
- `com.linroid.ketch.endpoints` -- `Api` (Ktor `@Resource` definitions for REST API)
- `com.linroid.ketch.endpoints.model` -- `TaskSnapshot`, `TasksResponse`, `TaskEvent`,
  `TaskEventType`, `ErrorResponse`, `ResolveUrlRequest`, `SpeedLimitRequest`,
  `PriorityRequest`, `ConnectionsRequest`

### `config`
- `com.linroid.ketch.config` -- `KetchConfig`, `ConfigStore`, `FileConfigStore`,
  `ServerConfig`, `RemoteConfig`, `PlatformFileSystem` (expect/actual)

### `library:remote`
- `com.linroid.ketch.remote` -- `RemoteKetch` (implements `KetchApi`), `RemoteDownloadTask`,
  `ConnectionState`, `WireModels`, `WireMapper`

### `ai:discover` (JVM only)
- `com.linroid.ketch.ai` -- `AiModule`, `AiConfig`, `ResourceDiscoveryService`,
  `DiscoverQuery`, `DiscoverResult`, `RankedCandidate`
- `com.linroid.ketch.ai.agent` -- `DiscoveryToolSet`, `AgentOutputParser`,
  `DeviceSafetyFilter`, `LinkExtractor`, `DiscoveryStepListener`
- `com.linroid.ketch.ai.fetch` -- `SafeFetcher`, `UrlValidator`, `ContentExtractor`,
  `RateLimiter`
- `com.linroid.ketch.ai.search` -- `SearchProvider`, `DummySearchProvider`
- `com.linroid.ketch.ai.site` -- `SiteProfiler`, `SiteProfile`, `SiteProfileStore`,
  `RobotsTxtParser`

## Implemented Features

### Core Download Engine
- Multi-platform: Android (minSdk 26), JVM 11+, iOS (iosArm64, iosSimulatorArm64), WasmJs
- Segmented downloads with concurrent HTTP Range requests
- Pause / Resume with server identity validation (ETag, Last-Modified)
- File integrity check on resume (validates local file size vs. claimed progress)
- Retry with exponential backoff for transient errors
- Persistent task metadata via `TaskStore` interface
- Duplicate download guards in `start()`, `startFromRecord()`, `resume()`

### Queue Management (`DownloadQueue`)
- Configurable concurrent download slots (`DownloadConfig.maxConcurrentDownloads`)
- Per-host connection limits (`DownloadConfig.maxConnectionsPerHost`)
- Priority-based ordering (`DownloadPriority`: LOW, NORMAL, HIGH, URGENT)
- URGENT preemption: pauses lowest-priority active download to make room

### Speed Limiting
- Global speed limit via `DownloadConfig.speedLimit` or `KetchApi.setGlobalSpeedLimit()`
- Per-task speed limit via `DownloadRequest.speedLimit` or `DownloadTask.setSpeedLimit()`
- Token-bucket algorithm (`TokenBucket`) with delegating wrapper

### Download Scheduling (`DownloadScheduler`)
- `DownloadSchedule.Immediate`, `AtTime(Instant)`, `AfterDelay(Duration)`
- `DownloadCondition` interface for user-defined conditions (e.g., WiFi-only)
- Reschedule support via `DownloadTask.reschedule()`

### Pluggable Download Sources (`DownloadSource`)
- `SourceResolver` routes URLs to the appropriate source
- `HttpDownloadSource` is the built-in HTTP/HTTPS implementation
- `FtpDownloadSource` handles FTP/FTPS with segmented parallel transfers
- `TorrentDownloadSource` handles BitTorrent/Magnet downloads
- Additional sources registered via `Ketch(additionalSources = listOf(...))`
- Each source defines: `type`, `canHandle()`, `resolve()`, `download()`, `resume()`
- `managesOwnFileIo` flag: when `true`, engine skips `FileAccessor` (used by torrent)

### Multi-File Download Support
- `ResolvedSource.files` lists selectable files within a source (e.g., torrent)
- `FileSelectionMode.MULTIPLE` for subset selection (torrent)
- `FileSelectionMode.SINGLE` for single-variant selection (HLS quality)
- `DownloadRequest.selectedFileIds` specifies which files to download

### FTP/FTPS Support (`library:ftp`)
- FTP and FTPS (FTP over TLS) as a pluggable `DownloadSource`
- Segmented parallel downloads via multiple FTP connections with REST offsets
- Resume support with MDTM-based server file change validation
- FTPS on JVM/Android via ktor-network-tls; iOS deferred (no ktor TLS support)
- Passive mode only (PASV/EPSV); FTP URL parsing with credentials
- Platforms: Android, JVM, iOS (no WasmJs — requires raw TCP sockets)

### BitTorrent/Magnet Support (`library:torrent`) — In Progress
- `.torrent` files and `magnet:` URIs as a pluggable `DownloadSource`
- libtorrent4j (v2.1.0-39) as the underlying engine
- Multi-file selection: resolve returns file list, user selects subset
- Resume with persisted resume data (base64-encoded libtorrent state)
- Per-torrent speed limiting via `TorrentSession.setDownloadRateLimit()`
- Metadata fetch from magnet links with configurable timeout
- `managesOwnFileIo = true` — torrent engine handles its own file writes
- Platforms: Android, JVM (no iOS, no WasmJs)

### AI-Driven Resource Discovery (`ai:discover`) — In Progress
- LLM agent-driven discovery using Koog framework (v0.6.2)
- 7 agent tools: `searchWeb`, `searchSites`, `fetchPage`, `headUrl`,
  `extractDownloads`, `validateUrl`, `emitStep`
- SSRF protection, device safety scoring, rate limiting
- JVM only (uses Koog + Ktor CIO client)

### Configuration (`config/`)
- TOML-based configuration via ktoml library
- `KetchConfig` root with server, download, and remote sections
- `ServerConfig`: host, port, API token, CORS, mDNS
- `RemoteConfig`: pre-configured remote server connections
- `FileConfigStore`: platform-specific file persistence via okio

### Daemon Server (`server/`)
- Ktor-based REST API: create, list, pause, resume, cancel downloads
- SSE event stream for real-time state updates
- Remote backend (`RemoteKetch`) communicates via HTTP + SSE
- Auto-reconnection with exponential backoff

### Logging System
- `Logger.None` (default, zero overhead), `Logger.console()`, `KermitLogger`
- Platform-specific console: Logcat (Android), NSLog (iOS), println/stderr (JVM), println (Wasm)
- `KetchLogger` uses `inline` functions with `Logger.None` fast-path for zero-cost disabled logging
- `Logger` interface accepts `String` messages; lazy evaluation handled by `KetchLogger`

### Error Handling (sealed `KetchError`)
- `Network` (retryable), `Http(code)` (5xx/429 retryable), `Disk`, `Unsupported`,
  `FileChanged`, `CorruptResumeState`, `Canceled`, `SourceError`,
  `AuthenticationFailed`, `Unknown`
- I/O exceptions from `FileAccessor` classified as `KetchError.Disk`

## Architecture Patterns

### Dual Backend via `KetchApi`
- `KetchApi` is the service interface (in `library:api`)
- `Ketch` (core) is the in-process implementation
- `RemoteKetch` (remote) communicates with a daemon server over HTTP + SSE
- UI code works identically regardless of backend

### Pluggable Components
- `HttpEngine` interface for custom HTTP clients (default: Ktor)
- `TaskStore` interface for persistence (InMemoryTaskStore, SqliteTaskStore)
- `DownloadSource` interface for protocol-level extensibility
- `Logger` interface for logging backends

### Expect/Actual for Platform Code
- `FileAccessor`: okio `FileHandle` via `PathFileAccessor` on Android/JVM/iOS;
  `ContentUriFileAccessor` for Android SAF; `NoOpFileAccessor` for self-managed sources
- `PlatformFileSystem`: okio `FileSystem.SYSTEM` on Android/JVM/iOS; throws on WasmJs
- Console logger: platform-specific implementations
- All implementations use Mutex for thread-safety

### Coroutine-Based Concurrency
- `supervisorScope` for segment downloads (one failure doesn't cancel others)
- Structured concurrency for cleanup on cancel/pause
- `Dispatchers.IO` for file operations

## Development Guidelines

### Code Quality
- 2-space indentation, max 100 char lines (see `.editorconfig`)
- No star imports; trailing commas on multiline named parameters/arguments only (not positional)
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
- Use `KetchLogger` for all internal logging — instantiate per component:
  `private val log = KetchLogger("Coordinator")`
- Tags: "Ketch", "Coordinator", "SegmentDownloader", "RangeDetector", "KtorHttpEngine",
  "DownloadQueue", "DownloadScheduler", "SourceResolver", "HttpSource", "FtpSource",
  "FtpClient", "TorrentSource", "TokenBucket"
- Levels: verbose (segment detail), debug (state changes), info (user events),
  warn (retries), error (fatal)
- Use lazy lambdas: `log.d { "expensive $computation" }`
- Keep log calls on one line when the message is short enough (within 100 chars)

## Current Limitations

1. WasmJs: Local file I/O not supported (`PlatformFileSystem` throws
   `UnsupportedOperationException`). Use `RemoteKetch` for browser-based downloads.
2. iOS support is best-effort via expect/actual (iosArm64 + iosSimulatorArm64)
3. `library:sqlite` does not support WasmJs -- use `InMemoryTaskStore` on that platform
4. `library:ftp` does not support WasmJs (requires raw TCP sockets)
5. `library:torrent` supports Android and JVM only (no iOS, no WasmJs)
6. FTPS (FTP over TLS) only works on JVM/Android; iOS throws `KetchError.Unsupported`
   (blocked by [KTOR-7475](https://youtrack.jetbrains.com/issue/KTOR-7475))
7. `ai:discover` is JVM only (depends on Koog + Ktor CIO)

## Roadmap

Planned features not yet implemented:

1. **Metalink** - Multi-source downloads with mirrors, checksums, and chunk verification
2. **WebDAV** - Download from WebDAV servers with resume support
3. **HLS Support** - HTTP Live Streaming (HLS) as a pluggable `DownloadSource`, downloading
   and merging `.m3u8` playlist segments into a single media file
4. **Media Downloads** - Web media extraction (like yt-dlp) as a pluggable `DownloadSource`,
   supporting various media sites and extractors
5. **Resource Sniffer** - Detect and extract downloadable resources (media, files) from
   web pages by analyzing network requests, HTML, and embedded players
6. **Browser Extension** - Browser extension for intercepting and managing downloads
   directly from the browser, integrating with the Ketch daemon server
7. **MCP Server** - Expose Ketch capabilities as tools for AI agents via Model Context
   Protocol
