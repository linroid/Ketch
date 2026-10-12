# Ketch Kermit logger

Sends Ketch's logs to [Kermit](https://github.com/touchlab/Kermit), so they appear in Logcat on
Android, OSLog on iOS and the console elsewhere, next to your app's own Kermit logs. Use it when
your app already logs with Kermit, or when you want Ketch's logs on every platform without writing
a `Logger` yourself.

## Installation

```kotlin
// build.gradle.kts
dependencies {
  implementation("com.linroid.ketch:kermit:<latest-version>")
}
```

The module depends only on `library:api` and Kermit.

## Usage

Create a `KermitLogger` and pass it to `Ketch`:

```kotlin
import co.touchlab.kermit.Severity
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.log.KermitLogger

val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  logger = KermitLogger(minSeverity = Severity.Debug),
)
```

`KermitLogger` takes three optional arguments:

| Parameter | Default | Meaning |
| --- | --- | --- |
| `minSeverity` | `Severity.Info` | The lowest severity that is logged |
| `tag` | `"Ketch"` | The Kermit tag of every Ketch log |
| `config` | `null` | A Kermit `StaticConfig` to use instead of the platform writer; its `minSeverity` replaces the `minSeverity` argument |

Messages below the minimum severity cost nothing: Ketch asks the logger before it builds them.

### Choose a level

| Severity | What Ketch logs |
| --- | --- |
| `Verbose` | Detailed diagnostics: speed limiter waits, FTP commands, per-peer torrent events |
| `Debug` | Internal operations: server detection, segment calculations, tracker announces |
| `Info` | User-facing events: download started, task state changes, completion |
| `Warn` | Recoverable problems: retries, changed server files |
| `Error` | Failures |

```kotlin
// Detailed diagnostics
val verboseLogger = KermitLogger(minSeverity = Severity.Verbose)

// Production
val infoLogger = KermitLogger(minSeverity = Severity.Info)

// Drop every Ketch log (Ketch never logs at Assert)
val silentLogger = KermitLogger(minSeverity = Severity.Assert)
```

To turn logging off entirely, keep `Ketch`'s default `Logger.None`: Ketch then skips building log
messages altogether.

### Custom tag

```kotlin
val logger = KermitLogger(
  minSeverity = Severity.Debug,
  tag = "MyApp",
)
```

### Custom configuration

Pass your own Kermit configuration to add log writers:

```kotlin
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.platformLogWriter

val config = StaticConfig(
  minSeverity = Severity.Debug,
  logWriterList = listOf(
    platformLogWriter(),
    // Add your own log writers here
  ),
)

val logger = KermitLogger(config = config)
```

## Example output

Each message starts with the Ketch component that wrote it, such as `[Ketch]` or `[HttpSource]`.
Kermit's platform writer adds the severity and tag; on the JVM it prints:

```text
Info: (Ketch) [Ketch] Ketch v1.0.0 (762f2b3) initialized: name=Ketch
Info: (Ketch) [Ketch] Downloading: taskId=3f2a, url=https://example.com/file.zip, connections=0, priority=NORMAL
Info: (Ketch) [RangeDetector] Server info: contentLength=1048576, acceptRanges=true, supportsResume=true, etag="abc123", lastModified=null
Info: (Ketch) [Execution] Resolved taskId=3f2a: source=http, totalBytes=1048576, outputPath=/Users/me/Downloads/file.zip
Info: (Ketch) [HttpSource] Server supports ranges. Using 4 connections for taskId=3f2a, totalBytes=1048576
Info: (Ketch) [Ketch] Task state: taskId=3f2a, Queued -> Downloading
Debug: (Ketch) [SegmentDownloader] Starting segment 0 for taskId=3f2a: range 0..262143 (262144 bytes remaining)
Debug: (Ketch) [SegmentDownloader] Starting segment 1 for taskId=3f2a: range 262144..524287 (262144 bytes remaining)
...
Info: (Ketch) [Execution] Download completed for taskId=3f2a
Info: (Ketch) [Ketch] Task state: taskId=3f2a, Downloading -> Completed(/Users/me/Downloads/file.zip, totalBytes=1048576, downloadTime=1.204s)
```

On Android the tag is the Logcat tag, and on iOS Kermit writes to OSLog.

## Platform support

- Android
- iOS (arm64 and simulator)
- JVM and desktop
- JavaScript (browser and Node.js)
- WebAssembly (`wasmJs`)

There is no WASI target, because Kermit does not publish one.

## See also

- [Kermit documentation](https://github.com/touchlab/Kermit)
- [Ketch logging](../../docs/logging.md): log format, troubleshooting and what is never logged
- [Ketch API reference](../../docs/api.md)
