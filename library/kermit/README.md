# Ketch Kermit Logger

Kermit-based logger implementation for Ketch, providing structured logging across all platforms.

## Installation

Add the Kermit logger module to your dependencies:

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.linroid.ketch:kermit:1.0.0")
}
```

## Usage

### Basic Usage

```kotlin
import com.linroid.ketch.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.log.KermitLogger
import co.touchlab.kermit.Severity

// Create a KermitLogger with desired severity level
val logger = KermitLogger(
    minSeverity = Severity.Debug
)

// Pass it to Ketch
val ketch = Ketch(
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
[INFO] [Ketch.Ketch] Ketch v1.0.0 initialized with config: DownloadConfig(...)
[INFO] [Ketch.Ketch] Starting download: taskId=task1, url=https://..., connections=4
[DEBUG] [Ketch.Coordinator] Detecting server capabilities for https://...
[INFO] [Ketch.RangeDetector] Server info: contentLength=1048576, acceptRanges=bytes, supportsResume=true
[INFO] [Ketch.Coordinator] Server supports range requests. Using 4 connections
[DEBUG] [Ketch.SegmentDownloader] Starting segment 0: range 0..262143 (262144 bytes remaining)
[DEBUG] [Ketch.SegmentDownloader] Starting segment 1: range 262144..524287 (262144 bytes remaining)
...
[INFO] [Ketch.Coordinator] Download completed successfully for taskId=task1
```

## Platform Support

The Kermit logger works on all platforms supported by Ketch:
- Android
- iOS (arm64, simulator)
- JVM/Desktop
- WebAssembly (wasmJs)

## See Also

- [Kermit Documentation](https://github.com/touchlab/Kermit)
- [Ketch Core Documentation](../core/README.md)
