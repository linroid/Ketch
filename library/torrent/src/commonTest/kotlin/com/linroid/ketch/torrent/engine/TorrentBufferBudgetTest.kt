package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.TorrentConfig
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

  @Test
  fun aLoneShareMayUseTheWholeParent() {
    val parent = TorrentBufferBudget(100)
    val share = TorrentFairShares(parent, clock = { 0L }).share()
    val lease = assertNotNull(share.reserve(100))
    assertEquals(100, parent.allocated)
    lease.close()
  }

  @Test
  fun aShareHeldBackWithinItsPartStopsAnotherGrowingPastItsOwn() {
    var now = 0L
    val parent = TorrentBufferBudget(100)
    val shares = TorrentFairShares(parent, clock = { now })
    val busy = shares.share()
    val quiet = shares.share()
    // Alone, the busy torrent fills the parent.
    val held = List(10) { assertNotNull(busy.reserve(10)) }
    // The quiet one finds no room within its part of 50 and is held back.
    assertNull(quiet.reserve(10))
    // A buffer the busy torrent gives back goes to the quiet one, not back to the busy one.
    held[0].close()
    assertNull(busy.reserve(10))
    val first = assertNotNull(quiet.reserve(10))
    held[1].close()
    held[2].close()
    assertNull(busy.reserve(10))
    val second = assertNotNull(quiet.reserve(20))
    assertEquals(70, busy.allocated)
    assertEquals(30, quiet.allocated)
    // Once the quiet one stops asking, the busy one may grow past its part again.
    second.close()
    now += 5_000
    val regrown = assertNotNull(busy.reserve(20))
    regrown.close()
    held.drop(3).forEach { it.close() }
    first.close()
    assertEquals(0, parent.allocated)
  }

  @Test
  fun aShareWhosePieceExceedsItsPart_stillGetsOnePiece() {
    val parent = TorrentBufferBudget(100)
    val shares = TorrentFairShares(parent, clock = { 0L })
    val small = List(2) { shares.share() }
    val large = shares.share()
    // Two torrents of small pieces fill the parent while the third has none.
    val held = small.flatMap { share -> List(5) { assertNotNull(share.reserve(10)) } }
    // Its pieces of 40 are larger than its part of 33: it is held back all the same.
    assertNull(large.reserve(40))
    // The second gives four buffers back; the first, over its part, may not take them.
    held.drop(5).take(4).forEach { it.close() }
    assertNull(small[0].reserve(10))
    val piece = assertNotNull(large.reserve(40))
    piece.close()
    (held.take(5) + held.last()).forEach { it.close() }
    assertEquals(0, parent.allocated)
  }

  @Test
  fun aShareOverItsPartDoesNotHoldOthersBack() {
    val parent = TorrentBufferBudget(100)
    val shares = TorrentFairShares(parent, clock = { 0L })
    val first = shares.share()
    val second = shares.share()
    val a = assertNotNull(first.reserve(60))
    val b = assertNotNull(second.reserve(40))
    // Over its part of 50, the first finds no room: that never stops the second.
    assertNull(first.reserve(10))
    b.close()
    val c = assertNotNull(second.reserve(40))
    a.close()
    c.close()
  }

  @Test
  fun idleSharesLeaveTheSplit() {
    var now = 0L
    val parent = TorrentBufferBudget(100)
    val shares = TorrentFairShares(parent, clock = { now })
    val first = shares.share()
    val second = shares.share()
    val lease = assertNotNull(second.reserve(10))
    lease.close()
    // The second holds nothing and was never held back, so the first's part is the whole parent.
    now += 5_000
    val whole = assertNotNull(first.reserve(100))
    whole.close()
  }
}
