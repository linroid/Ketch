package com.linroid.ketch.core.segment

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.RetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Shared loop logic for segmented parallel downloads with dynamic
 * resegmentation support.
 *
 * Both [com.linroid.ketch.core.engine.HttpDownloadSource] and
 * FTP/other segmented sources can delegate to this helper instead
 * of reimplementing the download loop, progress aggregation, and
 * connection-change watcher logic independently.
 *
 * Each segment runs over its own connection as a [SegmentLane]. A connection that finishes early
 * takes over the remaining bytes of a slower one, so a download no longer waits on its slowest
 * connection. A connection that fails retryably gives its remaining bytes up to the others and
 * retries on its own, after [RetryPolicy]'s delay, while the others keep running.
 *
 * @param progressIntervalMs minimum interval between progress
 *   reports in milliseconds
 * @param tag log tag for this helper instance
 * @param minSplitBytes the smallest range a connection takes over from another; smaller tails
 *   finish on the connection that has them; [Long.MAX_VALUE] never takes one over
 * @param timeSource measures the connections' rates, which decide where a range is split
 */
class SegmentedDownloadHelper(
  private val progressIntervalMs: Long = 200,
  tag: String = "SegmentHelper",
  private val minSplitBytes: Long = SegmentLedger.DEFAULT_MIN_SPLIT_BYTES,
  private val timeSource: TimeSource = TimeSource.Monotonic,
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
   * With [supportsRanges], a connection whose transfer fails with a retryable [KetchError] other
   * than HTTP 429 does not stop the others: its unwritten bytes go to the next connection that
   * finishes, and it waits [RetryPolicy.delayMs] before taking them back, or another
   * connection's tail when they were taken. Its retries count from zero again after a transfer
   * that wrote bytes. The batch fails, for the download to retry the run, when a connection runs
   * out of retries, the error is not retryable, it is a 429 (the source then lowers the
   * connections), or no other connection is transferring.
   *
   * @param context the download context with progress callbacks
   * @param segments initial list of segments (some may already
   *   have progress)
   * @param totalBytes total download size
   * @param supportsRanges whether the server can transfer from arbitrary
   *   byte offsets (HTTP Range, FTP REST). When `false`, changes to
   *   [DownloadContext.maxConnections] are ignored: splitting the single
   *   transfer would need offsets the server cannot serve, and no connection takes over
   *   another's bytes.
   * @param requestedConnections the [DownloadContext.maxConnections] value
   *   [segments] were sized from. Any other value, including one set while
   *   the source was still preparing, resegments the first batch. Sources
   *   should read it when they size their segments; it defaults to the
   *   current value.
   * @param downloadSegment protocol-specific transfer of one [SegmentLane]: from
   *   [SegmentLane.offset] to [SegmentLane.end], writing each chunk as [SegmentLane.admit]
   *   allows and reporting it with [SegmentLane.commit]. It may be called again on the same
   *   connection's coroutine for ranges taken over from slower connections. Returning before the
   *   lane is complete fails the download with a retryable [KetchError.Network].
   */
  suspend fun downloadAll(
    context: DownloadContext,
    segments: List<Segment>,
    totalBytes: Long,
    supportsRanges: Boolean = true,
    requestedConnections: Int = context.maxConnections.value,
    downloadSegment: suspend (lane: SegmentLane) -> Unit,
  ) {
    var currentSegments = segments
    var requested = requestedConnections

    while (true) {
      if (currentSegments.all { it.isComplete }) break

      val batchCompleted = downloadBatch(
        context, currentSegments, totalBytes, supportsRanges, requested, downloadSegment,
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
   * Downloads one batch of incomplete segments concurrently, one connection per segment, but no
   * more connections than [requestedAtStart] allows: the other segments, gaps that resegmenting
   * to fewer connections could not join, wait for a connection to finish. A connection that
   * finishes its segment takes over a waiting one, else the tail of the slowest one still running
   * ([SegmentLedger.steal]) instead of closing, so the batch no longer waits on its slowest
   * connection; it closes once no segment has [minSplitBytes] left to hand over.
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
    totalBytes: Long,
    supportsRanges: Boolean,
    requestedAtStart: Int,
    downloadSegment: suspend (lane: SegmentLane) -> Unit,
  ): Boolean {
    var lastProgressUpdate = Clock.System.now()
    val progressMutex = Mutex()
    lateinit var ledger: SegmentLedger

    suspend fun updateProgress() {
      val now = Clock.System.now()
      progressMutex.withLock {
        if (now - lastProgressUpdate >= progressIntervalMs.milliseconds) {
          val snapshot = ledger.snapshot()
          context.onProgress(snapshot.sumOf { it.downloadedBytes }, totalBytes)
          context.segments.value = snapshot
          lastProgressUpdate = now
        }
      }
    }

    ledger = SegmentLedger(
      allSegments,
      minSplitBytes = if (supportsRanges) minSplitBytes else Long.MAX_VALUE,
      timeSource = timeSource,
      onCommitted = { updateProgress() },
      // A server that refused more connections (429) must not get one per gap.
      connections = context.effectiveConnections(requestedAtStart).coerceAtLeast(1),
    )
    // Connections the batch really runs: fewer than effectiveConnections() when a source capped
    // them (HttpDownloadSource.applyRateLimit) or segments already finished, never more.
    val lanes = ledger.initialLanes
    val runningConnections = lanes.size

    val downloadedBytes = allSegments.sumOf { it.downloadedBytes }
    context.onProgress(downloadedBytes, totalBytes)
    context.segments.value = allSegments

    return try {
      coroutineScope {
        val batchScope = this
        // The watcher reacts to every value other than the one the segments were sized from,
        // 0 (Auto) included. maxConnections replays its current value on subscription, so a
        // change made before the watcher subscribes counts too, such as one made during the
        // progress report above or while the source was preparing.
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
          lanes.map { lane ->
            launch {
              runConnection(context, ledger, lane, retriesAlone = supportsRanges, downloadSegment)
            }
          }.joinAll()
        } finally {
          watcherJob?.cancel()
        }
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
        context.segments.value = ledger.snapshot()
      }
    }
  }

  /**
   * Runs one connection: [first], then every tail it takes over from slower connections. With
   * [retriesAlone], a retryable failure releases the lane and retries after a delay instead of
   * failing the batch, as [downloadAll] describes.
   */
  private suspend fun runConnection(
    context: DownloadContext,
    ledger: SegmentLedger,
    first: SegmentLane,
    retriesAlone: Boolean,
    downloadSegment: suspend (lane: SegmentLane) -> Unit,
  ) {
    val policy = RetryPolicy(context.config)
    var retries = 0
    var lane = first
    while (true) {
      try {
        runLane(lane, downloadSegment)
      } catch (e: KetchError) {
        if (!retriesAlone || !e.isRetryable || (e is KetchError.Http && e.code == 429)) throw e
        if (lane.laneBytes > 0) retries = 0
        // Read before the release: a connection may take the released bytes, finish them and end
        // before this one reads the count again.
        val ended = ledger.ended.value
        if (!policy.allows(retries) || !ledger.release(lane)) throw e
        retries++
        val delayMs = policy.delayMs(retries)
        log.w {
          "Range ${lane.offset}..${lane.end} failed for taskId=${context.taskId}, " +
            "retrying the connection in ${delayMs}ms: ${e.describeCauses()}"
        }
        context.segments.value = ledger.snapshot()
        // Stop waiting once another connection took this lane and one ended for lack of work:
        // none is left for this one.
        withTimeoutOrNull(delayMs) { ledger.ended.first { it > ended && !lane.released } }
        val steal = ledger.rejoin(lane) ?: return
        if (steal.victimIdle) steal.victim.transfer?.cancel(LaneDrainedException())
        if (steal.lane !== lane) {
          log.d {
            "Taking over ${steal.lane.start}..${steal.lane.end} instead of the range at " +
              "${lane.start} for taskId=${context.taskId}"
          }
        }
        lane = steal.lane
        continue
      }
      log.d { "Range ${lane.start}..${lane.end} completed for taskId=${context.taskId}" }
      context.segments.value = ledger.snapshot()
      val steal = ledger.steal(lane) ?: return
      // A victim waiting for data it no longer needs would hold its connection until then.
      if (steal.victimIdle) steal.victim.transfer?.cancel(LaneDrainedException())
      lane = steal.lane
      log.d {
        "Taking over ${lane.start}..${lane.end} from the range at ${steal.victim.start} " +
          "for taskId=${context.taskId}"
      }
    }
  }

  /**
   * Runs [downloadSegment] for [lane] in a job of its own, which [runConnection] cancels when
   * another connection takes the lane's last bytes. A transfer that ends, however, once the lane
   * is complete has done its work: a lane whose tail was taken stops its source by throwing.
   */
  private suspend fun runLane(
    lane: SegmentLane,
    downloadSegment: suspend (lane: SegmentLane) -> Unit,
  ) {
    try {
      coroutineScope {
        lane.transfer = launch {
          try {
            downloadSegment(lane)
          } catch (e: Exception) {
            if (!lane.isComplete) throw e
          }
        }
      }
    } finally {
      lane.transfer = null
    }
    if (!lane.isComplete) {
      throw KetchError.Network(
        Exception(
          "Connection closed prematurely: received ${lane.offset - lane.start} of " +
            "${lane.end - lane.start + 1} bytes",
        ),
      )
    }
  }
}
