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
  torrent-api/  # Torrent control contract (TorrentController and its models; same targets as
                # api) -- published SDK module
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
hooks/        # Commands and webhooks run on download events (JVM module: server, desktop)
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
  `DownloadRequest`, `DownloadState`, `DownloadProgress`, `DownloadConfig`, `DownloadCategory`,
  `Destination`,
  `Segment`, `KetchError`, `SpeedLimit`, `DownloadPriority`, `DownloadSchedule`,
  `DownloadCondition`, `KetchStatus`, `NetworkInterfaces`, `NetworkInterfaceConfig`,
  `ProxyConfig`, `ProxyMode`, `ProxyAddress`, `ResolvedSource`, `SourceFile`, `FileSelectionMode`,
  `ResolveInput` (what `KetchApi.resolve`, its one probing function, takes: `Url(url, headers)`,
  which the instance fetches, or `Content(bytes, fileName)` the caller holds; the `resolve(url)`
  and `resolve(bytes, fileName)` extensions build them),
  `ActiveConnections`, `ActiveConnection`, `ConnectionDirection`, `ConnectionRoute`, `PeerDetails`,
  `ControlKey` (`KetchApi.controller(key)` looks up optional typed controllers that other modules
  define; `null` by default), `Interest` (what `KetchApi.subscribe(interest, interval)`, abstract,
  follows: `Interest.Segments(taskIds)`, `Interest.Stats` (`KetchStats`) and
  `Interest.Connections(limit)`; other modules define their own, all serializable, each naming the
  `valueSerializer` servers encode what it reports with; a flow ends when what it follows is gone,
  and an instance fails those it does not know with `UnsupportedOperationException` when
  collected), `Checksum`, `ChecksumAlgorithm`, and
  `FileOrder`, `NaturalOrder`, `fileExtension()` and `sortedByFileOrder()`, the file orders the
  apps, the CLI, MCP and the torrent controller share
- `com.linroid.ketch.api.log` -- `Logger`, `LogLevel`, `KetchLogger`, `FormattedConsoleLogger`,
  `redactUrl()`, `describeCauses()`
- `library:api` and `library:core` name no protocol: torrent types live in `library:torrent-api`
  and `library:torrent`, which plug in through `ControlKey`, `Interest` and
  `DownloadSource.attach`

