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
  core/       # In-process download engine (Android, iOS, JVM, JS/Node, WasmWasi) -- published
  ktor/       # Ktor-based HttpEngine implementation (Android, iOS, JVM) -- published SDK module
  ftp/        # FTP/FTPS DownloadSource (Android, iOS, JVM only) -- published SDK module
  torrent/    # BitTorrent/Magnet DownloadSource (Android, JVM, iOS) -- published SDK module
  kermit/     # Optional Kermit logging integration -- published SDK module
  sqlite/     # SQLite-backed TaskStore (Android, iOS, JVM only) -- published SDK module
  remote/     # Remote KetchApi client (HTTP + SSE) -- published SDK module
  endpoints/  # Shared REST API endpoint definitions (Ktor Resources) -- published SDK module
  server/     # Ktor-based daemon server with REST API, SSE events and mDNS (JVM only)
  mcp/        # MCP server exposing KetchApi as tools for AI agents (JVM only)
config/       # Multiplatform TOML-based configuration (server, download, remotes, AI, ...)
ai/
  discover/   # LLM agent-driven resource discovery (JVM only, Koog framework)
app/
  shared/     # Shared Compose Multiplatform UI (supports Core + Remote backends)
  android/    # Android app
  desktop/    # Desktop (JVM) app
  web/        # Wasm browser app
  ios/        # Native iOS app (Xcode project, consumes shared module)
  browser-extension/  # Chromium/Firefox extension that sends downloads to Ketch (plain JS)
cli/          # CLI: downloads plus `server`, `mcp` and `ai-discover` (JVM; GraalVM native releases)
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
  `RangeSupportDetector`, `ServerInfo`, `DownloadSource`, `HttpDownloadSource`, `SourceResolver`,
  `SourceResumeState`, `DownloadContext`, `DownloadQueue`, `DownloadScheduler`,
  `SpeedLimiter`, `TokenBucket`, `DelegatingSpeedLimiter`, `MultiNetworkHttpEngine`,
  `ConfigurableNetworkHttpEngine`, `NetworkInterfaceProvider`
- `com.linroid.ketch.core.segment` -- `SegmentCalculator`, `SegmentDownloader`,
  `SegmentedDownloadHelper`
- `com.linroid.ketch.core.file` -- `FileAccessor`, `createFileAccessor()` (expect/actual),
  `PathFileAccessor`, `ContentUriFileAccessor` (Android), `NoOpFileAccessor`,
  `platformFileSystem` (expect/actual), `FileNameResolver`, `DefaultFileNameResolver`
- `com.linroid.ketch.core.task` -- `RealDownloadTask`, `TaskHandle`, `TaskController`,
  `TaskStore`, `InMemoryTaskStore`, `TaskRecord`, `TaskState`

### `library:ktor`, `library:kermit`, `library:sqlite`
- `com.linroid.ketch.engine` -- `KtorHttpEngine` (`withNetworkInterfaces()` on Android/JVM)
- `com.linroid.ketch.log` -- `KermitLogger`
- `com.linroid.ketch.sqlite` -- `SqliteTaskStore`, `DriverFactory` (expect/actual)

### `library:ftp`
- `com.linroid.ketch.ftp` -- `FtpDownloadSource` (implements `DownloadSource`), `FtpClient`,
  `RealFtpClient`, `FtpUrl`, `FtpReply`, `FtpError`, `FtpResumeState`, `tlsUpgrade()`
  (expect/actual)

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
  `WebConfigStore` (WasmJs, localStorage), `ServerConfig`, `RemoteConfig`, `AiSettings`,
  `LlmSettings`, `LlmProvider`, `SearchSettings`, `SearchProvider`, `TorrentSettings`,
  `AppearanceConfig`, `AccentColor`, `ThemeMode`, `SpeedSettings`, `SpeedRule`,
  `UiPreferences`, `DesktopSettings`, `NotificationSettings`, `IntegrationSettings`

### `app:shared` (`com.linroid.ketch.app`)
- `App` (root composable), `state` (`AppController`, `AppState`, `TaskListModel`, `PulseModel`,
  `IntakeState`, `SpeedModeController`, `PendingOps`), `instance` (`InstanceManager`,
  `DevicePresence`, `DeviceScope`), `theme` (`KetchTheme` tokens), `components` (the Ketch
  controls), `icons` (`KetchIcon`), `input` (`KetchCommands`, `ShortcutMatcher`), `feedback`
  (`MessageCenter`, `ActivityMonitor`) and `util`
- `ui` -- `AppShell` and `shell` (layout, navigation, device switcher, drop berths), `sidebar`,
  `downloads` and `list` (table, list rows, launchpad), `inspector`, `intake` (add sheet),
  `palette`, `devices`, `connect`, `discover`, `settings`, `pulse`, `feedback`, `onboarding`

