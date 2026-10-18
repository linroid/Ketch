package com.linroid.ketch.core.segment

import com.linroid.ketch.api.Segment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One connection's share of a segmented download: the bytes from [offset] to [end].
 *
 * A connection that finished its own share early may take over the tail of this one, which
 * lowers [end] while the transfer runs. Sources therefore ask [admit] how much of each chunk
 * still belongs to the lane before writing it, and report what they wrote with [commit]:
 *
 * ```
 * val allowed = lane.admit(data.size)
 * throttle(allowed)
 * fileAccessor.writeAt(offset, data.copyOf(allowed))
 * offset += allowed
 * lane.commit(allowed)
 * ```
 *
 * Once a lane whose tail was taken has written all its bytes, [admit] and [commit] end the
 * transfer by throwing a [CancellationException]: the source stops reading and closes its
 * response, and [SegmentedDownloadHelper] counts the lane as done. A transfer that ends while the
 * lane still has bytes left fails the download as a connection closed prematurely.
 */
class SegmentLane internal constructor(
  private val ledger: SegmentLedger,
  /** The first byte of this lane's range, inclusive. */
  val start: Long,
  cursor: Long,
  limit: Long,
  internal val startedAt: TimeMark,
) {
  /** The next offset to write. */
  @Volatile
  internal var cursor: Long = cursor

  /** The offset after this lane's last byte; only ever lowered, never below [admitted]. */
  @Volatile
  internal var limit: Long = limit

  /** The offset after the last byte [admit] handed out; [cursor] once those are committed. */
  internal var admitted: Long = cursor

  /** Whether another lane took over this one's tail. */
  internal var cut: Boolean = false

  /** Bytes committed since this lane started, for its rate. */
  internal var laneBytes: Long = 0

  /** The transfer running this lane, cancelled when its tail is taken while it waits. */
  @Volatile
  internal var transfer: Job? = null

  /** The next offset to write. */
  val offset: Long get() = cursor

  /** The last byte this lane downloads, inclusive. It drops when another lane takes the tail. */
  val end: Long get() = limit - 1

  /** Bytes this lane still downloads. */
  val remaining: Long get() = (limit - cursor).coerceAtLeast(0)

  /** Whether every byte of this lane is written. */
  val isComplete: Boolean get() = cursor >= limit

  /**
   * Reserves the next [bytes] of the transfer and returns how many of them belong to this lane:
   * [bytes], or fewer at its [end]. Write exactly that many at [offset], then [commit] them.
   * Throws a [CancellationException] when another lane took the rest.
   */
  suspend fun admit(bytes: Int): Int = ledger.admit(this, bytes)

  /** Records [bytes] admitted by [admit] as written at [offset]. */
  suspend fun commit(bytes: Int) = ledger.commit(this, bytes)

  internal fun toSegment(index: Int): Segment =
    Segment(index = index, start = start, end = limit - 1, downloadedBytes = cursor - start)
}

/** Ends the transfer of a lane whose remaining bytes another lane took over. */
internal class LaneDrainedException : CancellationException("Lane drained")

/** A lane [SegmentLedger.steal] handed to a connection, taken from [victim]'s tail. */
internal class Steal(val lane: SegmentLane, val victim: SegmentLane, val victimIdle: Boolean)

/**
 * The byte ranges of one segmented batch and the lanes downloading them.
 *
 * Every byte belongs to exactly one lane. A connection that finished its lane takes over the
 * tail of the lane expected to finish last ([steal]), split where both are expected to finish
 * together from their measured rates, or in half while the rates are unknown. A split never
 * moves a byte already admitted, and leaves both lanes at least [minSplitBytes]. A connection
 * that received nothing in its last measured lane takes nothing, and a lane that received
 * nothing hands all of it over.
 *
 * @param minSplitBytes the smallest range a split hands over; [Long.MAX_VALUE] never splits
 * @param rateWindow how long a lane runs before its rate counts
 * @param setupTime what starting another request costs, weighed against continuing the victim
 * @param onCommitted runs after every [commit], outside the lock
 */
