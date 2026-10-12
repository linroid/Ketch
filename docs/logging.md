# Logging

Ketch can log what it does, from each task's state changes down to single torrent peers. This
page is for developers who embed the library and want to turn logging on, and for anyone who needs
the logs of a Ketch app or the CLI to troubleshoot a download or attach them to a bug report.

## Turn logging on

Logging is off by default (`Logger.None`, which costs nothing). Pass a `Logger` to `Ketch` to
turn it on.

### The built-in console logger

```kotlin
val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  logger = Logger.console(), // stdout, Logcat or the browser console
)
```

`Logger.console()` writes every level. Pass a minimum to filter it:

```kotlin
val logger = Logger.console(LogLevel.INFO)
```

### Kermit

The optional `kermit` module sends Ketch's logs through [Kermit](https://github.com/touchlab/Kermit):

```kotlin
dependencies {
  implementation("com.linroid.ketch:kermit:<latest-version>")
}
```

```kotlin
import co.touchlab.kermit.Severity
import com.linroid.ketch.log.KermitLogger

val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  logger = KermitLogger(minSeverity = Severity.Debug), // Info by default
)
```

See the [Kermit logger readme](../library/kermit/README.md) for tags and custom configuration.

### More than one destination

`Logger.combine` sends every record to several loggers, as the apps do for the console and their
log file:

```kotlin
val logger = Logger.combine(Logger.console(LogLevel.DEBUG), myFileLogger)
```

> [!NOTE]
> The logger is process-wide: each `Ketch` installs the logger it was given for all of Ketch's
> logging, so the most recently created instance's logger wins. Give every instance the same one.

## Logs of the apps and the CLI

### Choosing the level