### `library:remote`
- `com.linroid.ketch.remote` -- `RemoteKetch` (implements `KetchApi`), `RemoteDownloadTask`,
  `ConnectionState`

### `library:server`, `library:mcp` (JVM only)
- `com.linroid.ketch.server` -- `KetchServer`, `TaskMapper`; `server.api` holds the routes and
  `server.mdns` the `MdnsRegistrar` implementations
- `com.linroid.ketch.mcp` -- `KetchMcpServer`, `KetchToolSet`, `asDeclaredTools()`

### `ai:discover` (JVM/Android only)
- `com.linroid.ketch.ai` -- `AiModule`, `AiConfig`, `LlmClientFactory`,
  `ResourceDiscoveryService`, `DiscoverQuery`, `DiscoverResult`,
  `RankedCandidate`
- `com.linroid.ketch.ai.agent` -- `DiscoveryToolSet`, `asDeclaredTools()`, `AgentOutputParser`,
  `DeviceSafetyFilter`, `LinkExtractor`, `DiscoveryStepListener`, `SiteAllowlist`
- `com.linroid.ketch.ai.fetch` -- `SafeFetcher`, `UrlValidator`, `ValidatingDns`,
  `ContentExtractor`, `RateLimiter`, `FetchBudget`
- `com.linroid.ketch.ai.search` -- `SearchProvider`, `BraveSearchProvider`,
  `GoogleSearchProvider`, `DummySearchProvider`
- `com.linroid.ketch.ai.site` -- `SiteProfiler`, `SiteProfile`, `SiteProfileStore`,
  `RobotsTxtParser`

## Implemented Features

### Core Download Engine
- Multi-platform: Android (minSdk 26), JVM 11+, iOS (iosArm64, iosSimulatorArm64), plus
  JS (Node.js) and WasmWasi for the engine; the browser (WasmJs) uses `RemoteKetch`
- Segmented downloads with concurrent HTTP Range requests
- Servers without Range support use a single connection; resuming them restarts from zero
- Pause / Resume with server identity validation (ETag, Last-Modified)
- File integrity check on resume (validates local file size vs. claimed progress)
- `DownloadState.Completed` reports the size and the download time, summed over every run and
  excluding time scheduled, queued or paused (`TaskRecord.downloadTime`, unknown for older records),
  and `completedAt`, stamped once in whole milliseconds and saved as `TaskRecord.completedAt`
  (SQLite `completed_at`, `4.sqm`; `null` for tasks completed before it was tracked)
- `DownloadState.Paused.reason` (`PauseReason`): `User`, `Preempted(byTaskId)`, `Shutdown` or
  `WaitingForCondition` (defined, not produced yet); unknown wire types decode as `User`. The
  reason is not persisted: a `PAUSED` record is a user pause
- `Ketch.close()` pauses running tasks for `Shutdown`, keeping their partial files and their
  `DOWNLOADING` records, so the next `start()` resumes them
- `KetchStatus.features` lists the optional behaviors an instance supports (`KetchFeatures`)
- Retry with exponential backoff for transient errors
- Persistent task metadata via `TaskStore` interface
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
- `FtpDownloadSource` handles FTP/FTPS with segmented parallel transfers
- `TorrentDownloadSource` handles BitTorrent/Magnet downloads
- Additional sources registered via `Ketch(additionalSources = listOf(...))`
- Each source defines: `type`, `canHandle()`, `resolve()`, `download()`, `resume()`,
  `buildResumeState()`; optional `canHandleContent()` / `resolveContent()` accept file content
  such as `.torrent` bytes (`KetchApi.resolveContent`)
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
- Verified selected-file storage, ownership journal, restart rehash, live limits and explicit
  seeding
- Apps open `.torrent` files from the system file manager (Android, desktop, iOS, installed web
  app) and resolve them through `KetchApi.resolveContent`, like dropped files
- Native torrent engine dependencies exist only in interoperability tests
- Public v2/hybrid download, selection, limits, pause/resume and TaskStore restart are implemented.
  V2 incoming routing, upload/seeding, PEX and hybrid v1-only peers remain roadmap work.
- See [support and migration](docs/torrent.md) and
  [verification](docs/development/torrent-verification.md)

### AI-Driven Resource Discovery (`ai:discover`) — In Progress
- LLM agent-driven discovery using Koog framework (v1.2.0)
- Providers: OpenAI, Anthropic, Google Gemini, Ollama, any
  OpenAI-compatible endpoint (`LlmClientFactory` maps them to Koog clients)
- Configured under Settings → Discover and persisted under `[ai]` in
  `config.toml`; blank credentials fall back to environment variables
