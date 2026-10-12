# BitTorrent and magnet downloads

Ketch downloads BitTorrent v1, v2 and hybrid torrents, from `.torrent` files, `.torrent` URLs and
magnet links, with an engine written in Kotlin. This page shows how to download and seed torrents
in the apps and the CLI and how to configure them, then covers platform limits, the SDK API and
how the engine works.

The desktop, Android and iOS apps, the CLI and `ketch server` all include torrent support. The
web app controls a device that has it, and the
[browser extension](../app/browser-extension/README.md) sends clicked magnet links and `.torrent`
downloads to a Ketch device.

## Download a torrent or magnet

### In the apps

1. Paste a magnet link or a `.torrent` URL into the Add sheet, drop a `.torrent` file on the
   window, or open one from the system file manager (see
   [opening `.torrent` files](#opening-torrent-files-in-the-apps)).
2. Ketch looks up the torrent and lists its files. Untick the ones you do not want.
3. Choose where to save it and add it.

A magnet link names a torrent without describing its files, so Ketch first asks peers for them.
Ketch keeps looking until it finds them or you stop it, because the peers of a small swarm may come
online later. If the lookup takes two minutes, the Add sheet offers:

- **Keep waiting**, to go on looking.
- **Add anyway**, to add the download now. It shows as Starting, "Finding peers", and once the file
  list arrives it waits for you to choose files (see [choosing files](#choosing-files)).

### With the CLI

```bash
ketch 'magnet:?xt=urn:btih:...'
```

```bash
ketch https://example.com/linux.iso.torrent
```

To download only some files, list them first, then pass their IDs:

```bash
ketch --list-files 'magnet:?xt=urn:btih:...'
```

```bash
ketch --files 0,2,5 'magnet:?xt=urn:btih:...'
```

`--list-files` prints `ID  SIZE  PATH` rows. Sort them with `--sort name|size|type` (`type` is the
file extension) and `--reverse`. `ketch <url>` exits once its downloads finish; it does not seed.
To keep seeding, run [`ketch server`](#seeding-and-uploading).

### Where the files go

- A v1 torrent with one file saves to the destination as a file; a v1 torrent with several files
  saves into the destination as a folder.
- A v2 or hybrid torrent always saves into a folder, even with a single file.
- File names come from the torrent, cleaned of characters and paths that are not safe on disk.

Progress counts verified bytes of the files you chose. The network speed also counts bytes that
are received but not kept, such as retries and the parts of pieces that overlap a skipped file.

### Opening `.torrent` files in the apps

The apps register for `.torrent` files, so the system file manager offers Ketch under "Open with":

| Platform | How it registers | How the file arrives |
| --- | --- | --- |
| Android | Opens `application/x-bittorrent`, and `.torrent` files with a generic type | A `content:` URI to the running app |
| macOS | File association in the packaged app | Finder's open-file events |
| Windows, Linux | File association in the MSI or DEB package; for the portable Windows app, Settings → Integration | A path or `file:` URI argument; a second launch hands it to the running app and exits |
| iOS | `org.bittorrent.torrent` document type | Files, AirDrop and share sheets |
| Web | Manifest `file_handlers` | Only in the app installed from a Chromium browser, over HTTPS or localhost |

Each file opens the Add sheet with its name, size and file list, as a dropped file does. Nothing
downloads until you confirm. Several files open one after another, and a file stays pending until
it is added or dismissed. Its content goes to the device you are using, so remote devices work too.
Files over 4 MiB are rejected. On iOS, the copies placed in the app's `Documents/Inbox` are deleted
after reading.

File associations only apply to packaged desktop builds (`packageDistributionForCurrentOS`), not to
`./gradlew :app:desktop:run`. The desktop app runs once per configuration directory: another launch,
including a development run, hands its files to the running app instead of opening a window.

## Choosing files

You can change which files a torrent downloads at any time until it is canceled, for v1, v2 and
hybrid torrents alike:

- **Apps**: the inspector's Files tab lists the files. Tick or untick them and apply. At least one
  file stays selected.
- **CLI**: `--files <ids>` when you start a download (see [above](#with-the-cli)).
- **MCP**: the `listDownloadFiles` and `selectDownloadFiles` tools.
- **SDK**: `DownloadTask.selectFiles(ids)`, with IDs from `ResolvedSource.files` (1 to 100,000 of
  them). `RemoteKetch` sends it as `PUT /api/tasks/{id}/files`.

What happens next depends on the task's state:

- **Downloading**: the torrent switches without reconnecting to its peers. Newly chosen files are
  created and downloaded. Files you deselect stop downloading but stay on disk as they are, with
  their verified pieces.
- **Paused, queued, scheduled or failed**: the new selection applies when the task starts.
- **Completed**: adding files downloads them into the same folder (Queued, Downloading, then
  Completed again), taking over the task's seeding if it seeds. Removing files only changes the
  task's size, and seeding shares the smaller selection from then on.

Choosing the current files again does nothing, and an empty selection is refused. Ketch checks the
IDs against the torrent's metadata it saved for the task, never against sizes a client sent.

### Waiting for a choice

A download added before its file list is known, as "Add anyway" adds a magnet, waits for you once
the list arrives. It shows "Choose files to download", stays paused across restarts and holds no
queue slot or folder until you choose. A torrent with a single file does not wait.

- Choosing files starts it, without looking up the metadata again.
- Resuming it, or **Download all files**, downloads every file.

In the SDK, set `DownloadRequest.awaitFileSelection = true` and leave `selectedFileIds` empty; the
task then pauses as `DownloadState.Paused(PauseReason.AwaitingFileSelection)`. A request sent again
with the same `requestId` ignores the selection and this flag.

### Sorting files

The apps, the server's file pages and the CLI sort files the same way: torrent order, name in
natural order ("Episode 2" before "Episode 10"), size, type, or selected files first, each
reversible. The apps' Kind order groups files by kind (video, audio, subtitles, ...), while the
server and the CLI sort by the plain file extension.

## Seeding and uploading

Ketch uploads nothing by default. Peers you upload to see this device's IP address. Choose a mode
under Settings → BitTorrent → Uploading ("Share with peers"), or with `[torrent] upload`:

| Mode | `upload` | What it does |
| --- | --- | --- |
| Off | `off` (default) | Never uploads. Peers cannot fetch a magnet's metadata from this device either. |
| While downloading | `while-downloading` | Uploads verified pieces while a torrent downloads, and stops when it finishes. |
| Keep seeding | `seed` | Keeps uploading finished torrents until you stop them, and seeds them again after a restart. |

`[torrent] uploadLimit` caps the upload speed of all torrents together (unlimited by default). It
takes the same values as other speed limits, such as `"1m"` or `"500k"`; a bare number is bytes
per second. Both settings apply immediately: peers are choked within about 10 seconds, and seeding
stops once the mode leaves `seed`.

A seeding task shows as completed and "Seeding". Use **Stop seeding** on its row or in the
inspector to stop it, and **Seed** in the inspector to start a completed task seeding again. Ketch
rechecks the files first, and only seeds while the mode is `seed` and an engine slot is free.

Ketch remembers which tasks seed. On the next start it seeds them again, oldest completion first,
into free engine slots only, each after a recheck finds its files complete. Seeding stops when:

- you stop it or remove the task;
- the upload mode leaves `seed`;
- a download waits for one of the engine's slots (`maxActiveTorrents`, 5 by default) and takes the
  oldest seeder's; a torrent that finishes while a download waits does not seed at all;
- the same torrent is added as another task;
- Ketch quits.

A seeder stopped by a waiting download or by quitting seeds again later; one you stop, or whose
files changed on disk, does not.

Which programs seed:

- The apps seed while they run. On Android, a seeding-only app is not kept in the foreground, so
  Android may stop it in the background. On iOS seeding stops while the app is suspended.
- `ketch server` seeds while it runs, and again after a restart.
- `ketch mcp --standalone` seeds while it runs, but never restores seeding after a restart. MCP has
  no seeding tool.
- `ketch <url>` exits once its downloads finish.

## Settings

The apps edit these settings under Settings → BitTorrent for the device they run on and apply them
immediately. A remote device's BitTorrent settings can only be changed on that device or in its
`config.toml`.

### `config.toml`

The apps, the CLI and `ketch server` read the `[torrent]` table:

```toml
[torrent]
upload = "seed"               # "off" (default), "while-downloading" or "seed"
uploadLimit = "1m"            # all torrents together; "unlimited" by default
encryption = "preferred"      # "preferred" (default), "required" or "off"
utp = true                    # default
portMapping = true            # default
localDiscovery = true         # default
trackers = ["udp://tracker.example.org:6969/announce"]
trackerList = true            # default
listenPort = 16881            # read by `ketch server` only; 0 (default) picks a free port
```

| Key | Default | Meaning |
| --- | --- | --- |
| `upload` | `off` | What this device uploads; see [seeding](#seeding-and-uploading). |
| `uploadLimit` | unlimited | Upload cap shared by all torrents. |
| `encryption` | `preferred` | Protocol encryption; see [encryption](#encryption). An unknown value loads as `preferred`. |
| `utp` | `true` | Also reach peers over uTP; see [uTP](#utp). |
| `portMapping` | `true` | Ask the router to forward the listen port; see [port forwarding](#port-forwarding). |
| `localDiscovery` | `true` | Find peers on the local network; see [local peer discovery](#local-peer-discovery). |
| `trackers` | none | Extra `http`, `https` or `udp` trackers for public torrents. |
| `trackerList` | `true` | Subscribe to the tracker lists in `trackerListUrls`. |
| `trackerListUrls` | two public lists | Plain-text tracker lists, one announce URL per line. |
| `listenPort` | `0` | Incoming peer port, read by `ketch server` only. |

Values that cannot be read, such as `upload = false`, load as the default rather than failing the
whole file.

### Ports and firewalls

Incoming peers connect to the listen port over TCP. DHT and uTP share one UDP socket on the same
port when they can, so forwarding one port, TCP and UDP, reaches all three.

- The apps and most CLI commands let the system pick a free port at every start, so they never
  compete with a server on the same machine.
- `ketch server` takes the port from `--torrent-port`, then `KETCH_TORRENT_PORT`, then
  `[torrent] listenPort`. The [Docker image](docker.md) uses 16881.
- A port in use fails the torrents that start with a network error, which is retried like other
  temporary failures.

### Encryption

Protocol encryption (MSE/PE) hides BitTorrent traffic from networks that recognize and slow it
down, and reaches peers that require it. It hides what the traffic is, not who you connect to; it
is not authentication.

| Setting | Behavior |
| --- | --- |
| `preferred` (default) | Connects encrypted first and unencrypted when a peer refuses. Accepts both. |
| `required` | Only encrypted connections, both ways. Fewer peers can connect. |
| `off` | Never encrypts. Peers that require encryption cannot connect. |

### uTP

uTP (BEP 29) carries peer connections over UDP. It reaches more peers behind routers, and slows
down when your other traffic needs the bandwidth. With `utp` on, a peer that TCP cannot reach is
tried over uTP, peers known to speak uTP are dialed over uTP first, and peers may connect to the
listen port over uTP. Private torrents use it too.

### Port forwarding

With `portMapping` on, Ketch asks the home router to forward the listen port, so that peers behind
other routers can connect to this device. It tries NAT-PMP first, then UPnP, and tells trackers,
DHT and peers the external port it gets. Mappings last an hour, are renewed every half hour and are
removed when the engine stops. Without a router that answers, Ketch logs that incoming peers need a
forwarded port and tries again every 10 minutes.

### Local peer discovery

With `localDiscovery` on, Ketch finds devices on the local network that download or share the same
public torrents (BEP 14), so they exchange pieces directly, without trackers or the internet. A
public magnet can also get its file list from another device at home this way.

- It works over IPv4 and IPv6. Over IPv6 it only finds devices with a unique local address
  (`fc00::/7`); devices with only a link-local address are heard but cannot be dialed.
- Devices announce every 5 minutes, so a new one may take that long to be found.
- Private and tracker-only torrents never take part, and peers found this way are never shared
  with other peers.
- It runs on desktop and Android. iOS does not support it, as multicast there needs an entitlement
  from Apple.
- On Android 17 it needs the local network permission. The app asks once, the first time discovery
  wants the network, and again whenever you switch the setting on; the system stops asking after
  two denials. Without it, Ketch retries every minute.

### Trackers and tracker lists

Extra trackers help where a network blocks a torrent's own trackers, or where a magnet has no
trackers and DHT cannot start. Ketch announces public torrents to them alongside the torrent's own
trackers. Private torrents and tracker-only lookups never contact them.

- **Extra trackers** (`trackers`, Settings → BitTorrent → Extra trackers): `http`, `https` or `udp`
  announce URLs. Invalid URLs are ignored.
- **Tracker lists** (`trackerList`, `trackerListUrls`, Settings → BitTorrent → Tracker lists): on by
  default, subscribed to ngosang's [`trackers_best.txt`](https://github.com/ngosang/trackerslist)
  and XIU2's [`best.txt`](https://github.com/XIU2/TrackersListCollection). Each list is downloaded
  daily, from a jsDelivr mirror when GitHub fails, and a copy Ketch ships stands in for ngosang's
  list until its first download. Downloading a list shows its host this device's IP address. Set
  `trackerList = false` to turn the lists off.

Ketch uses at most 128 extra trackers in all, the ones you added first. New lists apply to torrents
started or resumed afterwards.

### How download settings apply to torrents

Torrent tasks follow the same download settings as HTTP and FTP tasks, with these differences:

| Setting | Torrent behavior |
| --- | --- |
| Simultaneous downloads | Ketch's queue starts torrents like other tasks. The engine also runs at most 5 torrents at once, seeding included. Further torrents wait for a slot as downloading at 0 B/s; they can be paused or canceled at once and start by priority, then arrival order. A seeder gives its slot to a waiting download. |
| Speed limits | Global and per-task limits apply to torrents too, live. |
| Connections per task | The task's peer limit, applied live, clamped to 1-512 (v1) or 1-500 (v2). Auto (0) means 100 peers. |
| Connections per download | Not used: it counts HTTP segments. The engine opens at most 200 peer connections across all torrents. |
| Priority | Orders the queue and the wait for an engine slot. |
| Retries | Peer, tracker and DHT failures are retried inside the torrent without failing the task. Network failures that reach the task, such as a failed `.torrent` download or a busy listen port, are retried; disk, verification and protocol failures are not. |

## Public and private torrents

Torrents are public by default: Ketch finds peers through trackers, DHT, peer exchange and local
discovery.

A **private** torrent (BEP 27) uses only its trackers: no DHT, peer exchange or local discovery,
and it never shares its metadata with peers. It keeps one working tracker until that one fails, and
disconnects its old peers before switching. A public magnet that turns out to describe a private
torrent is rejected; use the `.torrent` file instead.

SDK users can also resolve a torrent as **tracker-only**: it uses only the trackers it names, even
when the torrent is public (see [discovery privacy](#choosing-discovery-privacy)). Trackers hear
that a download completed only once every file is selected and complete.

## Platform support and limits

- Torrents download on Android 8.0+ (API 26), JVM 11+ (desktop, CLI, server) and iOS (arm64 and
  the arm64 simulator). Browsers have no engine of their own; the web app controls another device.
- Seeding only runs while the app or server runs. There is no background seeding.
- Local peer discovery does not run on iOS.
- Downloads go to ordinary folders. When the default directory is an Android `content://` folder,
  torrents save to the platform's default directory instead. iOS saves inside the app sandbox.
- Limits: `.torrent` files up to 4 MiB, 10,000 files per torrent and 16 MiB pieces. Many peers or
  large pieces use more memory.
- Ketch does not create torrents and has no ratio management.

## For developers

### Add the torrent source

Add `com.linroid.ketch:torrent` and register `TorrentDownloadSource`:

```kotlin
val torrents = TorrentDownloadSource(TorrentConfig())
val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  additionalSources = listOf(torrents),
)
ketch.start()

val resolved = ketch.resolve(torrentUrl) // a .torrent URL or path, or a btih/btmh magnet
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

An empty `selectedFileIds` downloads every file. File IDs keep their index in the metainfo, so a
hybrid torrent's IDs may have gaps where padding files are left out.

`KetchApi.resolve` waits until a magnet's metadata is found or the caller cancels it; bound it with
`withTimeout` if you need to. Up to 16 magnet lookups run at once; more wait for one to finish.

`TorrentDownloadSource` takes:

- `config`: a `TorrentConfig` (below).
- `httpEngine`: the engine for `.torrent` URLs, HTTP trackers and web seeds. It stays owned by the
  caller; without one the source creates its own `KtorHttpEngine`.
- `restoreSeeding`: whether `Ketch.start()` seeds remembered tasks again (`true`).
- `multicastLock`: a `TorrentMulticastLock` held while local discovery is joined. The Android app
  passes one backed by a Wi-Fi `MulticastLock`.

### Torrent content you already hold

Pass `.torrent` bytes to `resolve`, then download with the returned source and its `url`:

```kotlin
val resolved = ketch.resolve(bytes, "example.torrent")
val task = ketch.download(
  DownloadRequest(url = resolved.url, resolvedSource = resolved),
)
```

`torrents.resolveMetainfo(bytes)` does the same on the source without a `Ketch`. `RemoteKetch`
uploads the bytes to the server's `POST /api/resolve/content`, as the apps do for dropped files.
Local `.torrent` paths and `file:` URLs also work as URLs.

### `TorrentConfig`

| Property | Default | Meaning |
| --- | --- | --- |
| `uploadPolicy` | `null` (`enableUpload`) | `DISABLED`, `WHILE_DOWNLOADING` or `SEED_AFTER_COMPLETION`. |
| `enableUpload` | `false` | Legacy switch: `true` means `SEED_AFTER_COMPLETION` when `uploadPolicy` is unset. |
| `uploadRateLimit` | `0` | Upload cap for all torrents, bytes per second; 0 is unlimited. |
| `encryption` | `PREFERRED` | `TorrentEncryption`: `PREFERRED`, `REQUIRED` or `DISABLED`. |
| `utpEnabled` | `true` | Also use uTP. |
| `portMapping` | `true` | NAT-PMP and UPnP port forwarding. |
| `localDiscovery` | `true` | Local peer discovery (Android and JVM). |
| `dhtEnabled` | `true` | Use the DHT. |
| `dhtBootstrap` | four public routers | DHT bootstrap endpoints, `host:port` or `[IPv6]:port`. |
| `listenPort` | `0` | Incoming TCP port, shared with UDP when possible; 0 picks one at every start. |
| `stateDirectory` | `null` | Folder for DHT routing snapshots and tracker list copies. The apps and CLI use a `torrent-state` folder in their data directory. |
| `discoveryPrivacy` | `PUBLIC` | Default for new inputs: `PUBLIC` or `TRACKER_ONLY`. |
| `additionalTrackers` | empty | Extra trackers for public torrents. |
| `trackerListUrls` | empty | Tracker lists to subscribe to. The apps and CLI pass `DEFAULT_TRACKER_LISTS`. |
| `maxActiveTorrents` | `5` | Torrents the engine runs at once, seeding included (1-64). |
| `connectionsPerTorrent` | `100` | Peer limit of a task that sets no connections (1-512). |
| `maxConnections` | `200` | Connections across all torrents, metadata and incoming peers included. |
| `maxBufferedBytes` | 32 MiB | Piece data buffered across the engine. |
| `maxMetadataBytes` | 4 MiB | Largest metainfo accepted. |
| `maxFilesPerTorrent`, `maxPiecesPerTorrent` | 10,000, 250,000 | Size limits checked before a torrent is loaded. |

`maxExchangeBytes`, `maxCachedMetadataBytes`, `maxSessionStateBytes` and `maxOpenPayloadFiles`
bound memory and open files further; see their KDoc.

### Changing settings at runtime

These `TorrentDownloadSource` functions apply at once. The upload, encryption, uTP, port mapping,
local discovery and tracker setters never start the engine; they only change a running one and the
next one:

| Function | Effect |
| --- | --- |
| `setUploadPolicy`, `setUploadRateLimit` | Change uploading for running and later torrents. |
| `setTaskUploadRateLimit(taskId, bps)` | Cap an active task's upload, also while it seeds; 0 is unlimited. |
| `setEncryption`, `setUtpEnabled` | Apply to connections opened afterwards. |
| `setPortMapping` | Start port mapping, or stop it and remove the mapping. |
| `setLocalDiscovery` | Join or leave local discovery; peers it found stay connected. |
| `setConnectionLimit` | Change `maxConnections` (1-4096), closing excess connections. |
| `setAdditionalTrackers`, `setTrackerLists` | Apply to torrents started or resumed afterwards. |
| `refreshTrackerLists()` | Download the tracker lists again; `trackerLists` reports each one's state. |

### Choosing discovery privacy

Choose privacy before resolving a magnet. To use only the trackers an input names, resolve it on
the source and pass the result into the request:

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

Unlike `KetchApi.resolve`, this call makes a single two-minute lookup for a magnet and fails with
`KetchError.Network` when it finds nothing; call it again to keep looking.
`resolveMetainfo(bytes, privacy = ...)` takes the same choice, and
`TorrentConfig(discoveryPrivacy = ...)` sets the default. Tracker-only magnets need tracker URLs,
ignore explicit peer addresses and never use DHT or peer exchange. Discovery already performed in
public cannot be undone by a later private choice. A task keeps the privacy it was resolved with,
even when its metadata must be fetched again. Privacy can only be chosen locally; remote instances
use their default.

### `TorrentController`

`library:torrent-api` defines `TorrentController`, which Ketch returns when the torrent source is
registered:

```kotlin
val controller = ketch.controller(TorrentController) ?: return // no torrent control
val caps = controller.capabilities()
val page = controller.files(taskId, order = TorrentFileOrder.NAME)
```

It offers `capabilities()` (such as `file-selection`, `v1`, `v2`, `hybrid`, `utp`, `mse-pe` and
`seeding`, which describe the backend, not its current settings), `snapshot(taskId)`, sorted file
pages (`files`), and `select` and `setSeeding` commands guarded by a revision and an idempotency
key. Follow a task's snapshots with `ketch.subscribe(TorrentInterest(taskId))`. `RemoteKetch`
returns a controller that works over `/api/torrents` with servers listing `torrent.control`. See the
[control contract](design/torrent-control-contract.md#version-1-runtime).

### Logging

Torrent logs reduce tracker URLs to `scheme://host:port` and magnets to their hash and name, and
never log tracker passkeys. Avoid turning on URL logging in your own HTTP engine for private
trackers. At debug level, each running torrent logs a summary every 30 seconds; see
[troubleshooting](logging.md#troubleshooting) for reading it when a torrent stalls.

## How it works

This section describes the engine's protocol behavior for people who debug swarms or work on it.

### Finding peers

- **Trackers**: HTTP(S) and UDP trackers, with tiers, IPv4 and IPv6, lifecycle events and
  failover. Extra trackers are announced alongside the torrent's own rather than as a later tier.
- **DHT**: public torrents look up peers in the DHT. With a `stateDirectory`, routing candidates
  are saved, so a restart reaches known nodes even where the bootstrap names do not resolve. Peers
  of public torrents exchange DHT ports (BEP 5 `PORT`), and Ketch pings the nodes they name, so the
  DHT starts from a tracker's peers even where no bootstrap router answers.
- **Peer exchange** (BEP 11) for v1, v2 and hybrid public torrents. Ketch advertises its listen
  port (BEP 10 `p`) and dials the port a peer announced, never an incoming peer's source port.
  Peers reached over uTP or with encryption are advertised with the matching BEP 11 flags.
- **Magnet metadata** (BEP 9) is asked of up to four peers at once. A peer only takes the shared
  metadata buffer once it answers, and a failed one is asked again after 5, 15 and 30 seconds.
  Lookups run in two-minute rounds, each asking again the peers the last one gave up on.

### Encryption details

MSE/PE runs a 768-bit Diffie-Hellman exchange, then RC4. With `PREFERRED`, every dial, metadata
requests included, starts encrypted; a peer that hangs up or does not answer within 10 seconds is
dialed again unencrypted, and dialed unencrypted first while it is among the last 1024 such peers.
Ketch sends no initial payload: the BitTorrent handshake follows on the negotiated stream.

Older libtorrent versions (before 2.0.13 and 2.1.0) compute one handshake value wrongly when the
shared secret starts with a zero byte (one exchange in 256). Ketch retries such a refused dial once
with new keys before falling back to plaintext, and accepts that value on incoming connections when
it can.

### uTP details

uTP uses LEDBAT congestion control, keeping queuing delay near 100 ms, and carries v1, v2, hybrid
and metadata connections. Ketch dials TCP first and uTP when TCP fails, except for the last 4096
peers known to speak uTP (from peer exchange flags, or a uTP fallback that worked), which get uTP
first.

### Port mapping details

NAT-PMP goes to the first and last address of each private /24 network this device is on. UPnP
runs an SSDP search, then SOAP on the router's WANIPConnection or WANPPPConnection service, only on
a device on one of our private networks and never through a proxy. TCP and the UDP port map to one
external port; when another device holds that port, UPnP picks another. Only engines listening on
every interface map ports.

### Local discovery details

While a public torrent runs, the engine joins `239.192.152.143:6771` on each interface with a
private or link-local IPv4 address and `[ff15::efc0:988f]:6771` on each interface with a
link-local or unique local IPv6 address (not VPN tunnels), on one socket shared with other clients
(`SO_REUSEADDR`). It sends `BT-SEARCH` announces to both groups with its TCP listen port (never a
router-mapped one), a random cookie and up to 20 info hashes, when each torrent starts and every 5
minutes, at most once a minute per torrent. A v1 torrent announces its info hash, a v2 torrent the
first 20 bytes of its SHA-256 hash, and a hybrid both.

Announces are accepted only from private (10/8, 172.16/12, 192.168/16, 100.64/10) or link-local
IPv4 senders and unique local IPv6 senders, with a valid port and at most 20 hashes in 1400 bytes,
never with our own cookie, 32 at once then 4 a second, and each peer once a minute per torrent. The
groups are joined again when either address family's interfaces change. A public magnet joins under
its info hash (a btmh magnet under the first 20 bytes of its SHA-256 hash) until its metadata is
found.

### Web seeds

Web seeds (BEP 19: a metainfo's `url-list`, or a magnet's `ws` parameters; `http` and `https` only,
at most 16) let a torrent download from HTTP mirrors as well as peers, so it completes even with no
reachable peer. A magnet's metadata still comes from peers.

- A single-file torrent's URL names the file (or, ending in `/`, its folder). A multi-file
  torrent's URL names the folder holding the torrent's folder; each file is fetched from the URL,
  the torrent name and the file's path, percent-encoded.
- Each seed fetches runs of whole pieces no one else is fetching, up to 16 pieces or 4 MiB, with
  one range request per file a run spans, and no more than its share of the free pieces. Later
  pieces of a run only use buffers that leave half the buffer limit free.
- Each piece is checked as it arrives, like a peer's: SHA-1 for v1, the piece layer for v2 and
  hybrid. BEP 47 padding files are never requested.
- In the endgame a seed may fetch one piece a peer is fetching; the first verified copy wins.
- A failed request or corrupt piece keeps the pieces stored before it, gives back the rest and
  backs the seed off (2 seconds, doubling to a minute). Five failures in a row drop it until the
  task runs again.
- Requests go through the source's HTTP engine with the system's proxy settings, count against the
  download limits, fail after 30 seconds without data, and show among the task's live connections
  as `HTTP`.

### Uploading

Each torrent has four upload slots: three go every 10 seconds to the peers that sent the most
while downloading, or took the most while seeding, and one optimistic slot rotates every 30
seconds. Peers get verified pieces only, through a bounded read cache; a verified piece that
changed on disk stops the task. A hybrid's v1 peers get v1 blocks, with zeros for the padding.

An upload limit holds a block back in its peer's queue while the connection keeps downloading, so
a low upload limit never stalls downloads from the same peers. With uploading off, Ketch never
unchokes a peer, does not offer `ut_metadata` and rejects BEP 52 hash requests.

### V2 and hybrid torrents

V2 and hybrid torrents accept incoming peers, routed by the 20-byte tag in their handshake. They
upload, seed, serve BEP 52 proofs and exchange peers. A hybrid also joins the v1 swarm: it talks to
v1-only peers and announces its v1 hash to trackers and the DHT. A private hybrid announces both
hashes to one tracker at a time. Hybrid payloads must pass both SHA-1 and SHA-256 checks.

Pieces read for peers and proofs waiting to be sent take at most half of `maxBufferedBytes`, so
uploads never starve downloads; with less than the default 32 MiB, pieces larger than half the
buffer are never uploaded. Reading a piece to prove its block hashes counts against the upload
limits, and such reads take at most half of a limit.

### Fast extension

Every connection, v1, v2 and hybrid, negotiates the Fast extension (BEP 6): Have All and Have None,
rejected rather than dropped requests, a choke that leaves requests to be rejected (those still
unanswered after 5 seconds are canceled), and allowed-fast pieces requested and served while
choked. While it uploads, Ketch offers IPv4 peers BEP 6's set of 10 allowed-fast pieces. A rejected
piece goes to other peers without counting against the peer. A torrent at its peer limit closes
peers that dial it before answering their handshake.

### Partial selections

In v1, a piece that spans a selected and a skipped file needs all its bytes to be verified. The
skipped file's part is kept in a hidden sidecar for the task, not in the skipped file. When that
file is chosen later, its verified bytes are copied from the sidecar and rechecked. A v2 or hybrid
task refuses a newly chosen file whose path holds a file it did not create.

## Storage, restart and compatibility

Data is verified before it is written as complete. Checkpoints record the metainfo, the selection
and which files the task owns, and a restart rehashes the data rather than trusting a saved bitmap.
Configure a durable `TaskStore` (such as `SqliteTaskStore`) to keep tasks across restarts.

- File identities and an append-only ownership journal stop cleanup from deleting files that
  existed before the download or were replaced. A v2 or hybrid task also keeps
  `.ketch-v2-<task-id>.creation` beside its folder until it is removed, to recover files created
  before the first checkpoint; keep it with the task.
- Payload I/O opens files through their parent folder without following symlinks. Do not let an
  untrusted process change the destination during cleanup: checking a file and deleting it are not
  one atomic operation. A crash between creating a file and recording it may leave a file no task
  claims, which recovery keeps rather than guessing.
- Tasks from versions that used a native engine keep their data, but their resume data is never
  read. With metainfo, existing data is rechecked; without metainfo or a usable magnet, add the
  original `.torrent` again. Cleanup keeps data it cannot prove belongs to the task.

Choosing files changed no storage format, so an older Ketch still opens these tasks, with limits:
a magnet waiting for its files shows as paused and downloads every file when resumed; a paused task
whose selection changed fails to resume and keeps its files; and the seeding choice is ignored.

## Implementation status

All torrent parsing, hashing, the wire protocol, trackers, DHT, scheduling and storage are Kotlin.
Release builds contain no libtorrent engine or torrent JNI bindings. The JVM file system code uses
JNA for OS directory and handle calls; Android and iOS call platform APIs. A pinned libtorrent4j
exists only in JVM tests, as an independent peer. See
[release licensing and provenance](development/licensing.md).

The public v2 and hybrid download milestone landed in
[PR #240](https://github.com/linroid/Ketch/pull/240). The broader
[v2 roadmap](https://github.com/linroid/Ketch/issues/162) remains open.

## See also

- [Verification and measurements](development/torrent-verification.md)
- [Implementation progress](plans/pure-kotlin-torrent-v2-progress.md)
- [Torrent control contract](design/torrent-control-contract.md)
- [Logging and troubleshooting](logging.md#troubleshooting)
- [Docker](docker.md)
