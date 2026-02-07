# KDown Kermit Logger

Kermit-based logger implementation for KDown, providing structured logging across all platforms.

## Installation

Add the Kermit logger module to your dependencies:

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.linroid.kdown:kermit:1.0.0")
}
```

## Usage

### Basic Usage

```kotlin
import com.linroid.kdown.KDown
import com.linroid.kdown.KermitLogger
import com.linroid.kdown.KtorHttpEngine
import co.touchlab.kermit.Severity

// Create a KermitLogger with desired severity level
val logger = KermitLogger(
    minSeverity = Severity.Debug
)

// Pass it to KDown
val kdown = KDown(
    httpEngine = KtorHttpEngine(),
    logger = logger
)
```

### Log Levels

- **Verbose**: Detailed diagnostic information
- **Debug**: Debugging information (segment downloads, server detection)
- **Info**: General informational messages (download started, paused, completed)
- **Warn**: Warning messages (retries, validation issues)
- **Error**: Error messages (download failures)

```kotlin
// Verbose logging for detailed diagnostics
val verboseLogger = KermitLogger(minSeverity = Severity.Verbose)

// Info logging for production
val infoLogger = KermitLogger(minSeverity = Severity.Info)

// Disable all logs
val noLogger = KermitLogger(minSeverity = Severity.Assert)
```

### Custom Tag

```kotlin
val logger = KermitLogger(
    minSeverity = Severity.Debug,
    tag = "MyApp"  // All logs will be prefixed with "MyApp"
)
```

### Custom Configuration

For advanced use cases, you can provide a custom Kermit configuration:

```kotlin
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.platformLogWriter
import co.touchlab.kermit.Severity

val config = StaticConfig(
    minSeverity = Severity.Debug,
    logWriterList = listOf(
        platformLogWriter(),
        // Add your custom log writers here
    )
)

val logger = KermitLogger(config = config)
```

## Example Output

```
[INFO] [KDown.KDown] KDown v1.0.0 initialized with config: DownloadConfig(...)
[INFO] [KDown.KDown] Starting download: taskId=task1, url=https://..., connections=4
[DEBUG] [KDown.Coordinator] Detecting server capabilities for https://...
[INFO] [KDown.RangeDetector] Server info: contentLength=1048576, acceptRanges=bytes, supportsResume=true
[INFO] [KDown.Coordinator] Server supports range requests. Using 4 connections
[DEBUG] [KDown.SegmentDownloader] Starting segment 0: range 0..262143 (262144 bytes remaining)
[DEBUG] [KDown.SegmentDownloader] Starting segment 1: range 262144..524287 (262144 bytes remaining)
...
[INFO] [KDown.Coordinator] Download completed successfully for taskId=task1
```

## Platform Support

The Kermit logger works on all platforms supported by KDown:
- Android
- iOS (arm64, simulator)
- JVM/Desktop
- WebAssembly (wasmJs)

## See Also

- [Kermit Documentation](https://github.com/touchlab/Kermit)
- [KDown Core Documentation](../core/README.md)
