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

**Note:** The library is published on Maven Central (`com.linroid.ketch`), but it is still pre-1.0,
so public API breaking changes are allowed.

## Module Structure

```
library/
  api/        # Public API interfaces and models -- published SDK module
  core/       # In-process download engine (Android, iOS, JVM, JS/Node, WasmWasi) -- published
  ktor/       # Ktor-based HttpEngine implementation (Android, iOS, JVM) -- published SDK module
  ftp/        # FTP/FTPS DownloadSource (Android, iOS, JVM only) -- published SDK module
  hls/        # Finite HLS DownloadSource (same targets as core) -- published SDK module
  dash/       # Finite DASH DownloadSource (same targets as core) -- published SDK module
  torrent/    # BitTorrent/Magnet DownloadSource (Android, JVM, iOS) -- published SDK module
  kermit/     # Optional Kermit logging integration -- published SDK module
  sqlite/     # SQLite-backed TaskStore (Android, iOS, JVM only) -- published SDK module
  remote/     # Remote KetchApi client (HTTP + SSE) -- published SDK module
  endpoints/  # Shared REST API endpoint definitions (Ktor Resources) -- published SDK module
  server/     # Ktor-based daemon server with REST API, SSE events and mDNS (JVM only)
  mcp/        # MCP server exposing KetchApi as tools for AI agents (JVM only)
config/       # Multiplatform TOML-based configuration (server, download, remotes, AI, ...)
updater/      # GitHub release updates for desktop, direct Android and CLI (JVM module)
ai/
  discover/   # LLM agent-driven resource discovery (JVM only, Koog framework)
app/
  shared/     # Shared Compose Multiplatform UI (supports Core + Remote backends)
  android/    # Android app
  desktop/    # Desktop (JVM) app
  web/        # Wasm browser app
  ios/        # Native iOS app (Xcode project, consumes shared module)
  browser-extension/  # Chromium/Firefox/Safari extension that sends downloads to Ketch (plain JS)
cli/          # CLI: downloads, `server`, `mcp`, `health` and `ai-discover` (JVM; GraalVM native)
docker/       # Docker image of `ketch server` (Dockerfile, entrypoint, compose files for NAS)
```

## Package Structure

### `library:api` (public API)
- `com.linroid.ketch.api` -- `KetchApi` (`VERSION`/`REVISION` constants), `DownloadTask`,
  `DownloadRequest`, `DownloadState`, `DownloadProgress`, `DownloadConfig`, `Destination`,
  `Segment`, `KetchError`, `SpeedLimit`, `DownloadPriority`, `DownloadSchedule`,
  `DownloadCondition`, `KetchStatus`, `NetworkInterfaces`, `NetworkInterfaceConfig`,
  `ResolvedSource`, `SourceFile`, `FileSelectionMode`
- `com.linroid.ketch.api.log` -- `Logger`, `LogLevel`, `KetchLogger`, `FormattedConsoleLogger`,
  `redactUrl()`, `describeCauses()`
- `com.linroid.ketch.api.torrent` -- `TorrentController` (optional `KetchApi.torrents`; no backend
  implements it yet), `TorrentCapabilities`, `TorrentSnapshot`, `TorrentRevision`

### `library:core` (implementation)
- `com.linroid.ketch.core` -- `Ketch` (implements `KetchApi`), `KetchDispatchers`
- `com.linroid.ketch.core.engine` -- `HttpEngine`, `DownloadCoordinator`, `DownloadExecution`,
  `RangeSupportDetector`, `ServerInfo`, `RequestHeaders`, `DownloadSource`, `HttpDownloadSource`,
  `SourceResolver`, `SourceResumeState`, `DownloadContext`, `DownloadQueue`, `DownloadScheduler`,
  `SpeedLimiter`, `TokenBucket`, `DelegatingSpeedLimiter`, `MultiNetworkHttpEngine`,
  `ConfigurableNetworkHttpEngine`, `NetworkInterfaceProvider`
- `com.linroid.ketch.core.segment` -- `SegmentCalculator`, `SegmentDownloader`,
  `SegmentedDownloadHelper`
- `com.linroid.ketch.core.file` -- `FileAccessor`, `createFileAccessor()` (expect/actual),
  `PathFileAccessor`, `ContentUriFileAccessor` (Android), `NoOpFileAccessor`,
  `platformFileSystem` (expect/actual), `FileNameResolver`, `DefaultFileNameResolver`,
  `sanitizeFileName()`, `OutputPathReservations`, `DestinationPathPolicy`,
  `PathRejectedException`
- `com.linroid.ketch.core.task` -- `RealDownloadTask`, `TaskHandle`, `TaskController`,
  `TaskStore`, `InMemoryTaskStore`, `TaskRecord`, `TaskState`

### `library:ktor`, `library:kermit`, `library:sqlite`
- `com.linroid.ketch.engine` -- `KtorHttpEngine` (`withNetworkInterfaces()` on Android/JVM),
  `RedirectCache`
- `com.linroid.ketch.log` -- `KermitLogger`
- `com.linroid.ketch.sqlite` -- `SqliteTaskStore`, `DriverFactory` (expect/actual),
  `UnreadableDatabase`

### `library:ftp`
- `com.linroid.ketch.ftp` -- `FtpDownloadSource` (implements `DownloadSource`), `FtpClient`,
  `RealFtpClient`, `FtpUrl`, `FtpReply`, `FtpError`, `FtpResumeState`, `tlsUpgrade()`
  (expect/actual)

### `library:hls`, `library:dash`
- `com.linroid.ketch.hls` -- `HlsDownloadSource` (`.m3u8`)
- `com.linroid.ketch.dash` -- `DashDownloadSource` (`.mpd`)
- Both depend on core's `com.linroid.ketch.core.media` helpers for bounded manifest fetching,
  URL/header handling and sequential transfer; each owns its parser and tests

### `library:torrent`
- `com.linroid.ketch.torrent` -- `TorrentDownloadSource` (implements `DownloadSource`),
  `TorrentEngine`, `TorrentSession`, `TorrentConfig`, `TrackerListState`, `TorrentMetadata`,
  `TorrentResumeState`, `MagnetUri`, `InfoHash`, `Bencode`, `Sha1`

### `library:endpoints`
- `com.linroid.ketch.endpoints` -- `Api` (Ktor `@Resource` definitions for REST API)
- `com.linroid.ketch.endpoints.model` -- `TaskSnapshot`, `TasksResponse`, `TaskEvent`,
  `TaskEventType`, `ErrorResponse`, `ResolveUrlRequest`, `SpeedLimitRequest`,
  `PriorityRequest`, `ConnectionsRequest`, `PairingRequest`, `PairingTicket`, `PairingStatus`,
  `PairingState`, `HealthResponse`, `HealthStatus`

### `updater` (JVM module, also consumed by direct Android)
- `com.linroid.ketch.updater` -- `ReleaseVersion`, `Release`, `ReleaseAsset`, `ReleaseProduct`,
  `ReleasePlatform`, `ReleaseFeed`, `GitHubReleases`, `ReleaseDownloader`, `UpdateException`,
  `extractArchive()`

### `config`
- `com.linroid.ketch.config` -- `KetchConfig`, `ConfigStore`, `FileConfigStore`,
  `UnreadableConfig`, `WebConfigStore` (WasmJs, localStorage), `ServerConfig`, `RemoteConfig`,
  `AiSettings`, `LlmSettings`, `LlmProvider`, `LlmApi`, `LlmProviderGroup`, `SearchSettings`,
  `SearchProvider`,
  `PageAccessSettings`, `PageAccessMode`, `SiteNames`, `TorrentSettings`, `AppearanceConfig`,
  `AccentColor`, `ThemeMode`, `SpeedSettings`, `SpeedRule`, `UiPreferences`, `DesktopSettings`,
  `NotificationSettings`, `IntegrationSettings`

