package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentV2UploadCacheTest {
  private var now = 0L
  private val budget = TorrentBufferBudget(1_000)

  /** A read of [size] bytes holding its own reservation, as the store hands one out. */
  private fun read(size: Int, fill: Int = 0): TorrentV2PieceStore.ReadBuffer =
    TorrentV2PieceStore.ReadBuffer(ByteArray(size) { fill.toByte() },
      assertNotNull(budget.reserve(size)))

  @Test
  fun lruRespectsByteBudget() {
    val cache = TorrentV2UploadCache(300, { now })
    cache.put(0, read(100, 1))
    cache.put(1, read(100, 2))
    cache.put(2, read(100, 3))
    assertEquals(300, budget.allocated)
    // Using piece 0 makes piece 1 the least recently used, which a fourth piece evicts.
    assertNotNull(cache[0])
    cache.put(3, read(100, 4))
    assertNull(cache[1])
    assertEquals(setOf(0, 2, 3), (0..3).filter { it in cache }.toSet())
    assertEquals(300, budget.allocated)
    // A piece already cached keeps the first copy and returns the new one's reservation.
    cache.put(0, read(100, 9))
    assertContentEquals(ByteArray(100) { 1 }, cache[0])
    assertEquals(300, budget.allocated)
    // One piece is always allowed, even beyond the capacity.
    cache.put(4, read(400, 5))
    assertEquals(1, cache.size)
    assertTrue(4 in cache)
    assertEquals(400, budget.allocated)
    cache.close()
    assertEquals(0, budget.allocated)
    // A closed cache never keeps another piece.
    cache.put(5, read(10))
    assertFalse(5 in cache)
    assertEquals(0, budget.allocated)
    // Half of the upload partition, between one and four pieces.
    assertEquals(4_096L, TorrentV2UploadCache.capacity(4_096, TorrentBufferBudget(1_000)))
    assertEquals(16_384L, TorrentV2UploadCache.capacity(4_096, TorrentBufferBudget(1 shl 20)))
    assertEquals(25_000L, TorrentV2UploadCache.capacity(16_384, TorrentBufferBudget(50_000)))
  }

  @Test
  fun shrinkMakesRoomForAPieceBeingRead() {
    val cache = TorrentV2UploadCache(300, { now })
    cache.put(0, read(100))
    cache.put(1, read(100))
    assertNotNull(cache[0])
    // Room for 150 bytes more: the least recently used piece goes first.
    cache.shrink(150)
    assertEquals(setOf(0), (0..1).filter { it in cache }.toSet())
    cache.shrink(0)
    assertEquals(0, cache.size)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun shrinkKeepsPiecesPeersAreTakingBlocksFrom() {
    val cache = TorrentV2UploadCache(300, { now })
    cache.put(0, read(100))
    cache.put(1, read(100))
    cache.put(2, read(100))
    // Piece 0 is the least recently used, but a peer still takes blocks from it.
    assertEquals(200L, cache.evictableBytes(setOf(0)))
    cache.shrink(200, keep = setOf(0))
    assertEquals(setOf(0, 2), (0..2).filter { it in cache }.toSet())
    // Kept pieces stay even when that leaves more than asked for.
    cache.shrink(0, keep = setOf(0))
    assertEquals(setOf(0), (0..2).filter { it in cache }.toSet())
    assertEquals(0L, cache.evictableBytes(setOf(0)))
    cache.close()
    assertEquals(0, budget.allocated)
  }

  @Test
  fun uploadPartitionIsHalfTheTransferBudget() {
    val budgets = TorrentExchangeBudgets(TorrentConfig(maxBufferedBytes = 1 shl 20))
    assertEquals(1 shl 19, budgets.uploads.capacity)
    // Upload reads count against the transfer budget, and never take more than their half.
    val upload = assertNotNull(budgets.uploads.reserve(1 shl 19))
    assertEquals(1 shl 19, budgets.transfer.allocated)
    assertNull(budgets.uploads.reserve(1))
    val frames = assertNotNull(budgets.transfer.reserve(1 shl 19))
    frames.close()
    upload.close()
    assertEquals(0, budgets.allocated)
  }

  @Test
  fun idleEntriesExpire() {
    val cache = TorrentV2UploadCache(1_000, { now }, idleMs = 5_000)
    assertNull(cache.nextExpiryMs())
    cache.put(0, read(100))
    now = 2_000
    cache.put(1, read(100))
    now = 4_000
    assertNotNull(cache[0])
    assertEquals(3_000L, cache.nextExpiryMs())
    now = 7_000
    cache.expire()
    // Piece 1 sat idle for five seconds; piece 0 was used two seconds later.
    assertFalse(1 in cache)
    assertTrue(0 in cache)
    assertEquals(100, budget.allocated)
    now = 9_000
    cache.expire()
    assertEquals(0, cache.size)
    assertNull(cache.nextExpiryMs())
    assertEquals(0, budget.allocated)
  }

  @Test
  fun trimFreesBudgetForDownloads() {
    val cache = TorrentV2UploadCache(1_000, { now })
    cache.put(0, read(400))
    cache.put(1, read(400))
    // Downloads cannot reserve a piece while uploads hold the budget.
    assertNull(budget.reserve(400))
    cache.trim()
    assertEquals(0, cache.size)
    val download = assertNotNull(budget.reserve(400))
    download.close()
    // The cache keeps working after a trim.
    cache.put(2, read(100))
    assertNotNull(cache[2])
    cache.close()
    assertEquals(0, budget.allocated)
  }
}