internal class SegmentLedger(
  segments: List<Segment>,
  private val minSplitBytes: Long = DEFAULT_MIN_SPLIT_BYTES,
  private val timeSource: TimeSource = TimeSource.Monotonic,
  private val rateWindow: Duration = 1.seconds,
  private val setupTime: Duration = 0.5.seconds,
  private val onCommitted: suspend () -> Unit = {},
) {
  init {
    require(minSplitBytes > 0) { "minSplitBytes must be positive" }
  }

  private val mutex = Mutex()
  private val lanes: MutableList<SegmentLane> = segments.map { segment ->
    SegmentLane(this, segment.start, segment.currentOffset, segment.end + 1, timeSource.markNow())
  }.toMutableList()

  /** The lanes of the incomplete segments the ledger started with. */
  val initialLanes: List<SegmentLane> = lanes.filter { !it.isComplete }

  /** The ranges as segments, ordered and indexed by offset. */
  suspend fun snapshot(): List<Segment> = mutex.withLock {
    lanes.sortedBy { it.start }.mapIndexed { index, lane -> lane.toSegment(index) }
  }

  internal suspend fun admit(lane: SegmentLane, bytes: Int): Int {
    require(bytes >= 0) { "bytes must not be negative" }
    val allowed = mutex.withLock {
      val allowed = minOf(bytes.toLong(), lane.limit - lane.admitted).coerceAtLeast(0).toInt()
      if (allowed == 0 && lane.cut) return@withLock -1
      lane.admitted += allowed
      allowed
    }
    if (allowed < 0) throw LaneDrainedException()
    return allowed
  }

  internal suspend fun commit(lane: SegmentLane, bytes: Int) {
    val drained = mutex.withLock {
      require(bytes >= 0 && lane.cursor + bytes <= lane.admitted) {
        "Committed bytes must have been admitted"
      }
      lane.cursor += bytes
      lane.laneBytes += bytes
      lane.cut && lane.cursor >= lane.limit
    }
    onCommitted()
    if (drained) throw LaneDrainedException()
  }

  /**
   * Splits the tail off the lane expected to finish last and returns it as a new lane for
   * [thief], which finished its own; `null` when no lane has enough left to be worth it.
   */
  suspend fun steal(thief: SegmentLane): Steal? = mutex.withLock {
    if (minSplitBytes == Long.MAX_VALUE) return@withLock null
    val candidates = lanes.filter { it !== thief && it.limit - it.admitted >= minSplitBytes }
    if (candidates.isEmpty()) return@withLock null
    val thiefRate = rateOf(thief)
    val rates = candidates.associateWith { rateOf(it) }
    val known = rates.values.filterNotNull().sorted()
    val typical = thiefRate ?: known.getOrNull(known.size / 2)
    // Expected time to finish, or bytes left while no rate is known.
    val ordered = candidates.sortedByDescending { lane ->
      val left = (lane.limit - lane.admitted).toDouble()
      val rate = rates[lane] ?: typical
      when {
        rate == null -> left
        rate <= 0.0 -> Double.POSITIVE_INFINITY
        else -> left / rate
      }
    }
    for (victim in ordered) {
      val left = victim.limit - victim.admitted
      val keep = keepFor(left, rates[victim], thiefRate) ?: continue
      if (left - keep < minSplitBytes) continue
      val split = victim.admitted + keep
      val lane = SegmentLane(this, split, split, victim.limit, timeSource.markNow())
      victim.limit = split
      victim.cut = true
      // A victim left with no byte at all is replaced rather than kept as an empty range, which
      // would be saved as a segment ending before it starts.
      if (split == victim.start) lanes -= victim
      lanes += lane
      val idle = victim.cursor == victim.admitted && victim.cursor >= victim.limit
      return@withLock Steal(lane, victim, idle)
    }
    null
  }

  /**
   * How many of [left] bytes the victim keeps so that it and the thief finish together, from
   * their rates in bytes per second; half while either is unknown, `null` when splitting would
   * not finish sooner, as when the thief received nothing in its last lane.
   */
  private fun keepFor(left: Long, victimRate: Double?, thiefRate: Double?): Long? {
    // Checked first: a victim too new to have a rate would otherwise lose half to it.
    if (thiefRate != null && thiefRate <= 0.0) return null
    if (victimRate == null || thiefRate == null) return left / 2
    if (victimRate <= 0.0) return 0
    val setup = setupTime.inWholeMilliseconds / 1000.0
    // keep / victimRate == (left - keep) / thiefRate + setup
    val keep = ((left + thiefRate * setup) * victimRate / (victimRate + thiefRate)).toLong()
    if (keep >= left) return null
    val alone = left / victimRate
    val together = (left - keep) / thiefRate + setup
    if (together >= alone) return null
    return keep.coerceAtLeast(0)
  }

  /** [lane]'s rate in bytes per second, once it ran for [rateWindow]. */
  private fun rateOf(lane: SegmentLane): Double? {
    val elapsed = lane.startedAt.elapsedNow()
    if (elapsed < rateWindow) return null
    return lane.laneBytes / (elapsed.inWholeMilliseconds / 1000.0)
  }

  companion object {
    /** The smallest range a connection takes over from another: 1 MiB. */
    const val DEFAULT_MIN_SPLIT_BYTES: Long = 1024 * 1024
  }
}
