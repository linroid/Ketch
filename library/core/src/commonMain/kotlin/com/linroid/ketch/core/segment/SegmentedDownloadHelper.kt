package com.linroid.ketch.core.segment

import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.engine.DownloadContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
   * @param downloadSegment protocol-specific download function for
   *   a single segment. Receives the segment and a progress
   *   callback (bytesDownloaded so far for this segment). Must
   *   return the completed segment with final downloadedBytes.
   */
  suspend fun downloadAll(
    context: DownloadContext,
    segments: List<Segment>,
    totalBytes: Long,
    downloadSegment: suspend (
      segment: Segment,
      onProgress: suspend (bytesDownloaded: Long) -> Unit,
    ) -> Segment,
  ) {
    var currentSegments = segments

    while (true) {
      val incomplete = currentSegments.filter { !it.isComplete }
      if (incomplete.isEmpty()) break

      val batchCompleted = downloadBatch(
        context, currentSegments, incomplete, totalBytes,
        downloadSegment,
      )

      if (batchCompleted) break

      val newCount = context.pendingResegment
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
   * A watcher coroutine monitors [DownloadContext.maxConnections]
   * for changes. When the connection count changes, it sets
   * [DownloadContext.pendingResegment] and cancels the scope.
   *
   * @return `true` if all segments completed, `false` if
   *   interrupted for resegmentation
   */
  private suspend fun downloadBatch(
    context: DownloadContext,
    allSegments: List<Segment>,
    incompleteSegments: List<Segment>,
    totalBytes: Long,
    downloadSegment: suspend (
      segment: Segment,
      onProgress: suspend (bytesDownloaded: Long) -> Unit,
    ) -> Segment,
  ): Boolean {
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
        val watcherJob = launch {
          val lastSeen = context.maxConnections.value
          context.maxConnections.first { count ->
            count > 0 && count != lastSeen
          }
          context.pendingResegment =
            context.maxConnections.value
          log.i {
            "Connection change detected for " +
              "taskId=${context.taskId}: " +
              "$lastSeen -> ${context.pendingResegment}"
          }
          throw CancellationException("Resegmenting")
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
          watcherJob.cancel()
        }

        context.segments.value = currentSegments()
        true
      }
    } catch (e: CancellationException) {
      if (context.pendingResegment > 0) {
        withContext(NonCancellable) {
          context.segments.value = currentSegments()
        }
        false
      } else {
        throw e
      }
    }
  }
}
