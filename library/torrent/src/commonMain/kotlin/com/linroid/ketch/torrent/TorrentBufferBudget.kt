package com.linroid.ketch.torrent

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Shared across sessions; reservations precede allocating piece and connection buffers. */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentBufferBudget(
  val capacity: Int,
  private val parent: TorrentBufferBudget? = null,
) {
  private val used = AtomicInt(0)
  val allocated: Int get() = used.load()

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
    val parentLease = parent?.reserve(bytes)
    if (parent != null && parentLease == null) return null
    while (true) {
      val current = used.load()
      if (bytes > capacity - current) {
        parentLease?.close()
        return null
      }
      if (used.compareAndSet(current, current + bytes)) return Lease(bytes, parentLease)
    }
  }
}

/** Independent partitions guarantee one metadata reservation under saturated payload load. */
internal class TorrentExchangeBudgets(config: TorrentConfig) {
  private val root = TorrentBufferBudget(config.maxExchangeBytes)
  val transfer = TorrentBufferBudget(config.maxBufferedBytes, root)
  val metadata = TorrentBufferBudget(config.metadataExchangeBytes, root)
  val cache = TorrentBufferBudget(config.maxCachedMetadataBytes, root)
  val sessions = TorrentBufferBudget(config.maxSessionStateBytes, root)
  val allocated: Int get() = root.allocated
}