- The Discover destination shows wherever discovery is supported; until it
  is set up it shows a setup page, where searches from the add sheet, the
  palette and Find another source wait. In the apps the Enable switch is
  authoritative (an env key fills a blank token but never enables the
  feature — only the CLI auto-enables)
- Provider defaults track current models; unknown ids resolve as custom
  Koog models, and `temperature` is only sent to models that accept it
- 7 agent tools: `searchWeb`, `searchSites`, `fetchPage`, `headUrl`,
  `extractDownloads`, `validateUrl`, `emitStep`
- SSRF protection on every redirect hop, device safety scoring, rate limiting; the
  fetcher also resolves hosts through the validator when it connects, so DNS
  rebinding cannot reach a private address
- `DiscoverQuery.sites` ("Limit to websites", CLI `--sites`) is a hard allowlist,
  subdomains included, capped by `DiscoveryConfig.allowedDomains`: the tools refuse
  other hosts and the output parser drops their candidates, but redirects a listed
  site answers with (e.g. github.com to its CDN) are followed
- JVM/Android only (uses Koog + Ktor CIO and OkHttp clients)
- Shrunk app releases keep what Koog reaches by reflection: `app/proguard-rules.pro` (Android
  R8 and desktop ProGuard) keeps `ToolSet` classes and `@LLMDescription`. The Android app also
  registers Koog's HTTP client factory under `META-INF/services` (Koog's Android AAR omits it)
  and must package `kotlin/**/*.kotlin_builtins` and `kotlinx-schema.properties`
- See [AI discovery configuration](docs/ai-discovery.md)

### Configuration (`config/`)
- TOML-based configuration via ktoml library
- `KetchConfig` root with server, download, remotes, AI, appearance, torrent, speed, UI,
  desktop, notifications and integration sections
- `AiSettings`: AI discovery provider, token, model, endpoint and search keys
- `AppearanceConfig`: accent palette and light/dark `ThemeMode` (app-only;
  CLI and server ignore it)
- `TorrentSettings`: extra trackers for public torrents (`TorrentConfig.additionalTrackers`),
  edited on the embedded instance's BitTorrent settings page and applied to torrents as they
  start or resume; a remote instance's trackers are only editable on that device
- `SpeedSettings`: the embedded device's speed mode (Full speed, Slow lane, Auto with weekly
  `SpeedRule`s), applied by the apps' `SpeedModeController`; `UiPreferences` (`[ui]`): view
  state such as table columns, sort, sidebar, inspector, density, per-device add sheet defaults
  and onboarding; `DesktopSettings`: close action, open at login, Dock badge;
  `NotificationSettings` and `IntegrationSettings` (magnet and `.torrent` handlers)
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
- `ServerConfig`: host, port, API token, CORS, `allowedHosts`, mDNS, `autoStart`
  (apps start the server on launch)
- `RemoteConfig`: pre-configured remote server connections
- `FileConfigStore`: platform-specific file persistence via okio; on the JVM a leading `~` in
  `download.defaultDirectory` expands to the home directory when the file is loaded. The web app
  uses `WebConfigStore` (TOML in localStorage)

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
  web) take a pairing link, an address or a device found on the network
- Every keyboard command lives in `KetchCommands`; `ShortcutHost` runs the global chords,
  `⌘K` opens the command palette and `⌘/` the shortcut sheet. Commands the window's shell
  must run from outside it, such as the macOS menu bar's, go through `AppState.runInShell`