### `library:torrent-api` (torrent control contract)
- `com.linroid.ketch.torrent.api` -- `TorrentController` (its companion `Key` is the
  `ControlKey`: `ketch.controller(TorrentController)`; `Ketch` returns one when the torrent source is
  registered, `RemoteKetch` one over REST and SSE that fails closed against older servers),
  `TorrentInterest(taskId)` (a task's snapshots through `KetchApi.subscribe`, `null` once gone),
  `TorrentFeatures` (`CONTROL` = `torrent.control`), `TorrentCapabilities` (`TorrentCapability`:
  `inspect`, `file-selection`, `v1`, `v2`, `hybrid`, `utp` and `mse-pe`, which the engine
  speaks whatever its settings, and `seeding` while uploads seed),
  `TorrentSnapshot`, `TorrentCounters`, `TorrentActivity`, `TorrentRevision`,
  `TorrentCommandContext`, `TorrentCommandResult`, `TorrentCommandError`/
  `TorrentCommandException`, `TorrentPageRequest`, `TorrentFilePage`, `TorrentFileEntry`,
  `TorrentFileOrder` (wire names, each naming a `FileOrder`)

### `library:core` (implementation)
- `com.linroid.ketch.core` -- `Ketch` (implements `KetchApi`), `KetchDispatchers`
  (`KetchDispatchers.Default`, shared by every instance)
- `com.linroid.ketch.core.engine` -- `HttpEngine`, `DownloadCoordinator`, `DownloadExecution`,
  `RangeSupportDetector`, `ServerInfo`, `RequestHeaders`, `DownloadSource`, `HttpDownloadSource`,
  `SourceResolver`, `SourceState`, `DownloadContext`, `DownloadQueue`, `DownloadScheduler`,
  `SelectionRequest`, `SelectionPlan`, `SelectionUpdate`, `SourceExtension` and `SourceHost`
  (what `DownloadSource.attach` adds: controllers for `KetchApi.controller`, `start`,
  `taskCompleted` and `selectionChanged` hooks, and the host's record reads, ledgered
  `selectFiles`/`setSharing`, `showSharing` and `saveSharingIntent`),
  `SpeedLimiter`, `TokenBucket`, `DelegatingSpeedLimiter`, `MultiNetworkHttpEngine`,
  `ConfigurableNetworkHttpEngine`, `NetworkInterfaceProvider`, `ConnectionRegistry`,
  `ConnectionReporter`, `ConnectionHandle`, `ConnectionSpec`, `UrlAuthority`, `HttpExchange`,
  `HttpExchangeObserver`, `TransferWatchdog`, `TransferMonitor`, `TransferStalledException`,
  `RetryPolicy`
- `com.linroid.ketch.core.segment` -- `SegmentCalculator`, `SegmentDownloader`,
  `SegmentedDownloadHelper`
- `com.linroid.ketch.core.file` -- `FileAccessor`, `createFileAccessor()` (expect/actual),
  `PathFileAccessor`, `ContentUriFileAccessor` (Android), `NoOpFileAccessor`,
  `platformFileSystem` (expect/actual), `FileNameResolver`, `DefaultFileNameResolver`,
  `sanitizeFileName()`, `OutputPathReservations`, `partialPathFor()` and
  `movePartialToFinal()` (`PartialFiles.kt`), `DestinationPathPolicy`, `PathRejectedException`
- `com.linroid.ketch.core.digest` -- `MessageDigest`, `Md5`, `Sha1`, `Sha256`, `Sha512`,
  `ChecksumAlgorithm.newDigest()`: pure Kotlin hashes on every engine target, shared by checksum
  verification and `library:torrent`
- `com.linroid.ketch.core.task` -- `RealDownloadTask`, `TaskHandle`, `TaskController`,
  `TaskStore`, `InMemoryTaskStore`, `TaskRecord`, `TaskState`, `TaskControl` (selection
  generation, sharing intent and the command retry ledger; SQLite `control_json`, `5.sqm`),
  `ControlCommand`, `ControlOutcome`, `ControlRevision`, `ControlLedgerException`

### `library:ktor`, `library:kermit`, `library:sqlite`
- `com.linroid.ketch.engine` -- `KtorHttpEngine` (`withNetworkInterfaces()` on Android/JVM),
  `RedirectCache`; internal proxy transports: `ProxyRoute`, `HttpTransports`, `JvmTransports`
  (with `EnvironmentProxies`), `AndroidTransports`, `DarwinTransports`, `Socks5Socket`
- `com.linroid.ketch.log` -- `KermitLogger`
- `com.linroid.ketch.sqlite` -- `SqliteTaskStore`, `DriverFactory` (expect/actual),
  `UnreadableDatabase`

### `library:ftp`
- `com.linroid.ketch.ftp` -- `FtpDownloadSource` (implements `DownloadSource`), `FtpClient`,
  `RealFtpClient`, `FtpUrl`, `FtpReply`, `FtpError`, `FtpSourceState`, `tlsUpgrade()`
  (expect/actual)

### `library:hls`, `library:dash`
- `com.linroid.ketch.hls` -- `HlsDownloadSource` (`.m3u8`)
- `com.linroid.ketch.dash` -- `DashDownloadSource` (`.mpd`)
- Both depend on core's `com.linroid.ketch.core.media` helpers for bounded manifest fetching,
  URL/header handling and sequential transfer; each owns its parser and tests

### `library:torrent`
- `com.linroid.ketch.torrent` -- the public API, `TorrentDownloadSource` (implements
  `DownloadSource<TorrentSourceState>`; the state is opaque outside the module), `TorrentConfig`,
  `TorrentEncryption`, `TorrentUploadPolicy`, `TorrentDiscoveryPrivacy`, `TorrentMulticastLock`
  and `TrackerListState`; internal `TorrentSourceMetainfo` and the helpers every package uses
  (`TorrentLog`, `monotonicClock()`, `TorrentDispatchers`). Everything else is internal, in
  sub-packages by concern:
- `com.linroid.ketch.torrent.engine` -- `TorrentEngine`, `KotlinTorrentEngine`,
  `TorrentSession`, `KotlinTorrentSession`, session admission and registry, `TorrentActiveSlots`,
  `TorrentRouteTable`, `TorrentAdvertisedPort`, the buffer and connection budgets and
  `TorrentRateLimiter`
- `com.linroid.ketch.torrent.metainfo` -- `Bencode`, `InfoHash`, `TorrentIdentity`
  (`V2InfoHash`), `TorrentMetadata`, `TorrentV2Info`, `TorrentV2Document`, piece layers and the
  hybrid and content layouts
- `com.linroid.ketch.torrent.magnet` -- `MagnetUri` and BEP 9 metadata lookup
  (`TorrentMetadataExchange`, `TorrentV2MetadataExchange`, `TorrentMetadataPeers`,
  `TorrentMetadataCache`)
- `com.linroid.ketch.torrent.crypto` -- `Sha1` and `Sha256` (aliases of core's `digest` ones,
  `Digests`), the BEP 52 Merkle tree (`TorrentMerkleRoot`, `TorrentMerkleProof`,
  `TorrentMerkleFrontier`, `merkleZeroHash()`) and `torrentRandomBytes()`
- `com.linroid.ketch.torrent.net` -- `TorrentNetwork`, `TorrentConnection` and `PeerEndpoint`
  (`TorrentTransport`), `NioTorrentNetwork` and `NioSelector` (JVM/Android) and
  `PosixTorrentNetwork` (iOS), DNS, `TorrentHttp`, `TorrentUdpPort`, `LocalIpv4Network` and the
  address helpers (`TorrentAddresses`)
- `com.linroid.ketch.torrent.mse` (MSE/PE: `MseHandshake`, `Rc4`, `TorrentPeerEncryption`),
  `.utp` (`UtpSocket`, `UtpPacket`, `UtpEndpoint`), `.portmap` (`NatPmpClient`, `UpnpClient`,
  `TorrentPortMapper`), `.dht` (`DhtNode`, `DhtRpc`, `DhtRoutingTable`), `.tracker`
  (`TorrentTracker`, `TrackerDiscovery`, `TrackerList`, `PrivateTrackerGroup`) and `.lsd`
  (BEP 14: `TorrentLocalDiscovery`, with `LsdAnnounce`, `LsdTopic` and the `LsdTransport` that
  `openLsdMulticast()` opens)
- `com.linroid.ketch.torrent.peer` -- the peer wire v1 and v2 share: `PeerWire`,
  `PeerProtocolState`, `PeerExtensions` (BEP 10), `PeerFastExtension` (BEP 6), `PeerExchange`
  (BEP 11), `PeerIdentityHandshake`, `PeerTraffic`, `PeerDiagnostics` and `TorrentChoker`
- `com.linroid.ketch.torrent.v1` -- the v1 swarm: `TorrentSwarm`, `TorrentPieceScheduler`,
  `TorrentPeerDownloader`, `TorrentUploadCache`
- `com.linroid.ketch.torrent.v2` -- v2 and hybrid sessions: `TorrentV2DownloadSession`,
  `TorrentV2SessionLoop` (`TorrentV2Swarm`), `TorrentV2PieceScheduler`, `TorrentV2Candidates`,
  `TorrentV2Uploader`, `TorrentV2Extensions`, `TorrentV2ServeWorker` and their peers' views
- `com.linroid.ketch.torrent.v2.peer` -- v2 peer connections: `PeerV2Connector`,
  `PeerV2Dialer`, `PeerV2Pool`, `PeerV2DownloadActor`, `PeerBlockExchange` and the BEP 52 hash
  exchange (`PeerHashWire`, `PeerHashTransport`, `PeerHashExchange`, `TorrentV2HashServer`)
- `com.linroid.ketch.torrent.storage` -- files on disk: `TorrentPieceStore` (v1),
  `TorrentV2PieceStore`, `TorrentCheckpoint`, `TorrentV2Checkpoint`, `TorrentV2CreationLog`,
  `TorrentOwnershipJournal`, `TorrentContentCatalog`, `TorrentOutputMapping`, `TorrentFileIo`
  and the platform file systems
- `com.linroid.ketch.torrent.webseed` -- BEP 19: `WebSeedWorker` and `WebSeedLayout`
  (`TorrentWebSeed`), `TorrentV2WebSeeds`
- `com.linroid.ketch.torrent.control` (internal) -- `TorrentControls` (the source's
  `SourceExtension`: seeding watcher and restore), `KetchTorrentController`, `TorrentRevisions`,
  `TorrentControlSource` (with `LiveTorrent`, `SeedingTask`, `SeedingOutcome`)

### `library:endpoints`
- `com.linroid.ketch.endpoints` -- `Api` (Ktor `@Resource` definitions for REST API),
  `ConnectionEvents` (live connection stream event names and error codes)
- `com.linroid.ketch.endpoints.model` -- `TaskSnapshot`, `TasksResponse`, `TaskEvent`,
  `TaskEventType`, `ErrorResponse`, `ResolveUrlRequest`, `SpeedLimitRequest`,
  `PriorityRequest`, `ConnectionsRequest`, `FileSelectionRequest`, `PairingRequest`,
  `PairingTicket`, `PairingStatus`, `PairingState`, `HealthResponse`, `HealthStatus`
- `com.linroid.ketch.endpoints.torrent` -- `Torrents` (the `/api/torrents` resources),
  `TorrentSelectionRequest`, `TorrentSeedingRequest`
- `com.linroid.ketch.endpoints.stream` -- the event stream's wire format (see the
  [plan](docs/plans/event-stream.md)): `StreamEvents` (`FEATURE` = `events.stream`, event names,
  error codes, limits, `intervalStep`), `StreamSubscription` (`progressIntervalMs`,
  `StreamSegments` and `StreamInterest`s: an ID, an `Interest` and its `intervalMs`),
  `StreamSerializers` (the interests' `type`s; unknown ones decode as `UnknownInterest`),
  `StreamHello`, `StreamSubscribed`, `TaskStateUpdate`, `TaskProgressUpdate`,
  `TaskSegmentsUpdate`, `StreamTaskRef`, `InterestUpdate`, `InterestRef`, `InterestError`,
  `StreamError`

### `updater` (JVM module, also consumed by direct Android)
- `com.linroid.ketch.updater` -- `ReleaseVersion`, `Release`, `ReleaseAsset`, `ReleaseProduct`,
  `ReleasePlatform`, `ReleaseFeed`, `GitHubReleases`, `ReleaseDownloader`, `UpdateException`,
  `extractArchive()`

### `hooks` (JVM module)
- `com.linroid.ketch.hooks` -- `HookRunner`, `HookPayload`, `HookError`, `ProcessLauncher`,
  `HookProcess`; internal `CommandRunner`, `WebhookSender`, `Hook`, `transition()`

### `config`
- `com.linroid.ketch.config` -- `KetchConfig`, `ConfigStore`, `FileConfigStore`,
  `UnreadableConfig`, `WebConfigStore` (WasmJs, localStorage), `ServerConfig`, `RemoteConfig`,
  `AiSettings`, `LlmSettings`, `LlmProvider`, `LlmApi`, `LlmProviderGroup`, `SearchSettings`,
  `SearchProvider`,
  `PageAccessSettings`, `PageAccessMode`, `SiteNames`, `TorrentSettings`, `AppearanceConfig`,
  `AccentColor`, `ThemeMode`, `SpeedSettings`, `SpeedRule`, `UiPreferences`, `DesktopSettings`,
  `NotificationSettings`, `IntegrationSettings`, `PowerSettings`, `HookConfig`, `HookEvent`

### `app:shared` (`com.linroid.ketch.app`)
- `App` (root composable), `state` (`AppController`, `AppState`, `TaskListModel`, `PulseModel`,
  `ConnectionGridModel`, `IntakeState`, `SpeedModeController`, `PendingOps`, `ForegroundPolicy`,
  `KeepAwake`, `SleepInhibitor`, `FileSelectionModel`, `FileOrder`, `AiDiscoverController`,
  `DiscoverSession`, `DiscoverHistoryStore`, `FileDiscoverHistoryStore` on JVM/Android), `instance`
  (`InstanceManager`, `DevicePresence`, `DeviceScope`, `PairingRequests`), `theme` (`KetchTheme`
  tokens), `components` (the Ketch controls), `icons` (`KetchIcon`), `input` (`KetchCommands`,
  `CommandScope`, `ShortcutMatcher`), `feedback` (`MessageCenter`, `ActivityMonitor`,
  `UnreadableFiles`) and `util`
- `ui` -- `AppShell` and `shell` (layout, navigation and its badges, device switcher, drop
  berths), `sidebar`, `downloads` and `list` (table, list rows, launchpad), `inspector`, `intake`
  (add sheet), `palette`, `devices`, `connect`, `discover` (chat, history, approval cards),
  `settings`, `pulse`, `feedback`, `onboarding`

### `library:remote`
- `com.linroid.ketch.remote` -- `RemoteKetch` (implements `KetchApi`; `progressInterval` and
  `foreground` shape its event stream), `RemoteDownloadTask`, `RemoteTorrentController`,
  `ConnectionState`, `RemotePairing`, `PairingResult`, `RemoteApiException`; internal
  `StreamInterests` (the callers' interests merged into one subscription, an ID per interest) and
  `StreamTopics` (each interest's latest value and end, by ID)

### `library:server`, `library:mcp` (JVM only)
- `com.linroid.ketch.server` -- `KetchServer`, `TaskMapper`, `PairingApprover`,
  `DestinationGuard`, `AuthThrottle`; `server.api` holds the routes and `receiveJson`,
  `server.mdns` the `MdnsRegistrar` implementations, and `server.stream` the event stream's
  `EventHub` (`EventStream`, `StreamPlan`, `TaskTracker`, `TaskFrame`, `SharedSource`)
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
- Partial files: downloads the engine writes (HTTP, FTP, HLS, DASH; not torrents) write
  `<final name>.ketch` beside their output path (`PARTIAL_FILE_SUFFIX`, `partialPathFor`), saved
  as `TaskRecord.partialPath` (SQLite `partial_path`, `6.sqm`), so an unfinished file never looks
  complete. The final name is still chosen and reserved when the download starts, and
  `OutputPathReservations` holds both names and counts a name whose partial file exists as taken.
  After the last flush the file is closed and moved with okio `atomicMove`; a file that appeared
  at the final name meanwhile is kept, and the download takes the next free `name (n).ext` and
  records it as its output path (a file created in the instant between the check and the move
  would still be replaced). A file `Destination` is replaced, as it was written in place before.
  Pause, failure, `close()` and `remove(deleteFiles = false)` keep the partial file; cancel and
  `remove(deleteFiles = true)` delete it, never a file at the final name the task did not write.
  A name too long for the suffix (over 249 bytes), a resume given a new destination and tasks
  saved before partial files (no `partialPath`) write to the output path itself. In an Android
  `content://` folder the document is created up front under the final name its provider picks,
  then renamed (`DocumentsContract.renameDocument`) to exactly that name plus `.ketch`, and back
  once finished, reporting its URI as the output path until then; a provider without
  `FLAG_SUPPORTS_RENAME`, or one that changes the partial name, keeps the final name throughout,
  and a final rename that fails leaves the finished document under its partial name
- Category folders: a request whose destination is `null` or a bare name is saved in the folder,
  inside the default directory, of the first of `DownloadConfig.categories` it matches
  (`DownloadCategory`: `extensions` or `mimeTypes`, either enough, and `hosts` with subdomains
  when set; no rules matches nothing), checked in `DownloadExecution` when a download starts
  against the saved name, `ResolvedSource.contentType` (HTTP `Content-Type`, from
  `ServerInfo.contentType`) and the request host. Folder names pass through
  `sanitizeFileName()`; absolute folders, drives and `..` fail in `DownloadCategory`'s `init`.
  `resolveChildFolder` finds or creates them in an Android `content://` tree. Listed as
  `KetchFeatures.CATEGORY_FOLDERS`; `DownloadConfig.categoryFor` is the matcher the apps preview
  with
- File integrity check on resume (validates local file size vs. claimed progress)
- Checksums: `DownloadRequest.checksum` (`Checksum`: `ChecksumAlgorithm` MD5, SHA-1, SHA-256 or
  SHA-512 and lowercase hex; `Checksum.parse` reads `sha256:<hex>`, base64 digest-header values
  and bare hex, and JSON carries it as that text) is checked once the last byte is flushed:
  `DownloadExecution.verify` reads the file back through `FileAccessor.readAt` on the io
  dispatcher (`fileDigest`, yielding every 256 KiB, so pause and cancel stop it) while the task
  still reports `Downloading`. A match completes with `Completed.checksum`; a difference fails
  with `KetchError.ChecksumMismatch` (not retryable), deletes the file and discards its
  progress, so a resume downloads it again. Without one, HTTP downloads check the digest the
  server publishes (`ServerInfo.checksum` / `ResolvedSource.checksum`, from
  `ServerInfo.publishedChecksum`: the strongest `Repr-Digest` entry, else a legacy `Digest` of a
  `HEAD` or `200` response, nothing when `Content-Encoding` is set), refreshed by a resume's
  `HEAD` (`DownloadContext.publishedChecksum`), and fail with `published = true`. A restored
  completed task reports only the request's checksum (published ones are not saved). Applies to
  sources Ketch writes one file for (HTTP, FTP, HLS, DASH); `download` refuses it for sources
  that manage their own files. Listed as `KetchFeatures.CHECKSUM` (`download.checksum`). Hashes
  are core's pure Kotlin `digest` package, which `library:torrent` uses too; `updater` verifies
  release assets through `DownloadRequest.checksum`
- Only `cancel()` and `remove(deleteFiles = true)` delete a partial file (the coordinator tells the
  execution); a failure, `close()` or `remove(deleteFiles = false)` keeps it and its segments, but
  a failed final flush resets their progress. For files the engine writes (not torrents), an empty
  segment list is no progress: it is never saved, and resuming one starts from zero
- `DownloadState.Completed` reports the size and the download time, summed over every run and
  excluding time scheduled, queued or paused (`TaskRecord.downloadTime`, unknown for older records),
  and `completedAt`, stamped in whole milliseconds each time the task completes (a selection
  that adds files clears it until then) and saved as `TaskRecord.completedAt` (SQLite
  `completed_at`, `4.sqm`; `null` for tasks completed before it was tracked). `sharing` is `true`
  while the source shares the finished content (a seeding torrent); it is not persisted
- `DownloadState.Paused.reason` (`PauseReason`): `User`, `Preempted(byTaskId)`, `Shutdown`,
  `AwaitingFileSelection` (a download that waits for `selectFiles`, wire
  `awaiting_file_selection`) or `WaitingForCondition` (defined, not produced yet); unknown wire
  types decode as `User`. The reason is not persisted: a `PAUSED` record is a user pause, except
  that a waiting task is derived from its record (`awaitFileSelection` with no selection)
- `Ketch.close()` pauses running tasks for `Shutdown`, keeping their partial files and their
  `DOWNLOADING` records, so the next `start()` resumes them. It closes dispatchers it was given
  only once the cancelled coroutines have finished (from `Dispatchers.Default`), never under
  them, and never `KetchDispatchers.Default`, whose `close()` does nothing
- `KetchStatus.features` lists the optional behaviors an instance supports (`KetchFeatures`),
  including registered `DownloadSource.features`: `hls.finite` and `dash.finite` independently,
  plus the legacy `media.finite` when both are present, `download.fileSelection` and
  `download.awaitFileSelection` from sources that choose files (the torrent source), and the
  torrent source's `torrent.control` (`TorrentFeatures.CONTROL`, outside `KetchFeatures.ALL`).
  `DownloadSource.previousTypes` lets stored tasks with the old `media` type route to the
  matching protocol by URL
- Retries of transient errors (`KetchError.isRetryable`) follow `RetryPolicy`: `retryCount` in a
  row (or forever with `DownloadConfig.unlimitedRetries`), counted again from zero once an attempt
  gets further than every attempt before it (`DownloadExecution.progressReached`, from reported
  progress and kept segment bytes). Retry `n` waits `retryDelayMs` doubled `n - 1` times, capped
  at `maxRetryDelayMs` (60 s), with equal jitter (half to all of it); a 429's `Retry-After`
  replaces it
- Stalled transfers: `TransferWatchdog` (`DownloadContext.watchdog`, from
  `DownloadConfig.idleTimeoutMs`, 60 s by default, and `minSpeed` bytes per second averaged over
  `MIN_SPEED_WINDOW_MS`, 30 s, off by default; `0` turns either off) wraps each HTTP segment
  request, the unknown-size stream, each HLS/DASH part and each FTP segment connection (connect,
  login and `RETR`). Sources report bytes with `TransferMonitor.received` and run `throttle` in
  `TransferMonitor.throttled`, so speed limit waits count as neither idle nor slow; a stall
  cancels the transfer and fails it with a retryable `KetchError.Network` whose cause is a
  `TransferStalledException`. It checks every tenth of the idle timeout (10 ms to 1 s) with
  `delay`, so tests run it on virtual time. Request and socket timeouts stay infinite;
  `KtorHttpEngine`'s own clients (not Darwin) and `RealFtpClient` connect within 30 s, and FTP's
  `QUIT` waits 5 s. Listed as `KetchFeatures.TRANSFER_TIMEOUTS` (`download.timeouts`)
- Persistent task metadata via `TaskStore` interface
- Unreadable storage never stops a start: `SqliteTaskStore.loadAll` skips (and logs) rows it
  cannot decode, leaving them in the table, and `DriverFactory` (JVM and Android) moves a
  database SQLite reports corrupt or not a database aside to `<name>.broken-<UTC time>`, with its
  journal, and starts an empty one (on Android also when corruption shows up later, instead of
  Android deleting it), telling its `onUnreadable` callback
- Duplicate download guards in `DownloadCoordinator.start()` and `resume()`
- HTTP requests can be spread round-robin over selected network interfaces
  (`KetchApi.selectNetworkInterfaces`, `MultiNetworkHttpEngine`); see
  [multiple networks](docs/multiple-networks.md)
- Proxies for HTTP(S), HLS and DASH downloads (not FTP or BitTorrent; Discover never proxies):
  `DownloadConfig.proxy` (`[download.proxy]`), replaced per task by `DownloadRequest.proxy`, which
  `DownloadCoordinator` folds into the run's `DownloadContext.config` (`forRequest`); `resolve`
  uses the global one. `ProxyConfig` is `SYSTEM` (default), `DIRECT` or `MANUAL` with an
  `http://` or `socks5://` URL (`ProxyAddress.parse`), username/password and a bypass list
  (domains with subdomains, IPs, CIDR, `<local>`, `*`); loopback hosts always go directly and
  `toString` masks the password. Sources call `HttpEngine.withProxy` (internal `through()`) only
  for non-SYSTEM modes; its default refuses them with `KetchError.Unsupported`, so a proxy is
  never skipped. `Ketch` lists `KetchFeatures.PROXY` only when `HttpEngine.supportsProxies`, and
  otherwise refuses such proxies in `updateConfig` and `download`. `Multi`/`ConfigurableNetworkHttpEngine` return views sharing their selection and
  round robin. `KtorHttpEngine` resolves a `ProxyRoute` per request and redirect hop, keeps one
  client per route (`HttpTransports`) and remembers redirects per proxy (`RedirectCache.Key`): JVM uses CIO for direct requests the JVM `ProxySelector`
  would not proxy and OkHttp (HTTP/1.1) otherwise, SYSTEM reading `https_proxy`/`http_proxy`/
  `all_proxy`/`no_proxy` (`EnvironmentProxies`) then the JVM selector, which reads the OS settings
  with `-Djava.net.useSystemProxies=true` (the desktop launcher sets it); Android leaves SYSTEM to
  OkHttp; iOS sets `NSURLSession` proxy dictionaries. HTTP proxy credentials only go in `CONNECT`
  and proxied plain-HTTP requests; SOCKS5 is Ketch's own `Socks5Socket` (RFC 1928/1929, names
  resolved by the proxy). The interface-bound engines reach proxies through their address or
  network; a custom `HttpClient` only follows SYSTEM. `RemoteKetch` refuses a proxy for servers
  without `KetchFeatures.PROXY` (`http.proxy`). See [proxies](docs/proxy.md)

### Live Connections (`ConnectionRegistry`)
- `KetchApi.subscribe(Interest.Connections(limit))` streams `ActiveConnections` snapshots, listed
  as `KetchFeatures.ACTIVE_CONNECTIONS` (`net.activeConnections`); instances without them fail it
  with `UnsupportedOperationException` when collected. `limit` must be in 1..1024 (default 256): a
  snapshot keeps the fastest connections, ordered by when they opened, while `total`,
  `downloadBps` and `uploadBps` cover all of them
- `Ketch` keeps a `ConnectionRegistry` (at most 4096 open, more count as `dropped`). Sources open
  a `ConnectionHandle` through `DownloadContext.connections` (`SeedingTask.connections` while
  seeding), count bytes with one atomic add per chunk and close it in `finally`; `remove` and
  `close` close what a source leaked. Its sampler reads the counters once a second, rates over
  the last two samples, and runs only while collected; idle repeats are not sent
- HTTP: one connection per segment request (the unknown-size stream is one), with the URL's host
  and port until the engine reports more. Sources run each GET under an `HttpExchangeObserver`
  (a coroutine context element, so wrapping engines pass it on unchanged); `KtorHttpEngine`
  reports every hop's host, port, TLS, HTTP version (`HTTP/1.1`, `HTTP/2`) and `ProxyRoute`, the
  final hop last, and engines that report nothing leave the URL's values
- FTP: one per segment transfer, with the control host and port (FTPS reports `secure`, as its
  data connections are encrypted too). HLS/DASH: one per task, following the host of the part
  being fetched. HEAD, probes, manifests and `resolve` are never reported. Hosts and peer
  addresses are never logged (`ConnectionSpec`'s `toString` leaves the host out)
- Torrent: one per connected peer once its handshake succeeds, v1 (`TorrentSwarm`'s workers) and
  v2/hybrid (the session loop, from `Ready` to `Closed`, on `TorrentV2PeerView`), either
  direction, through `PeerTraffic`: protocol `BitTorrent`, `PeerDetails.wire` `v1` (also a
  hybrid's v1-swarm peers) or `v2`, choke and interest state as it changes, the peer's IP and port
  (an incoming peer's source port until BEP 10 `p` names its listen port), and payload on the
  wire both ways (a v1 route's padding included). `TorrentTaskSpec`/`TorrentV2TaskSpec`
  `.connections` carry the task's reporter for the session's life, so a seeding session (lent
  after its download returned, started by `startSeeding`, restored or adopted) keeps reporting.
  Magnet metadata peers, trackers and DHT are not reported. A web seed (BEP 19) is one
  connection while it fetches, protocol `HTTP` until the engine reports its hops

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
  immediately, other fields (default directory, connections, retries, idle timeout and minimum
  speed, intervals, buffer size) are snapshotted into `DownloadContext.config` when a download starts or resumes
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
- Each source is a `DownloadSource<S>` over its own serializable source state `S`
  (`HttpSourceState`, `FtpSourceState`, `TorrentSourceState`, `Unit` for HLS/DASH) and defines
  `type`, `stateSerializer`, `canHandle(input)` and `resolve(input, config)`, plus three
  state functions: `download(context, state)` continues from the stored `state` after checking
  the content is unchanged, or starts from the beginning with `null` (Ketch passes the state the
  run started from, then the latest stored one on each retry, and drops the saved segments of a
  task with progress but no state); `store(context)` is the state to save (Ketch asks as each run
  starts, before any byte is written, every `saveIntervalMs` and once as it stops, the pause
  checkpoint; `null` keeps the last); and `cleanup(context, state, deleteFiles)` runs on removal
  (stops what outlived the download, such as a seeding torrent, and deletes files only with
  `deleteFiles`). Segments are Ketch's (`TaskRecord.segments`, `DownloadContext.segments`), not
  part of a source's state. Ketch saves the state as JSON in
  `TaskRecord.sourceState` (`SourceState`, written and read only through
  `encodeState`/`decodeState`, which ignores unknown fields and refuses another
  source's state) and hands sources their state decoded: one that cannot be decoded fails a
  resume with `KetchError.CorruptResumeState` and reaches `cleanup` as `null`. A resolution that
  describes all the content (`ResolvedSource.METAINFO_KEY`, a torrent's metainfo) is kept in
  `TaskRecord.resolvedSource` (SQLite `resolved_source_json`, `7.sqm`), which only Ketch writes:
  fresh starts and resumes get it as `DownloadContext.preResolved`, selections as
  `SelectionRequest.resolved`, and the request keeps it without the bulky metadata. Torrent
  states saved before then keep their `metainfo`, read as a fallback. `canHandle` and `resolve`
  take a `ResolveInput`: a URL, or file content such as `.torrent` bytes, which only the torrent
  source accepts; the `canHandle(url)` and `resolve(url, headers, config)` extensions build the
  URL input. `resolve` may take as long as finding the content does (a magnet waits for peers) and
  stops when cancelled; `KetchApi.resolve`, the preview, waits the same way, so callers bound it
  if they must (the server's resolve routes stop when the client disconnects)
- `managesOwnFileIo` flag: when `true`, engine skips `FileAccessor` (used by torrent)

### Multi-File Download Support
- `ResolvedSource.files` lists selectable files within a source (e.g., torrent)
- `FileSelectionMode.MULTIPLE` for subset selection (torrent)
- `FileSelectionMode.SINGLE` for single-variant selection (reserved for HLS quality; no built-in
  source uses it yet): core supports it already. `selectFiles` takes exactly one ID (Ketch
  refuses several before `planSelection`, and `download` refuses them against a `SINGLE`
  `resolvedSource`), and the default, `ResolvedSource.defaultSelection()`, is the first of
  `files` (every file for `MULTIPLE`). Tests: `SingleChoiceSelectionTest`
- Sources that implement `planSelection` list `KetchFeatures.FILE_SELECTION`
  (`download.fileSelection`) and, when their tasks can wait, `AWAIT_FILE_SELECTION`
  (`download.awaitFileSelection`) in `DownloadSource.features`; clients gate on those, never on
  the source type
- `DownloadRequest.selectedFileIds` specifies which files to download; empty downloads the
  source's default (every file, or the first variant)
- `DownloadTask.selectFiles` changes the selection in every state but canceled (default throws
  `UnsupportedOperationException`; `RemoteDownloadTask` sends `PUT /api/tasks/{id}/files`). Ketch
  validates it with `DownloadSource.planSelection` against metadata the source holds, never
  client sizes, saves it (selection, total, segments and `TaskControl.selectionGeneration` in one
  record write), then delivers it: a running task takes it live as a new
  `DownloadContext.selection` revision, which the source acknowledges (a run that ends with an
  unacknowledged selection runs again through `resume`, so completion covers the latest one);
  paused, queued, scheduled and failed tasks use it when they start; a completed task that gains
  files reopens into the same folder, adopting its live seeding session, while one that only
  drops files changes its size, and its seeding session shares the smaller selection
  (`SourceExtension.selectionChanged`). Re-selecting the current files does nothing
- `DownloadRequest.awaitFileSelection`: a download added without a selection, as the apps' "Add
  anyway" adds a magnet, parks as `Paused(AwaitingFileSelection)` once it lists several files or
  variants (either mode), holding no queue slot, output path or segments; `selectFiles` starts it
  and `resume` downloads the default. A `requestId` resubmission ignores the selection and the
  flag

### FTP/FTPS Support (`library:ftp`)
- FTP and FTPS (FTP over TLS) as a pluggable `DownloadSource`
- Segmented parallel downloads via multiple FTP connections with REST offsets
- Resume support with MDTM-based server file change validation
- FTPS on JVM/Android via ktor-network-tls; iOS deferred (no ktor TLS support)
- FTPS (`AUTH TLS`, `PBSZ 0`, `PROT P`) wraps every data connection in TLS too, after the `RETR`
  preliminary reply, checked against the control host; a server refusing `PROT P` fails the
  connection rather than sending clear data, and a failed data handshake is `KetchError.Network`.
  ktor-network-tls cannot resume TLS sessions, so servers requiring data connections to reuse
  the control session refuse transfers. `RealFtpClientTlsTest` (jvmTest) runs a scripted JSSE
  server
- Passive mode only (PASV/EPSV); FTP URL parsing with credentials
- Platforms: Android, JVM, iOS (no WasmJs — requires raw TCP sockets)

### BitTorrent/Magnet Support (`library:torrent`)
- Pure Kotlin BitTorrent v1/v2/hybrid downloads on Android, JVM and iOS;
  browser control through RemoteKetch
- HTTP(S)/local metainfo, SDK bytes, btih magnets, tracker tiers, DHT and peer exchange
- DHT bootstrap routers are asked whatever address their names resolve to, reserved ranges such
  as 198.18.0.0/15 included; such a router only introduces nodes and never joins the routing
  table, while addresses learned from the network stay filtered. Peers of public torrents (v1,
  v2 and hybrid) exchange DHT ports (BEP 5 `PORT`) while a DHT node of their family runs; the
  engine pings the nodes they name (the last 1024 once each, four at a time), so trackers' peers
  start a DHT whose routers do not answer
- Protocol encryption (MSE/PE), `TorrentConfig.encryption` (`TorrentEncryption`): `PREFERRED`
  (default) dials encrypted first through `TorrentPeerEncryption` (`connectPeer`, which every
  peer and metadata dial uses) and again in plaintext when a peer refuses, remembering the last
  1024 such peers; `dispatchIncoming` answers either kind (`MseHandshake`, `Rc4`, `MseKeyPair`
  over a pure Kotlin `Montgomery` modular exponentiation). `REQUIRED` is RC4 only, `DISABLED`
  plaintext only. Peers reached encrypted carry the BEP 11 encryption flag in PEX
- Btih magnets of hybrid torrents resolve their v2 identity (`TorrentMetadata` with
  `allowHybrid`, on the magnet path only) and run as v2 owners
- Web seeds (BEP 19): `TorrentMetadata.webSeeds` from the metainfo's `url-list` (a string or
  list, http/https only, at most 16) and magnet `ws` parameters (`MagnetUri.webSeeds`, carried
  into the rebuilt metainfo by `metainfoFromInfo`). The v1 swarm runs a `WebSeedWorker` per URL
  (`TorrentWebSeed.kt`) through the engine's `TorrentHttp` (`fetchRange`, which fails with
  `HttpIdleException` after 30 s without data): to `TorrentPieceScheduler` it is a peer with every
  piece that claims runs in order (`claimRun`), so peers and seeds never fetch the same piece
  outside the endgame. A run is the first free wanted piece and the free wanted pieces after it,
  at most `WEB_SEED_RUN_PIECES` (16) and `WEB_SEED_RUN_BYTES` (4 MiB), no more than its share of
  the free pieces split between every peer and seed (the swarm joins every seed before any
  claims), and later pieces only take buffers that leave half the budget free; an endgame
  duplicate is a single piece. `WebSeedLayout` maps a piece to one range request per file it spans
  (multi-file: URL + name + path, segments percent-encoded); the worker joins a run's spans that
  follow each other in a file into one request, and commits and gives back each piece as its last
  byte arrives (`WebSeedPieces.release(piece, verified)`), so a corrupt piece or a dropped
  response keeps the pieces before it and gives the rest back. Pieces are SHA-1 checked like
  peers'; a failed run backs the seed off (`webSeedRetryMs`, doubling to 60 s) and five in a row
  drop it.
  Payload goes through the swarm's download limits and is reported as a `torrent` connection
  with protocol `HTTP`; the swarm summary adds `WebSeedStats`. V2 and hybrid owners read
  `TorrentV2Document.webSeeds` (v2 magnets keep `ws` in the metainfo `TorrentV2MetadataExchange`
  builds) and run the same worker through `TorrentV2WebSeeds` (`WebSeedPieces` is the worker's
  view: `SwarmWebSeedPieces` for v1, the hub's for v2): an idle seed tells the session loop, which
  claims a run from `TorrentV2PieceScheduler.claimWebSeedRun` (`claimWebSeed`'s next free wanted
  piece from a cursor, extended as for v1 with the loop's peers and running seeds as the
  fetchers and active slots below the limit; in the endgame a single piece peers assemble that no
  seed fetches) and assigns it; the seed fetches it with one range request per file
  (`WebSeedV2Layout`: a v2 piece lies in one file, padding is never requested),
  `TorrentV2PieceStore.commitOwned` checks each piece against the piece layers, and `webSeedDone`
  returns each. Mirrors pay the engine's, owner's and task's limits, keep a transfer
  without peers alive while one runs, and add `WebSeedStats` to the v2 swarm summary
- Extra trackers and daily-updated tracker lists, on by default (ngosang's `trackers_best.txt` and
  XIU2's `best.txt`, from jsDelivr when GitHub fails, with a copy of ngosang's shipped for before
  its first download) for public torrents
- Magnet metadata is asked of four peers at once, failed peers again after 5, 15 and 30 s; tasks
  and previews keep looking (`DownloadSource.resolve`, in 2-minute lookups that ask given-up
  peers again) until found or stopped. The apps show such a task as Starting, "Finding peers",
  and a preview that takes 2 minutes offers Keep waiting and Add anyway
- Verified selected-file storage, ownership journal, restart rehash, live limits, and opt-in
  upload and seeding (`[torrent] upload`)
- Live file selection for v1, v2 and hybrid: a running swarm or v2 loop takes a new selection
  without reconnecting its peers. v1 keeps the files it created ("materialized" files) and their
  verified pieces when they are deselected, copies verified spans of boundary pieces from the
  task's sidecar into newly selected files and rehashes them, discards pieces no longer wanted
  without blaming the peer, and revokes rather than fails on an unreadable piece outside the
  selection; a recheck heals a piece from its sidecar. v2 and hybrid create or recheck newly
  selected files, refuse one whose path holds a file they did not create, keep deselected
  verified pieces readable, and bind their creation log without the selection once it changes.
  A seeding task whose selection grows downloads again and seeds once more. Trackers hear
  `completed` only once every file is selected and complete; admission charges every file
- Seeding control: `Completed.sharing` and the controller's `SEEDING` activity show it, and
  `TorrentController.setSeeding` (REST `PUT /api/torrents/{id}/seeding`, the inspector's Seed and
  Stop seeding) starts or stops it. `TorrentControls` (the source's `SourceExtension`) saves the
  intent in `TaskControl.sharing` once a task seeds, and after `Ketch.start()` seeds those tasks
  again, oldest completion first, under `upload =
  seed` and into free engine slots only, each after a recheck; a task whose files changed on disk
  loses the intent, one a download or shutdown stopped keeps it. `TorrentDownloadSource(...,
  restoreSeeding = false)`, as `ketch mcp --standalone` builds it, never restores
- Port mapping, `TorrentConfig.portMapping` (on; only engines whose `KotlinTorrentEngine`
  `portMapping` argument allows it, as `TorrentDownloadSource` always does, map ports, so engines
  built directly in tests never reach the router): `TorrentPortMapper` tries `NatPmpClient` (RFC 6886, to the
  `LocalIpv4Network.gatewayGuesses` of `localIpv4Networks()`, an expect/actual over
  `NetworkInterface` and `getifaddrs`), then `UpnpClient` (SSDP, the device description and SOAP
  over a minimal HTTP/1.1 client on direct TCP; only devices on one of our private networks). TCP
  and the UDP port DHT and uTP share map to one external port, which
  `TorrentAdvertisedPort.setMapped` announces; mappings last an hour, renew every half hour and are
  removed by `stop` within 2 s; UPnP tries other ports on 718 and permanent leases on 725.
  `PortMappingTest` runs both against scripted gateways on loopback
- Local Service Discovery (BEP 14), `TorrentConfig.localDiscovery` (on; only engines given a
  `KotlinTorrentEngine` `localDiscovery` transport factory, as `TorrentDownloadSource` passes
  `openLsdMulticast`, join the group, so engines built directly in tests never send multicast):
  `TorrentLocalDiscovery` joins `239.192.152.143:6771` and `[ff15::efc0:988f]:6771` while a public
  torrent's discovery runs (`participate`, from the same block as DHT, so private and tracker-only
  torrents never take part), announcing `BT-SEARCH` datagrams (`LsdAnnounce`: our TCP listen port,
  never the mapped one, a random cookie, up to 20 `Infohash` headers) when each starts and every 5
  minutes, at most once a minute per hash, to both groups (`LsdTransport.send` writes each group's
  `Host`), and hands the peers others announce to the torrent (v1: its discovery channel, counted as
  `lsd` in the swarm summary; v2: `PeerOrigin.LSD` offers, the truncated v2 hash in v2 mode and a
  hybrid's v1 hash in v1 mode). A public magnet's metadata lookup (`discoverMagnet`, not
  tracker-only ones) participates too, under its btih or the truncated btmh hash, until the metadata
  is found or the lookup stops, and asks the LAN peers announced for it for the metadata like DHT
  and tracker peers. It accepts private and link-local IPv4 and unique local IPv6 senders only
  (`lsdSenderAllowed`, `lsdPeerDialable`: IPv6 link-local senders are dropped, as `PeerEndpoint`
  carries no zone to dial them), never its own cookie, 32 at once then 4 a second, each peer once a
  minute per torrent. `openLsdMulticast` is one dual-stack `MulticastSocket` with `SO_REUSEADDR`
  (`JvmLsdTransport`, jvmAndAndroidMain) that joins the IPv4 group on each private IPv4 interface
  (`lsdInterfaces`) and the IPv6 group on each non-point-to-point interface with a link-local or
  unique local IPv6 address (`lsdIpv6Interfaces`), sending IPv6 announces to the group scoped to
  each interface (macOS finds no route to `ff15::` otherwise); its `isCurrent` compares both
  families' interfaces and addresses. It is `null` on iOS (multicast needs Apple's entitlement).
  `TorrentDownloadSource(multicastLock = ...)` holds a `TorrentMulticastLock` while the group is
  joined: the Android app's `KetchService` passes a Wi-Fi `MulticastLock`.
  `TorrentLocalDiscoveryTest` uses virtual time and `FakeLsdTransport`, `JvmLsdTransportTest` real
  multicast (skipped where the system refuses it)
- `TorrentConfig.listenPort` is the incoming TCP port; DHT binds the same port over UDP when it
  can (else any port), so one forwarded port reaches both. `0` lets the system pick at every
  start. Only `ketch server` sets one (`[torrent] listenPort`, `KETCH_TORRENT_PORT`,
  `--torrent-port`; 16881 in the Docker image): the apps and other commands may run beside it
- Apps open `.torrent` files from the system file manager (Android, desktop, iOS, installed web
  app) and resolve their content through `KetchApi.resolve`, like dropped files
- Native torrent engine dependencies exist only in interoperability tests
- uTP (BEP 29), `TorrentConfig.utpEnabled` (on by default): `UtpPacket` (codec, selective-ack
  and unknown extensions, `looksLikeUtp`) and `UtpSocket`, a deterministic state machine without
  I/O or clock (`receive`, `write`, `read`, `close`, `abort`, `tick`): SYN/STATE handshake,
  16-bit sequence wrap, reordering up to 2048 packets, selective acks, fast retransmit after
  three duplicate or overtaking acks, timeouts with backoff, LEDBAT congestion control (100 ms
  target, slow start until half of it), the receive window with zero-window probes, FIN and
  RESET. `TorrentUdpPort` shares one UDP socket per address family between DHT (`openDht`) and
  uTP, bound at the TCP listen port (any port when taken) when the engine starts, and again
  when it stops working; `UtpEndpoint` runs its sockets under one lock (dial, SYN answers through
  `TorrentConnectionBudget.admit` into `dispatchIncoming`, RESETs for unknown connections at
  most 64 a second, a 50 ms tick) and exposes each as a `TorrentConnection` (`utp`).
  `TorrentUtpFallback`, below the budget, dials TCP first and uTP (two SYN retries, 7 s) when
  TCP fails, except for the last 4096 peers known to speak uTP (BEP 11 flags the v1 swarm
  passes from PEX, `preferUtp`, or a uTP fallback that worked), which get uTP first and are
  forgotten when it fails. Peers reached over uTP carry the BEP 11 uTP flag in PEX, and the
  swarm summary counts uTP connections. Private and tracker-only torrents use uTP too: tests
  count DHT and UDP tracker datagrams (`countingPublicDiscovery`), not UDP binds
- Public v2/hybrid download, selection, limits, pause/resume and TaskStore restart are implemented.
  V2/hybrid incoming routing through one engine route table keyed by 20-byte wire tags
  (`TorrentRouteTable`, `launchIncoming`/`dispatchIncoming`), upload and seeding with 4-slot
  rechoke and optimistic unchoke (`TorrentChoker`, v1 and v2), BEP 52 hash serving (piece and
  block layers, `TorrentV2HashServer`), BEP 10 `ut_metadata`/`ut_pex`/`p` on v2 connections with
  PEX flags from one `pexFlags()`, and hybrid participation in v1 swarms (v1 peers, v1-topic
  trackers and DHT) are implemented. V2/hybrid owners live in the engine (`addV2Task`), so
  `removeTorrent`, slot lending and the upload setters work for either hash. Seeding lasts until
  it is stopped or the task removed, until `upload` leaves `seed`, until a download waiting for
  one of the `TorrentConfig.maxActiveTorrents` slots takes the oldest seeder's (the task keeps its
  intent), or until Ketch closes; a torrent that finishes while a download waits does not seed.
  Seeding runs only while the process runs
- Fast extension (BEP 6) on every peer connection: v1 (`TorrentSwarm`'s `PeerWorker`), v2 and
  hybrid ones, a hybrid's v1-swarm peers included (below). The handshake sets reserved byte 7 bit
  0x04 (`PeerHandshake.fast`), and with both bits set `PeerProtocolState(fast = true)` takes
  Have All / Have None in place of a bitfield (we send them when we hold every piece or none),
  Suggest Piece (ignored), Allowed Fast (at most 32, requested while choked through
  `TorrentPieceScheduler.claim`'s `eligible`) and Reject Request. A CHOKE keeps our requests
  until the peer rejects them; requests left unanswered for `FAST_REJECT_GRACE_MS` (5 s) are
  canceled. A reject while choked hands the piece back to the scheduler at once; unchoked, the
  block is asked for again until the piece has had as many rejects as blocks, then other peers
  fetch it (`refused` until the peer unchokes us anew). We reject requests we will not serve (choked or uninterested peer, a piece we lack, a
  full queue, a cancel), and while uploading offer IPv4 peers BEP 6's canonical allowed-fast set
  (`allowedFastSet`, k = 10, /24) and serve it while they are choked, within the upload limits.
  Fast messages on a connection that did not negotiate it close the peer. The v1 pipeline also
  stays within the peer's BEP 10 `reqq`. The summary counts `fast` peers
- On v2 and hybrid connections `PeerV2Connector` sets the bit (`PeerIdentityHandshake`'s `fast`,
  `PeerInfo.fast`) and `PeerBlockExchange(fast = true)` runs the same `PeerProtocolState`: a CHOKE
  keeps requests, `releaseChoked` cancels those still unanswered after `FAST_REJECT_GRACE_MS`
  (the inbox's `Released` event; the actor hands them back like dropped ones), allowed-fast
  requests stay. The session loop follows Have All, Allowed Fast and rejects per peer
  (`TorrentV2PeerView`'s `allowedFast`, `rejects`, `refused`) when it plans requests, and
  `TorrentV2Uploader` sends Have All / Have None, rejects what it will not serve (batched as
  `Command.Reject`, also after its CHOKE), offers `allowedFastOffer` for the connection's wire
  tag (`Command.AllowFast`, only while uploading) and serves those pieces while it chokes the
  peer. BEP 52 hash rejects are separate messages. The v2 summary counts `fast` peers too
- See [support and migration](docs/torrent.md) and
  [verification](docs/development/torrent-verification.md)

### AI-Driven Resource Discovery (`ai:discover`) — In Progress
- LLM agent-driven discovery using Koog framework (v1.3.0)
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
- Settings → Discover lists the saved providers (`ProvidersGroup` in
  `ui/settings/AiProviderSettings.kt`): the one in use is checked, Use switches, and Add provider
  or Edit opens a dialog (provider, name, key, endpoint, model candidates, which it can load from
  the provider, and Test) that saves only on Add or Save (`AiSettingsController.addProvider`,
  `saveProvider`; `testConnection` and `loadModels` also take an unsaved `LlmSettings`). The
  Discover composer's model chip (`DiscoverModelChip`) switches provider and model
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
  desktop, notifications, integration and power sections
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
  trackers are only editable on that device. `upload` (`off`/`while-downloading`/`seed`,
  `TorrentUploadMode`, mapped to `TorrentConfig.uploadPolicy` by an exhaustive `when` at each of
  the four `TorrentConfig` sites) and `uploadLimit` (`TorrentConfig.uploadRateLimit`), both read
  leniently (a value of any type that cannot be read loads as off or unlimited, and a bare
  `uploadLimit` number is bytes per second), are applied live through
  `TorrentDownloadSource.setUploadPolicy`/`setUploadRateLimit`. `encryption`
  (`TorrentEncryptionMode`: `preferred`/`required`/`off`, unknown values load as `preferred`,
  mapped to `TorrentEncryption` at the four sites), `utp`, `portMapping` and `localDiscovery`
  (all on), edited under Settings → BitTorrent → Connections (local discovery only on desktop
  and Android, `localPeerDiscoverySupported`), apply live through
  `setEncryption`, `setUtpEnabled`, `setPortMapping` and `setLocalDiscovery` (the engine's
  `TorrentPeerEncryption.policy`, a uTP endpoint that refuses new connections while off, a port
  mapper started or stopped, and the LAN discovery group joined or left)
- `SpeedSettings`: the embedded device's speed mode (Full speed, Slow lane, Auto with weekly
  `SpeedRule`s), applied by the apps' `SpeedModeController`; `UiPreferences` (`[ui]`): view
  state such as table columns, sort, sidebar, inspector, density, per-device add sheet defaults,
  Discover's docked history (`discoverHistory`) and onboarding; `DesktopSettings`: close action,
  open at login, Dock badge, the speed in the macOS menu bar (`menuBarSpeed`), daily update
  checks; `NotificationSettings`,
  `IntegrationSettings` (magnet and `.torrent` handlers) and `PowerSettings` (`[power]`:
  `keepAwake`, on by default)
- `[[hooks]]` tables hold `KetchConfig.hooks` (`HookConfig`), outside `DownloadConfig`, so
  `PUT /api/config` and `updateConfig` never see them; no UI edits them. Event, origin, category
  and host names are kept as strings (`HookConfig.eventSet` drops unknown events) and a
  `timeoutSeconds` that is not a number loads as the default; see
  [Automation hooks](#automation-hooks-hooks)
- `[[download.categories]]` tables hold `DownloadConfig.categories` (`folder`, `extensions`,
  `mimeTypes`, `hosts`), edited in Settings → Downloads (`ui/settings/CategorySettings.kt`, with
  `SuggestedCategory` presets named in the app's language and the field parsing in
  `state/CategoryRules.kt`) for any device listing the feature; the add sheet's Save to pill
  shows the category folder a single link goes to (`IntakeSession.categoryFolder`)
- Apps edit it in Settings, `SettingsCategory` pages in two groups: *This app* (General,
  Notifications, Integration, Discover, About) and *Device* (Downloads, Speed, Network,
  BitTorrent, Sharing). Desktop opens Settings in a window of its own (⌘, / Ctrl+,), wider
  windows elsewhere show it in place of the content card and phones as a page. Changes apply as
  they are made; there are no Save buttons
- The Device pages edit the device chosen in Settings, by default the one shown, through its
  `InstanceSettingsController` (`AppState.settingsFor`): pushed live via
  `KetchApi.updateConfig` / `selectNetworkInterfaces`. Downloads settings are saved to
  `config.toml` only for the embedded instance; the network selection is runtime-only and
  never saved. The Network page also edits the device's proxy (`ProxySettings`), shown for
  devices listing `KetchFeatures.PROXY`. On devices listing `KetchFeatures.TRANSFER_TIMEOUTS`,
  the Downloads page's Retries stepper ends in Unlimited (`unlimitedRetries`) and a Stalled
  connections stepper sets `idleTimeoutMs` (Off, 15 s to 5 min); `minSpeed` has no control yet
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
  (defaults would serve on every interface without the token), `ketch mcp --standalone` and
  `ai-discover` warn on stderr and use the defaults, and an unreadable `--config` file stops
  `ketch mcp --standalone`

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
- Android 17 blocks the local network until `ACCESS_LOCAL_NETWORK` is granted: `NearbyAccess`
  asks before searching for or connecting to a device (13 to 16 ask `NEARBY_WIFI_DEVICES` only to
  search), and `MainActivity` asks once a remote device is saved or this device is shared, and
  once ever (`local_peers_offered`) when local peer discovery first wants the LAN
  (`KetchService.lanPeerSearchStarted`, set as the engine takes its multicast lock); switching
  Settings → BitTorrent → Local peer discovery on asks through `NearbyAccess`. The engine knows
  nothing of it: without the permission it fails to join the group and retries
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
- Keep awake (`[power] keepAwake`, Settings → General → Power on desktop and Android,
  `keepAwakeSupported`): `KeepAwake.follow` holds a host's `SleepInhibitor` while the embedded
  device's tasks download or wait in the queue (`ForegroundStatus.keepsAwake`; the local server
  and Discover never count), and releases it when they stop, the setting goes off or its scope
  ends. Each keeps the system from sleeping while idle only, never stopping the user's own sleep,
  lid close or the display turning off: desktop's `systemSleepInhibitor` takes an IOKit
  `PreventUserIdleSystemSleep` assertion on macOS, `SetThreadExecutionState` from a thread of its
  own on Windows and a logind `idle` lock through `systemd-inhibit` on Linux, taken again with
  backoff when it ends while still wanted (a `sleep` lock would need an administrator for the
  user's own suspend; GNOME ignores `idle` locks); Android's
  `WakeLockInhibitor` is a partial `WakeLock`, held only while `KetchService` is in the
  foreground, which keeps it out of Android vitals' excessive wake lock count
- Task states: `waitsInQueue` and `isPausedUntilResumed` (`state/TaskStates.kt`) decide
  everywhere that a task paused for an urgent download counts as waiting (Waiting tab, Start
  now, Pause all) rather than paused. Rows say why the engine paused a task, where a queued one
  waits ("next in line", "2 ahead") and when a finished one finished (Finished column and sort,
  Smart "Finished today" groups). A task waiting for its files says "Choose files to
  download" (Choose files…, and Download all files unless its source offers a single choice) and a seeding one "Seeding" (Stop seeding; Seed
  is in the inspector only). Auto connections and queue positions only show for devices whose
  `KetchStatus.features` list them (`AppState.featuresOf`; the embedded engine has all,
  `EmbeddedFeatures`)
- Remote devices send what is shown: every task's state at once and progress every second; the
  segments of the rows on screen every second on desktop and the web (`WatchShownTasks`; phones
  leave them out) and of the inspected tasks every 500 ms (`AppState.followSegments`); while the
  app is in the background (`InstanceManager.setInForeground`) only state changes
  (`RemoteKetch.foreground`)
- Live connections grid: on the Downloads page the Pulse bar shows, left of the device's
  connection from 540 dp, three rows of up to twelve squares, one per live connection of the
  shown devices listing `net.activeConnections` (oldest top left, then "+N"); hovering one names
  it, a click opens `ConnectionsPopover` (count and speeds, a legend, cells grouped by task in
  list order and under All devices by device, the hovered, focused or picked cell's details with
  Show task, which opens the inspector on its Connections tab for HTTP and FTP, and "N devices
  don't report connections"). Phones show a 3×8 mini strip after the summary line and the
  popover's content in the Pulse sheet. `ConnectionGridModel` asks each online device for its own
  stream (`AppState.connectionGrid.state(36)` and `state(512)`, the limit split between
  devices) only while one is on screen, stopping 5 s after, and asks again with backoff (1 s
  doubling to 30 s) when one fails or ends; a device refusing it as unsupported is listed, not
  asked again. Cells draw on one canvas (`components/KetchCellGrid`, hit-testing, arrow keys and
  Enter, a live-region description of the picked cell) in `KetchColors.traffic`: download is the
  accent, upload the seeding teal (Purple's fill for the Teal, Green and Blue accents,
  `TrafficColorsTest` keeps them ΔE ≥ 20 apart), in four levels by fixed rates (under 16 KB/s,
  256 KB/s, 2 MB/s, and more); download solid, upload a ring, both split along the diagonal,
  idle a hairline outline
- Files: the inspector's Files tab lists the files or variants of any task whose resolved source
  names some (torrents also page names from `controller(TorrentController)`) and, on devices listing
  `download.fileSelection`, checks and unchecks them with an Apply bar (`FileSelectionModel`, at
  least one file stays checked), or picks one with radio buttons (`KetchRadio`) for a
  `FileSelectionMode.SINGLE` source, and starts a waiting task. "Add anyway" in the add sheet
  sets `awaitFileSelection` for a link whose files are not known yet on devices listing
  `download.awaitFileSelection`; the add sheet and the Files tab sort
  with one `FileOrder` (Name in natural order, Size, Kind by `FileKind`, Selected first, Torrent
  order, and Progress in the inspector), reversible and saved per surface in `[ui] sort`
  (`torrentFiles.intake`, `torrentFiles.inspector`). The server's `extension` order is the plain
  file extension, not the apps' `FileKind` groups
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
  progress). The tray icon (`TrayIcon.kt`) is an AWT `TrayIcon` of its own rather than
  Compose's `Tray`, which squeezes every image into a square: its sail fills from the foot as
  downloads progress, the macOS menu bar shows the speed after it (`[desktop] menuBarSpeed`)
  and Windows paints it in the app icon's gradient
- Release notes (desktop, direct Android): What's new on the update toasts, and Settings → About,
  opens `ReleaseNotesDialog` (`ui/settings`; `AppState.releaseNotesRequest` for the toasts),
  which reads `AppUpdates.releaseNotes(version, since)`: the GitHub releases after `since`
  (`releasesBetween`), whose generated pull request lines `releaseChanges` groups into New, Fixed
  and Improved, leaving out developer-only types and `deps`. Each launch records its version as
  `[ui] lastVersion`; a newer one toasts "Updated to Ketch …". About's Release history
  (`ReleaseHistoryDialog`, `AppUpdates.releaseHistory`, `releasesFrom`, which only pages through
  the release list) lists every published release since 0.1.0, each opening to its changes. See
  [updates](docs/updates.md#release-notes)
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
- Event stream (`StreamRoutes`, `EventHub`; `/api/status` lists `events.stream`): `GET
  /api/stream` opens one SSE stream per client (at most 32, 429 `too_many_streams` beyond; a
  comment every 15 s, which also frees the streams of clients that left). It sends `hello` (the
  stream ID), `subscribed`, `tasks` (every task), then each task's lifecycle and state changes
  at once (`task.added`, `task.removed`, `task.state` with the queue position, and the request
  when it changed), and what `PUT /api/stream/{id}/subscription` asks for: every downloading
  task's progress every `progressIntervalMs`, the `segments` of the tasks it names as
  `task.segments`, and per `StreamInterest` (stats, live connections, torrent snapshots or any
  interest `StreamSerializers` names) `interest` events with its ID and values encoded with its
  `valueSerializer` from `KetchApi.subscribe`, then `interest.end` when what it follows is gone or
  `interest.error` (`unsupported`, also for interests the server does not know). Each at most once
  per interval step (200 ms to 60 s; the first at once, an end or error at once after the value
  it held back). One `TaskFrame` per task and one upstream per interest, each value encoded once,
  are shared by every stream; each stream sends the difference to what it sent, so a slow client
  skips values instead of queueing them. `subscribed` comes before any event of the interests it
  adds and none follow for those it drops. A revision not above the applied one changes nothing;
  404 `unknown_stream`, 400 `invalid_subscription`. Task views and events never carry a torrent's
  metainfo
  (`ResolvedSource.withoutBulkMetadata()`)
- `/api/events` (deprecated, for older clients): every change of every task; `TaskSnapshot` and
  `state_changed` carry the task's `queuePosition` (a queue change sends `state_changed` for every
  waiting task whose position moved), `progress` carries none and leaves out a request that did
  not change
- Torrents: `PUT /api/tasks/{id}/files` (`FileSelectionRequest`) chooses a task's files; with
  `torrent.control` (the instance's `controller(TorrentController)`; without one the routes are
  not installed and answer `404`), `/api/torrents` serves `capabilities`, a task's snapshot, sorted
  file pages (`files?limit&cursor&sort=torrent|name|size|extension|selected&desc=true`),
  `selection` and `seeding` commands guarded by a revision and an idempotency key, and an SSE
  `events` stream of snapshots (deprecated: the event stream carries them). Command failures
  answer with the `TorrentCommandError` wire name
- Live connections (`ConnectionRoutes`): `GET /api/connections?limit=1..1024` answers the first
  `ActiveConnections` snapshot and `/api/connections/events` (deprecated: the event stream
  carries them) streams `snapshot` events (id: a per-stream count), repeating the last one every
  15 s while nothing changes; `error`
  (`ErrorResponse`) ends it. Another limit is 400 `invalid_limit`, an engine without them 501
  `unsupported` (or that `error` event), and past 16 open streams (`ConnectionStreams`) 429
  `too_many_streams`. Behind the token and startup gate like every route; hosts and peer
  addresses never go on `/api/events`. Event names and codes: `ConnectionEvents` in endpoints.
  `RemoteKetch` follows `Interest.Connections` on the event stream, failing with
  `UnsupportedOperationException` when the server answers `unsupported`; against older servers
  it asks `features()` first, failing the same way for servers lacking `net.activeConnections`
  (and, on the old stream, for a 404/405 without a body, 501 or `unsupported`), and reads the
  stream as text in a `channelFlow` (Darwin runs `execute` elsewhere). It drops repeats and ends
  with the failure when the stream drops or closes, never reconnecting
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
  `path_rejected`. A request sent again with a task's `requestId` keeps that task's destination
  when it names the same place (`DestinationPathPolicy.confinesTo`), as the task's own file would
  otherwise give it a new name. With a token and no list, callers may save anywhere, as the apps
  let owners type any folder on a remote device
- `POST /api/tasks` takes `checksum` as text (`"sha256:<hex>"`; a malformed one is 400), and
  task views carry it in `request` and the verified one in `Completed.checksum`; a mismatch is a
  `checksum_mismatch` error. `RemoteKetch.download` refuses a checksum for servers without
  `KetchFeatures.CHECKSUM`
- `PUT /api/config` keeps the server's value of every field the body leaves out (merged over
  the current config), as clients older than category folders or idle timeouts send it
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
- Remote backend (`RemoteKetch`) communicates via HTTP + SSE. Against servers listing
  `events.stream` it reads `/api/stream`, whose task snapshot replaces `GET /api/tasks`: every
  task's lifecycle and state at once, progress every `progressInterval` (1 s by default, `null`
  for none), and the interests its callers `subscribe` to, merged into one subscription
  (`StreamInterests`: segments by interval step, each other interest once at its shortest
  interval, under an ID it keeps while held) and sent again on every connection; it applies each
  subscription when the stream's `subscribed` event arrives, ignoring events of interests it does
  not name. `foreground = false` drops to lifecycle and state only. Every interest comes from that
  stream while it runs (`interest.error` `unsupported` becomes `UnsupportedOperationException`);
  otherwise segments, stats, connections and torrents come from their own endpoints and others
  are unsupported. Older servers get `/api/events`. Calls the server refuses throw
  `RemoteApiException` with the status, the `ErrorResponse` code and message (the HTTP status
  without one) and `Retry-After`, unless `KetchApi` names another exception for the case. The
  apps show the server's message, except for `path_rejected`, which their own text explains
- Auto-reconnection with exponential backoff
- `ketch server` starts listening, then restores the tasks saved in `ketch.db`, so a daemon that
  cannot bind never resumes them; `Ketch.start` keeps downloads added meanwhile in the list,
  and a failed restore stops the server
- Only one engine runs the downloads of a config directory, holding its `instance.lock` while it
  has `ketch.db` open. `ketch server` and `ketch mcp --standalone` (`claimDownloads`,
  `CliInstance`) refuse to start while another process holds it, naming the command
  `instance.json` describes while its pid runs, else the desktop app (`otherEngine`), and while
  the app runs at all, as one from before the lock runs them without it. Once their tasks are
  restored, they describe themselves in the owner-only `instance.json` (command, pid, loopback
  URL, token) for the attaching commands and the app
- The desktop app takes the lock before it opens `ketch.db` and keeps it until its process exits,
  removing an `instance.json` a stopped command left (`DownloadsLock`). Opened while a command
  holds it, it waits up to 3 s for the command to say where it listens, then starts without an
  engine (`DownloadsClaim.Taken`): the command's API, only at an `http` address of this machine
  (`CliEngine.device`), is a device named after the command (`InstanceManager.standIn`, listed
  first, shown at launch and never saved), `LocalApiServer` serves a client of it (and refuses
  clients when there is none), and banners name the command and pid and, once it exits, say to
  reopen the app to run the downloads there
- Health: `GET /api/health` (`Api.Health`, no token) answers `200 {"status":"ready"}` once the
  tasks are restored and `503 {"status":"starting"}` before. `KetchServer(ready = false)` plus
  `markReady()`, which `serveDaemon` calls after `KetchApi.start()`; other embedders are ready
  from the start. Until then `startupGate` answers every other API route but pairing with `503`
  `starting` and `Retry-After: 1`, so no download races the restore. `ketch health` asks it (0
  ready, 1 not, 2 usage) at the URL the server published in `instance.json`, which follows its
  options, else where the config file and environment variables say (`healthCheckUrl`)
- `ketch server` configuration layers: flags over `KETCH_*` environment variables
  (`ServerEnv`, `applyServerEnvironment`; blank counts as unset, an unusable value exits 2 naming
  it) over `config.toml`. It exits 1 when it cannot start and 2 for invalid options

### Native CLI (`cli/`)
- Released as a GraalVM native binary; reflection and resource metadata lives in
  `META-INF/native-image/<module>/` of the module that needs it (`cli`, `library:mcp` for the MCP
  SDK, `ai:discover` for Koog's clients). The binary has no kotlin-reflect: it is excluded from
  `nativeImageClasspath` as well as `runtimeClasspath`, as native-image would otherwise compile it
  in without its metadata, and Ktor's StatusPages failed every error response printing a lambda.
  It is built with `--install-exit-handlers`, so SIGINT and SIGTERM run the shutdown hooks, with
  `-Os` on GraalVM for JDK 23 and later and `-Ob` before (smaller than the default `-O2`), and
  bundles the web UI with its wasm and JS gzipped, which `KetchServer` sends compressed to
  browsers that accept gzip
- `ketch add`, `list`, `pause`, `resume` and `watch` work on a running instance through
  `RemoteKetch` instead of an engine of their own (`InstanceCommands`). `InstanceLocator` takes
  `--server` / `KETCH_SERVER` (with `--token` / `KETCH_API_TOKEN`), else the desktop app, asked
  over its `SingleInstance` endpoint for `LocalApiServer` (`DesktopApp`, request `cli/connect`;
  an older app's empty reply asks to update it), else the `ketch server` or
  `ketch mcp --standalone` that `instance.json` names, when its pid runs. Results go to stdout,
  everything else to stderr; exit 0, 1 on failure, 2 for invalid arguments (`InstanceExit`).
  Task IDs may be shortened to a unique prefix
- `ketch add` sends a `requestId` (`--idempotency-key`: a UUID as it is, other text a name-based
  UUID) and sends it again, up to three times, when the connection fails or the instance answers
  5xx (`submitDownload`); a destination is made absolute for an instance on this machine.
  `ketch watch` prints task JSON lines (`snapshot`, `added`, `state`, `progress`, `removed`),
  progress every second or every `--interval` (`500ms`, `2s`, or seconds); with IDs it exits
  once they finish, and it exits 1 when the connection is lost
- `ketch <url>` downloads through `[download.proxy]` of the config file, `ketch add` through the
  instance's setting; `--proxy <url>`, `--proxy-bypass <hosts>` and `--no-proxy` (`ProxyOptions`)
  set `DownloadRequest.proxy` instead, which `ketch add` refuses for an instance without
  `KetchFeatures.PROXY`
- `ketch <url>` and `ketch add` take `--checksum <digest>` (`sha256:<hex>`, also `md5`, `sha1`,
  `sha512`, base64 or bare hex; `parseChecksumOption`), a malformed one exiting 2; `ketch add`
  refuses it for an instance without `KetchFeatures.CHECKSUM`, and task JSON carries `checksum`
  and `verifiedChecksum`
- `ketch <url> --files <ids>` downloads only those files of a torrent
  (`DownloadRequest.selectedFileIds`); `ketch --list-files <url>` resolves the URL and prints
  `ID  SIZE  PATH` rows instead (`fileListRows`), in the order of `--sort name|size|type` (`type`
  is the file extension) and `--reverse`, exiting 1 when it cannot list them. Invalid download
  options, such as `--list-files` with `--files`, exit 2
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

### Automation hooks (`hooks`)
- `[[hooks]]` in `config.toml` (`HookConfig`): `events` (`added`, `started`, `completed`,
  `failed`, `canceled`, `removed`; `HookEvent`), then `command` (an argv list, never a shell
  line, with `workingDirectory`) or else `webhook` (`http(s)` URL, `headers`, `secret`), plus
  `timeoutSeconds` (30), filters `origins` (`ketch.origin`), `categories` (folder of the
  `DownloadConfig.categoryFor` match) and `hosts` (subdomains included), `name` and `enabled`.
  `Hook.of` leaves out, with a warning, hooks without a usable action or known event
- `HookRunner.attach(ketch)` collects `KetchApi.tasks` and each task's `state`: a task created
  after attaching is `added` (restored ones never are) and starts from no state; others start
  from the state first seen. `transition(previous, current)` names the event: `started` on
  entering `Downloading`, `completed` when not completed before or `completedAt` changed (once
  per completion; `sharing` changes are none), `failed`, `canceled`; leaving the list is
  `removed`. Deliveries go through a 256-slot channel to 4 workers (dropped with a warning when
  full), so downloads never wait
- `HookPayload` (JSON; `environment()` gives the `KETCH_*` variables): event, timestamp,
  `deliveryId`, task ID, state, `redactUrl`-ed URL, host, file name, output path, sizes,
  download time, origin, category, source type and `HookError` (kind, HTTP code, message through
  `redactUrlsIn`). Commands (`CommandRunner` over `ProcessLauncher.System`, a `ProcessBuilder`
  discarding output) get it on stdin and in the environment, never in argv, and are killed with
  their descendants after the timeout; cancelling leaves them running. Webhooks
  (`WebhookSender`, a CIO client without redirects) `POST` it with `X-Ketch-Event`,
  `X-Ketch-Delivery` and, with a secret, `X-Ketch-Signature: sha256=<HMAC-SHA256 hex>`, retried
  after 2 s and 10 s on transport errors, timeouts, 408, 429 and 5xx
- Logs (tag "Hooks") name a hook by `name`, its program's file name or its webhook's host; never
  the URL, headers, secret, arguments or exception messages (which quote URLs)
- Only the process holding `instance.lock` runs hooks: `ketch server` and
  `ketch mcp --standalone` (`HookRunner.start` before `start()`, closed by the shutdown hook),
  and the desktop app's embedded engine (`InstanceFactory.embeddedFactory`, never when the claim
  is `Taken`). `ketch <url>` and the attaching commands run none; Android, iOS and the web run
  none (webhooks on Android are a possible follow-up). Hooks are read at launch only. See
  [hooks](docs/hooks.md)

### Self-update (`updater`)
- Shared by desktop, direct Android and the CLI: `GitHubReleases` reads the latest (or a tagged)
  release, or a page of them with their notes (`ReleaseFeed.releases`, `releasesBetween`), from
  the GitHub API, `Release.asset` / `androidAsset` pick the file by the workflow's
  names, and
  `ReleaseDownloader` downloads it with a private Ketch engine, which checks the SHA-256 digest
  GitHub publishes per asset (`DownloadRequest.checksum`; files without one are refused). Pass it the process's logger:
  every `Ketch` installs its logger globally
- `ReleaseVersion` orders `rc9` before `rc15` and pre-releases before their release
- `ketch update [--check] [--version <v>]` replaces the native binary (`CliInstallation`): renamed
  over the old one on macOS/Linux, the old one set aside as `ketch.exe.old` on Windows; refuses
  on a JVM. See [updates](docs/updates.md)

### MCP Server (`library:mcp`)
- `KetchMcpServer` exposes any `KetchApi` over stdio or SSE through Koog's MCP server bridge
- `KetchToolSet` provides 14 tools: list/get/start/pause/resume/cancel/remove downloads,
  `resolveUrl` (without the metainfo), `listDownloadFiles` (`cursor`, `limit` up to 1000, `sort`
  and `descending`, from `controller(TorrentController)`, else the request's resolved files),
  `selectDownloadFiles` (comma-separated IDs), `getStatus`, `setSpeedLimit`, `setPriority`,
  `updateConfig` (also `retryCount`, -1 for unlimited, `idleTimeoutSeconds` and `minSpeed`,
  each kept when left out); `startDownload` takes `fileIds` and `checksum` (`sha256:<hex>` or the
  like), and task JSON has `selectedFiles`, `totalFiles`, the request's `checksum` and a
  completed task's `sharing` and `verifiedChecksum`. There is no seeding tool. Downloads it starts
  carry `DownloadRequest.properties["ketch.origin"] = "agent"`
- Agent tools are `TextTool`s (`library:mcp` and `ai:discover` each have a copy): a name, a
  description and string or integer parameters (also boolean ones in `library:mcp`), required
  unless the tool has a default, written out by hand and called with the arguments by name.
  They need no kotlin-reflect, unlike Koog's `ToolSet`, and pass `String` results on as they
  are, so tools return their JSON as text. A missing or malformed argument is a
  `ToolException.ValidationFailure`
- `KetchToolSet` and `KetchMcpServer` take `connect: suspend () -> KetchApi`, asked on every
  call (or a `KetchApi`); what it throws fails the call with its message. `startDownload` takes
  an optional `requestId`, refused by an instance without `KetchFeatures.REQUEST_ID`
- `ketch mcp` attaches to the instance the CLI's `InstanceLocator` finds (`InstanceConnection`):
  when a tool first needs it, and again once the `RemoteKetch` is no longer connected, as when
  the app restarts on another port. `--standalone` runs its own engine on `ketch.db` instead,
  with a loopback `KetchServer` (per-run token) the attaching commands use. It passes the real
  stdout to `startStdio` and redirects `System.out` to stderr, so the banner, the console logger
  and Logback never corrupt the JSON-RPC stream
- Stdio uses Ketch's own `StdioTransport`: when stdin ends it answers the requests already read,
  then closes, and `startStdio` returns (`Server.onClose` only fires on `Server.close()`, so it
  waits for the transport). The SDK's `StdioServerTransport` drops those replies. `ketch mcp`
  then calls `exitProcess`, so a non-daemon thread cannot keep it alive; its shutdown hooks close
  the connection, or `Ketch` with `--standalone`
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
  `NativeHostRegistration`, `LocalApiServer`). The host asks the running app over
  `SingleInstance`, opening it if needed, for a loopback-only `KetchServer` on a free port with a
  per-run token, separate from the Settings server, over the app's engine or, while a
  `ketch server` runs the downloads instead, a client of it; the `ketch` CLI asks for the same
  server (`CLI_CONNECT_REQUEST`), which then does not count as an extension connection. The app
  registers the host with installed browsers on every launch; the Chromium extension id is pinned
  by the manifest `key`. The browser reads the host's stdout as length-prefixed messages, so
  nothing else may write there: the launcher sends the JVM's own warnings to stderr (`-Xlog` in
  `app/desktop/build.gradle.kts`)
- Captures browser downloads (Chromium holds them in `onDeterminingFilename`, Firefox pauses
  them) and falls back to the browser when Ketch fails; context menus per instance; a content
  script sends trusted magnet link clicks
- Forwards cookies, referrer and user agent as `DownloadRequest.headers`; `.torrent` downloads
  whose URL Ketch would not recognize are fetched and resolved via `POST /api/resolve/content`
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
- `Ketch` logs every task state transition; torrent swarms (v1 `TorrentSwarm`, v2/hybrid
  `TorrentV2SessionLoop`) log a debug summary every 30s with how their peer connections ended
  (`PeerDiagnostics`, `PeerOutcome`), at info when they run out of peers; `PeerSurveyTest`
  (`KETCH_TORRENT_SURVEY`, `KETCH_TORRENT_SURVEY_WIRE`) measures public swarms
- See [logging](docs/logging.md) for the format, troubleshooting and sensitive-data rules
- `KetchLogger` uses `inline` functions with `Logger.None` fast-path for zero-cost disabled logging
- `Logger` interface accepts `String` messages; `KetchLogger` builds them lazily, only when
  `Logger.isLoggable(level)`

### Error Handling (sealed `KetchError`)
- `Network` (retryable), `Http(code)` (5xx/429 retryable), `Disk`, `Unsupported`,
  `FileChanged`, `CorruptResumeState`, `Canceled`, `SourceError`,
  `AuthenticationFailed`, `ChecksumMismatch(algorithm, expected, actual, published)`, `Unknown`
- I/O exceptions from `FileAccessor` classified as `KetchError.Disk`
- `KetchError.tolerantSerializer()` (used by `DownloadState.Failed`) reads unknown error types
  as `Unknown`

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
- `DownloadSource` interface for protocol-level extensibility; `DownloadSource.attach` returns a
  `SourceExtension` for protocol controllers (`KetchApi.controller(ControlKey)`) and task lifecycle
  hooks, so `library:api` and `library:core` stay protocol-neutral
- `Logger` interface for logging backends

### Expect/Actual for Platform Code
- `FileAccessor` (`createFileAccessor()`): okio `FileHandle` via `PathFileAccessor` on every
  engine platform; `ContentUriFileAccessor` for Android SAF; `NoOpFileAccessor` for
  self-managed sources. Accessors serialize I/O with `limitedParallelism(1)`
- `platformFileSystem`: okio `FileSystem.SYSTEM` on Android/JVM/iOS, `NodeJsFileSystem` on JS,
  `WasiFileSystem` on WasmWasi; the `config` module's copy throws on WasmJs
- `KetchDispatchers.Default` (`createDefaultDispatchers()`), created once per process and shared by
  every `Ketch` given no dispatchers: Ketch's own daemon threads, apart from the app's
  `Dispatchers.IO`/`Default`. On Android/JVM (`jvmAndAndroidMain`, `ketchThreadPool`) at most 8
  `ketch-network-N` and 8 `ketch-disk-N` threads, started only when every thread is busy and ended
  after 30 s idle; on iOS two pools of 8; `Dispatchers.Default` on JS/WasmWasi. `main` is `network`
  itself, so there is no coordination thread, and each `Ketch` takes a serial view of its own
- Torrent threads (`TorrentDispatchers`): the engine, peers and hashing run on
  `KetchDispatchers.Default.network`, file I/O on its `disk`, DNS and interface lookups on a
  slice of `disk` (2 at once) and local discovery's blocking receive on another (1), which its
  sends never wait for. Peer sockets on JVM/Android are `NioTorrentNetwork`'s: one
  `ketch-torrent-selector` thread waits for readiness and the caller's dispatcher reads and
  writes. Not ktor-network, which runs every socket's loops on `Dispatchers.IO` (hardcoded), so
  hundreds of peers grew that pool to its 64 threads; HTTP (CIO) and FTP sockets still do, with
  few connections
- HTTP threads (`library:ktor`, `HttpDispatcher.kt`): the clients `KtorHttpEngine` builds run on
  `ketch-http-N` daemon threads, started only when every one is busy and ended after 30 s idle:
  OkHttp (`httpDispatcher`), which reads each response body there while it streams, at most 64 at
  once; CIO, whose selector, connections and name lookups run there, at most 8 per client
  (`cioDispatcher()`, a slice of its own, as each client's selector keeps a thread of it), as a
  burst of its short tasks would otherwise start a thread each. Our apps cap the app-wide
  `Dispatchers.IO` at 16 (`kotlinx.coroutines.io.parallelism`) and idle workers at 10 s: the desktop
  app's `main` (and its launcher's `jvmArgs`), the CLI's `main` (and `native-image`'s `-D`
  arguments, as the binary initializes kotlinx.coroutines while it is built) and the Android
  `KetchApplication`'s constructor. The library sets neither
- Console logger: platform-specific implementations

### Coroutine-Based Concurrency
- Each `Ketch` serializes task coordination on a `limitedParallelism(1)` view of
  `KetchDispatchers.main`; `network` runs transfers and `disk` blocking file I/O
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
- `library:api` marks module-shared helpers `@InternalKetchApi` and requires
  `KetchImplementationApi` to implement `KetchApi`/`DownloadTask`; the root build opts in. Update
  its API dump with `./gradlew :library:api:updateKotlinAbi`

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
  "TorrentTracker", "TorrentWebSeed", "TorrentLsd", "TrackerList", "TorrentController",
  "TorrentControls", "Connections", "RemoteKetch", "RemoteTask", "RemotePairing",
  "RemoteTorrents", "TokenBucket", "SqliteStore",
  "SqliteDriver", "ConfigStore", "KetchServer", "ServerRoutes", "DownloadRoutes", "TorrentRoutes",
  "ConnectionRoutes", "EventRoutes", "StreamRoutes", "EventHub", "Pairing", "McpStdio",
  "GitHubReleases", "Hooks";
  `ai:discover`, mDNS and app code tag by component name (e.g. "DiscoveryService", "KetchService")
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
5. `library:torrent` has no browser-local engine. uTP is only dialed after TCP fails; seeding runs
   only while the process runs (no background seeding). Local service discovery does not run on
   iOS, and over IPv6 only finds peers with a unique local address.
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
6. **Work-stealing segments** - The `LaneScheduler` / `RangeLedger` from the helper devices
   [proposal](docs/design/multi-instance-downloads.md), shipped on its own first: connections
   that finish claim the remaining bytes of slower ones, slow tails split, a failed range retries
   alone instead of cancelling the batch, and a minimum segment size keeps small files whole
7. **Power options** - The apps can quit, sleep or shut down once the queue is empty, driven by
   the busy signal keep awake already follows (`ForegroundStatus.keepsAwake`)
8. **Cross-device Task Transfer** - Send to / Move to carry a task's downloaded data (partial
   bytes, segment progress, resume state, finished files) between instances, so the destination
   continues instead of starting over; see the [plan](docs/plans/task-transfer.md)
9. **Helper devices** - Paired Ketch instances relay byte ranges of one download through their
   own network and IP, joining or leaving mid-download without pausing it; see the
   [proposal](docs/design/multi-instance-downloads.md). Its scheduler ships first, as item 6
