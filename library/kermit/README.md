# Ketch Kermit Logger

Kermit-based logger implementation for Ketch, providing structured logging across all platforms.

## Installation

Add the Kermit logger module to your dependencies:

```kotlin
// build.gradle.kts
dependencies {
  implementation("com.linroid.ketch:kermit:<latest-version>")
}
```

The module depends only on `library:api` and Kermit.

## Usage

### Basic Usage

```kotlin
import co.touchlab.kermit.Severity
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.log.KermitLogger

// Create a KermitLogger with desired severity level (default: Severity.Info)
val logger = KermitLogger(
  minSeverity = Severity.Debug,
)

// Pass it to Ketch
val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  logger = logger,
)
```

### Log Levels

- **Verbose**: Detailed diagnostics (segment progress, FTP commands, per-peer torrent events)
- **Debug**: Internal operations (server detection, segment calculations, tracker announces)
- **Info**: User-facing events (download started, task state changes, completion)
- **Warn**: Recoverable problems (retries, changed server files)
- **Error**: Failures (download failures)

```kotlin
// Verbose logging for detailed diagnostics
val verboseLogger = KermitLogger(minSeverity = Severity.Verbose)

// Info logging for production
val infoLogger = KermitLogger(minSeverity = Severity.Info)

// Drop every Ketch log (Ketch never logs at Assert)
val silentLogger = KermitLogger(minSeverity = Severity.Assert)
```

To disable logging entirely, keep the default `Logger.None`: Ketch then skips building log
messages altogether.

### Custom Tag

```kotlin
val logger = KermitLogger(
  minSeverity = Severity.Debug,
  tag = "MyApp",  // Kermit tag for all Ketch logs (default: "Ketch")
)
```

### Custom Configuration

For advanced use cases, you can provide a custom Kermit configuration. Its `minSeverity`
replaces the `minSeverity` argument:

```kotlin
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.platformLogWriter

val config = StaticConfig(
  minSeverity = Severity.Debug,
  logWriterList = listOf(
    platformLogWriter(),
    // Add your custom log writers here
  ),
)

val logger = KermitLogger(config = config)
```

## Example Output

Each message starts with the Ketch component that wrote it, such as `[Ketch]` or
`[HttpSource]`. Kermit's platform writer adds the severity and tag; on the JVM it prints:

```
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

## Platform Support

The Kermit logger supports:
- Android
- iOS (arm64, simulator)
- JVM/Desktop
- JavaScript (browser and Node.js)
- WebAssembly (wasmJs)

It has no WASI target because Kermit does not publish one.

## See Also

- [Kermit Documentation](https://github.com/touchlab/Kermit)
- [Ketch Logging](../../docs/logging.md)
- [Ketch API Reference](../../docs/api.md)
