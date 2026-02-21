# Ketch Logging

Ketch provides a pluggable logging system for diagnostics and debugging.

## Quick Start

### Option 1: Use the built-in console logger

```kotlin
val ketch = Ketch(
  httpEngine = KtorHttpEngine(),
  logger = Logger.console()  // Logs to console/logcat/NSLog
)
```

### Option 2: Use Kermit for structured logging (Recommended)

Add the dependency:
```kotlin
dependencies {
    implementation("com.linroid.ketch:kermit:1.0.0")
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

- **Verbose**: Detailed diagnostic information (segment downloads, byte-level details)
- **Debug**: Debugging information (server detection, metadata operations)
- **Info**: General informational messages (download started, paused, resumed, completed)
- **Warn**: Warning messages (retries, validation issues, non-critical errors)
- **Error**: Error messages (download failures, validation failures)

## What Gets Logged

### Info Level (Recommended for Production)
- Ketch initialization
- Download start/pause/resume/cancel
- Server capabilities (range support, content length)
- Download completion
- Retry attempts

### Debug Level (Recommended for Development)
- Server info detection
- Segment calculations
- File preallocation
- Metadata save/load operations
- Segment start/completion

### Verbose Level (For Detailed Diagnostics)
- Segment-level progress details
- Individual segment operations

## Example Log Output

With `Logger.console()` or `Severity.Debug`:

```
[INFO] [Ketch] Ketch v1.0.0 initialized
[INFO] [Ketch] Starting download: taskId=download-123, url=https://example.com/file.zip, connections=4
[DEBUG] [Coordinator] Detecting server capabilities for https://example.com/file.zip
[DEBUG] [RangeDetector] Sending HEAD request to https://example.com/file.zip
[DEBUG] [KtorHttpEngine] HEAD request: https://example.com/file.zip
[INFO] [RangeDetector] Server info: contentLength=10485760, acceptRanges=bytes, supportsResume=true, etag="abc123", lastModified=null
[INFO] [Coordinator] Server supports range requests. Using 4 connections, totalBytes=10485760
[DEBUG] [Coordinator] Preallocating 10485760 bytes for taskId=download-123
[DEBUG] [Coordinator] Saving metadata for taskId=download-123
[DEBUG] [SegmentDownloader] Starting segment 0: range 0..2621439 (2621440 bytes remaining)
[DEBUG] [KtorHttpEngine] GET request: https://example.com/file.zip, range=0-2621439
[DEBUG] [SegmentDownloader] Starting segment 1: range 2621440..5242879 (2621440 bytes remaining)
[DEBUG] [KtorHttpEngine] GET request: https://example.com/file.zip, range=2621440-5242879
[DEBUG] [SegmentDownloader] Starting segment 2: range 5242880..7864319 (2621440 bytes remaining)
[DEBUG] [KtorHttpEngine] GET request: https://example.com/file.zip, range=5242880-7864319
[DEBUG] [SegmentDownloader] Starting segment 3: range 7864320..10485759 (2621440 bytes remaining)
[DEBUG] [KtorHttpEngine] GET request: https://example.com/file.zip, range=7864320-10485759
[DEBUG] [SegmentDownloader] Completed segment 0: downloaded 2621440 bytes
[DEBUG] [SegmentDownloader] Completed segment 1: downloaded 2621440 bytes
[DEBUG] [SegmentDownloader] Completed segment 2: downloaded 2621440 bytes
[DEBUG] [SegmentDownloader] Completed segment 3: downloaded 2621440 bytes
[INFO] [Coordinator] Download completed successfully for taskId=download-123
```

### Pause/Resume Example

```
[INFO] [Ketch] Pausing download: taskId=download-123
[INFO] [Coordinator] Pausing download for taskId=download-123
[DEBUG] [Coordinator] Saving pause state for taskId=download-123
...
[INFO] [Ketch] Resuming download: taskId=download-123
[INFO] [Coordinator] Resuming download for taskId=download-123, url=https://example.com/file.zip
[DEBUG] [Coordinator] Validating server state for resume
[DEBUG] [RangeDetector] Sending HEAD request to https://example.com/file.zip
[INFO] [RangeDetector] Server info: contentLength=10485760, acceptRanges=bytes, supportsResume=true, etag="abc123", lastModified=null
[DEBUG] [Coordinator] Server validation passed, continuing resume
```

### Retry Example

```
[ERROR] [KtorHttpEngine] Network error: Connection timeout
[WARN] [Coordinator] Retry attempt 1 after 1000ms delay: Network error: Connection timeout
[DEBUG] [SegmentDownloader] Starting segment 0: range 0..2621439 (2621440 bytes remaining)
...
```

## Custom Logger Implementation

You can implement your own logger by implementing the `Logger` interface:

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
- `Logger.console()` uses Android's `Log` class (appears in Logcat)
- Tags are prefixed with "Ketch."

### iOS
- `Logger.console()` uses `NSLog` (appears in Xcode console)

### JVM/Desktop
- `Logger.console()` uses `println` for most logs
- Error messages use `System.err`

### WebAssembly
- `Logger.console()` uses `println` (appears in browser dev tools)

## See Also

- [Kermit Logger Documentation](../library/kermit/README.md)
- [Main README](../README.md)