| Where | Default level | How to change it |
|-------|---------------|------------------|
| Desktop app | debug | `KETCH_LOG_LEVEL` = `verbose`, `debug`, `info`, `warn` or `error`; applies to the console and the log file |
| CLI | info | `-v` or `--verbose` for debug, `--debug` for verbose |
| Android and iOS apps | debug | — |
| Web app | info | — (logs go to the browser's developer console) |

For example, to run the desktop app from source with every detail:

```bash
KETCH_LOG_LEVEL=verbose ./gradlew :app:desktop:run
```

### Log files

A packaged desktop app has no visible console, and Android and iOS users cannot reach Logcat or the
Xcode console, so the desktop, Android and iOS apps also keep their logs in files. The web app logs
to the browser console only.

| App | Log folder |
|-----|------------|
| macOS | `~/Library/Application Support/ketch/logs/` |
| Windows | `%APPDATA%\ketch\logs\`; `data\logs\` beside `Ketch.exe` for the [portable app](updates.md#the-portable-windows-app) |
| Linux | `$XDG_CONFIG_HOME/ketch/logs/`, or `~/.config/ketch/logs/` |
| Android | `files/logs/` in the app's private storage |
| iOS | `Library/Application Support/logs/` in the app's container |

Records are appended to `ketch.log` in the [same format](#log-format) and at the same level as the
console, and earlier runs are kept. Before a record would take `ketch.log` past 5 MiB, the file is
renamed to `ketch.1.log`, the previous `ketch.1.log` becomes `ketch.2.log`, and the previous
`ketch.2.log` is deleted. The logs never take much more than 15 MiB.

The desktop app also logs, at error level, exceptions nothing caught on any thread, including one
that stops it from starting; it then quits rather than run without a window.

Logging never waits for the disk: a log call only formats and queues its record, and one background
writer appends records in order, flushing whenever its queue runs empty. A record that cannot be
written, for example on a full disk, is dropped.

### Sharing logs in a bug report

Open **Settings → About → Troubleshooting**:

- **Desktop**: **Open log folder** shows the folder in Finder, Explorer or the file manager.
- **Android and iOS**: **Share logs** joins the files, oldest first, into `ketch-logs.txt` and opens
  the system share sheet.

Review the logs before posting them publicly; see [sensitive data](#sensitive-data).

### Reading stack traces from release builds

Release builds of the Android and desktop apps are obfuscated, so their stack traces show short
placeholder names. Each GitHub release carries the mapping files to restore them:

- **Android**: unzip `ketch-android-<version>-mapping.zip` and run
  `retrace mapping.txt ketch-logs.txt` (in the Android SDK's `cmdline-tools/latest/bin/`).
- **Desktop**: unzip `ketch-desktop-<version>-<os>-<arch>-mapping.zip` and use ProGuard's `retrace`
  (`bin/retrace.sh` in a [ProGuard release](https://github.com/Guardsquare/proguard)).

Log messages keep the names of Ketch's API types and of exceptions, and the CLI is not obfuscated.

## Troubleshooting

| Symptom | What to look for |
|---------|------------------|
| A download fails | `Download failed for taskId=...` with its cause chain, and the `Retry n/m` lines before it |
| A resume restarts from zero | `ETag mismatch`, `Last-Modified mismatch` or `Local file integrity check failed` |
| A task stays queued | `[DownloadQueue]` lines showing the concurrency and per-host limits |
| A magnet never resolves | `Fetching metadata for magnet` repeating, tracker announce failures and DHT contacts; at debug, `Metadata for magnet ... not found within` after each 2-minute round |
| A torrent stays at 0% | The [swarm summary](#torrent-swarm-summaries): `discovered=0` points at trackers or DHT (`lsd=0` while a device on the LAN shares it: local discovery is off or multicast is blocked), `connected=0` at reachability, `corrupt>0` at bad peers, `webSeedFailures` at mirrors |
| A remote instance is offline | `[RemoteKetch] Connection to ... failed (attempt n)` or `rejected the API token` |

Task-scoped messages include `taskId=`, so you can follow one download with `grep`.

## Log levels

| Level | Use | Examples |
|-------|-----|----------|
| Verbose | Detailed diagnostics | Speed limiter waits, FTP commands, per-peer torrent events |
| Debug | Internal operations | Server detection, segments, tracker announces, DHT, swarm summaries |
| Info | User-facing events | Download start, state changes, completion, torrent lifecycle |
| Warn | Recoverable problems | Retries, changed server files, corrupt torrent pieces |
| Error | Failures | A download giving up, an engine that cannot start |

Info is a good level for production and debug for development.

### Info

- Ketch initialization, with version and revision
- Download start, with the resolved source, size and output path
- Every task state transition, such as `Queued -> Downloading` or `Downloading -> Failed(...)`
- Torrent engine start, resolved torrents, file checks, completion and seeding
- The start and end of magnet metadata lookups. A task or preview keeps looking until the metadata
  is found or it is stopped, in rounds of 2 minutes

### Debug

- HEAD requests and GET probes, redirects, response headers and segment calculations
- Each segment's start and completion
- File preallocation and resume validation
- Announces per tracker, DHT bootstrap and lookups, and magnet lookup rounds that found nothing
- Local service discovery (`TorrentLsd`): joining and leaving the LAN group and which torrents were
  announced there, by short hash
- A [swarm summary](#torrent-swarm-summaries) every 30 seconds for each running torrent

### Verbose

- Speed limiter waits
- FTP protocol commands and replies
- Individual torrent peer disconnects, failed metadata requests and rejected incoming connections
- Each LAN peer local service discovery hands a torrent, and announces dropped by its rate limit
- Progress events received from a remote instance

### Torrent swarm summaries

Every 30 seconds each torrent swarm logs one line at debug level with:

- verified pieces, connected and queued peers, and peers that negotiated the Fast extension
  (`fast`);
- newly discovered and failed peers, corrupt pieces, peers found on the LAN (`lsd`), upload slots,
  interested peers and uploaded bytes;
- for v2 and hybrid swarms, also incoming peers, a hybrid's v1 peers, hash requests served and
  rejected, and candidates being dialed;
- with web seeds (BEP 19), the seeds still running of all, those dropped after repeated failures,
  the pieces and payload they delivered, and their failed requests.

The line ends with how peer connections ended. Each dialed endpoint is counted once, by the
furthest it got:

| Outcome | Meaning |
|---------|---------|
| `refused`, `connectTimeout`, `unreachable`, `connectFailed` | The connection never opened. |
| `handshakeRejected` | The handshake failed, or (v2 and hybrid) the peer was for another torrent or banned. |
| `handshakeTimeout` | The peer did not finish the handshake in time. |
| `handshakeClosed` | The peer accepted the connection and closed it on the plaintext handshake, as peers requiring encryption do. |
| `connected` | The handshake succeeded; for v2 and hybrid this includes duplicates closed right after it. |
| `received` | The peer sent payload. |

Incoming connections are counted the same way. The summary also says how many connected peers use
protocol encryption and how many run over uTP, and how many dialed endpoints peers advertised as
supporting uTP or preferring encryption, and how many of those failed.

A v1 swarm that runs out of peers logs this at info. A v2 or hybrid swarm, which keeps waiting for
discovery, logs it at info each time every peer it tried is gone and none is left to dial, at most
once per 30 seconds.

## Log format

`Logger.console()` and the app log files write one record per call, prefixed with the local time,
the level and the component's tag. A record's stack trace follows it on the same stream:

```text
2026-09-28 14:03:12.345 [INFO] [Ketch] Ketch v1.0.0 (762f2b3) initialized: name=Ketch
```

Warnings without a stack trace, such as retries, still name the underlying cause chain.

### Examples

An HTTP download that survives one dropped connection:

```text
14:03:12.410 [INFO] [Ketch] Downloading: taskId=3f2a, url=https://example.com/file.zip, connections=0, priority=NORMAL
14:03:12.520 [INFO] [RangeDetector] Server info: contentLength=10485760, acceptRanges=true, supportsResume=true, etag="abc123", lastModified=null
14:03:12.522 [INFO] [Execution] Resolved taskId=3f2a: source=http, totalBytes=10485760, outputPath=/Users/me/Downloads/file.zip
14:03:12.524 [INFO] [HttpSource] Server supports ranges. Using 4 connections for taskId=3f2a, totalBytes=10485760
14:03:12.530 [INFO] [Ketch] Task state: taskId=3f2a, Queued -> Downloading
14:03:17.001 [WARN] [Execution] Retry 1/3 for taskId=3f2a in 1000ms: Network: Network error occurred <- IOException: Connection reset by peer
14:03:25.870 [INFO] [Execution] Download completed for taskId=3f2a
14:03:25.871 [INFO] [Ketch] Task state: taskId=3f2a, Downloading -> Completed(/Users/me/Downloads/file.zip, totalBytes=10485760, downloadTime=13.348s)
```

A magnet download (dates omitted, task IDs shortened):

```text
[INFO] [TorrentEngine] Torrent engine listening on port 51413 (IPv4 and IPv6), dht=true, maxActiveTorrents=5, maxConnections=200, extraTrackers=2
[INFO] [TorrentEngine] Fetching metadata for magnet 0123456789ab: privacy=PUBLIC, trackers=3, peers=0, timeout=2m
[DEBUG] [TorrentTracker] Announce STARTED for 0123456789ab to udp://tracker.example:6969: peers=50, interval=1800s
[DEBUG] [TorrentTracker] Announce STARTED for 0123456789ab to https://tracker2.example:443 failed (1 in a row): timed out
[DEBUG] [TorrentEngine] IPv4 DHT bootstrap from 4 router(s) and 60 saved node(s): 112 contact(s)
[INFO] [TorrentEngine] Fetched metadata for magnet 0123456789ab in 4.2s
[INFO] [TorrentSource] Resolved torrent from magnet: 0123456789ab "ubuntu.iso", v1, files=1, totalBytes=6114656256
[INFO] [TorrentSession] Downloading taskId=9c1e (0123456789ab) with up to 100 peer(s), upload=DISABLED, privacy=PUBLIC
[DEBUG] [TorrentSwarm] Swarm taskId=9c1e (0123456789ab): pieces 120/23326, peers connected=12 fast=9 active=20/100, queued=40, known=140, discovered=88, lsd=1, failed=31, corrupt=0, uploadSlots=0/4 interestedPeers=3 uploaded=0, discovery=open
```

Here `timeout=2m` is the length of one lookup round, not a limit on the whole lookup.

Torrents with web seeds, v1 and v2 (summaries shortened with `...`):

```text
[DEBUG] [TorrentSwarm] Swarm taskId=4f2a (89abcdef0123): pieces 300/512, peers connected=0 fast=0 active=0/100, queued=0, known=0, discovered=0, lsd=0, failed=0, corrupt=0, uploadSlots=0/4 interestedPeers=0 uploaded=0, discovery=open; ...; webSeeds=1/2 (dropped=1), webSeedPieces=300, webSeedPayload=314572800, webSeedFailures=5
[INFO] [TorrentWebSeed] Transfer taskId=51d0 (v2 3991fc2074c5): downloading from 2 web seed(s) too
[DEBUG] [TorrentSwarm] V2 swarm taskId=51d0 (3991fc2074c5): pieces 210/524, peers=0 (in=0, v1=0) ... pendingCommits=0; ...; webSeeds=2/2 (dropped=0), webSeedPieces=210, webSeedPayload=220200960, webSeedFailures=0
```

A hybrid torrent seeding to two peers, one of them from its v1 swarm (`in` counts peers that dialed
in, `v1` peers on the v1 protocol, which get no hashes):

```text
[INFO] [TorrentSession] Seeding taskId=51d0 (v2 3991fc2074c5): upload=SEED_AFTER_COMPLETION
[DEBUG] [TorrentSwarm] V2 swarm taskId=51d0 (3991fc2074c5): pieces 524/524, peers=2 (in=1, v1=1) uploadSlots=2/4 interestedPeers=2 uploaded=7340032 hashServed=3 hashRejected=0 candidates=4 dialing=0 lsd=0 unchoked=0, pendingCommits=0; dialed 9 [refused=3 connectTimeout=4 connected=1 received=1], incoming [connected=1], encrypted 1, utp 0, pex flags utp=2 (failed 2) encryption=3 (failed 3)
```

## Sensitive data

Logs are meant to be shared in bug reports, so Ketch keeps credentials out of them:

- **URL passwords** are masked: `ftp://user:***@example.com/file`.
- **Credential-like query parameters** (`passkey`, `token`, `X-Amz-Signature`, ...) are masked:
  `https://tracker.example/download.php?id=42&passkey=***`.
- **URLs inside error messages** are masked the same way in cause summaries and in stack traces
  printed by `Logger.console()` or written to the app log files. A custom `Logger`, including
  `KermitLogger`, receives the original throwable, so a crash reporter still sees the real
  exception.
- **Magnet links** keep only their `xt` topic and `dn` name; tracker and source parameters are
  counted, not printed.
- **Request headers** are never logged. A redirect to another origin logs only the names of the
  headers left behind, such as `Not sending Cookie, Authorization to another origin`, and
  `Cookie`, `Set-Cookie` and `Authorization` response headers are logged as `***`.
- **Tracker URLs** are reduced to `scheme://host:port`, including in error messages, because paths
  and queries carry private tracker passkeys.
- **Proxies** are logged by their address (`Sending GET for ... through socks5://127.0.0.1:1080`),
  without credentials, and `Proxy.Manual` prints its password as `***`, as in `Config updated`.
- **Torrent peer addresses** appear only in verbose per-peer lines and a few warnings about a peer's
  own failures, never in swarm summaries. The live connections Ketch reports
  (`Interest.Connections`) list each peer's IP and port and each HTTP host, but nothing logs them.

Logs still name the files you downloaded, the hosts and paths they came from, and what you asked
Discover for, so review them before posting them publicly. The app log files stay on the device
until you share them; Android backups and transfers to a new device leave them out.

### Rules for contributors

When adding log lines:

- Pass URLs through `redactUrl()`, and text that may quote URLs through `redactUrlsIn()`.
- Name an error's causes with `describeCauses()`, not `error.message`.
- In `library:torrent`, reduce tracker URLs with `trackerLabel()` and describe tracker errors with
  `describeWithoutUrls()`.
- Never log header values, tokens or passwords.

`redactUrl()` and `describeCauses()` live in `com.linroid.ketch.api.log`, next to
`redactUrlsIn()`, which is internal to Ketch's own modules (`@InternalKetchApi`). See
the [code style](development/code-style.md) for the rest of the logging conventions.

## Writing a custom logger

Implement `Logger` (`com.linroid.ketch.api.log.Logger`, in `library:api`):

```kotlin
class CustomLogger(private val minLevel: LogLevel = LogLevel.INFO) : Logger {
  override fun isLoggable(level: LogLevel): Boolean = level >= minLevel

  override fun v(message: String) {
    if (isLoggable(LogLevel.VERBOSE)) println("[VERBOSE] $message")
  }

  override fun d(message: String) {
    if (isLoggable(LogLevel.DEBUG)) println("[DEBUG] $message")
  }

  override fun i(message: String) {
    if (isLoggable(LogLevel.INFO)) println("[INFO] $message")
  }

  override fun w(message: String, throwable: Throwable?) {
    println("[WARN] $message")
    throwable?.printStackTrace()
  }

  override fun e(message: String, throwable: Throwable?) {
    System.err.println("[ERROR] $message")
    throwable?.printStackTrace()
  }
}

val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  logger = CustomLogger(),
)
```

Things to know:

- Messages arrive already formatted, with the component's tag in front: `[Coordinator] ...`.
- Override `isLoggable(level)` to filter: Ketch only builds a message when its level is loggable,
  so a quiet logger costs little. The default returns `true` for every level.
- `w` and `e` receive the original throwable, unredacted.

## Platform behavior

| Platform | `Logger.console()` writes to |
|----------|------------------------------|
| Android | Logcat, through `android.util.Log` with the tag `Ketch` (Logcat adds its own timestamps) |
| iOS | Standard output, timestamped (the Xcode console) |
| JVM and desktop | Standard output, timestamped; errors go to `System.err`, each record with its stack trace on the same stream |
| JavaScript and WebAssembly | `println`, timestamped (the browser's developer tools, or the Node.js console) |

## See also

- [Kermit logger](../library/kermit/README.md)
- [BitTorrent support](torrent.md)
- [Main readme](../README.md)