- Toasts and banners go through `MessageCenter`; removals and other undoable operations wait in
  `PendingOps` for their Undo window
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
- Localization: the apps follow the system or per-app language and fall back to English; the
  languages are listed in [localization](docs/development/localization.md#languages). UI text
  lives in `composeResources/values/strings_<area>.xml`; state and model code returns `UiText`
  (`i18n/UiText.kt`), which composables `resolve()` and coroutines `load()`, and sizes, speeds,
  durations and dates come from `i18n/Formats.kt`. Never put a `UiText` in a string template: it
  prints `⟦key⟧`. `HardcodedTextTest` counts the literals left per file
  (`hardcoded-text-allowlist.txt`, which only shrinks) and `LocalizationResourcesTest` checks the
  translations against English
- Desktop: closing the window follows `[desktop] closeAction` (asks the first time while
  downloads run, then keeps Ketch in the menu bar or notification area; minimizes where there
  is no tray). The tray lists every device with its own actions, and the macOS menu bar, the
  tray and the Dock menu are generated from `KetchCommands` (`DesktopMenuBar`, `DesktopTray`,
  `TaskbarFeedback` for the Dock and taskbar badge and progress)
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
- Optional bearer-token auth (`ServerConfig.apiToken`), CORS and mDNS advertising (`_ketch._tcp`)
- Without an API token, `HostValidator` answers 403 to requests whose `Host` is not a loopback
  name, an interface IP, the machine's host name or `<host>.local`, or in `allowedHosts`
  (DNS rebinding protection); with a token any `Host` is accepted
- Without an API token, refuses requests from web pages on other origins (403
  `origin_not_allowed`) and ignores `corsAllowedHosts`; its own web UI, browser extensions and
  non-browser clients pass. With a token, `corsAllowedHosts` grants CORS (`host[:port]` for
  both schemes, `scheme://host[:port]`, or `*`), and the apps default it to `*` so the hosted
  web app can connect
- Remote backend (`RemoteKetch`) communicates via HTTP + SSE
- Auto-reconnection with exponential backoff
- `ketch server` starts listening, then restores the tasks saved in `ketch.db`, so a daemon that
  cannot bind never resumes them

### Native CLI (`cli/`)
- Released as a GraalVM native binary; reflection and resource metadata lives in
  `META-INF/native-image/<module>/` of the module that needs it (`cli`, `library:mcp` for the MCP
  SDK and `KetchToolSet`, `ai:discover` for `DiscoveryToolSet`). Koog tool sets use kotlin-reflect,
  so new tool methods may need the types in their signatures registered
- Koog's Anthropic, Gemini and OpenAI Responses clients have Ktor find their request and response
  serializers by class, and Gemini parts and Responses items use content-polymorphic serializers,
  so `ai:discover` registers those classes too; the Ollama and chat-completions clients do not
- `NativeImageConfigTest` (in `cli`, `library:mcp` and `ai:discover`) checks that the metadata
  names existing classes and covers every serializable MCP SDK type and every subtype of Koog's
  content-polymorphic types; build with `./gradlew :cli:nativeCompile` and exercise `ketch mcp`
  and `ketch ai-discover` with each LLM provider to verify changes

### MCP Server (`library:mcp`)
- `KetchMcpServer` exposes any `KetchApi` over stdio or SSE through Koog's MCP server bridge
- `KetchToolSet` provides 12 tools: list/get/start/pause/resume/cancel/remove downloads,
  `resolveUrl`, `getStatus`, `setSpeedLimit`, `setPriority`, `updateConfig`; downloads it starts
  carry `DownloadRequest.properties["ketch.origin"] = "agent"`
- Register Koog tool sets with `asDeclaredTools()` (`library:mcp` and `ai:discover` each have a
  copy), not `tools(toolSet)`: Koog 1.2.0 describes every parameter of a `@Tool` method as
  required and JSON-encodes a `String` result again. The adapter makes parameters with default
  values optional and passes `String` results on as they are, so tools return their JSON as text
- `ketch mcp` runs it on stdio against a local engine. It passes the real stdout to
  `startStdio` and redirects `System.out` to stderr, so the banner, the console logger and
  Logback never corrupt the JSON-RPC stream
- Stdio uses Ketch's own `StdioTransport`: when stdin ends it answers the requests already read,
  then closes, and `startStdio` returns (`Server.onClose` only fires on `Server.close()`, so it
  waits for the transport). The SDK's `StdioServerTransport` drops those replies. `ketch mcp`
  then calls `exitProcess`, so a non-daemon thread cannot keep it alive; its shutdown hook closes
  `Ketch`

### Browser Extension (`app/browser-extension`)
- Manifest V3 extension for Chromium browsers and Firefox; plain JavaScript modules with no
  dependencies. `src/` loads unpacked in Chromium; `node build.mjs` writes `build/chrome`,
  `build/firefox` (event page instead of service worker, gecko id) and zips, which the release
  workflow attaches to GitHub releases (manifest version: the tag's numbers plus the run number)
- Talks to the daemon REST API (`/api/tasks`, task pause/resume, `/api/resolve/content`,
  `/api/status`) of one or more instances: the Ketch app on this computer and servers
  (`ketch server`, other devices), each with an optional bearer token. Captured downloads and
  magnets go to the default one
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
  "TorrentTracker", "RemoteKetch", "RemoteTask", "TokenBucket", "SqliteStore", "SqliteDriver",
  "KetchServer", "ServerRoutes", "DownloadRoutes", "EventRoutes", "McpStdio"; `ai:discover`, mDNS
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
3. **HLS Support** - HTTP Live Streaming (HLS) as a pluggable `DownloadSource`, downloading
   and merging `.m3u8` playlist segments into a single media file
4. **Media Downloads** - Web media extraction (like yt-dlp) as a pluggable `DownloadSource`,
   supporting various media sites and extractors
5. **Resource Sniffer** - Detect and extract downloadable resources (media, files) from
   web pages by analyzing network requests, HTML, and embedded players