### `app:shared` (`com.linroid.ketch.app`)
- `App` (root composable), `state` (`AppController`, `AppState`, `TaskListModel`, `PulseModel`,
  `IntakeState`, `SpeedModeController`, `PendingOps`, `AiDiscoverController`, `DiscoverSession`,
  `DiscoverHistoryStore`, `FileDiscoverHistoryStore` on JVM/Android), `instance`
  (`InstanceManager`, `DevicePresence`, `DeviceScope`, `PairingRequests`), `theme` (`KetchTheme`
  tokens), `components` (the Ketch controls), `icons` (`KetchIcon`), `input` (`KetchCommands`,
  `CommandScope`, `ShortcutMatcher`), `feedback` (`MessageCenter`, `ActivityMonitor`,
  `UnreadableFiles`) and `util`
- `ui` -- `AppShell` and `shell` (layout, navigation and its badges, device switcher, drop
  berths), `sidebar`, `downloads` and `list` (table, list rows, launchpad), `inspector`, `intake`
  (add sheet), `palette`, `devices`, `connect`, `discover` (chat, history, approval cards),
  `settings`, `pulse`, `feedback`, `onboarding`

### `library:remote`
- `com.linroid.ketch.remote` -- `RemoteKetch` (implements `KetchApi`), `RemoteDownloadTask`,
  `ConnectionState`, `RemotePairing`, `PairingResult`, `RemoteApiException`

### `library:server`, `library:mcp` (JVM only)
- `com.linroid.ketch.server` -- `KetchServer`, `TaskMapper`, `PairingApprover`,
  `DestinationGuard`, `AuthThrottle`; `server.api` holds the routes and `receiveJson`, and
  `server.mdns` the `MdnsRegistrar` implementations
- `com.linroid.ketch.mcp` -- `KetchMcpServer`, `KetchToolSet`, `TextTool`

### `ai:discover` (JVM/Android only)
- `com.linroid.ketch.ai` -- `AiModule`, `AiConfig`, `LlmClientFactory`, `LlmModelLister`,
  `ResourceDiscoveryService`, `DiscoverQuery`, `DiscoverTurn`, `DiscoverResult`,
  `RankedCandidate`, `DiscoveryException`, `PageAccessApprover`, `PageAccessRequest`,
  `PageAccessKind`
- `com.linroid.ketch.ai.agent` -- `DiscoveryToolSet`, `TextTool`, `AgentOutputParser`,
  `sanitizeAgentText()`, `DeviceSafetyFilter`, `LinkExtractor`, `DiscoveryStepListener`,
  `SiteAllowlist`
- `com.linroid.ketch.ai.fetch` -- `SafeFetcher`, `UrlValidator`, `ValidatingDns`,
  `ContentExtractor`, `RateLimiter`, `FetchBudget`
- `com.linroid.ketch.ai.search` -- `SearchProvider`, `BraveSearchProvider`,
  `GoogleSearchProvider`, `PacedSearchProvider`, `DummySearchProvider`
- `com.linroid.ketch.ai.site` -- `SiteProfiler` (robots.txt), `RobotsTxtParser`

## Implemented Features

### Core Download Engine
- Multi-platform: Android (minSdk 26), JVM 11+, iOS (iosArm64, iosSimulatorArm64), plus
  JS (Node.js) and WasmWasi for the engine; the browser (WasmJs) uses `RemoteKetch`
- Segmented downloads with concurrent HTTP Range requests
- Servers without Range support use a single connection; resuming them restarts from zero
- Content of unknown size (no `Content-Length`, e.g. generated archives) streams over one
  connection; a retry or resume restarts it, and the completed task records the file's size
- Pause / Resume with server identity validation (ETag, Last-Modified)
- HTTP probes use HEAD; when it is refused with 400, 403, 404, 405 or 501 (URLs presigned for GET
  only), `RangeSupportDetector` asks `HttpEngine.probe`, a `GET` with `Range: bytes=0-0` whose
  body is never read. Engines without it (the default throws `UnsupportedOperationException`)
  keep the HEAD error
- Request headers (`DownloadRequest.headers`, `resolve` properties): `Ketch.download` rejects
  non-token names and values with control characters (`RequestHeaders.requireValid`, whose
  messages never quote values); engines drop `Host`, `Range`, `Content-Length` and hop-by-hop
  headers (`RequestHeaders.sendable`)
- `KtorHttpEngine` sends `User-Agent: Ketch/<version>` unless the headers name one (`userAgent`,
  `null` for none) and follows redirects itself (`followRedirects` off on its client copy): at
  most 20, never HTTPS to HTTP or to other schemes. A hop to another scheme, host or port keeps
  only `User-Agent`, `Accept`, `Accept-Encoding`, `Accept-Language` and `Referer` cut to its
  origin, and drops URL user info. `RedirectCache` remembers each request's final target and
  that hop's headers (per URL and headers, 30 minutes, 64 entries); HEAD and probes always follow
  the redirects and refresh it, GETs reuse it and forget it when the target fails
