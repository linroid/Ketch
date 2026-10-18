package com.linroid.ketch.core.segment

import com.linroid.ketch.api.Segment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
  startedAt: TimeMark,
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

  /**
   * Whether this lane's connection failed and waits to retry: its remaining bytes are free for
   * the next connection that asks [SegmentLedger.steal] or [SegmentLedger.rejoin].
   */
  @Volatile
  internal var released: Boolean = false

  /** When this lane's current transfer began, for its rate. */
  internal var startedAt: TimeMark = startedAt

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

/**
 * A lane [SegmentLedger.steal] handed to a connection, taken from [victim]'s tail, or all of
 * [victim]'s remaining bytes when its connection failed.
 */
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
 * A connection whose transfer failed [release]s its lane while it waits to retry: the next
 * connection to finish takes all its remaining bytes, whatever their size, unless it received
 * nothing in its last measured lane, and the failed one [rejoin]s once it waited, taking its own
 * lane back if nobody did, else a split like a finished connection.
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

  /** Connections transferring, or about to: every lane's but those waiting to retry. */
  private var active = initialLanes.size

  private val _ended = MutableStateFlow(0)

  /**
   * How many connections ended because nothing was left to take: a connection waiting to retry
   * has nothing left either once it rises.
   */
  val ended: StateFlow<Int> = _ended.asStateFlow()

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
    takeOver(thief, rateOf(thief)).also {
      if (it == null) {
        active--
        _ended.value++
      }
    }
  }

  /**
   * Gives up [lane] after its transfer failed, so other connections can take its remaining bytes
   * while its connection waits to retry. Returns `false`, changing nothing, when no other
   * connection is transferring: the failure is then likely the server's or the network's, not
   * this connection's.
   */
  suspend fun release(lane: SegmentLane): Boolean = mutex.withLock {
    if (active <= 1) return@withLock false
    active--
    // Bytes admitted but not committed were never written.
    lane.admitted = lane.cursor
    lane.released = lane.cursor < lane.limit
    true
  }

  /**
   * The lane a connection that [release]d [lane] runs once it waited to retry: [lane] again when
   * no other connection took it, else a tail split off another lane; `null` when nothing is left
   * worth taking, and the connection ends.
   */
  suspend fun rejoin(lane: SegmentLane): Steal? = mutex.withLock {
    val steal = if (lane.released) {
      lane.released = false
      restart(lane)
      Steal(lane, lane, victimIdle = false)
    } else {
      // The failed connection's own rate says nothing of the next transfer.
      takeOver(lane, thiefRate = null)
    }
    if (steal != null) active++
    steal
  }

  private fun takeOver(thief: SegmentLane, thiefRate: Double?): Steal? {
    // A connection that received nothing in its last lane takes nothing, not even a failed
    // connection's bytes, which then wait for their own connection to retry.
    if (thiefRate != null && thiefRate <= 0.0) return null
    lanes.firstOrNull { it.released }?.let { orphan ->
      // A failed connection's bytes go whole to the first connection free to take them.
      val lane = SegmentLane(this, orphan.cursor, orphan.cursor, orphan.limit, timeSource.markNow())
      orphan.released = false
      orphan.limit = orphan.cursor
      // Nothing written: the new lane replaces it rather than leave an empty range.
      if (orphan.cursor == orphan.start) lanes.remove(orphan)
      lanes += lane
      return Steal(lane, orphan, victimIdle = false)
    }
    if (minSplitBytes == Long.MAX_VALUE) return null
    val candidates = lanes.filter { it !== thief && it.limit - it.admitted >= minSplitBytes }
    if (candidates.isEmpty()) return null
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
      return Steal(lane, victim, idle)
    }
    return null
  }

  private fun restart(lane: SegmentLane) {
    lane.startedAt = timeSource.markNow()
    lane.laneBytes = 0
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
