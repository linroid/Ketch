package com.linroid.ketch.core.segment

import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.engine.DownloadContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shared loop logic for segmented parallel downloads with dynamic
 * resegmentation support.
 *
 * Both [com.linroid.ketch.core.engine.HttpDownloadSource] and
 * FTP/other segmented sources can delegate to this helper instead
 * of reimplementing the download loop, progress aggregation, and
 * connection-change watcher logic independently.
 *
 * @param progressIntervalMs minimum interval between progress
 *   reports in milliseconds
 * @param tag log tag for this helper instance
 */
class SegmentedDownloadHelper(
  private val progressIntervalMs: Long = 200,
  tag: String = "SegmentHelper",
) {
  private val log = KetchLogger(tag)

  /**
   * Downloads all incomplete segments concurrently with dynamic
   * resegmentation support.
   *
   * Manages a while loop that calls [downloadBatch] for incomplete
   * segments. When [DownloadContext.maxConnections] changes, the
   * batch is canceled, progress is snapshotted, segments are
   * merged/split via [SegmentCalculator.resegment], and a new
   * batch starts.
   *
   * @param context the download context with progress callbacks
   * @param segments initial list of segments (some may already
   *   have progress)
   * @param totalBytes total download size
   * @param supportsRanges whether the server can transfer from arbitrary
   *   byte offsets (HTTP Range, FTP REST). When `false`, changes to
   *   [DownloadContext.maxConnections] are ignored: splitting the single
   *   transfer would need offsets the server cannot serve.
   * @param requestedConnections the [DownloadContext.maxConnections] value
   *   [segments] were sized from. Any other value, including one set while
   *   the source was still preparing, resegments the first batch. Sources
   *   should read it when they size their segments; it defaults to the
   *   current value.
   * @param downloadSegment protocol-specific download function for
   *   a single segment. Receives the segment and a progress
   *   callback (bytesDownloaded so far for this segment). Must
   *   return the completed segment with final downloadedBytes.
   */
  suspend fun downloadAll(
    context: DownloadContext,
    segments: List<Segment>,
    totalBytes: Long,
    supportsRanges: Boolean = true,
    requestedConnections: Int = context.maxConnections.value,
    downloadSegment: suspend (
      segment: Segment,
      onProgress: suspend (bytesDownloaded: Long) -> Unit,
    ) -> Segment,
  ) {
    var currentSegments = segments
    var requested = requestedConnections

    while (true) {
      val incomplete = currentSegments.filter { !it.isComplete }
      if (incomplete.isEmpty()) break

      val batchCompleted = downloadBatch(
        context, currentSegments, incomplete, totalBytes,
        supportsRanges, requested, downloadSegment,
      )

      currentSegments = context.segments.value
      if (batchCompleted) break

      // Read the request again instead of using the watcher's target: it may have changed while
      // the batch was stopping, when no watcher was listening. The next batch compares against
      // this value, so a change made after this read restarts it.
      requested = context.maxConnections.value
      val newCount = context.effectiveConnections(requested)
      context.pendingResegment = 0
      currentSegments = SegmentCalculator.resegment(
        context.segments.value, newCount,
      )
      context.segments.value = currentSegments
      log.i {
        "Resegmented to $newCount connections for " +
          "taskId=${context.taskId}"
      }
    }

    context.segments.value = currentSegments
    context.onProgress(totalBytes, totalBytes)
  }

  /**
   * Downloads one batch of incomplete segments concurrently.
   *
   * When [supportsRanges] is `true`, a watcher coroutine monitors
   * [DownloadContext.maxConnections] for values other than
   * [requestedAtStart]. When the effective connection count of such a
   * value (0 meaning Auto) differs from the number of segments the batch
   * runs, it sets [DownloadContext.pendingResegment] and cancels the scope.
   *
   * @return `true` if all segments completed, `false` if
   *   interrupted for resegmentation
   */
  private suspend fun downloadBatch(
    context: DownloadContext,
    allSegments: List<Segment>,
    incompleteSegments: List<Segment>,
    totalBytes: Long,
    supportsRanges: Boolean,
    requestedAtStart: Int,
    downloadSegment: suspend (
      segment: Segment,
      onProgress: suspend (bytesDownloaded: Long) -> Unit,
    ) -> Segment,
  ): Boolean {
    // Connections the batch really runs: fewer than effectiveConnections() when a source capped
    // them (HttpDownloadSource.applyRateLimit) or segments already finished.
    val runningConnections = incompleteSegments.size
    // The watcher reacts to every value other than the one the segments were sized from,
    // 0 (Auto) included. maxConnections replays its current value on subscription, so a change
    // made before the watcher subscribes counts too, such as one made during the progress report
    // below or while the source was preparing.
    val segmentProgress =
      allSegments.map { it.downloadedBytes }.toMutableList()
    val segmentMutex = Mutex()
    val updatedSegments = allSegments.toMutableList()

    var lastProgressUpdate = Clock.System.now()
    val progressMutex = Mutex()

    suspend fun currentSegments(): List<Segment> {
      return segmentMutex.withLock {
        updatedSegments.mapIndexed { i, seg ->
          seg.copy(downloadedBytes = segmentProgress[i])
        }
      }
    }

    suspend fun updateProgress() {
      val now = Clock.System.now()
      progressMutex.withLock {
        if (now - lastProgressUpdate >=
          progressIntervalMs.milliseconds
        ) {
          val snapshot = currentSegments()
          val downloaded = snapshot.sumOf { it.downloadedBytes }
          context.onProgress(downloaded, totalBytes)
          context.segments.value = snapshot
          lastProgressUpdate = now
        }
      }
    }

    val downloadedBytes = allSegments.sumOf { it.downloadedBytes }
    context.onProgress(downloadedBytes, totalBytes)
    context.segments.value = allSegments

    return try {
      coroutineScope {
        val batchScope = this
        val watcherJob = if (supportsRanges) {
          launch(start = CoroutineStart.UNDISPATCHED) {
            val target = context.maxConnections
              .dropWhile { it == requestedAtStart }
              .map { context.effectiveConnections(it) }
              .first { it != runningConnections }
            context.pendingResegment = target
            log.i {
              "Connection change detected for taskId=${context.taskId}: " +
                "$runningConnections -> $target"
            }
            batchScope.cancel(CancellationException("Resegmenting"))
          }
        } else {
          null
        }

        try {
          val results = incompleteSegments.map { segment ->
            async {
              val completed = downloadSegment(
                segment,
              ) { bytesDownloaded ->
                segmentMutex.withLock {
                  segmentProgress[segment.index] =
                    bytesDownloaded
                }
                updateProgress()
              }
              segmentMutex.withLock {
                updatedSegments[completed.index] = completed
                segmentProgress[completed.index] = completed.downloadedBytes
              }
              context.segments.value = currentSegments()
              log.d {
                "Segment ${completed.index} completed for " +
                  "taskId=${context.taskId}"
              }
              completed
            }
          }
          results.awaitAll()
        } finally {
          watcherJob?.cancel()
        }

        context.segments.value = currentSegments()
        true
      }
    } catch (e: CancellationException) {
      currentCoroutineContext().ensureActive()
      if (context.pendingResegment > 0) {
        false
      } else {
        throw e
      }
    } finally {
      // Keep every written byte, even when throttled progress has not been published yet.
      withContext(NonCancellable) {
        context.segments.value = currentSegments()
      }
    }
  }
}