- Names from a server (`Content-Disposition`), a URL or an FTP path pass through
  `sanitizeFileName()` (last `/` or `\` segment, no control, bidi or Windows-reserved characters
  or device names, at most 255 UTF-8 bytes, `null` when nothing is left), in the sources and again
  in `DownloadExecution` for any name not from a `Destination`; the joined path must stay inside
  the folder (`KetchError.Disk` otherwise). A file `Destination` is used as it is.
  `OutputPathReservations` holds every running download's path for the process, so `name (n)`
  deduplication also avoids files other downloads have not created yet
- `DownloadTask.outputPath` reports where a task saves once the download chose it (core tasks;
  `null` from remote ones)
- File integrity check on resume (validates local file size vs. claimed progress)
- Only `cancel()` and `remove(deleteFiles = true)` delete a partial file (the coordinator tells the
  execution); a failure, `close()` or `remove(deleteFiles = false)` keeps it and its segments, but
  a failed final flush resets their progress. For files the engine writes (not torrents), an empty
  segment list is no progress: it is never saved, and resuming one starts from zero
- `DownloadState.Completed` reports the size and the download time, summed over every run and
  excluding time scheduled, queued or paused (`TaskRecord.downloadTime`, unknown for older records),
  and `completedAt`, stamped once in whole milliseconds and saved as `TaskRecord.completedAt`
  (SQLite `completed_at`, `4.sqm`; `null` for tasks completed before it was tracked)
- `DownloadState.Paused.reason` (`PauseReason`): `User`, `Preempted(byTaskId)`, `Shutdown` or
  `WaitingForCondition` (defined, not produced yet); unknown wire types decode as `User`. The
  reason is not persisted: a `PAUSED` record is a user pause
- `Ketch.close()` pauses running tasks for `Shutdown`, keeping their partial files and their
  `DOWNLOADING` records, so the next `start()` resumes them
- `KetchStatus.features` lists the optional behaviors an instance supports (`KetchFeatures`),
  including registered `DownloadSource.features`: `hls.finite` and `dash.finite` independently,
  plus the legacy `media.finite` when both are present. `DownloadSource.previousTypes` lets
  stored tasks with the old `media` type route to the matching protocol by URL
- Retry with exponential backoff for transient errors
- Persistent task metadata via `TaskStore` interface
- Unreadable storage never stops a start: `SqliteTaskStore.loadAll` skips (and logs) rows it
  cannot decode, leaving them in the table, and `DriverFactory` (JVM and Android) moves a
  database SQLite reports corrupt or not a database aside to `<name>.broken-<UTC time>`, with its
  journal, and starts an empty one (on Android also when corruption shows up later, instead of
  Android deleting it), telling its `onUnreadable` callback
- Duplicate download guards in `DownloadCoordinator.start()` and `resume()`
- HTTP requests can be spread round-robin over selected network interfaces
  (`KetchApi.updateNetworkInterfaces`, `MultiNetworkHttpEngine`); see
  [multiple networks](docs/multiple-networks.md)

### Queue Management (`DownloadQueue`)
- Configurable concurrent download slots (`DownloadConfig.maxConcurrentDownloads`)
- Per-host download limits (`DownloadConfig.maxConnectionsPerHost`), keyed by the lowercased
  URL host; host-less URIs (magnet, `torrent:`, local files) are not counted
- `KetchApi.updateConfig` applies queue limits immediately: raising one promotes queued
  tasks, lowering one never interrupts running tasks
- Priority-based ordering (`DownloadPriority`: LOW, NORMAL, HIGH, URGENT)
- URGENT preemption: pauses lowest-priority active download to make room; the victim stays
  in the queue as `Paused(Preempted)` with a `QUEUED` record and resumes when a slot frees.
  Tasks that already finished are never preempted
- `DownloadTask.queuePosition`: 1-based place of `Queued` and preempted tasks in the queue
  (priority, then age), published under the queue mutex after every change; `null` otherwise,
  and never persisted

### Live Configuration
- `Ketch` keeps the current `DownloadConfig`; `updateConfig` applies speed and queue limits
  immediately, other fields (default directory, connections, retries, intervals, buffer size)
  are snapshotted into `DownloadContext.config` when a download starts or resumes
- Sources read defaults from `DownloadContext.config` and `DownloadContext.effectiveConnections()`
- Per-task `setSpeedLimit` / `setConnections` / `setPriority` / `reschedule` persist to the
  `TaskRecord` in any non-terminal state and apply live when the task is running
- `setConnections(0)` is Auto: the run's `DownloadConfig.maxConnectionsPerDownload` (a torrent's
  own peer default); a running batch resegments when the effective count differs from the
  segments it runs

### Speed Limiting
- Global speed limit via `DownloadConfig.speedLimit`, changed at runtime with
  `KetchApi.updateConfig()`
- Per-task speed limit via `DownloadRequest.speedLimit` or `DownloadTask.setSpeedLimit()`;
  it caps the task in addition to the global limit (the lower rate wins)
- Token-bucket algorithm (`TokenBucket`) with delegating wrapper

### Download Scheduling (`DownloadScheduler`)
- `DownloadSchedule.Immediate`, `AtTime(Instant)`, `AfterDelay(Duration)`
- `DownloadCondition`: sealed; `DownloadCondition.Test(flow)` gates a task on a runtime
  `Flow<Boolean>` (e.g., WiFi-only)
- Reschedule support via `DownloadTask.reschedule()`

### Pluggable Download Sources (`DownloadSource`)
- `SourceResolver` routes URLs to the appropriate source
- `HttpDownloadSource` is the built-in HTTP/HTTPS implementation
- `HlsDownloadSource` (`library:hls`) and `DashDownloadSource` (`library:dash`) are optional
  sources, registered by the apps and CLI ahead of HTTP: finite, unencrypted `.m3u8` HLS and
  `.mpd` DASH streams are concatenated into one media file. HLS masters choose the
  highest-bandwidth variant; byte ranges and initialization segments are supported. Transfers
  are sequential and pause/retry restarts from zero. Live streams, encryption and separate
  audio/video tracks are rejected; see [media support and limits](docs/media.md)
- `FtpDownloadSource` handles FTP/FTPS with segmented parallel transfers
- `TorrentDownloadSource` handles BitTorrent/Magnet downloads
- Additional sources registered via `Ketch(additionalSources = listOf(...))`
- Each source defines: `type`, `canHandle()`, `resolve()`, `download()`, `resume()`,
  `buildResumeState()`; optional `canHandleContent()` / `resolveContent()` accept file content
  such as `.torrent` bytes (`KetchApi.resolveContent`), and `resolveForDownload()` lets a task
  wait where a preview (`resolve()`) would fail, as magnets whose peers are offline do
- `managesOwnFileIo` flag: when `true`, engine skips `FileAccessor` (used by torrent)

### Multi-File Download Support
- `ResolvedSource.files` lists selectable files within a source (e.g., torrent)
- `FileSelectionMode.MULTIPLE` for subset selection (torrent)
- `FileSelectionMode.SINGLE` for single-variant selection (reserved for HLS quality; no built-in
  source uses it yet)
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
- Extra trackers and daily-updated tracker lists, on by default (ngosang's `trackers_best.txt` and
  XIU2's `best.txt`, from jsDelivr when GitHub fails, with a copy of ngosang's shipped for before
  its first download) for public torrents
- Magnet metadata is asked of four peers at once, failed peers again after 5, 15 and 30 s; a task
  keeps looking (`DownloadSource.resolveForDownload`) until found or stopped, while previews
  give up after one `metadataTimeout`. The apps show it as Starting, "Finding peers"
- Verified selected-file storage, ownership journal, restart rehash, live limits and explicit
  seeding
- `TorrentConfig.listenPort` is the incoming TCP port; DHT binds the same port over UDP when it
  can (else any port), so one forwarded port reaches both. `0` lets the system pick at every
  start. Only `ketch server` sets one (`[torrent] listenPort`, `KETCH_TORRENT_PORT`,
  `--torrent-port`; 16881 in the Docker image): the apps and other commands may run beside it
- Apps open `.torrent` files from the system file manager (Android, desktop, iOS, installed web
  app) and resolve them through `KetchApi.resolveContent`, like dropped files
- Native torrent engine dependencies exist only in interoperability tests
- Public v2/hybrid download, selection, limits, pause/resume and TaskStore restart are implemented.
  V2 incoming routing, upload/seeding, PEX and hybrid v1-only peers remain roadmap work.
- See [support and migration](docs/torrent.md) and
  [verification](docs/development/torrent-verification.md)

### AI-Driven Resource Discovery (`ai:discover`) — In Progress
- LLM agent-driven discovery using Koog framework (v1.2.0)
- Providers are `LlmProvider` presets, data: id, `LlmApi` (OpenAI Responses, chat completions,
  Anthropic, Gemini, Ollama), endpoints per region, model suggestions, key variables and key
  page. OpenAI, Anthropic, Gemini, Ollama, LM Studio, hosted chat-completions services
  (OpenRouter, DeepSeek, xAI, Mistral, Groq, Together, Fireworks), ones in China (Zhipu,
  Moonshot, Alibaba Model Studio, Volcengine Ark, SiliconFlow, MiniMax) and any
  OpenAI-compatible endpoint; `LlmClientFactory` maps them to Koog clients by `LlmApi`. An
  unknown id loads as `OpenAiCompatible`. See [AI discovery](docs/ai-discovery.md#providers)
- Several providers are saved at once (`AiSettings.providers`, `[[ai.providers]]`, each an
  `LlmSettings` with a unique id, name, key, endpoint, its default `model` and the `models` the
  user added); `active` names the one in use. `ConfigStore.decode` loads an old `[ai.llm]` as
  one active entry (`AiSettings.migrated`). With none saved, `entries`/`llm` give a default
  OpenAI one with a blank id
- `LlmModelLister` lists a provider's models (`/models`, Anthropic's and Gemini's model lists,
  Ollama's tags) for the settings page (`AiSettingsController.loadModels`); errors never quote
  the provider's reply
- Configured under Settings → Discover and persisted under `[ai]` in
  `config.toml`; blank credentials of every saved provider fall back to its provider's
  environment variables (`LlmProvider.envKeys`)
- Switching the provider or model (`AiSettingsController.use`) applies to the next turn; running
  turns finish on the engine they started with, and each `DiscoverTurn` records its
  `TurnModel` (provider name and model id), saved in the history
- `AiSettings.enabled` defaults to on. The Discover destination, and every way into it (add
  sheet, palette, phone search, Find another source, launchpad), shows wherever discovery is
  supported while it is switched on (`AiSettingsController.offered`); until it is set up it shows
  a setup page, where searches wait, saved sessions open read-only, and Turn off Discover
  (`AppState.turnOffDiscover`, with Undo; also in the page's ⋯ menu) hides it. In the apps the
  switch is authoritative and env keys only fill blank credentials of the provider the settings
  name (`resolveAiSettingsFromEnv` with `autoConfigure = false`); only the CLI lets untouched
  settings take their providers from the environment (`[ai.access]` alone never counts as
  configured)
- Discover is a chat (`AiDiscoverController`, `DiscoverSession` of `DiscoverTurn`s): every
  entry point with a query starts a new session; a follow-up sends `DiscoverQuery.history`
  (each earlier request and its sites, results only from finished turns, the first turn plus
  the latest five, replayed with code-written replies) and the discarded links as
  `excludedUrls`. The agent answers `{summary, candidates}`; reasons, summaries, steps,
  candidate titles, descriptions and file names and source titles pass through
  `sanitizeAgentText` (one line of plain text; step details keep up to 12 lines), which the CLI
  applies again, as one line, when it prints
- `DiscoverQuery.userDevice` / `downloadDevice` (`DiscoverDevice`: the system and CPU a device
  reports) name the device the user searches from and the one that downloads, as hint lines in
  the request; the agent uses them only for software built per platform when the request names
  none. The apps pass the embedded device and the Discover target (else the active device) from
  `DevicePresence` (`AppState.discoverDevices`), read as each turn starts, so the composer shows
  the target chip (`DiscoverTargetChip`) on wider pages until the add bar does; the CLI passes
  this machine for both
- Discarding is per session, by `SiteNames.canonicalUrl`; discard, session delete and Clear
  history go through `PendingOps` (Undo). At most 3 turns run at once across sessions (the
  rest `Queued`), one per session; switching discovery off (provider `null`) stops them all,
  a new model lets them finish. Brave and Google searches start 1.1 s apart across runs
  (`PacedSearchProvider`)
- History: `DiscoverHistoryStore`; `FileDiscoverHistoryStore` writes `discover-history.json`
  (desktop: next to `config.toml`; Android: `filesDir`, excluded from backup and device
  transfer), newest 50 sessions, the last 30 steps per turn with URLs redacted, failures as
  `AiDiscoverFailure.brief` (no provider reason). Running turns load as `Stopped`
- Page access (`[ai.access]`: `allow`, `ask-site` default, `ask`, `trustedSites`;
  `PageAccessSettings.allowsWithoutAsking` is the one policy): `fetchPage`, `headUrl` and
  redirects to new hosts ask the run's `PageAccessApprover` after the allowlist and
  `UrlValidator.check` (no DNS: a host is looked up only once approved; `validateUrl` never
  resolves; result candidates are resolved to drop private addresses) and while budget is
  left, before spending any; a declined site is refused for the rest of the run. Sites are
  `SiteNames.normalize`d, which keeps `www.` before a shared suffix (`www.github.io`) so no
  answer covers a whole suffix. Only `DiscoverQuery.sites` skips asking (not `allowedDomains`);
  an approved page covers robots.txt on its host, and a robots.txt redirect to another host
  asks. Apps keep answers per session in memory, save Always allow with
  `AiSettingsController.saveAccess` (keeps the provider: `AiSettings.engineSettings` leaves
  `access` out) and show a toast and nav badge (`AppState.discoverWaitingCount`) while a request
  waits elsewhere. The CLI (`CliPageAccess`) asks on `/dev/tty` (Windows: `System.console()`,
  so not with redirected streams); `--yes` allows all; with no terminal a run that may ask
  exits 1, as does a failed run; invalid arguments exit 2
- The app page (`ui/discover`) renders a session as `threadItems()`, with approval cards inline
  (`approvalChoices(mode)`) and a "Needs your OK" jump button while one is out of view. Its
  history docks beside the chat on pages from 780 dp (`historyPlacement`, toggled by
  `UiPreferences.discoverHistory`), floats over narrower ones and is a sheet on phones;
  `DiscoverChrome` in `ShellState` holds the floating state and the composer focus requests
  (`⌘E`, New search). Its chords are `CommandScope.Discover`
- Provider defaults track current models; unknown ids resolve as custom
  Koog models, and `temperature` is only sent to models that accept it
- 7 agent tools: `searchWeb`, `searchSites`, `fetchPage`, `headUrl`,
  `extractDownloads`, `validateUrl`, `emitStep`
- The agent runs Koog's single-run loop with one change
  (`ResourceDiscoveryService.discoveryStrategy`): a reply with neither tool calls nor a JSON
  answer, such as a model narrating its next step, is answered with a reminder to call a tool or
  answer, at most twice a run, instead of ending the run with no results
- Content filter (`AiSettings.contentFilter`, `[ai] contentFilter`, on by default;
  `DiscoverQuery.contentFilter`, CLI `--no-filter`): `DeviceSafetyFilter` drops shorteners,
  aggregators, piracy signals, look-alikes of its trusted hosts, URLs with user info and
  installers from unlisted hosts without HTTPS (over HTTPS they are scored down). The system
  prompt follows it too (`ResourceDiscoveryService.systemPrompt`): only with the filter on is the
  agent told to refuse piracy and block risky links. `DiscoverResult.filtered` counts them, and the app's turn (`DiscoverTurn.filtered`, saved in the
  history) shows "N hidden by the content filter" or a No results card with Discover settings.
  Like `access`, it is left out of `AiSettings.engineSettings`
- SSRF protection on every redirect hop, device safety scoring, rate limiting; the
  fetcher also resolves hosts through the validator when it connects, so DNS
  rebinding cannot reach a private address
- `DiscoverQuery.sites` ("Limit to websites", CLI `--sites`) is a hard allowlist,
  subdomains included, capped by `DiscoveryConfig.allowedDomains`: the tools refuse
  other hosts and the output parser drops their candidates, but redirects a listed
  site answers with (e.g. github.com to its CDN) are followed
- JVM/Android only (uses Koog + Ktor's OkHttp client; the search and LLM clients take the app's
  default engine)
- The tools are `TextTool`s, described by hand rather than through Koog's reflective `ToolSet`,
  so the apps and the CLI leave kotlin-reflect out (see [app size](#app-size)). The Android app
  registers Koog's HTTP client factory under `META-INF/services` (Koog's Android AAR omits it)
- See [AI discovery configuration](docs/ai-discovery.md)

### Configuration (`config/`)
- TOML-based configuration via ktoml library
- `KetchConfig` root with server, download, remotes, AI, appearance, torrent, speed, UI,
  desktop, notifications and integration sections
- `AiSettings`: AI discovery's saved LLM providers (`[[ai.providers]]`, `active`), search keys
  and page access (`access`, `[ai.access]`)
- `AppearanceConfig`: accent palette, light/dark `ThemeMode` and the language chosen in
  Settings (app-only; CLI and server ignore it)
- `TorrentSettings`: `listenPort` (read by `ketch server` only), extra trackers for public
  torrents (`TorrentConfig.additionalTrackers`) and
  tracker list subscriptions, on by default (`trackerList`, `trackerListUrls`, default ngosang's
  and XIU2's best lists; `TorrentConfig.trackerListUrls`; a 0.3.0 `trackerListUrl` is still
  read), edited on the embedded instance's
  BitTorrent settings page and applied to torrents as they start or resume; a remote instance's
  trackers are only editable on that device
- `SpeedSettings`: the embedded device's speed mode (Full speed, Slow lane, Auto with weekly
  `SpeedRule`s), applied by the apps' `SpeedModeController`; `UiPreferences` (`[ui]`): view
  state such as table columns, sort, sidebar, inspector, density, per-device add sheet defaults,
  Discover's docked history (`discoverHistory`) and onboarding; `DesktopSettings`: close action,
  open at login, Dock badge, daily update checks; `NotificationSettings` and
  `IntegrationSettings` (magnet and `.torrent` handlers)
- Apps edit it in Settings, `SettingsCategory` pages in two groups: *This app* (General,
  Notifications, Integration, Discover, About) and *Device* (Downloads, Speed, Network,
  BitTorrent, Sharing). Desktop opens Settings in a window of its own (⌘, / Ctrl+,), wider
  windows elsewhere show it in place of the content card and phones as a page. Changes apply as
  they are made; there are no Save buttons
- The Device pages edit the device chosen in Settings, by default the one shown, through its
  `InstanceSettingsController` (`AppState.settingsFor`): pushed live via
  `KetchApi.updateConfig` / `updateNetworkInterfaces`. Downloads settings are saved to
  `config.toml` only for the embedded instance; the network selection is runtime-only and
  never saved
- `ServerConfig`: host, port, API token, CORS, `allowedHosts`, `allowedDirectories`, mDNS,
  `autoStart` (apps start the server on launch)
- `RemoteConfig`: pre-configured remote server connections, with the system each device last
  reported, which picks its `DeviceType` glyph in the apps' pennants
- `defaultConfigDir()` (JVM: desktop app and CLI) is `KETCH_CONFIG_DIR` when set (`/config` in
  the Docker image), else the platform's directory
- `FileConfigStore`: platform-specific file persistence via okio; on the JVM a leading `~` in
  `download.defaultDirectory` expands to the home directory when the file is loaded. The web app
  uses `WebConfigStore` (TOML in localStorage)
- A `config.toml` that does not parse, or holds a value no setting takes, makes `load()` throw
  unless the store has an `onUnreadable` callback: the apps pass one, so the file moves aside to
  `config.toml.broken-<UTC time>` and they start with the defaults. They report it, and a
  database moved aside, through `UnreadableFiles`, which the app shows once as a sticky warning.
  The CLI never moves the file it shares with the desktop app: `ketch server` refuses to start
  (defaults would serve on every interface without the token), `ketch mcp` and `ai-discover`
  warn on stderr and use the defaults, and an unreadable `--config` file stops `ketch mcp`

### Apps (`app/`)
- One Compose Multiplatform UI (`app:shared`) for Android, desktop, iOS and the web, laid out
  by window width (`KetchLayout`): a 220 dp sidebar on wide windows (collapsible to the rail),
  a 72 dp rail on medium ones, and on phones a top bar, the Add button and, with three
  destinations or more, a bottom bar. Destinations: Downloads, Discover (where supported) and
  Devices; the downloads list shows as a table or as list rows, with a task inspector
- Devices are first class: the sidebar and rail list every device with its live line, health
  and failures, plus All devices (`⌘⌥0`, `AppState.showAllDevices`), which lists every
  device's tasks with a Device column. `⌘⌥1`-`9` switch, `⇧⌘D` opens the device switcher
  (a sheet on phones); rows can be sent or moved to another device, and links dropped on a
  device row, a rail pennant or a drop berth are added there. The Devices page shows a card per
  device; the Add device sheet and the connect page (shown while no device is active, as on the
  web) take a pairing link, an address or a device found on the network. A found device that
  advertises `pairing=1` is asked instead of typing its code (`ConnectForm.pair`,
  `RemotePairing`): both devices show the same four digits, and the shared device asks its
  owner in `PairingApprovalHost`, from the tray (desktop) or with Allow and Don't allow
  notification buttons (Android, `AndroidNotifier.CHANNEL_PAIRING`) while it is not in front
- Every keyboard command lives in `KetchCommands`, each in a `CommandScope` (Global, List,
  Intake, Palette, Discover): `ShortcutHost` runs the global chords, and the list, the add
  sheet, the palette and the Discover page match their own with a `ShortcutMatcher`. `⌘K`
  opens the command palette and `⌘/` the shortcut sheet, grouped by scope (Discover's only where
  Discover is offered). Commands the window's shell must run from outside it, such as the macOS
  menu bar's, go through `AppState.runInShell`
- Toasts and banners go through `MessageCenter`; removals and other undoable operations wait in
  `PendingOps` for their Undo window. A message posted with `notify = true` (Discover's page
  access questions) also becomes a system notification, once, while the app is not in front or
  when it leaves the front while the message shows, whatever the download notification settings
  say (`MessageNotifications`, posted by the desktop `TrayNotifier` and Android
  `AndroidNotifier`); download events are notified by the hosts' `ActivityMonitor` routing
  instead. On Android, `ForegroundPolicy` keeps the service in the foreground while Discover
  searches, so a search keeps running and can ask once the app is in the background
- Success feedback (`SuccessFeedback`, `[notifications] successSound` / `successVibration`): a
  synthesized soft `Chime` and, on Android, a short vibration when a download finishes and is
  reported (`QueueDrained` included), or a Discover turn ends with results
  (`AiDiscoverController.found`), at most once per 1.5 s. Hosts play it through a
  `SuccessFeedbackPlayer` (`DesktopFeedbackPlayer`, `AndroidFeedbackPlayer`, which follows silent
  mode and Do Not Disturb); on Android a finished download posted as a notification leaves the
  alert to its channel. iOS and the web play none
- Task states: `waitsInQueue` and `isPausedUntilResumed` (`state/TaskStates.kt`) decide
  everywhere that a task paused for an urgent download counts as waiting (Waiting tab, Start
  now, Pause all) rather than paused. Rows say why the engine paused a task, where a queued one
  waits ("next in line", "2 ahead") and when a finished one finished (Finished column and sort,
  Smart "Finished today" groups). Auto connections and queue positions only show for devices
  whose `KetchStatus.features` list them (`AppState.featuresOf`; the embedded engine has all)
- Design tokens: feature code reads colors, type, spacing, shapes and motion from `KetchTheme`
  and uses the controls in `components/` and `KetchIcon`. `DesignTokenUsageTest` fails when
  code outside `theme/` and `components/` adds literal radii, colors or text sizes,
  `MaterialTheme.` or Material icons; keep its allowlist (`design-token-allowlist.txt`) empty.
  Composables read the time from `LocalClock`, never `Clock.System`
- Localization: the apps follow the system language, or the one chosen in Settings → General
  (`AppLanguages`; Android 13+ and iOS keep it in their per-app setting), and fall back to
  English; the languages are listed in
  [localization](docs/development/localization.md#languages). UI text
  lives in `composeResources/values/strings_<area>.xml`; state and model code returns `UiText`
  (`i18n/UiText.kt`), which composables `resolve()` and coroutines `load()`, and sizes, speeds,
  durations and dates come from `i18n/Formats.kt`. Never put a `UiText` in a string template: it
  prints `⟦key⟧`. `HardcodedTextTest` counts the literals left per file
  (`hardcoded-text-allowlist.txt`, which only shrinks) and `LocalizationResourcesTest` checks the
  translations against English
- Desktop: closing the window follows `[desktop] closeAction` (asks the first time while
  downloads run, then keeps Ketch in the menu bar or notification area; minimizes where there
  is no tray). On macOS Ketch leaves the Dock while none of its windows is open (`MacDock`, the
  activation policy through JNA) and comes back with the window. The main window exists only
  while it shows: hidden, it is disposed with its GPU
  memory, and its content comes back with what it saves (`rememberSaveable`, kept in a
  `SaveableStateHolder` outside the window), such as the page, the filter and scroll
  positions. The tray lists every device with its own actions, and the
  macOS menu bar, the tray and the Dock menu are generated from `KetchCommands`
  (`DesktopMenuBar`, `DesktopTray`, `TaskbarFeedback` for the Dock and taskbar badge and
  progress)
- Self-update (Android): `direct` and `play` distribution flavors; only `direct` supplies
  `AndroidUpdater` through `LocalAppUpdates` in Settings → About. It reuses `GitHubReleases`
  and `ReleaseDownloader`, checks the APK package, release version and increasing version code,
  and hands installation to Android through a private activity and FileProvider. Only this
  flavor declares `REQUEST_INSTALL_PACKAGES`. Release builds check daily while the main screen's
  model lives, using the existing `[desktop] checkForUpdates`; debug builds only check manually.
  GitHub ships `assembleDirectRelease`; Play uses `bundlePlayRelease`. See [updates](docs/updates.md).
- Self-update (desktop): `DesktopUpdater` implements the shared `AppUpdates`
  (`LocalAppUpdates`, shown in Settings → About and the macOS Help menu); it checks GitHub daily
  while `[desktop] checkForUpdates` is on, downloads this system's installer with the `updater`
  module, and `UpdateInstaller` replaces the app once it quits (macOS bundle swap, `msiexec`,
  `pkexec dpkg -i`) and opens it again. The installers carry a monotonic numeric version
  (`installerVersion()` in `app/desktop/build.gradle.kts`) and the MSI a pinned `upgradeUuid`,
  so Windows Installer upgrades; see [updates](docs/updates.md)
- Portable Windows app (`packageReleasePortableZip`, released as `…-windows-<arch>-portable.zip`):
  a `data` folder beside `Ketch.exe` (`PortableApp`) keeps everything the app writes there
  instead of `%APPDATA%\ketch`, and `WindowsPortable` updates it by replacing its own files with
  those of the new `-portable.zip`
- Phones: a welcome flow on first launch (`ui/onboarding`); Android shows a splash while its
  service binds, and iOS 26 keeps user-started downloads running in the background with
  `BGContinuedProcessingTaskRequest`
- UI snapshots: `./gradlew :app:shared:jvmTest -Psnapshots` renders the scenarios in
  `app/shared/src/jvmTest/.../app/snapshot/` headlessly to `app/shared/build/snapshots/`; see
  [testing](docs/development/testing.md#ui-snapshots)

### Daemon Server (`library:server`)
- Ktor-based REST API (`library:endpoints`): create, list, pause, resume, cancel, remove tasks;
  per-task speed limit, priority and connections (0 is Auto; older servers answer 400
  `invalid_connections`, which `RemoteDownloadTask` turns into `UnsupportedOperationException`);
  status (with `KetchStatus.features`), config, network interfaces, and resolving URLs or
  uploaded file content
- SSE event stream for real-time state updates; `TaskSnapshot` and `state_changed` carry the
  task's `queuePosition` (a queue change sends `state_changed` for every waiting task whose
  position moved), `progress` carries none
- Bearer-token auth (`ServerConfig.apiToken`), CORS and mDNS advertising (`_ketch._tcp`).
  `KetchServer` binds to `127.0.0.1` by default and logs a warning when it listens elsewhere
  without a token. Tokens are compared in constant time (SHA-256 digests,
  `MessageDigest.isEqual`); an address that sends ten wrong tokens within a minute gets 429
  `too_many_attempts` for any token until the minute ends (`AuthThrottle`)
- `ketch server` listening beyond loopback always has a token: `--token`, `KETCH_API_TOKEN`,
  `apiToken`, or one it creates in the owner-only `api-token` file of the config directory
  (`ServerToken`) and prints once; `--no-token` opts out with a loud warning. With a token and
  no `corsAllowedHosts` it allows any origin, like the apps
- Folders (`DestinationGuard` over core's `DestinationPathPolicy`): without a token, and with
  one when `allowedDirectories` is set, callers are kept to the download directory and
  `ServerConfig.allowedDirectories` for a new task's destination (relative paths rebased on the
  download directory, file names through `sanitizeFileName()`, existing or reserved paths never
  reused), a resume's `destination`, `PUT /api/config`'s `defaultDirectory` and
  `deleteFiles=true` (checked against `DownloadTask.outputPath`); anything else is 403
  `path_rejected`. With a token and no list, callers may save anywhere, as the apps let owners
  type any folder on a remote device
- JSON bodies are read with `receiveJson` (`application/json`, else 415): 1 MiB, 32 MiB for
  `POST /api/tasks`, 16 MiB for uploaded content, 4 KiB for pairing; longer is 413
  `payload_too_large`
- Without an API token, `HostValidator` answers 403 to requests whose `Host` is not a loopback
  name, an interface IP, the machine's host name or `<host>.local`, or in `allowedHosts`
  (DNS rebinding protection); with a token any `Host` is accepted
- Without an API token, refuses requests from web pages on other origins (403
  `origin_not_allowed`) and ignores `corsAllowedHosts`; its own web UI, browser extensions and
  non-browser clients pass. With a token, `corsAllowedHosts` grants CORS (`host[:port]` for
  both schemes, `scheme://host[:port]`, or `*`), and the apps default it to `*` so the hosted
  web app can connect
- Pairing: with a token and a `PairingApprover`, `POST /api/pairing` (no token) asks the owner
  for the token and `GET /api/pairing/{id}` returns it once allowed; requests expire after two
  minutes, one per address and four in all wait, and web pages (`Origin` http/https/null) are
  refused because CORS may admit them with a token. mDNS TXT adds `pairing=1`. The apps pass
  an approver backed by `PairingRequests`; the CLI passes none
- Remote backend (`RemoteKetch`) communicates via HTTP + SSE. Calls the server refuses throw
  `RemoteApiException` with the status, the `ErrorResponse` code and message (the HTTP status
  without one) and `Retry-After`, unless `KetchApi` names another exception for the case. The
  apps show the server's message, except for `path_rejected`, which their own text explains
- Auto-reconnection with exponential backoff
- `ketch server` starts listening, then restores the tasks saved in `ketch.db`, so a daemon that
  cannot bind never resumes them; a failed restore stops the server
- Health: `GET /api/health` (`Api.Health`, no token) answers `200 {"status":"ready"}` once the
  tasks are restored and `503 {"status":"starting"}` before. `KetchServer(ready = false)` plus
  `markReady()`, which `serveDaemon` calls after `KetchApi.start()`; other embedders are ready
  from the start. `ketch health` asks it (0 ready, 1 not, 2 usage), finding the port through the
  same config file and environment variables, and loopback for a server on every interface
- `ketch server` configuration layers: flags over `KETCH_*` environment variables
  (`ServerEnv`, `applyServerEnvironment`; blank counts as unset, an unusable value exits 2 naming
  it) over `config.toml`. It exits 1 when it cannot start and 2 for invalid options

### Native CLI (`cli/`)
- Released as a GraalVM native binary; reflection and resource metadata lives in
  `META-INF/native-image/<module>/` of the module that needs it (`cli`, `library:mcp` for the MCP
  SDK, `ai:discover` for Koog's clients). The binary has no kotlin-reflect. It is built with
  `-Os` on GraalVM for JDK 23 and later and `-Ob` before (smaller than the default `-O2`), and
  bundles the web UI with its wasm and JS gzipped, which `KetchServer` sends compressed to
  browsers that accept gzip
- Koog's Anthropic, Gemini and OpenAI Responses clients have Ktor find their request and response
  serializers by class, and Gemini parts and Responses items use content-polymorphic serializers,
  so `ai:discover` registers those classes too; the Ollama and chat-completions clients do not
- `NativeImageConfigTest` (in `cli`, `library:mcp` and `ai:discover`) checks that the metadata
  names existing classes and covers every serializable MCP SDK type and every subtype of Koog's
  content-polymorphic types; build with
  `./gradlew :cli:nativeCompile` and exercise `ketch mcp` and `ketch ai-discover` with each LLM
  provider to verify changes

### Docker image (`docker/`)
- `docker/Dockerfile` puts the native Linux `ketch` of each architecture
  (`docker/build/linux-<amd64|arm64>/`) on `debian:trixie-slim`; `ENV` sets `KETCH_DOCKER=1`
  (`ketch update` refuses and points to the image), `KETCH_CONFIG_DIR=/config`,
  `KETCH_DOWNLOAD_DIR=/downloads`, `KETCH_TORRENT_PORT=16881`, `PUID`/`PGID` 1000 and `UMASK`;
  `HEALTHCHECK` runs `ketch health`
- `docker/entrypoint.sh` (POSIX sh): options alone go to `ketch server`, other programs run as
  they are. As root it applies `UMASK`, adds passwd/group entries for `PUID`:`PGID` (home
  `/config`), gives every file in `/config` to them, takes over `/downloads` only when it is empty
  and root-owned (else warns), then `setpriv`s; started as another user (`user:`) it changes
  nothing. `docker/test-entrypoint.sh` tests it around a stub binary (CI `docker-tests`, with
  shellcheck), `docker/smoke-test.sh` a built image
- The native binary is built with `--install-exit-handlers`: as PID 1 it would otherwise ignore
  `docker stop`'s SIGTERM and skip the shutdown hooks
- The release workflow's `publish-docker` job unpacks the Linux CLI archives (`upx -d`: a UPX
  binary is unpacked into memory at every start), smoke-tests both architectures (arm64 under
  QEMU) and pushes `linux/amd64,linux/arm64` to `ghcr.io/<owner>/ketch`, and to Docker Hub
  (`DOCKERHUB_IMAGE`, default `linroid/ketch`) when the `DOCKERHUB_*` secrets are set. Tags:
  `X.Y.Z` always, `X.Y` and `latest` for stable releases
- `docker/compose.yaml`, `truenas.yaml` and `fnos.yaml` are the templates
  [Docker](docs/docker.md) ([中文](docs/docker.zh-CN.md)) walks through

### Self-update (`updater`)
- Shared by desktop, direct Android and the CLI: `GitHubReleases` reads the latest (or a tagged)
  release from the GitHub API, `Release.asset` / `androidAsset` pick the file by the workflow's
  names, and
  `ReleaseDownloader` downloads it with a private Ketch engine and checks the SHA-256 digest
  GitHub publishes per asset (files without one are refused). Pass it the process's logger:
  every `Ketch` installs its logger globally
- `ReleaseVersion` orders `rc9` before `rc15` and pre-releases before their release
- `ketch update [--check] [--version <v>]` replaces the native binary (`CliInstallation`): renamed
  over the old one on macOS/Linux, the old one set aside as `ketch.exe.old` on Windows; refuses
  on a JVM. See [updates](docs/updates.md)

### MCP Server (`library:mcp`)
- `KetchMcpServer` exposes any `KetchApi` over stdio or SSE through Koog's MCP server bridge
- `KetchToolSet` provides 12 tools: list/get/start/pause/resume/cancel/remove downloads,
  `resolveUrl`, `getStatus`, `setSpeedLimit`, `setPriority`, `updateConfig`; downloads it starts
  carry `DownloadRequest.properties["ketch.origin"] = "agent"`
- Agent tools are `TextTool`s (`library:mcp` and `ai:discover` each have a copy): a name, a
  description and string or integer parameters, required unless the tool has a default, written
  out by hand and called with the arguments by name. They need no kotlin-reflect, unlike Koog's
  `ToolSet`, and pass `String` results on as they are, so tools return their JSON as text. A
  missing or malformed argument is a `ToolException.ValidationFailure`
- `ketch mcp` runs it on stdio against a local engine. It passes the real stdout to
  `startStdio` and redirects `System.out` to stderr, so the banner, the console logger and
  Logback never corrupt the JSON-RPC stream
- Stdio uses Ketch's own `StdioTransport`: when stdin ends it answers the requests already read,
  then closes, and `startStdio` returns (`Server.onClose` only fires on `Server.close()`, so it
  waits for the transport). The SDK's `StdioServerTransport` drops those replies. `ketch mcp`
  then calls `exitProcess`, so a non-daemon thread cannot keep it alive; its shutdown hook closes
  `Ketch`
- Stdio builds its SDK `Server` itself (adding tools with Koog's `addTool`) without
  `tools.listChanged`. With Koog's `configureMcpServer`, which announces it, the SDK sent
  `notifications/tools/list_changed` for the tools it registered to a session starting in the same
  millisecond, at times after the session closed, and threw "Not connected". SSE still uses Koog's
  `startMcpServer`, whose sessions start later

### Browser Extension (`app/browser-extension`)
- Manifest V3 extension for Chromium browsers, Firefox and a limited Safari target; plain JavaScript modules with no
  dependencies. `src/` loads unpacked in Chromium, its `key` pinning the development id;
  `node build.mjs` writes `build/chrome` (no `key`: the Chrome Web Store package, item
  `flnjeochbgpaipiofdjmoijaeooemhka`), `build/firefox` (event page instead of service worker,
  gecko id) and zips. It is released apart from the apps, by running `extension-release.yml` by hand: the
  version is the latest app tag plus the run number (`0.3.1.7`, shown as `0.3.1`), tagged
  `extension-v0.3.1.7`, and the GitHub release is never marked latest (the updaters read the
  latest release).
  `build/safari` reaches configured servers only, with mandatory review and no download capture,
  native app launching or notifications. `npm run safari` generates an unsigned macOS Xcode host
  project; signing and Safari enablement are separate local/distribution steps
- Talks to the daemon REST API (`/api/tasks`, task pause/resume, `/api/resolve/content`,
  `/api/status`) of one or more instances: the Ketch app on this computer and servers
  (`ketch server`, other devices), each with an optional bearer token. Captured downloads and
  magnets go to the default one
- The Ketch desktop app is reached through the native messaging host `com.linroid.ketch`, its own
  launcher run with `--native-messaging-host` (`app/desktop`: `NativeMessagingHost`,
  `NativeHostRegistration`, `BrowserExtensionServer`). The host asks the running app over
  `SingleInstance`, opening it if needed, for a loopback-only `KetchServer` on a free port with a
  per-run token, separate from the Settings server. The app registers the host with installed
  browsers on every launch; the Chromium extension id is pinned by the manifest `key`. The
  browser reads the host's stdout as length-prefixed messages, so nothing else may write there:
  the launcher sends the JVM's own warnings to stderr (`-Xlog` in `app/desktop/build.gradle.kts`)
- Captures browser downloads (Chromium holds them in `onDeterminingFilename`, Firefox pauses
  them) and falls back to the browser when Ketch fails; context menus per instance; a content
  script sends trusted magnet link clicks
- Forwards cookies, referrer and user agent as `DownloadRequest.headers`; `.torrent` downloads
  whose URL Ketch would not recognize are fetched and resolved via `resolveContent`
- Tags every request with `DownloadRequest.properties["ketch.origin"] = "browser"`. The apps
  read this origin (`browser`, `discover`, `agent`, `app` or `cli`, set by whoever adds the
  download) for the Origin search facet; it never goes to `resolve()`, whose properties are headers
- Unit tests run with `node --test` in `app/browser-extension`; see its README

### Logging System
- `Logger.None` (default, zero overhead), `Logger.console()`, `KermitLogger`
- Platform-specific console: Logcat (Android); timestamped println elsewhere, with errors on
  stderr on the JVM (`FormattedConsoleLogger`)
- Apps log at debug; the desktop app reads `KETCH_LOG_LEVEL`, the CLI `-v`/`--debug`
- Desktop, Android and iOS apps also write `logs/ketch.log` in their data directory
  (`FileLogger` in `app/shared`, rotated at 5 MiB, 3 files kept, combined with the console via
  `Logger.combine`); Settings → About opens the folder (desktop) or shares a copy (phones)
- The desktop app logs uncaught exceptions on any thread there, and quits with status 1 when
  `application {}` fails, rather than linger without a window holding the single-instance lock
- `Ketch` logs every task state transition; torrent swarms log a debug summary every 30s
- See [logging](docs/logging.md) for the format, troubleshooting and sensitive-data rules
- `KetchLogger` uses `inline` functions with `Logger.None` fast-path for zero-cost disabled logging
- `Logger` interface accepts `String` messages; lazy evaluation handled by `KetchLogger`

### Error Handling (sealed `KetchError`)
- `Network` (retryable), `Http(code)` (5xx/429 retryable), `Disk`, `Unsupported`,
  `FileChanged`, `CorruptResumeState`, `Canceled`, `SourceError`,
  `AuthenticationFailed`, `Unknown`
- I/O exceptions from `FileAccessor` classified as `KetchError.Disk`

### App Size
- Release apps and the CLI leave out kotlin-reflect (`exclude` on the runtime classpath of
  `app:android` release builds, `app:desktop` and `cli`): Koog, kotlinx-schema and Ktor's server
  depend on it only for reflective tool sets and schemas and loading server modules by name.
  Code they ship must not need it, so tools are `TextTool`s
- Desktop packages keep only the build host's native libraries of sqlite-jdbc, Skiko and JNA
  (`StripForeignNatives` in `app/desktop/build.gradle.kts`)
- Desktop app images carry no class data sharing archive: JDK 21 rejects one whose jars are not at
  their build-machine paths, and jpackage's packages reset the jars' modification times, which
  JDK 25 still checks; see [testing](docs/development/testing.md#desktop-startup-memory)
- Desktop release builds are obfuscated by ProGuard. A generated rules file (`proguardRules`) adds
  the rules libraries ship in `META-INF/proguard`, keeps the names of `META-INF/services`
  interfaces and writes `build/outputs/proguard/mapping.txt`, which the release workflow
  publishes as `ketch-desktop-<version>-<os>-<arch>-mapping.zip`. `app/desktop/proguard-rules.pro`
  keeps volatile fields (atomic field updaters find them by name), line numbers, the
  `InnerClasses` attribute (`simpleName` of nested classes) and the names of `api` classes and
  exceptions, which the logs print

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
- `FileAccessor` (`createFileAccessor()`): okio `FileHandle` via `PathFileAccessor` on every
  engine platform; `ContentUriFileAccessor` for Android SAF; `NoOpFileAccessor` for
  self-managed sources. Accessors serialize I/O with `limitedParallelism(1)`
- `platformFileSystem`: okio `FileSystem.SYSTEM` on Android/JVM/iOS, `NodeJsFileSystem` on JS,
  `WasiFileSystem` on WasmWasi; the `config` module's copy throws on WasmJs
- `KetchDispatchers` defaults: dedicated `ketch-main`/`ketch-network`/`ketch-io` threads on
  Android/JVM/iOS, `Dispatchers.Default` on JS/WasmWasi
- Console logger: platform-specific implementations

### Coroutine-Based Concurrency
- `KetchDispatchers.main` (single-threaded) serializes task coordination; `network` runs
  transfers and `io` blocking file I/O
- Segments run as `async` children of one `coroutineScope`: a failed segment cancels the batch
  and the retry continues from saved segment progress; a connection change cancels and
  resegments it
- Structured concurrency for cleanup on cancel/pause

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
- Fake `HttpEngine` (`FakeHttpEngine` in core `commonTest`) for testing without network
- Test edge cases: 0-byte files, 1-byte files, uneven segment splits

### Logging
- Use `KetchLogger` for all internal logging — instantiate per component:
  `private val log = KetchLogger("Coordinator")`
- Tags: "Ketch", "DownloadTask", "Coordinator", "Execution", "SegmentCalc", "SegmentDownloader",
  "RangeDetector", "FileAccessor", "FileNameResolver", "KtorHttpEngine", "NetworkHttpEngine",
  "DownloadQueue", "DownloadScheduler", "SourceResolver", "HttpSource", "FtpSource",
  "FtpClient", "TorrentSource", "TorrentEngine", "TorrentSession", "TorrentSwarm",
  "TorrentTracker", "TrackerList", "RemoteKetch", "RemoteTask", "RemotePairing", "TokenBucket", "SqliteStore",
  "SqliteDriver", "ConfigStore", "KetchServer", "ServerRoutes", "DownloadRoutes", "EventRoutes",
  "Pairing", "McpStdio", "GitHubReleases"; `ai:discover`, mDNS
  and app code tag by component name (e.g. "DiscoveryService", "KetchService")
- Levels: verbose (speed limiter waits and per-peer detail), debug (internal operations and
  segment start/finish), info (user events and state transitions), warn (retries, recoverable
  problems), error (fatal)
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

1. No engine in the browser: `library:core` has no WasmJs target, so the web app uses
   `RemoteKetch` (and `WebConfigStore`; `FileConfigStore` throws on WasmJs). The core JS
   (Node.js) and WasmWasi targets have file I/O, but `library:ktor` ships no `HttpEngine` there.
2. iOS support is best-effort via expect/actual (iosArm64 + iosSimulatorArm64)
3. `library:sqlite` supports Android, iOS and JVM only -- use `InMemoryTaskStore` elsewhere
4. `library:ftp` does not support JS/Wasm (requires raw TCP sockets)
5. `library:torrent` has no browser-local engine. V2/hybrid uses outgoing v2 TCP;
   v2 incoming/upload/seeding/PEX, hybrid v1-only peers, uTP and encryption remain unimplemented.
6. FTPS (FTP over TLS) only works on JVM/Android; iOS throws `KetchError.Unsupported`
   (blocked by [KTOR-7475](https://youtrack.jetbrains.com/issue/KTOR-7475))
7. `ai:discover` is JVM/Android only (depends on Koog + Ktor CIO/OkHttp); iOS and
   the web app report AI discovery as unavailable
8. AI API tokens are stored in plain text in `config.toml`, like the server
   `apiToken`; use environment variables on shared machines

## Roadmap

Planned features not yet implemented:

1. **Metalink** - Multi-source downloads with mirrors, checksums, and chunk verification
2. **WebDAV** - Download from WebDAV servers with resume support
3. **More HLS and DASH formats** - Extend the optional HLS/DASH sources with variant selection
   (the reserved `FileSelectionMode.SINGLE`), matching separate audio/video tracks and AES-128
   HLS encryption. Finite, unencrypted single-stream playlists and byte ranges already work
4. **Media Downloads** - Web media extraction (like yt-dlp) as a pluggable `DownloadSource`,
   supporting various media sites and extractors
5. **Resource Sniffer** - Detect and extract downloadable resources (media, files) from
   web pages by analyzing network requests, HTML, and embedded players
6. **Checksums** - `DownloadRequest` carries an expected hash, checked once an HTTP or FTP
   download completes, with a server-published `Digest` / `Repr-Digest` as the fallback; a
   mismatch fails the task with a typed error. Pure Kotlin on every target, settable from the
   apps, the CLI (`--checksum`), REST and MCP. Today only `library:torrent` and `updater` hash
   content; Metalink and mirrors build on this
7. **Proxy** - HTTP(S) and SOCKS5 proxies with credentials and a bypass list, or the system
   proxy, set in `DownloadConfig` / `[download]` with a per-download override and applied by
   `KtorHttpEngine`, including the per-interface engines, which force `NO_PROXY` today. Today
   CIO on the JVM honors only JVM proxy properties; the OS proxy and `HTTPS_PROXY` are ignored
8. **Work-stealing segments** - The `LaneScheduler` / `RangeLedger` from the helper devices
   [proposal](docs/design/multi-instance-downloads.md), shipped on its own first: connections
   that finish claim the remaining bytes of slower ones, slow tails split, a failed range retries
   alone instead of cancelling the batch, and a minimum segment size keeps small files whole
9. **Timeouts and retry policy** - `DownloadConfig` gains an idle (no data) timeout, a minimum
   speed, unlimited retries and a capped, jittered backoff that resets once bytes are saved; a
   watchdog around HTTP segments and FTP transfers raises a retryable `KetchError.Network`.
   Today socket and request timeouts are `Long.MAX_VALUE`, so a stalled connection hangs until
   the task is paused, and the backoff (`retryDelayMs * (1 shl n)`) has no cap
10. **Temporary file names** - HTTP and FTP downloads write under a temporary name and are
    renamed to the final, deduplicated name after the last flush, never replacing a file that
    appeared meanwhile. Today they write to the final name, preallocated to full size, so an
    unfinished file looks complete
11. **Category folders** - Rules (by extension, MIME type or host) choose the folder under the
    default directory for downloads without an explicit destination, applied in the engine so
    every client, the server and the browser extension get them
12. **Torrent file selection after adding** - A magnet added without a selection can wait, with
    its metadata, until files are chosen, and a running torrent's selection can change, through
    `KetchApi`, the REST API and MCP. Today a selection can only be given up front
    (`DownloadRequest.selectedFileIds`, after a resolve), and a magnet added without one, as the
    extension, CLI and MCP always do, downloads every file
13. **Power options** - The apps keep the system awake while downloads run (an IOKit assertion
    on macOS, `SetThreadExecutionState` on Windows, a logind inhibitor on Linux, a partial
    `WakeLock` on Android), driven by the existing busy signal (`ForegroundPolicy`), and can
    quit, sleep or shut down once the queue is empty
14. **Automation hooks** - Task lifecycle events (added, completed, failed) run a configured
    command or `POST` a webhook from the embedded engine or `ketch server`, set in `config.toml`
15. **CLI for running instances** - `ketch` commands (add, list, pause, resume, watch as NDJSON)
    and `ketch mcp` attach through `RemoteKetch` to the running desktop app or a server instead
    of opening `ketch.db` with a second engine, and an idempotency key keeps retried submissions
    from creating duplicates. Today `ketch mcp` and `ketch server` can open the desktop app's
    `ketch.db` while it runs, and both engines resume the same tasks
16. **Cross-device Task Transfer** - Send to / Move to carry a task's downloaded data (partial
    bytes, segment progress, resume state, finished files) between instances, so the destination
    continues instead of starting over; see the [plan](docs/plans/task-transfer.md)
17. **Helper devices** - Paired Ketch instances relay byte ranges of one download through their
    own network and IP, joining or leaving mid-download without pausing it; see the
    [proposal](docs/design/multi-instance-downloads.md). Its scheduler ships first, as item 8
