# Kotlin torrent downloads

`library:torrent` downloads BitTorrent v1, v2, and hybrid content in common Kotlin on JVM 11+,
Android 26+, and iOS (arm64 and arm64 simulator). The desktop app, Android app, iOS app, CLI, and
daemon register `TorrentDownloadSource`. Browsers control the daemon through `RemoteKetch`; the
[browser extension](../app/browser-extension/README.md) sends clicked magnet links and `.torrent`
downloads to an instance's REST API.

```kotlin
val torrents = TorrentDownloadSource(TorrentConfig())
val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  additionalSources = listOf(torrents),
)
ketch.start()
val resolved = ketch.resolve(torrentUrl) // HTTPS .torrent URL or btih/btmh magnet
val task = ketch.download(
  DownloadRequest(
    url = torrentUrl,
    destination = Destination("/downloads/example"),
    resolvedSource = resolved,
    selectedFileIds = setOf(resolved.files.first().id),
  ),
)
task.await().getOrThrow()
ketch.close()
```

For v1, a single-file destination names the file; a multi-file destination names the root folder.
For v2/hybrid, the destination always names a root folder, including single-file torrents. Files
inside it use the sanitized paths shown by resolution. An empty selection downloads every file;
see [choosing files](#choosing-files) to change it later. IDs retain their original metainfo
indices. Progress counts verified selected bytes. In v1, a piece spanning selected and skipped
files needs all its bytes for verification; skipped boundary bytes live in a hidden task sidecar,
not in skipped output files. Network speed includes received payload, including those boundary
bytes/retries.

For SDK-provided bytes, call `ketch.resolveContent(bytes, "name.torrent")` (or
`torrents.resolveMetainfo(bytes)` on the source) and pass the returned source as
`DownloadRequest.resolvedSource`, with its `url` as the request URL. `resolveContent` also works
through `RemoteKetch`, which uploads the bytes to the daemon's `POST /api/resolve/content`; the
apps use it for `.torrent` files dropped onto the window. Local `.torrent` paths and
`file:` URLs also work. HTTP(S) metainfo is bounded and fetched through the HTTP engine; tracker
passkeys are not logged by the torrent HTTP adapter. Avoid enabling application-level URL logging
for private tracker URLs. A supplied HTTP engine remains owned by its caller. Torrent log lines
reduce tracker URLs to `scheme://host:port` and magnets to their topic and name. At debug level,
each running swarm logs a summary every 30 seconds; see
[troubleshooting](logging.md#troubleshooting) for reading it when a magnet or torrent stalls.

## Choosing files

`DownloadTask.selectFiles(ids)` changes which files a torrent task downloads at any time but
after it is canceled, for v1, v2 and hybrid torrents alike (`KetchFeatures.TORRENT_FILE_SELECTION`,
`torrent.fileSelection`). IDs are those of `ResolvedSource.files`, 1 to 100,000 of them. Ketch
checks them against the metainfo it saved for the task, never against sizes a client sent, saves
the selection and its size as the task's total, and then applies it:

- A running torrent follows it on its open connections, without reconnecting to its peers or
  starting its tracker announces over. Newly chosen files are created and downloaded. Files no
  longer chosen stop downloading and stay on disk as they are, with their verified pieces; pieces
  of them that still arrive are discarded without blaming the peer.
- Paused, queued, scheduled and failed tasks use it when they start.
- A completed task that gains files downloads them into the same folder (Queued, Downloading,
  then Completed again), taking over its seeding session if it has one. One that only drops files
  changes its size, and a seeding session shares the smaller selection from then on.
- In v1, the verified bytes of boundary pieces kept in the task's sidecar are copied into a file
  chosen later and rechecked, and a recheck heals a piece from its sidecar. A v2 or hybrid task
  refuses a newly chosen file whose path holds a file it did not create.
- Choosing the current files again does nothing. An empty selection is refused; to download
  nothing yet, wait for a choice instead.

A torrent added with `DownloadRequest.awaitFileSelection = true` and no selection
(`torrent.awaitFileSelection`), typically a magnet whose files are not known yet, waits for one:
once its metadata arrives it stops as `DownloadState.Paused(PauseReason.AwaitingFileSelection)`,
holding no queue slot, output folder or segments, and keeps waiting across restarts. A torrent of
one file does not wait. `selectFiles` starts it with the chosen files, without
looking up the metadata again; `resume()` downloads every file. The apps add a magnet this way
when it is added before its file list arrives ("Add anyway"). A `requestId` resubmission ignores
the selection and this flag.

`RemoteKetch` changes the files through `PUT /api/tasks/{id}/files`. `KetchApi.torrents` lists a
task's files a page at a time (`TorrentController.files`), sorted by `TorrentFileOrder`: torrent
order, case-insensitive natural name order ("Episode 2" before "Episode 10"), size, extension then
name, or selected files first, each reversible. The apps sort the same way, except that their Kind
order groups files by type (video, audio, subtitles, ...), where the server sorts by the plain
extension. The MCP tools `listDownloadFiles` and `selectDownloadFiles` and the CLI's `--list-files`
and `--files` build on these.

## Opening `.torrent` files in the apps

The apps register for `.torrent` files, so the system file manager offers Ketch under "Open with":

| Platform | Registration | Delivery |
|----------|--------------|----------|
| Android | `ACTION_VIEW` filters for `application/x-bittorrent`, plus `.torrent` paths with a generic type | `content:` URI to the running activity |
| macOS | File association in the packaged app | Finder open-file events |
| Windows, Linux | File association in the MSI/DEB package; for the portable Windows app, Settings → Integration | Path or `file:` URI argument; a second launch forwards it to the running app and exits |
| iOS | `org.bittorrent.torrent` document type | `onOpenURL` from Files, AirDrop and share sheets |
| Web | Manifest `file_handlers` | `launchQueue`, only for the app installed from a Chromium browser over HTTPS or localhost |

Each opened file shows the add dialog with its name, size and file selection, as a dropped file
does; nothing downloads until the user confirms. Several files open one after another, and a file
stays pending until downloaded or dismissed, so it survives an Android activity recreation. Like a
dropped file, it reaches the active backend through `resolveContent`, so remote daemons work too.
Files over the 4 MiB metainfo limit are rejected. On iOS, copies placed in the app's
`Documents/Inbox` are deleted after reading.

File associations apply to packaged desktop builds (`packageDistributionForCurrentOS`), not
`./gradlew :app:desktop:run`. The desktop app runs once per configuration directory: another launch,
including a development run, hands its files to the running app instead of opening a second window.

## Discovery and upload

- HTTP(S) and UDP trackers support tiers, IPv4/IPv6, lifecycle events, and failover.
- Public magnets use BEP 9 metadata exchange, DHT, trackers, and explicit peers. Public swarms
  support peer exchange for v1, v2 and hybrid torrents; Ketch advertises its listen port (BEP 10
  `p`) and never dials an incoming peer's source port, only the listen port it announced.
  Configure `stateDirectory` to persist DHT routing candidates;
  a restart then reaches known nodes directly, even where the bootstrap names do not resolve.
  The apps and CLI keep it in a `torrent-state` folder in their app data directory.
- Magnet metadata is asked of up to four peers at once, as they are found, within
  `metadataTimeout`. A peer only takes the shared metadata buffer once it answers the handshake,
  so peers that never answer cannot hold up one that does. A peer that fails is asked again after
  5, 15 and 30 seconds, behind peers found meanwhile, so swarms of one or two briefly busy peers
  still resolve. Each failure and its reason is logged at debug, each exchange step at verbose.
- A task keeps looking for a magnet's metadata (`DownloadSource.resolveForDownload`), one
  `metadataTimeout` lookup after another, until it is found or the task is paused or canceled,
  since the peers of a small swarm may come online later; each fruitless lookup is logged at
  info. A preview such as `KetchApi.resolve` still gives up after one lookup. The apps show such
  a task as Starting, "Finding peers", once it holds a download slot.
- `additionalTrackers` (`[torrent] trackers` in the apps' and CLI's `config.toml`) adds `http`,
  `https` or `udp` trackers to public torrents and public magnet lookups; invalid URLs are ignored
  and at most 128 are used. Each one is announced alongside the torrent's own trackers rather than
  as a later tier, so it helps when a network blocks the torrent's trackers. Private torrents and
  tracker-only discovery never contact them. `TorrentDownloadSource.setAdditionalTrackers` changes
  the list for torrents started or resumed later. The apps edit the embedded instance's list under
  Settings → BitTorrent and apply it this way; a remote instance's list can only be changed on
  that device.
- `trackerListUrls` (`[torrent] trackerList` and `trackerListUrls`) subscribes to plain-text
  tracker lists, one announce URL per line: by default ngosang's
  [`trackers_best.txt`](https://github.com/ngosang/trackerslist) and XIU2's
  [`best.txt`](https://github.com/XIU2/TrackersListCollection) (`DEFAULT_TRACKER_LISTS`). The
  source downloads each when created and daily after, at most 256 KiB and 128 usable trackers
  (others, such as `wss`, are dropped), from its jsDelivr mirror when a `raw.githubusercontent.com`
  download fails, and uses their trackers, in list order and without repeats, like
  `additionalTrackers`, after them, within the same 128. Each list keeps a copy in
  `stateDirectory` (`tracker-list-<hash>.txt`), used on restart without downloading while it is
  under a day old; a failed download keeps it and retries after an hour, and a list with no
  usable trackers counts as failed. Until ngosang's list first downloads,
  `TorrentConfig.BEST_TRACKERS`, the copy Ketch ships, stands in. The source has the shipped
  trackers from the start, and the first torrent waits up to 5 seconds for the saved copies to
  be read, so a magnet resolved right after launch already announces to them. The apps and CLI
  subscribe by default (`[torrent] trackerList = false` turns it off), so magnets without
  trackers still find peers where DHT cannot bootstrap; downloading a list shows its host this
  device's IP address. A custom `trackerListUrl` saved by Ketch 0.3.0 stays the only list until
  the lists are changed. `TorrentConfig.trackerListUrls` itself is empty for SDK users.
  `setTrackerLists` and `refreshTrackerLists` change or update them at runtime, `trackerLists`
  reports each; the apps show them under Settings → BitTorrent → Tracker lists.
- `listenPort` is the port incoming peers connect to over TCP. DHT binds the same port over UDP
  when it can (a dual-stack system refuses the IPv6 socket on it, which then takes any port), so
  one forwarded port, TCP and UDP, reaches both; trackers and DHT announce it. `0`, the default,
  lets the system pick each port at every start. `ketch server` takes it from `[torrent]
  listenPort`, `KETCH_TORRENT_PORT` or `--torrent-port`, and the [Docker image](docker.md) sets
  16881; the apps and the CLI's other commands always let the system pick. A port in use fails
  the torrents that start with `KetchError.Network`, retried like other transient failures.
- Private metainfo disables DHT and peer exchange, does not offer `ut_metadata`, keeps one
  working tracker until failover, and disconnects its old peers before switching. Public-mode
  magnets that reveal private metadata are rejected; use tracker-only resolution or authenticated
  metainfo. Trackers hear `completed` only once every file is selected and complete, so a partial
  selection never announces it.
- Upload is opt-in, with the same `TorrentConfig.uploadPolicy` for v1, v2 and hybrid torrents.
  It defaults to `DISABLED`, the previous `enableUpload = false` behavior; `enableUpload = true`
  maps to `SEED_AFTER_COMPLETION` when no policy is supplied.
  - `DISABLED` never unchokes a peer, does not offer `ut_metadata` (so magnet peers cannot fetch
    the info dictionary from this device) and rejects every BEP 52 hash request.
  - `WHILE_DOWNLOADING` uploads verified pieces while the task downloads and stops at completion.
  - `SEED_AFTER_COMPLETION` keeps uploading after completion, also for a v2 task that starts
    complete, until seeding is stopped, the task is removed, the policy leaves seeding, a waiting
    download takes its engine slot, or the source is closed, and seeds again after a restart
    (see [implementation boundary and limits](#implementation-boundary-and-limits)).

  The apps and the CLI map `[torrent] upload` (`off`, `while-downloading` or `seed`) to these
  policies and `[torrent] uploadLimit` to `uploadRateLimit`, the upload cap all torrents share;
  the apps edit both under Settings → BitTorrent → Uploading and apply them at once.
  `TorrentDownloadSource.setUploadPolicy` and `setUploadRateLimit` never start the engine: peers
  are choked within a rechoke (about 10 seconds), seeding sessions finish once the policy leaves
  seeding, and torrents started later use the new values. `ketch server` and
  `ketch mcp --standalone` seed while they run, and `ketch server` also seeds again after a
  restart; `ketch mcp --standalone` builds its source with `restoreSeeding = false`, and MCP
  offers no seeding tool. A `ketch <url>` download exits once its downloads finish.
- Each torrent has four upload slots: three regular ones, reassigned every 10 seconds to the
  peers that sent the most while it downloads, or took the most while it seeds, and one
  optimistic slot that rotates every 30 seconds. Peers are served verified pieces only, through
  a bounded read cache; a verified piece that changed on disk stops the task. A hybrid's v1 peers
  get canonical v1 blocks, with zeros for the padding.
- Task and global download limits share Ketch's limiter with HTTP/FTP. Live connection limits
  close excess peers (see [Ketch download settings](#ketch-download-settings)).
  `setUploadRateLimit` on the torrent source caps the upload of all torrents and
  `setTaskUploadRateLimit` one task's, v1, v2 or hybrid, also while it seeds; zero means
  unlimited. A block either limit holds back waits in its peer's queue while the connection
  keeps downloading, so a low upload limit never stalls downloads from the same peers.

## Ketch download settings

Torrent tasks honor the same `DownloadConfig` and per-task settings as HTTP and FTP tasks:

| Setting | Torrent behavior |
| --- | --- |
| Simultaneous downloads (`maxConcurrentDownloads`) | Ketch's queue starts torrents like any other task. The engine also runs at most `TorrentConfig.maxActiveTorrents` (default 5) torrents, seeding sessions included. Further started torrents wait for an engine slot instead of failing: they report as downloading at 0 bytes/s with their restored progress, can be paused or canceled at once, and start by priority, then arrival order. A seeding session yields its slot to a waiting download. |
| Global and per-task speed limits | Applied live to verified payload on v1 and v2, through the same limiter as HTTP/FTP. The per-task limit still applies after pause and resume. |
| Per-task connections (`DownloadRequest.connections`, `DownloadTask.setConnections`) | The task's peer cap, applied live. Values are clamped to 1..512 (v1) or 1..500 (v2), never rejected. Unset (0) uses `TorrentConfig.connectionsPerTorrent` (default 100). |
| Connections per download (`maxConnectionsPerDownload`) | Not used: it counts HTTP segments. `TorrentConfig.maxConnections` (default 200) bounds sockets across all torrents. |
| Priority | Ordered by Ketch's queue, and by the engine-slot wait above. |
| Retries (`retryCount`, `retryDelayMs`) | Peer, tracker and DHT failures are retried inside the swarm without failing the task. Failures that reach the task are retried only when transient (`KetchError.Network`: metadata resolution timeouts, remote metainfo fetches, a busy listen port). Storage failures (`KetchError.Disk`), verification and protocol failures (`KetchError.SourceError`) are not retried. |

Up to 16 magnet metadata fetches run at once; further magnet resolutions wait for one to finish.

### Choosing discovery privacy

Select privacy before resolving a magnet. Public discovery is the default. To use only the supplied
trackers for one input, resolve it explicitly and pass that result into the download request:

```kotlin
val resolved = torrents.resolve(
  magnetUri,
  privacy = TorrentDiscoveryPrivacy.TRACKER_ONLY,
)
val task = ketch.download(
  DownloadRequest(
    url = resolved.url,
    destination = Destination("/downloads/example"),
    resolvedSource = resolved,
  ),
)
```

Alternatively, set `TorrentConfig(discoveryPrivacy = TorrentDiscoveryPrivacy.TRACKER_ONLY)` as the
source default. `resolveMetainfo(bytes, privacy = ...)` accepts the same per-input choice. Tracker-only
magnets require supplied tracker URLs, ignore explicit peer addresses, and do not use DHT or peer
exchange, even if the metadata is public. Public discovery performed before this choice cannot be
undone by a later private flag.

New source resume records retain the chosen privacy independently of later default changes, including
when metadata must be fetched again. Legacy records without a privacy choice use the configured
default and record it on their next save. Privacy is chosen at the source, for the local backend;
remote privacy commands remain on the v2 roadmap. Capability negotiation, inspection, file pages
and the selection and seeding commands are available through `KetchApi.torrents`, locally and,
for servers listing `torrent.control`, over `/api/torrents`; see the
[control contract](design/torrent-control-contract.md#version-1-runtime).

## Storage and restart

Use writable filesystem paths. Android's default directory is app-owned external Downloads
(with an internal-files fallback); iOS uses the app sandbox. Arbitrary Android SAF content URIs
and iOS security-scoped destinations are outside this version's scope: when the configured default
directory is a content URI, torrents use the platform default directory instead.

Payload is verified before it is committed. Checkpoints include metainfo, selection and ownership;
restart rehashes actual data rather than trusting a saved bitmap. File identities and an append-only
ownership journal prevent ordinary cleanup from deleting pre-existing or replaced files. Parent
handles and no-follow opens prevent symlink traversal during payload I/O. Do not allow an untrusted
process to mutate the destination concurrently with cleanup: final-entry identity checking and
unlinking are not one atomic OS operation. A crash between creation and its ownership record may
leave an unclaimed file, which recovery preserves rather than guessing ownership.

Existing native resume blobs are never interpreted as Kotlin checkpoints. Metainfo, when available,
is used to recheck existing payload. Legacy records with neither metainfo nor a usable magnet
need their original `.torrent` input again; cleanup preserves data it cannot prove belongs to the task.

Choosing files changes no storage format, so an older Ketch still opens these tasks, with these
limits: a magnet waiting for its files shows as paused and downloads every file when resumed; a
paused task whose selection changed fails to resume (v1 with a source error, v2 and hybrid with a
creation-log binding mismatch) and keeps its files; and the seeding intent, kept in the task
database's `control_json` column, is ignored. Tasks whose selection never changed resume as
before.

## Implementation boundary and limits

The public v2/hybrid download-and-restart milestone landed in
[PR #240](https://github.com/linroid/Ketch/pull/240), following #231–#238.
The broader [production v2 roadmap](https://github.com/linroid/Ketch/issues/162) remains open;
the supported download workflow below is not a complete downloader/seeder release claim.

All torrent parsing, SHA-1, wire protocol, trackers, DHT, scheduling and storage coordination are
Kotlin. Product artifacts contain no libtorrent engine or torrent JNI bindings. JVM filesystem
adapters use JNA for OS directory/handle operations; Android and iOS call platform filesystem APIs.
OS sockets, TLS, filesystem and Unicode normalization services are permitted platform dependencies.
The pinned libtorrent4j dependency and its loader exist only in JVM tests as an independent peer.
See [release licensing and provenance](development/licensing.md) for dependency notices and
the scope of the torrent source provenance review.

The public v2/hybrid download workflow supports metainfo imports, full-identity `btmh` magnets
(including magnets with both exact topics), `btih` magnets of hybrid torrents, which resolve
their v2 identity from the metadata, authenticated piece-layer exchange, trackers/DHT,
selection, progress, shared download limits, live connection limits, pause/resume, safe removal,
and task-store restart. Hybrid payloads must pass both SHA-1 and SHA-256 verification. Hybrid
padding files are omitted from selection, so file IDs may have gaps. The source persists metainfo
and full SHA-256 identity with ownership checkpoints; `.ketch-v2-<task-id>.creation` beside the
output root also recovers owned files created before the first checkpoint. Keep this journal with
the task until removal. Configure a durable `TaskStore` to retain task records across processes.

V2 and hybrid torrents accept incoming peers: the engine routes each connection by the 20-byte wire
tag in its handshake, both of a hybrid's tags to its one task. They upload, seed, serve BEP 52
proofs from the piece layer and, for verified pieces, the block layer, exchange peers, and take part
in v1 swarms: a hybrid talks to v1-only peers, announces its v1 hash to trackers with tier state of
its own and looks it up in DHT too. A private hybrid announces both hashes to one tracker at a time
and moves both to the next one together (BEP 27). Pieces read back for peers and proofs waiting to
be sent take at most half of `maxBufferedBytes` across all torrents, v1 and v2, so uploading never
starves downloads. The default 32 MiB holds the largest pieces; with less, pieces larger than half
of it are never uploaded. A piece read back to prove its block hashes is paid for from both upload
limits once it was read, like uploading it: a limit owes at most a tenth of a second (or one block)
of it at once, and such reads take at most half of a limit, so a peer asking for proofs never
starves uploads of other peers or torrents. The block hashes of the last few pieces proved are kept,
so repeated requests read nothing. A torrent at its peer limit closes peers that dial it before
answering their handshake, v2 and hybrid like v1. The Fast extension (BEP 6) is not negotiated.
Seeding ends when it is stopped, when the task is removed, when the upload policy leaves seeding,
when a download waiting for one of the `maxActiveTorrents` engine slots takes the oldest seeder's,
when the same torrent is added as another task, which takes the seeder's slot, or when Ketch quits;
a torrent that finishes while a download waits for a slot does not seed at all. Seeding shows as
Completed with `DownloadState.Completed.seeding` set, and as the `SEEDING` activity of
`KetchApi.torrents`.
`TorrentController.setSeeding` (REST `PUT /api/torrents/{id}/seeding`, the apps' inspector) stops
it, or starts a completed task seeding again after a recheck, on a free engine slot only, while
the upload policy seeds. Ketch remembers which tasks seed: `Ketch.start()` seeds them again, oldest
completion first, under `SEED_AFTER_COMPLETION` and only into free engine slots, each once a
recheck finds its files complete. A seeder that a download or shutdown stopped keeps that intent;
stopping it, or files that changed on disk, clear it. Seeding runs only while the process runs: on
Android a seeding-only app is not kept in the foreground, so Android may stop it, and its seeding,
in the background; on iOS seeding stops while the app is suspended.

This version does not implement uTP, protocol encryption, web seeds, NAT mapping, local service
discovery, torrent creation, or ratio management. No automatic incoming-port mapping is
performed. Bounds include 4 MiB metainfo, 10,000 files, 16 MiB pieces, and configured
connection/buffer/task budgets. Configuring many peers or large pieces increases process memory
beyond the piece-buffer budget.

See [verification and measurements](development/torrent-verification.md) and the
[current implementation progress](plans/pure-kotlin-torrent-v2-progress.md).
