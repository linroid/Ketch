# Ketch Logging

Ketch provides a pluggable logging system for diagnostics and debugging.

## Quick Start

### Option 1: Use the built-in console logger

```kotlin
val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  logger = Logger.console()  // Logs to stdout, Logcat, or the browser console
)
```

`Logger.console()` emits every level; pass a minimum, such as `Logger.console(LogLevel.INFO)`, to
filter it.

### Option 2: Use Kermit for structured logging (Recommended)

Add the dependency:
```kotlin
dependencies {
    implementation("com.linroid.ketch:kermit:<latest-version>")
}
```

Use it:
```kotlin
import com.linroid.ketch.log.KermitLogger
import co.touchlab.kermit.Severity

val logger = KermitLogger(
    minSeverity = Severity.Debug  // Verbose, Debug, Info, Warn, Error, Assert
)

val ketch = Ketch(
    httpEngine = KtorHttpEngine(),
    logger = logger
)
```

### Option 3: Disable logging (Default)

```kotlin
val ketch = Ketch(
    httpEngine = KtorHttpEngine(),
    logger = Logger.None  // No logging (default)
)
```

## Log Levels

- **Verbose**: Detailed diagnostics (speed limiter waits, FTP commands, per-peer torrent events)
- **Debug**: Internal operations (server detection, segments, tracker announces, DHT, swarm
  summaries)
- **Info**: User-facing events (download start, state changes, completion, torrent lifecycle)
- **Warn**: Recoverable problems (retries, changed server files, corrupt torrent pieces)
- **Error**: Failures (a download giving up, an engine that cannot start)

## What Gets Logged

### Info Level (Recommended for Production)
- Ketch initialization with version and revision
- Download start, with the resolved source, size and output path
- Every task state transition, such as `Queued -> Downloading` or `Downloading -> Failed(...)`
- Torrent engine start, resolved torrents, file checks, completion and seeding
- Magnet metadata lookups and their timeouts

### Debug Level (Recommended for Development)
- HEAD requests and GET probes, redirects, response headers and segment calculations
- Each segment's start and completion
- File preallocation and resume validation
- Tracker announces per tracker, DHT bootstrap and lookups
- A torrent swarm summary every 30 seconds: verified pieces, connected and queued peers,
  newly discovered and failed peers, corrupt pieces

### Verbose Level (For Detailed Diagnostics)
- Speed limiter waits
- FTP protocol commands and replies
- Individual torrent peer disconnects, failed metadata requests and rejected incoming connections
- Progress events received from a remote instance

## Log Format

`Logger.console()` writes one record per call, prefixed with the local time and level. A
record's stack trace follows it on the same stream:

```
2026-09-28 14:03:12.345 [INFO] [Ketch] Ketch v1.0.0 (762f2b3) initialized: name=Ketch
```

Task-scoped messages include `taskId=`, so concurrent downloads can be told apart with
`grep`. Warnings that do not print a stack trace, such as retries, still name the underlying
cause chain.

## Example Log Output

An HTTP download that survives one dropped connection:

