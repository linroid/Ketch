# Coding Agent Instructions

This is the shared entry point for coding agents working in this repository. These instructions
apply throughout the repository, regardless of the agent or editor being used.

Before making changes, read and follow both shared rule documents:

- [Code style](docs/development/code-style.md)
- [Testing](docs/development/testing.md)

Keep project guidance in this file and the linked documentation. Any tool-specific instruction
files should only point here, so there is a single source of truth. For tools that do not discover
`AGENTS.md` automatically, explicitly include this file in their context or ask them to read it.

Historical design plans live in `docs/plans/`; consult them when relevant, but verify their
assumptions against the current code. The existing
[BitTorrent design assessment](docs/plans/kmp-expert-plan.md) is one such reference.

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
  torrent/    # BitTorrent/Magnet DownloadSource (Android, JVM, iOS) -- published SDK module
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
  browser-extension/  # Chromium/Firefox extension that sends downloads to Ketch (plain JS)
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
  `ServerConfig`, `RemoteConfig`, `AiSettings`, `LlmSettings`, `LlmProvider`,
  `SearchSettings`, `SearchProvider`, `TorrentSettings`, `PlatformFileSystem` (expect/actual)

### `library:remote`
- `com.linroid.ketch.remote` -- `RemoteKetch` (implements `KetchApi`), `RemoteDownloadTask`,
  `ConnectionState`, `WireModels`, `WireMapper`

### `ai:discover` (JVM/Android only)
- `com.linroid.ketch.ai` -- `AiModule`, `AiConfig`, `LlmClientFactory`,
  `ResourceDiscoveryService`, `DiscoverQuery`, `DiscoverResult`,
  `RankedCandidate`
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
- Per-host download limits (`DownloadConfig.maxConnectionsPerHost`), keyed by the lowercased
  URL host; host-less URIs (magnet, `torrent:`, local files) are not counted
- `KetchApi.updateConfig` applies queue limits immediately: raising one promotes queued
  tasks, lowering one never interrupts running tasks
- Priority-based ordering (`DownloadPriority`: LOW, NORMAL, HIGH, URGENT)
- URGENT preemption: pauses lowest-priority active download to make room

### Live Configuration
- `Ketch` keeps the current `DownloadConfig`; `updateConfig` applies speed and queue limits
  immediately, other fields (default directory, connections, retries, intervals, buffer size)
  are snapshotted into `DownloadContext.config` when a download starts or resumes
- Sources read defaults from `DownloadContext.config` and `DownloadContext.effectiveConnections()`
- Per-task `setSpeedLimit` / `setConnections` / `setPriority` / `reschedule` persist to the
  `TaskRecord` in any non-terminal state and apply live when the task is running

### Speed Limiting
- Global speed limit via `DownloadConfig.speedLimit`, changed at runtime with
  `KetchApi.updateConfig()`
- Per-task speed limit via `DownloadRequest.speedLimit` or `DownloadTask.setSpeedLimit()`;
  it caps the task in addition to the global limit (the lower rate wins)
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

### BitTorrent/Magnet Support (`library:torrent`)
- Pure Kotlin BitTorrent v1/v2/hybrid downloads on Android, JVM and iOS;
  browser control through RemoteKetch
- HTTP(S)/local metainfo, SDK bytes, btih magnets, tracker tiers, DHT and peer exchange
- Verified selected-file storage, ownership journal, restart rehash, live limits and explicit seeding
- Apps open `.torrent` files from the system file manager (Android, desktop, iOS, installed web
  app) and resolve them through `KetchApi.resolveContent`, like dropped files
- Native torrent engine dependencies exist only in interoperability tests
- Public v2/hybrid download, selection, limits, pause/resume and TaskStore restart are implemented.
  V2 incoming routing, upload/seeding, PEX and hybrid v1-only peers remain roadmap work.
- See [support and migration](docs/torrent.md) and [verification](docs/development/torrent-verification.md)

### AI-Driven Resource Discovery (`ai:discover`) — In Progress
- LLM agent-driven discovery using Koog framework (v1.2.0)
- Providers: OpenAI, Anthropic, Google Gemini, Ollama, any
  OpenAI-compatible endpoint (`LlmClientFactory` maps them to Koog clients)
- Configured under Settings → AI discovery and persisted under `[ai]` in
  `config.toml`; blank credentials fall back to environment variables
- The Discover destination is hidden until discovery is usable; in the
  apps the Enable switch is authoritative (an env key fills a blank token
  but never enables the feature — only the CLI auto-enables)
- Provider defaults track current models; unknown ids resolve as custom
  Koog models, and `temperature` is only sent to models that accept it
- 7 agent tools: `searchWeb`, `searchSites`, `fetchPage`, `headUrl`,
  `extractDownloads`, `validateUrl`, `emitStep`
- SSRF protection, device safety scoring, rate limiting
- JVM/Android only (uses Koog + Ktor CIO client)
- See [AI discovery configuration](docs/ai-discovery.md)

### Configuration (`config/`)
- TOML-based configuration via ktoml library
- `KetchConfig` root with server, download, remote, AI, and appearance sections
- `AiSettings`: AI discovery provider, token, model, endpoint and search keys
- `AppearanceConfig`: accent palette and light/dark `ThemeMode` (app-only;
  CLI and server ignore it)
- `TorrentSettings`: extra trackers for public torrents (`TorrentConfig.additionalTrackers`),
  edited on the embedded instance's BitTorrent settings page and applied to torrents as they
  start or resume; a remote instance's trackers are only editable on that device
- Apps edit it on the Settings destination, split into `SettingsCategory`
  pages (General, Downloads, Network, BitTorrent, Remote access, AI discovery, About).
  Wide windows open it as an overlay dialog (also ⌘, / Ctrl+, on desktop),
  narrow ones as a page. Changes apply as they are made; there are no Save
  buttons
