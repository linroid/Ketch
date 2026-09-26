# Kotlin torrent downloads

`library:torrent` downloads BitTorrent v1, v2, and hybrid content in common Kotlin on JVM 11+, Android 26+, and iOS
(arm64 and arm64 simulator). The desktop app, Android app, iOS app, CLI, and daemon register
`TorrentDownloadSource`. Browsers control the daemon through `RemoteKetch`.

```kotlin
val torrents = TorrentDownloadSource(
  TorrentConfig(),
)
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
inside it use the sanitized paths shown by resolution. An empty selection downloads every file. IDs retain their original metainfo indices.
Progress counts verified selected bytes. In v1, a piece spanning selected and skipped files needs all
its bytes for verification; skipped boundary bytes live in a hidden task sidecar, not in skipped
output files. Network speed includes received payload, including those boundary bytes/retries.

For SDK-provided bytes, call `ketch.resolveContent(bytes, "name.torrent")` (or
`torrents.resolveMetainfo(bytes)` on the source) and pass the returned source as
`DownloadRequest.resolvedSource`, with its `url` as the request URL. `resolveContent` also works
through `RemoteKetch`, which uploads the bytes to the daemon's `POST /api/resolve/content`; the
apps use it for `.torrent` files dropped onto the window. Local `.torrent` paths and
`file:` URLs also work. HTTP(S) metainfo is bounded and fetched through the HTTP engine; tracker
passkeys are not logged by the torrent HTTP adapter. Avoid enabling application-level URL logging
for private tracker URLs. A supplied HTTP engine remains owned by its caller.

## Opening `.torrent` files in the apps

The apps register for `.torrent` files, so the system file manager offers Ketch under "Open with":

| Platform | Registration | Delivery |
|----------|--------------|----------|
| Android | `ACTION_VIEW` filters for `application/x-bittorrent`, plus `.torrent` paths with a generic type | `content:` URI to the running activity |
| macOS | File association in the packaged app | Finder open-file events |
| Windows, Linux | File association in the MSI/DEB package | Path or `file:` URI argument; a second launch forwards it to the running app and exits |
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
  support peer exchange for v1. Configure `stateDirectory` to persist DHT routing candidates;
  a restart then reaches known nodes directly, even where the bootstrap names do not resolve.
  The apps and CLI keep it in their config directory.
- `additionalTrackers` (the apps' and CLI's `[torrent] trackers` in `config.toml`) adds trackers
  to public torrents and public magnet lookups. Each one is announced alongside the torrent's own
  trackers rather than as a later tier, so it helps when a network blocks the torrent's trackers.
  Private torrents and tracker-only discovery never contact them.
  `TorrentDownloadSource.setAdditionalTrackers` changes the list for torrents started later.
- Private metainfo disables DHT and peer exchange, keeps one working tracker until failover,
  and disconnects its old peers before switching. Public-mode magnets that reveal private metadata
  are rejected; use tracker-only resolution or authenticated metainfo. Partial selections do not
  send a completed announce.
- V1 upload defaults to `DISABLED`, preserving the previous `enableUpload = false` behavior.
  `WHILE_DOWNLOADING` exchanges verified pieces during transfer; `SEED_AFTER_COMPLETION` keeps
  the session alive after completion until removed or the source is closed. `enableUpload = true`
  maps to the latter when no explicit policy is supplied.
- Task and global download limits share Ketch's limiter with HTTP/FTP. Live connection limits
  close excess peers (see [Ketch download settings](#ketch-download-settings)).
  `setUploadRateLimit` and `setTaskUploadRateLimit` on the torrent source control upload
  independently; zero means unlimited.

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
default and record it on their next save. Source-level selection applies to the local backend;
remote privacy commands and capability negotiation remain on the v2 roadmap.

## Storage and restart

Use writable filesystem paths. Android's default directory is app-owned external Downloads
(with an internal-files fallback); iOS uses the app sandbox. Arbitrary Android SAF content URIs
and iOS security-scoped destinations are outside this version's scope.

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

The public v2/hybrid download workflow supports metainfo imports, full-identity `btmh` magnets
(including magnets with both exact topics), authenticated piece-layer exchange, trackers/DHT,
selection, progress, shared download limits, live connection limits, pause/resume, safe removal,
and task-store restart. Hybrid payloads must pass both SHA-1 and SHA-256 verification. Hybrid
padding files are omitted from selection, so file IDs may have gaps. The source persists metainfo
and full SHA-256 identity with ownership checkpoints; `.ketch-v2-<task-id>.creation` beside the
output root also recovers owned files created before the first checkpoint. Keep this journal with
the task until removal. Configure a durable `TaskStore` to retain task records across processes.

V2/hybrid currently downloads through outgoing v2 TCP connections. V2 incoming routing, uploads,
seeding, peer exchange, and hybrid participation in v1-only peer swarms remain separate roadmap
work; upload policy options apply to v1 sessions. This version does not implement uTP,
protocol encryption, web seeds, NAT mapping, local service discovery, torrent creation, or a ratio
management UI. No automatic incoming-port mapping is performed. Bounds include 4 MiB metainfo,
10,000 files, 16 MiB pieces, and configured connection/buffer/task budgets. Configuring many peers
or large pieces increases process memory beyond the piece-buffer budget.

See [verification and measurements](development/torrent-verification.md) and the
[current implementation progress](plans/pure-kotlin-torrent-v2-progress.md).
