package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TorrentBufferBudgetTest {
  @Test
  fun childFailureRollsBackParentAndDoubleCloseDoesNotUnderflow() {
    val root = TorrentBufferBudget(20)
    val child = TorrentBufferBudget(10, root)
    val lease = assertNotNull(child.reserve(8))
    assertNull(child.reserve(3))
    assertEquals(8, child.allocated)
    assertEquals(8, root.allocated)
    lease.close()
    lease.close()
    assertEquals(0, child.allocated)
    assertEquals(0, root.allocated)
  }

  @Test
  fun siblingReservationsCannotExceedAggregateCeiling() {
    val root = TorrentBufferBudget(10)
    val first = TorrentBufferBudget(10, root)
    val second = TorrentBufferBudget(10, root)
    val lease = assertNotNull(first.reserve(7))
    assertNull(second.reserve(4))
    assertEquals(0, second.allocated)
    lease.close()
    val next = assertNotNull(second.reserve(10))
    assertEquals(10, root.allocated)
    next.close()
    assertEquals(0, root.allocated)
  }

  @Test
  fun fullTransferPartitionCannotStarveMetadataPartition() {
    val config = TorrentConfig(maxBufferedBytes = 16_384, maxMetadataBytes = 1024)
    val budgets = TorrentExchangeBudgets(config)
    val transfer = budgets.transfer
    val metadata = budgets.metadata
    val payload = assertNotNull(transfer.reserve(transfer.capacity))
    val cached = assertNotNull(budgets.cache.reserve(budgets.cache.capacity))
    val state = assertNotNull(budgets.sessions.reserve(budgets.sessions.capacity))
    val info = assertNotNull(metadata.reserve(metadata.capacity))
    assertNull(transfer.reserve(1))
    assertEquals(transfer.capacity + metadata.capacity + budgets.cache.capacity +
      budgets.sessions.capacity, budgets.allocated)
    info.close()
    payload.close()
    cached.close()
    state.close()
    assertEquals(0, budgets.allocated)
  }

  @Test
  fun ceilingsRejectOverflowAndInsufficientMetadataHeadroom() {
    val root = TorrentBufferBudget(Int.MAX_VALUE)
    val lease = assertNotNull(root.reserve(Int.MAX_VALUE))
    assertNull(root.reserve(1))
    lease.close()
    assertEquals(0, root.allocated)
    assertFailsWith<IllegalArgumentException> { root.reserve(0) }
    assertFailsWith<IllegalArgumentException> {
      TorrentConfig(maxBufferedBytes = Int.MAX_VALUE, maxExchangeBytes = Int.MAX_VALUE)
    }
    assertFailsWith<IllegalArgumentException> {
      TorrentConfig(maxExchangeBytes = 32 * 1024 * 1024)
    }
  }
}