- Downloads and Network settings belong to the active instance
  (`InstanceSettingsController`): pushed live via `KetchApi.updateConfig` /
  `updateNetworkInterfaces`, and saved to `config.toml` only for the
  embedded instance
- `ServerConfig`: host, port, API token, CORS, mDNS, `autoStart` (apps start
  the server on launch)
- `RemoteConfig`: pre-configured remote server connections
- `FileConfigStore`: platform-specific file persistence via okio

### Daemon Server (`server/`)
- Ktor-based REST API: create, list, pause, resume, cancel downloads
- SSE event stream for real-time state updates
- Remote backend (`RemoteKetch`) communicates via HTTP + SSE
- Auto-reconnection with exponential backoff

### Browser Extension (`app/browser-extension`)
- Manifest V3 extension for Chromium browsers and Firefox; plain JavaScript modules with no
  dependencies. `src/` loads unpacked in Chromium; `node build.mjs` writes `build/chrome`,
  `build/firefox` (event page instead of service worker, gecko id) and zips, which the release
  workflow attaches to GitHub releases (manifest version: the tag's numbers plus the run number)
- Talks to the daemon REST API (`POST /api/tasks`, `/api/resolve/content`, `/api/status`) of
  one or more instances: the Ketch app on this computer and servers (`ketch server`, other
  devices), each with an optional bearer token. Captured downloads and magnets go to the
  default one
- The Ketch desktop app is reached through the native messaging host `com.linroid.ketch`, its own
  launcher run with `--native-messaging-host` (`app/desktop`: `NativeMessagingHost`,
  `NativeHostRegistration`, `BrowserExtensionServer`). The host asks the running app over
  `SingleInstance`, opening it if needed, for a loopback-only `KetchServer` on a free port with a
  per-run token, separate from the Settings server. The app registers the host with installed
  browsers on every launch; the Chromium extension id is pinned by the manifest `key`
- Captures browser downloads (Chromium holds them in `onDeterminingFilename`, Firefox pauses
  them) and falls back to the browser when Ketch fails; context menus per instance; a content
  script sends trusted magnet link clicks
- Forwards cookies, referrer and user agent as `DownloadRequest.headers`; `.torrent` downloads
  whose URL Ketch would not recognize are fetched and resolved via `resolveContent`
- Unit tests run with `node --test` in `app/browser-extension`; see its README

### Logging System
- `Logger.None` (default, zero overhead), `Logger.console()`, `KermitLogger`
- Platform-specific console: Logcat (Android); timestamped println elsewhere, with errors on
  stderr on the JVM (`FormattedConsoleLogger`)
- Apps log at debug; the desktop app reads `KETCH_LOG_LEVEL`, the CLI `-v`/`--debug`
- Desktop, Android and iOS apps also write `logs/ketch.log` in their data directory
  (`FileLogger` in `app/shared`, rotated at 5 MiB, 3 files kept, combined with the console via
  `Logger.combine`); Settings → About opens the folder (desktop) or shares a copy (phones)
- `Ketch` logs every task state transition; torrent swarms log a debug summary every 30s
- See [logging](docs/logging.md) for the format, troubleshooting and sensitive-data rules
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
- Tags: "Ketch", "Coordinator", "Execution", "SegmentDownloader", "RangeDetector",
  "KtorHttpEngine", "DownloadQueue", "DownloadScheduler", "SourceResolver", "HttpSource",
  "FtpSource", "FtpClient", "TorrentSource", "TorrentEngine", "TorrentSession",
  "TorrentSwarm", "TorrentTracker", "RemoteKetch", "RemoteTask", "TokenBucket"
- Levels: verbose (segment and per-peer detail), debug (internal operations), info (user
  events and state transitions), warn (retries, recoverable problems), error (fatal)
- Include `taskId=` in every task-scoped message; torrent engine code also uses `logHash()`
- Never log credentials: pass URLs through `redactUrl()`, tracker URLs through
  `trackerLabel()`, and describe tracker errors with `describeWithoutUrls()`
- Warnings without a stack trace name the cause with `error.describeCauses()`, not
  `error.message`, which for `KetchError` is only a generic summary
- Do not swallow failures silently: log best-effort work that fails (at debug when it is
  expected to fail often), as `attempt("label") { ... }` does in the torrent engine
- Use lazy lambdas: `log.d { "expensive $computation" }`
- Keep log calls on one line when the message is short enough (within 100 chars)

## Current Limitations

1. WasmJs: Local file I/O not supported (`PlatformFileSystem` throws
   `UnsupportedOperationException`). Use `RemoteKetch` for browser-based downloads.
2. iOS support is best-effort via expect/actual (iosArm64 + iosSimulatorArm64)
3. `library:sqlite` does not support WasmJs -- use `InMemoryTaskStore` on that platform
4. `library:ftp` does not support WasmJs (requires raw TCP sockets)
5. `library:torrent` has no browser-local engine. V2/hybrid uses outgoing v2 TCP;
   v2 incoming/upload/seeding/PEX, hybrid v1-only peers, uTP and encryption remain unimplemented.
6. FTPS (FTP over TLS) only works on JVM/Android; iOS throws `KetchError.Unsupported`
   (blocked by [KTOR-7475](https://youtrack.jetbrains.com/issue/KTOR-7475))
7. `ai:discover` is JVM/Android only (depends on Koog + Ktor CIO); iOS and
   the web app report AI discovery as unavailable
8. AI API tokens are stored in plain text in `config.toml`, like the server
   `apiToken`; use environment variables on shared machines

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
6. **MCP Server** - Expose Ketch capabilities as tools for AI agents via Model Context
   Protocol
