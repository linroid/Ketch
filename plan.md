# Refactoring Plan: Protocol-Agnostic Download Architecture

## Problem Statement

The core download engine was designed around HTTP Range requests. As we add more
protocols (FTP is already in, BitTorrent and HLS are planned), several HTTP-specific
assumptions leak through the architecture, causing:

1. **Duplicated logic** — FTP reimplements the entire segment download loop
   (`downloadSegments`/`downloadBatch`) that HttpDownloadSource also has
2. **Hardcoded HTTP references in core** — `DownloadExecution` directly references
   `HttpDownloadSource.META_ETAG`, `HttpDownloadSource.buildResumeState()`, and
   `HttpDownloadSource.TYPE` as fallbacks
3. **HTTP fields in TaskRecord** — `etag`, `lastModified`, `acceptRanges` are
   HTTP-specific but baked into the persistent record shared by all sources
4. **SegmentDownloader is HTTP-only** — Takes `HttpEngine` directly; FTP can't
   reuse it and had to build its own segment download implementation
5. **Protocol mismatch in DownloadSource interface** — `resolve()` takes `headers`
   parameter that means nothing for FTP, BitTorrent, etc.

### What breaks with BitTorrent?

BitTorrent has fundamentally different semantics:
- **Pieces** not byte-range segments (pieces can span files)
- **Peer connections** not HTTP connections to a single server
- **Multi-file** by default (a torrent contains a file tree)
- **Own file I/O** — the piece-to-file mapping doesn't match FileAccessor's
  `writeAt(offset, data)` model
- **Resume** via bitfield (which pieces are complete), not byte offsets
- **No server identity** — no ETag/Last-Modified equivalent; info hash IS the identity
- **Progress** is piece-count based, not byte-offset based

The current architecture would force BitTorrent to fight `Segment`, ignore
`headers`, fake `etag`/`lastModified` fields in TaskRecord, and reimplement
the entire download loop (as FTP already did).

---

## Refactoring Steps

### Step 1: Remove HTTP-specific fields from `TaskRecord`

**Files:** `library/core/.../task/TaskRecord.kt`

Remove `etag`, `lastModified`, and `acceptRanges` from `TaskRecord`. All
source-specific metadata should live in `sourceResumeState: SourceResumeState?`
which is already the proper abstraction (opaque JSON blob per source type).

```kotlin
// BEFORE
data class TaskRecord(
  val taskId: String,
  val request: DownloadRequest,
  val outputPath: String? = null,
  val state: TaskState = TaskState.QUEUED,
  val totalBytes: Long = -1,
  val error: KetchError? = null,
  val acceptRanges: Boolean? = null,   // ← HTTP-specific
  val etag: String? = null,            // ← HTTP-specific
  val lastModified: String? = null,    // ← HTTP-specific
  val segments: List<Segment>? = null,
  val sourceType: String? = null,
  val sourceResumeState: SourceResumeState? = null,
  val createdAt: Instant,
  val updatedAt: Instant,
)

// AFTER
data class TaskRecord(
  val taskId: String,
  val request: DownloadRequest,
  val outputPath: String? = null,
  val state: TaskState = TaskState.QUEUED,
  val totalBytes: Long = -1,
  val error: KetchError? = null,
  val segments: List<Segment>? = null,
  val sourceType: String? = null,
  val sourceResumeState: SourceResumeState? = null,
  val createdAt: Instant,
  val updatedAt: Instant,
)
```

**Migration:** HTTP source stores its etag/lastModified/acceptRanges in
`HttpResumeState` (it already does this for resume — just make it the
sole storage location from the start).

---

### Step 2: Remove `HttpDownloadSource` references from `DownloadExecution`

**Files:** `library/core/.../engine/DownloadExecution.kt`

Currently `DownloadExecution` has three direct references to `HttpDownloadSource`:
- Line 165-168: Reads `HttpDownloadSource.META_ETAG` / `META_LAST_MODIFIED` from
  resolved metadata to store in TaskRecord
- Line 204: Calls `HttpDownloadSource.buildResumeState()` as fallback when
  `taskRecord.sourceResumeState` is null
- Line 280: Calls `HttpDownloadSource.buildResumeState()` on completion

