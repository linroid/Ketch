package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.monotonicClock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Shared across sessions; reservations precede allocating piece and connection buffers. A budget
 * one of [shares] handed out also keeps to its fair part of their parent.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentBufferBudget(
  val capacity: Int,
  private val parent: TorrentBufferBudget? = null,
  private val shares: TorrentFairShares? = null,
) {
  private val used = AtomicInt(0)
  val allocated: Int get() = used.load()

  /** When a reservation within this budget's fair part last found no room, for [shares]. */
  internal val heldBackAt = AtomicLong(Long.MIN_VALUE / 2)

  init { require(capacity > 0) }

  inner class Lease(val bytes: Int, private val parentLease: Lease?) {
    private val closed = AtomicBoolean(false)
    fun close() {
      if (closed.compareAndSet(false, true)) {
        used.fetchAndAdd(-bytes)
        parentLease?.close()
      }
    }
  }

  fun reserve(bytes: Int): Lease? {
    require(bytes > 0)
    if (bytes > capacity) return null
    if (shares != null && !shares.admits(this, bytes)) return null
    val parentLease = parent?.reserve(bytes)
    if (parent != null && parentLease == null) {
      shares?.heldBack(this, bytes)
      return null
    }
    while (true) {
      val current = used.load()
      if (bytes > capacity - current) {
        parentLease?.close()
        shares?.heldBack(this, bytes)
        return null
      }
      if (used.compareAndSet(current, current + bytes)) return Lease(bytes, parentLease)
    }
  }
}

/**
 * Splits [parent] fairly between the budgets [share] hands out, one per torrent. Each may always
 * grow to its fair part, [parent]'s capacity divided between the shares that hold buffers or were
 * held back lately, and past it only while no other share is held back below its own part.
 * Without this, one busy torrent's peers take every buffer the moment it frees, and another
 * torrent, its web seeds above all, stalls until the first one finishes.
 *
 * Checking and reserving are not one step, so two shares passing at once may let one overshoot
 * its part briefly. Shares are never closed: an idle one drops out of the split.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentFairShares(
  private val parent: TorrentBufferBudget,
  private val clock: () -> Long = monotonicClock(),
) {
  private val members = AtomicReference(emptyList<TorrentBufferBudget>())

  /** A budget for one torrent, as large as [parent], that keeps to its fair part of it. */
  fun share(): TorrentBufferBudget = TorrentBufferBudget(parent.capacity, parent, this)

  /** Whether [share] may reserve [bytes] more: within its fair part, or nobody is held back. */
  internal fun admits(share: TorrentBufferBudget, bytes: Int): Boolean {
    val now = clock()
    val active = active(share, now)
    val fair = parent.capacity / active.size
    if (share.allocated + bytes <= maxOf(fair, bytes)) return true
    return active.none { it !== share && it.allocated < fair && isHeldBack(it, now) }
  }

  /**
   * [share] found no room for [bytes]; within its fair part, that holds the others back. A part
   * smaller than one of its pieces still holds that one piece.
   */
  internal fun heldBack(share: TorrentBufferBudget, bytes: Int) {
    val now = clock()
    if (share.allocated + bytes <= maxOf(parent.capacity / active(share, now).size, bytes)) {
      share.heldBackAt.store(now)
    }
  }

  /** The shares in the split now, [share] among them; idle ones leave it. */
  private fun active(share: TorrentBufferBudget, now: Long): List<TorrentBufferBudget> {
    while (true) {
      val current = members.load()
      val kept = current.filter { it === share || it.allocated > 0 || isHeldBack(it, now) }
      val next = if (kept.any { it === share }) kept else kept + share
      if (next == current || members.compareAndSet(current, next)) return next
    }
  }

  private fun isHeldBack(share: TorrentBufferBudget, now: Long) =
    now - share.heldBackAt.load() <= HELD_BACK_MS

  private companion object {
    /** How long a share that found no room within its part keeps the others from growing. */
    const val HELD_BACK_MS = 2_000L
  }
}

/** Independent partitions guarantee one metadata reservation under saturated payload load. */
internal class TorrentExchangeBudgets(config: TorrentConfig) {
  private val root = TorrentBufferBudget(config.maxExchangeBytes)
  val transfer = TorrentBufferBudget(config.maxBufferedBytes, root)

  /**
   * Pieces read back to upload or to prove, and the proofs waiting to be sent, across every
   * session, v1 and v2: half of [transfer] at most, so what peers ask of us can never take the
   * frames and pieces downloads need.
   */
  val uploads = TorrentBufferBudget(maxOf(1, config.maxBufferedBytes / 2), transfer)
  /**
   * Wire frames and piece availability of connected v1 peers, every session's together: as large
   * as [transfer] but apart from it, so connections never take the buffers pieces download into.
   * It sits outside [root], whose partitions it would otherwise squeeze.
   */
  val peers = TorrentBufferBudget(config.maxBufferedBytes)
  val metadata = TorrentBufferBudget(config.metadataExchangeBytes, root)
  val cache = TorrentBufferBudget(config.maxCachedMetadataBytes, root)
  val sessions = TorrentBufferBudget(config.maxSessionStateBytes, root)
  val allocated: Int get() = root.allocated
}
