package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentV2RecheckCancellationTest {
  @Test
  fun canceledRecheckRetainsReservationsAndDoesNotPublishTheBlockedPiece() = runTest {
    val payload = byteArrayOf(1, 2, 3)
    val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
      "meta version" to 2L, "piece length" to 16_384L,
      "file tree" to mapOf("payload" to mapOf("" to mapOf("length" to 3L,
        "pieces root" to sha256Digest(payload))))
    ), "piece layers" to emptyMap<String, Any>())))
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-scan-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val entered = CompletableDeferred<Unit>()
    val unblock = CountDownLatch(1)
    val budget = TorrentBufferBudget(3)
    val slots = Semaphore(1)
    var blockReads = false
    val provider = object : ForwardingFileSystem(torrentFileSystem) {
      override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        if (blockReads && file.name == "payload") {
          entered.complete(Unit)
          check(unblock.await(10, TimeUnit.SECONDS)) { "Test did not release blocked recheck" }
        }
        return super.openReadWrite(file, mustCreate, mustExist)
      }
    }
    val store = TorrentV2PieceStore(document, root, emptySet(), budget, slots, provider)
    try {
      store.initialize()
      assertTrue(store.commit(0, payload))
      blockReads = true
      val scan = async { store.recheck() }
      try {
        entered.await()
        scan.cancel()
        assertFalse(scan.isCompleted)
        assertEquals(3, budget.allocated)
        assertEquals(0, slots.availablePermits)
        unblock.countDown()
        scan.join()
        assertFalse(store.completed())
        assertEquals(mapOf("0" to 0L), store.progress())
        assertEquals(0, budget.allocated)
        assertEquals(1, slots.availablePermits)
        assertContentEquals(booleanArrayOf(true), store.recheck())
        assertTrue(store.completed())
      } finally {
        unblock.countDown()
        scan.cancelAndJoin()
      }
    } finally {
      store.cleanup()
    }
  }
}