**Refactoring approach:** Add a `buildResumeState()` method to `DownloadSource` so
each source can build its own resume state from the resolved metadata. Make the
source responsible for persisting and restoring its state.

```kotlin
// Add to DownloadSource interface:
interface DownloadSource {
  // ... existing methods ...

  /**
   * Builds a resume state from resolved metadata after a successful
   * download or before pausing. Sources use this to persist any
   * source-specific state needed for resume validation.
   */
  fun buildResumeState(resolved: ResolvedSource, totalBytes: Long): SourceResumeState
}
```

Then `DownloadExecution.executeFresh()` becomes:

```kotlin
// BEFORE (HTTP-specific)
handle.record.update {
  it.copy(
    etag = resolvedUrl.metadata[HttpDownloadSource.META_ETAG],
    lastModified = resolvedUrl.metadata[HttpDownloadSource.META_LAST_MODIFIED],
    sourceType = source.type,
    ...
  )
}

// AFTER (protocol-agnostic)
handle.record.update {
  it.copy(
    sourceType = source.type,
    sourceResumeState = source.buildResumeState(resolvedUrl, total),
    ...
  )
}
```

And `executeResume()` no longer needs `HttpDownloadSource.buildResumeState()`
fallback since `sourceResumeState` is always populated:

```kotlin
// BEFORE
val resumeState = taskRecord.sourceResumeState
  ?: HttpDownloadSource.buildResumeState(
    etag = taskRecord.etag,
    lastModified = taskRecord.lastModified,
    totalBytes = taskRecord.totalBytes,
  )

// AFTER
val resumeState = taskRecord.sourceResumeState
  ?: throw KetchError.CorruptResumeState("No resume state for taskId=$taskId")
```

---

### Step 3: Extract shared segmented download logic into `SegmentedDownloadHelper`

**Files:** New `library/core/.../segment/SegmentedDownloadHelper.kt`

Both `HttpDownloadSource` and `FtpDownloadSource` implement nearly identical
download loop logic:
- `downloadSegments()` — outer loop with resegmentation
- `downloadBatch()` — concurrent segment execution with connection-change watcher
- Progress aggregation with throttled updates
- Mutex-protected segment state

Extract this into a reusable helper that both sources can delegate to:

```kotlin
internal class SegmentedDownloadHelper(
  private val progressIntervalMs: Long = 200,
) {
  /**
   * Downloads segments concurrently with dynamic resegmentation support.
   *
   * @param downloadSegment called for each individual segment;
   *   the source provides the protocol-specific download logic
   */
  suspend fun downloadAll(
    context: DownloadContext,
    segments: List<Segment>,
    totalBytes: Long,
    downloadSegment: suspend (Segment, onProgress: suspend (Long) -> Unit) -> Segment,
  ) { /* shared loop logic */ }
}
```

`HttpDownloadSource` passes a lambda that creates `SegmentDownloader` per segment.
`FtpDownloadSource` passes a lambda that opens an FTP connection per segment.
Future sources (HLS) pass their own segment download logic.

This eliminates ~200 lines of duplicated code between HTTP and FTP sources.

---

### Step 4: Decouple `SegmentDownloader` from `HttpEngine`

**Files:** `library/core/.../segment/SegmentDownloader.kt`

Currently `SegmentDownloader` takes `HttpEngine` directly and calls
`httpEngine.download(url, range, headers)`. It should instead accept a
generic download function:

```kotlin
// BEFORE
internal class SegmentDownloader(
  private val httpEngine: HttpEngine,
  private val fileAccessor: FileAccessor,
  private val taskLimiter: SpeedLimiter,
  private val globalLimiter: SpeedLimiter,
)

// AFTER — simplified since SegmentedDownloadHelper handles the loop
// SegmentDownloader remains HTTP-specific but is clearly scoped to
// HttpDownloadSource only (not used by other sources).
```