```
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

```
[INFO] [TorrentEngine] Torrent engine listening on port 51413 (IPv4 and IPv6), dht=true, maxActiveTorrents=5, maxConnections=200, extraTrackers=2
[INFO] [TorrentEngine] Fetching metadata for magnet 0123456789ab: privacy=PUBLIC, trackers=3, peers=0, timeout=2m
[DEBUG] [TorrentTracker] Announce STARTED for 0123456789ab to udp://tracker.example:6969: peers=50, interval=1800s
[DEBUG] [TorrentTracker] Announce STARTED for 0123456789ab to https://tracker2.example:443 failed (1 in a row): timed out
[DEBUG] [TorrentEngine] IPv4 DHT bootstrap from 4 router(s) and 60 saved node(s): 112 contact(s)
[INFO] [TorrentEngine] Fetched metadata for magnet 0123456789ab in 4.2s
[INFO] [TorrentSource] Resolved torrent from magnet: 0123456789ab "ubuntu.iso", v1, files=1, totalBytes=6114656256
[INFO] [TorrentSession] Downloading taskId=9c1e (0123456789ab) with up to 100 peer(s), upload=DISABLED, privacy=PUBLIC
[DEBUG] [TorrentSwarm] Swarm taskId=9c1e (0123456789ab): pieces 120/23326, peers connected=12 active=20/100, queued=40, known=140, discovered=88, failed=31, corrupt=0, discovery=open
```

## Troubleshooting

| Symptom | What to look for |
|---|---|
| A download fails | `Download failed for taskId=...` with its cause chain, and the `Retry n/m` lines before it |
| A resume restarts from zero | `ETag mismatch`, `Last-Modified mismatch` or `Local file integrity check failed` |
| A task stays queued | `[DownloadQueue]` lines showing the concurrency and per-host limits |
| A magnet never resolves | `Metadata for magnet ... not found within`, tracker announce failures, DHT contacts |
| A torrent stays at 0% | The swarm summary: `discovered=0` points at trackers or DHT, `connected=0` at reachability, `corrupt>0` at bad peers |
| A remote instance is offline | `[RemoteKetch] Connection to ... failed (attempt n)` or `rejected the API token` |

The apps choose their level as follows:

- **Desktop**: debug by default; set `KETCH_LOG_LEVEL` to `verbose`, `debug`, `info`, `warn`
  or `error`, for example `KETCH_LOG_LEVEL=verbose ./gradlew :app:desktop:run`. The level
  applies to both the console and the log file
- **CLI**: info by default; `-v`/`--verbose` for debug, `--debug` for verbose
- **Android and iOS**: debug
- **Web**: info, in the browser's developer console

## App Log Files

A packaged desktop app has no visible console, and Android and iOS users cannot reach Logcat or
the Xcode console, so the desktop, Android and iOS apps also keep their logs in files. The web
app logs to the browser console only.

| App | Log folder |
|---|---|
| macOS | `~/Library/Application Support/ketch/logs/` |
| Windows | `%APPDATA%\ketch\logs\`; `data\logs\` beside `Ketch.exe` for the [portable app](updates.md#the-portable-windows-app) |
| Linux | `$XDG_CONFIG_HOME/ketch/logs/`, or `~/.config/ketch/logs/` |
| Android | `files/logs/` in the app's private storage |
| iOS | `Library/Application Support/logs/` in the app's container |

Records are appended to `ketch.log` in the [format above](#log-format), at the same level as the
console, and earlier runs are kept. Before a record would take `ketch.log` past 5 MiB, the file
is renamed to `ketch.1.log`, the previous `ketch.1.log` becomes `ketch.2.log` and the previous
`ketch.2.log` is deleted, so the logs never take much more than 15 MiB.

The desktop app also logs exceptions nothing caught, on any thread, at error level, including
one that stops it from starting: it then quits rather than run without a window.

To attach them to a bug report, open **Settings → About → Troubleshooting**:

- **Desktop**: *Open log folder* shows the folder in Finder, Explorer or the file manager.
- **Android and iOS**: *Share logs* joins the files, oldest first, into `ketch-logs.txt` and
  opens the system share sheet.

R8 renames the classes and methods of the Android release, so its stack traces show short
placeholder names. Each GitHub release carries the matching `ketch-android-<version>-mapping.zip`;
unzip it and run `retrace mapping.txt ketch-logs.txt` (in the Android SDK's
`cmdline-tools/latest/bin/`) to restore them. The desktop apps are shrunk but not renamed, so
their stack traces need no mapping.

Logging never waits for the disk: a log call only formats and queues its record, and one
background writer appends the records in order, flushing whenever its queue runs empty. A
record that cannot be written, for example on a full disk, is dropped.

The apps send records to the console and the file with `Logger.combine`, which works with any
`Logger`:

```kotlin
val logger = Logger.combine(Logger.console(LogLevel.DEBUG), myFileLogger)
```

## Sensitive Data

Logs are meant to be shared in bug reports, so Ketch keeps credentials out of them:

- Passwords in URLs are masked: `ftp://user:***@example.com/file`
- Query parameters with credential-like names (`passkey`, `token`, `X-Amz-Signature`, ...)
  are masked: `https://tracker.example/download.php?id=42&passkey=***`
- URLs quoted in error messages are masked the same way in cause summaries and in stack
  traces printed by `Logger.console()` or written to the app log files. A custom `Logger`,
  including `KermitLogger`, receives the original throwable, so a crash reporter still sees
  the real exception
- Magnet links keep only their `xt` topic and `dn` name; tracker and source parameters are
  counted, not printed
- Request header values are never logged. A redirect to another origin logs the names of the
  headers left behind, such as `Not sending Cookie, Authorization to another origin`, and
  `Cookie`, `Set-Cookie` and `Authorization` response headers are logged as `***`
- Tracker URLs are reduced to `scheme://host:port`, including URLs quoted in error messages,
  because paths and queries carry private tracker passkeys
- Cookie and authorization header values are masked in HTTP debug logs, and request headers
  are never logged

Logs still name the files you downloaded, the hosts and paths they came from and what you asked
Discover for, so review them before posting them publicly. The app log files stay on the device
until the user shares them; Android backups and transfers to a new device leave them out.

When adding log lines, pass URLs through `redactUrl()` and name an error's causes with
`describeCauses()`. In `library:torrent`, reduce tracker URLs with `trackerLabel()` and describe
tracker errors with `describeWithoutUrls()`.

## Custom Logger Implementation

You can implement your own logger by implementing the `Logger` interface
(`com.linroid.ketch.api.log.Logger`, in `library:api`):

```kotlin
class CustomLogger : Logger {
  override fun v(message: String) {
    println("[VERBOSE] $message")
  }

  override fun d(message: String) {
    println("[DEBUG] $message")
  }

  override fun i(message: String) {
    println("[INFO] $message")
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
    logger = CustomLogger()
)
```

**Note:** `Logger` receives pre-built `String` messages. Lazy evaluation is handled by
`KetchLogger`'s `inline` functions, which skip message construction entirely when
`Logger.None` is active (the default). Tags are included in the message by `KetchLogger` —
each message is formatted as `[ComponentTag] message`, so you don't need to handle tags
separately.

## Platform-Specific Behavior

### Android
- `Logger.console()` uses Android's `Log` class with the tag `Ketch` (appears in Logcat,
  which adds its own timestamps)

### iOS
- `Logger.console()` prints timestamped lines to standard output (appears in the Xcode console)

### JVM/Desktop
- `Logger.console()` prints timestamped lines to standard output; errors go to `System.err`
- A record's stack trace is printed with it, on the same stream

### JavaScript and WebAssembly
- `Logger.console()` prints timestamped lines with `println` (appears in browser dev tools)

## See Also

- [Kermit Logger Documentation](../library/kermit/README.md)
- [Main README](../README.md)
