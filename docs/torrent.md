# Kotlin torrent downloads

`library:torrent` implements BitTorrent v1 in common Kotlin on JVM 11+, Android 26+, and iOS
(arm64 and arm64 simulator). The desktop app, Android app, iOS app, CLI, and daemon register
`TorrentDownloadSource`. Browsers control the daemon through `RemoteKetch`.

```kotlin
val torrents = TorrentDownloadSource(
  TorrentConfig(uploadPolicy = TorrentUploadPolicy.WHILE_DOWNLOADING),
)
val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  additionalSources = listOf(torrents),
)
ketch.start()
val resolved = ketch.resolve(torrentUrl) // HTTPS .torrent URL or btih magnet
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

A single-file destination names the file; a multi-file destination names the torrent's root
folder. An empty selection downloads every file. IDs retain their original metainfo indices.
Progress counts verified selected bytes. A piece spanning selected and skipped files needs all
its bytes for verification; skipped boundary bytes live in a hidden task sidecar, not in skipped
output files. Network speed includes received payload, including those boundary bytes/retries.

For SDK-provided bytes, call `torrents.resolveMetainfo(bytes)` and pass the returned source as
`DownloadRequest.resolvedSource`, with its `url` as the request URL. Local `.torrent` paths and
`file:` URLs also work. HTTP(S) metainfo is bounded and fetched through the HTTP engine; tracker
passkeys are not logged by the torrent HTTP adapter. Avoid enabling application-level URL logging
for private tracker URLs. A supplied HTTP engine remains owned by its caller.

## Discovery and upload

- HTTP(S) and UDP trackers support tiers, IPv4/IPv6, lifecycle events, and failover.
- Public magnets use BEP 9 metadata exchange, DHT, trackers, and explicit peers. Public swarms
  support peer exchange. Configure `stateDirectory` to persist DHT routing candidates.
- Private metainfo disables DHT and peer exchange, keeps one working tracker until failover,
  and disconnects its old peers before switching. Private magnets are rejected: provide the
  authenticated `.torrent` file instead. Partial selections do not send a completed announce.
- Upload defaults to `DISABLED`, preserving the previous `enableUpload = false` behavior.
  `WHILE_DOWNLOADING` exchanges verified pieces during transfer; `SEED_AFTER_COMPLETION` keeps
  the session alive after completion until removed or the source is closed. `enableUpload = true`
  maps to the latter when no explicit policy is supplied.
- Task and global download limits share Ketch's limiter with HTTP/FTP. Live connection limits
  close excess peers. `setUploadRateLimit` and `setTaskUploadRateLimit` on the torrent source
  control upload independently; zero means unlimited.

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

All torrent parsing, SHA-1, wire protocol, trackers, DHT, scheduling and storage coordination are
Kotlin. Product artifacts contain no libtorrent engine or torrent JNI bindings. JVM filesystem
adapters use JNA for OS directory/handle operations; Android and iOS call platform filesystem APIs.
OS sockets, TLS, filesystem and Unicode normalization services are permitted platform dependencies.
The pinned libtorrent4j dependency and its loader exist only in JVM tests as an independent peer.

This version supports v1 TCP torrents. It rejects v2/hybrid torrents and does not implement uTP,
protocol encryption, web seeds, NAT mapping, local service discovery, torrent creation, or a ratio
management UI. No automatic incoming-port mapping is performed. Bounds include 4 MiB metainfo,
10,000 files, 16 MiB pieces, and configured connection/buffer/task budgets. Configuring many peers
or large pieces increases process memory beyond the piece-buffer budget.

See [verification and measurements](development/torrent-verification.md) and the
[stacked implementation roadmap](plans/pure-kotlin-torrent-progress.md).