With Step 3's `SegmentedDownloadHelper`, `SegmentDownloader` becomes an
internal detail of `HttpDownloadSource` rather than a shared component that
other sources are expected to (but can't) use.

---

### Step 5: Remove `headers` from `DownloadSource.resolve()` signature

**Files:** `library/core/.../engine/DownloadSource.kt`, all implementations

The `headers` parameter on `resolve()` is HTTP-specific. Non-HTTP sources ignore it.
Instead, source-specific configuration should come through the URL or constructor:

```kotlin
// BEFORE
suspend fun resolve(
  url: String,
  headers: Map<String, String> = emptyMap(),
): ResolvedSource

// AFTER
suspend fun resolve(
  url: String,
  properties: Map<String, String> = emptyMap(),
): ResolvedSource
```

Rename `headers` to `properties` — a generic key-value map. HTTP source reads
HTTP headers from it. FTP source ignores it (or reads credentials). BitTorrent
could read tracker preferences. The semantics are source-defined.

This also aligns with `DownloadRequest.properties` which already exists for
arbitrary extension data.

**Alternative (simpler):** Just pass the entire `DownloadRequest` to `resolve()`
so each source can pick what it needs:

```kotlin
suspend fun resolve(request: DownloadRequest): ResolvedSource
```

This is cleaner because `DownloadRequest` already has `url`, `headers`,
`properties`, `connections`, and `selectedFileIds` — everything a source
might need.

---

### Step 6: Remove `ServerInfo` dependency from `FileNameResolver`

**Files:** `library/core/.../file/FileNameResolver.kt`, `DownloadExecution.kt`

`DownloadExecution.toServerInfo()` converts `ResolvedSource` to `ServerInfo`
just to pass to `FileNameResolver`. This couples filename resolution to
HTTP-specific `ServerInfo`. Instead, `FileNameResolver` should work with
`ResolvedSource` directly:

```kotlin
// BEFORE
interface FileNameResolver {
  fun resolve(request: DownloadRequest, serverInfo: ServerInfo): String?
}

// AFTER
interface FileNameResolver {
  fun resolve(request: DownloadRequest, resolved: ResolvedSource): String?
}
```

`ServerInfo` then becomes purely internal to `HttpDownloadSource` /
`RangeSupportDetector` — it's an HTTP-layer detail, not a core model.

---

### Step 7: Source-managed resume state lifecycle

**Files:** `DownloadSource.kt`, `DownloadExecution.kt`, `DownloadCoordinator.kt`

Add a `saveResumeState()` to `DownloadSource` so sources can update their resume
state periodically (not just at download end):

```kotlin
interface DownloadSource {
  // ... existing ...

  /**
   * Called periodically during download to let the source update its
   * resume state. Called by the save-interval job in DownloadExecution.
   * Default implementation returns null (no update needed).
   */
  fun updateResumeState(context: DownloadContext): SourceResumeState? = null
}
```

This lets BitTorrent persist its bitfield incrementally, while HTTP sources
can return null (their resume state doesn't change mid-download).

---

## Summary of Changes

| # | Change | Files Touched | Risk |
|---|--------|---------------|------|
| 1 | Remove HTTP fields from TaskRecord | TaskRecord, DownloadExecution, HttpDownloadSource | Medium — migration of persisted data |
| 2 | Remove HttpDownloadSource refs from DownloadExecution | DownloadExecution, DownloadSource | Low |
| 3 | Extract SegmentedDownloadHelper | New file + HttpDownloadSource + FtpDownloadSource | Medium — refactoring shared logic |
| 4 | Scope SegmentDownloader to HTTP | SegmentDownloader, HttpDownloadSource | Low |
| 5 | Rename headers→properties in resolve() | DownloadSource, all impls, DownloadExecution | Low |
| 6 | FileNameResolver uses ResolvedSource | FileNameResolver, DownloadExecution | Low |
| 7 | Source-managed resume state lifecycle | DownloadSource, DownloadExecution | Low |

### Execution Order

Steps 1-2 should be done together (they're tightly coupled). Step 3 is the
largest change but is mostly mechanical. Steps 4-7 are independent and can
be done in any order after 1-2.

### What stays the same

- `KetchApi` interface — no public API changes
- `DownloadTask` interface — no changes
- `Segment` model — still used for byte-range protocols (HTTP, FTP); BitTorrent
  maps pieces to segments for progress reporting
- `DownloadQueue`, `DownloadScheduler` — unchanged (protocol-agnostic already)
- `SpeedLimiter`, `TokenBucket` — unchanged
- `DownloadConfig` — unchanged
- `DownloadState`, `DownloadProgress` — unchanged
