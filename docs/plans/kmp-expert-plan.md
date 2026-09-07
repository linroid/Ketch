# KMP Expert Assessment: BitTorrent DownloadSource Module

## Status: APPROVED with refinements

This document captures the KMP expert's assessment of the BitTorrent DownloadSource
architecture, including all decisions made during the design review with the architect.

---

## Module Configuration

### Targets
- `androidLibrary { namespace = "com.linroid.ketch.torrent" }`
- `iosArm64()`, `iosSimulatorArm64()` (iOS actual throws `KetchError.Unsupported`)
- `jvm()`
- **NO** `wasmJs` (requires raw TCP/UDP sockets)

### Plugins
```kotlin
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.mavenPublish)
}
```

### Source Set Structure
```
library/torrent/src/
  commonMain/       Pure Kotlin code shared across ALL targets
  commonTest/       Tests for pure Kotlin code
  jvmAndAndroid/    Libtorrent4jEngine (depends on libtorrent4j, JVM-only)
  androidMain/      actual fun createTorrentEngine + native lib deps
  jvmMain/          actual fun createTorrentEngine + native lib deps
  iosMain/          actual fun createTorrentEngine -> throws Unsupported
```

### Gradle Source Sets
```kotlin
sourceSets {
  commonMain.dependencies {
    api(projects.library.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
  }
  val jvmAndAndroid by creating {
    dependsOn(commonMain.get())
    dependencies {
      implementation(libs.libtorrent4j)
    }
  }
  androidMain {
    dependsOn(jvmAndAndroid)
    dependencies {
      runtimeOnly(libs.libtorrent4j.android.arm64)
      runtimeOnly(libs.libtorrent4j.android.arm)
      runtimeOnly(libs.libtorrent4j.android.x86.64)
      runtimeOnly(libs.libtorrent4j.android.x86)
    }
  }
  jvmMain {
    dependsOn(jvmAndAndroid)
    dependencies {
      runtimeOnly(libs.libtorrent4j.macos)
      runtimeOnly(libs.libtorrent4j.linux)
      runtimeOnly(libs.libtorrent4j.windows)
    }
  }
  commonTest.dependencies {
    implementation(libs.kotlin.test)
    implementation(libs.kotlinx.coroutines.test)
  }
}
```

---

## Expect/Actual Pattern

### Pattern: Interface + expect fun factory (matches `createFileAccessor`)

```kotlin
// commonMain — interface
internal interface TorrentEngine {
  suspend fun start()
  suspend fun stop()
  suspend fun addTorrent(params: AddTorrentParams): TorrentSession
  suspend fun removeTorrent(infoHash: InfoHash)
}

// commonMain — expect factory
internal expect fun createTorrentEngine(
  config: TorrentConfig = TorrentConfig(),
): TorrentEngine

// jvmAndAndroid — implementation class
internal class Libtorrent4jEngine(
  private val config: TorrentConfig,
) : TorrentEngine { ... }

// androidMain — thin actual
internal actual fun createTorrentEngine(config: TorrentConfig): TorrentEngine {
  return Libtorrent4jEngine(config)
}

// jvmMain — thin actual (identical to Android)
internal actual fun createTorrentEngine(config: TorrentConfig): TorrentEngine {
  return Libtorrent4jEngine(config)
}

// iosMain — unsupported actual
internal actual fun createTorrentEngine(config: TorrentConfig): TorrentEngine {
  throw KetchError.Unsupported("BitTorrent is not yet supported on iOS")
}
```

### Rationale
- `TorrentDownloadSource` lives in `commonMain` and needs to instantiate the engine
- `expect fun` factory provides platform-specific instantiation from commonMain
- `interface` provides polymorphism and testability (fake engines for testing)
- `jvmAndAndroid` intermediate source set holds the JVM-only libtorrent4j wrapper
- Leaf source sets (`androidMain`, `jvmMain`, `iosMain`) provide thin actual declarations

---

## CommonMain Maximization

### What goes in commonMain (pure Kotlin, no platform dependencies):
- `TorrentDownloadSource` — public `DownloadSource` implementation
- `TorrentEngine` interface — internal engine abstraction
- `expect fun createTorrentEngine` — factory expect declaration
- `TorrentSession` interface — tracks one active torrent
- `TorrentConfig` — engine configuration data class
- `TorrentResumeState` — `@Serializable` resume data
- `MagnetUri` — magnet: URI parser
- `TorrentFile` — .torrent file parser (bencode-based)
- `Bencode` — bencode encoder/decoder
- `PieceHasher` — SHA-1 piece hash verification
- `InfoHash` — 20-byte info hash value class
- `TorrentHandle` — internal download handle wrapper

### What goes in jvmAndAndroid (libtorrent4j dependency):
- `Libtorrent4jEngine` — `TorrentEngine` implementation wrapping libtorrent4j
- `Libtorrent4jSession` — `TorrentSession` implementation wrapping libtorrent4j handles
- Any libtorrent4j-specific type mappings and adapters

### What goes in leaf source sets (actuals only):
- `androidMain`: `actual fun createTorrentEngine` (one-liner)
- `jvmMain`: `actual fun createTorrentEngine` (one-liner)
- `iosMain`: `actual fun createTorrentEngine` (throws Unsupported)

---

## Critical Design Decision: File I/O

### Problem
`DownloadExecution` creates a single `FileAccessor` per task and assumes all sources
write through it. libtorrent4j manages its own file I/O (multi-file directory structure,
piece-based random-access writes).

### Solution: `managesOwnFileIo` flag on `DownloadSource`

```kotlin
interface DownloadSource {
  // ... existing methods ...

  /**
   * Whether this source manages its own file I/O instead of using
   * [DownloadContext.fileAccessor]. When true, [DownloadExecution]
   * passes a no-op FileAccessor, skips flush, and skips delete on
   * failure. The source handles file writing and cleanup.
   */
  val managesOwnFileIo: Boolean get() = false
}
```

### Impact on DownloadExecution
1. When `managesOwnFileIo == true`: pass `NoOpFileAccessor` to `DownloadContext`
2. Skip `fa.flush()` after download completion
3. Skip `fa.delete()` in `cleanupAfterExecution` (source handles cleanup)
4. `fa.close()` on `NoOpFileAccessor` is harmless (no-op)

### NoOpFileAccessor (in library:core)
```kotlin
internal object NoOpFileAccessor : FileAccessor {
  override suspend fun writeAt(offset: Long, data: ByteArray) {}
  override suspend fun flush() {}
  override fun close() {}
  override suspend fun delete() {}
  override suspend fun size(): Long = 0
  override suspend fun preallocate(size: Long) {}
}
```

### Output Path Semantics
- Single-file torrent: `outputPath` = full file path (e.g., `/downloads/movie.mkv`)
- Multi-file torrent: `outputPath` = torrent root directory (e.g., `/downloads/Ubuntu 24.04/`)
- Trailing slash distinguishes directory from file (matches `Destination` semantics)

---

## Segment Mapping

One `Segment` per selected file in the torrent. Segments are virtual progress units,
not byte ranges within a single output file.

- Single-file torrent: 1 segment
- Multi-file torrent with 3 selected files: 3 segments
- Progress per segment = sum of completed piece bytes overlapping that file

---

## Speed Limiting

Use libtorrent4j's native rate limiter (`SettingsPack.DOWNLOAD_RATE_LIMIT` for session,
`TorrentHandle.setDownloadLimit()` for per-torrent). Do NOT call `context.throttle()`.

**Known limitation**: Ketch's global `TokenBucket` is not consulted for torrent downloads.
Document this. Optionally accept `globalSpeedLimit: StateFlow<SpeedLimit>` and map it
reactively to libtorrent4j's session-level limit.

---

## Engine Lifecycle

`TorrentEngine` is a lazy singleton managed by `TorrentDownloadSource`:
- Created on first `download()` or `resolve()` call
- Shared across all torrent downloads (DHT routing table, peer connections)
- Thread-safe (libtorrent4j's SessionManager handles concurrency)
- `TorrentDownloadSource.close()` shuts down the engine

---

## Dependencies to Add

### libs.versions.toml
```toml
[versions]
libtorrent4j = "2.1.0-38"

[libraries]
libtorrent4j = { module = "org.libtorrent4j:libtorrent4j", version.ref = "libtorrent4j" }
libtorrent4j-android-arm64 = { module = "org.libtorrent4j:libtorrent4j-android-arm64", version.ref = "libtorrent4j" }
libtorrent4j-android-arm = { module = "org.libtorrent4j:libtorrent4j-android-arm", version.ref = "libtorrent4j" }
libtorrent4j-android-x86-64 = { module = "org.libtorrent4j:libtorrent4j-android-x86_64", version.ref = "libtorrent4j" }
libtorrent4j-android-x86 = { module = "org.libtorrent4j:libtorrent4j-android-x86", version.ref = "libtorrent4j" }
libtorrent4j-macos = { module = "org.libtorrent4j:libtorrent4j-macos", version.ref = "libtorrent4j" }
libtorrent4j-linux = { module = "org.libtorrent4j:libtorrent4j-linux", version.ref = "libtorrent4j" }
libtorrent4j-windows = { module = "org.libtorrent4j:libtorrent4j-windows", version.ref = "libtorrent4j" }
```

### settings.gradle.kts
```kotlin
include(":library:torrent")
```

### ProGuard (consumer-rules.pro)
```
-keep class org.libtorrent4j.** { *; }
-keepclassmembers class org.libtorrent4j.** { *; }
```

---

## Error Mapping

| Torrent Error | KetchError |
|---|---|
| Network/tracker failure | `KetchError.Network` |
| Disk write failure | `KetchError.Disk` |
| Invalid .torrent/magnet | `KetchError.SourceError("torrent")` |
| Metadata fetch timeout | `KetchError.Network` |
| Piece hash check failure | `KetchError.SourceError("torrent")` |
| iOS not supported | `KetchError.Unsupported` |
