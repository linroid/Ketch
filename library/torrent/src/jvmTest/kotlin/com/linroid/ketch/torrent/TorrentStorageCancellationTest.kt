package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentStorageCancellationTest {
  @Test
  fun canceledBlockedProviderRetainsSlotUntilOperationReturns() = runTest {
    val entered = CompletableDeferred<Unit>()
    val unblock = CountDownLatch(1)
    val slots = Semaphore(1)
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-storage-blocked-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
      "name" to "empty", "piece length" to 16_384L, "length" to 0L, "pieces" to byteArrayOf()
    ))))
    val provider = object : ForwardingFileSystem(torrentFileSystem) {
      override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        entered.complete(Unit)
        check(unblock.await(10, TimeUnit.SECONDS)) { "Test did not release blocked provider" }
        return super.openReadWrite(file, mustCreate, mustExist)
      }
    }
    val first = TorrentPieceStore(metadata, root / "first", emptySet(), "first", provider, slots)
    val second = TorrentPieceStore(metadata, root / "second", emptySet(), "second",
      storageSlots = slots)
    val blocked = async { first.initialize() }
    try {
      entered.await()
      blocked.cancel()
      assertFalse(blocked.isCompleted)
      assertEquals(0, slots.availablePermits)
      val waiting = async(start = CoroutineStart.UNDISPATCHED) { second.initialize() }
      assertFalse(waiting.isCompleted)
      assertFalse(torrentFileSystem.exists(root / "second"))
      unblock.countDown()
      blocked.join()
      waiting.await()
      assertTrue(second.isInitialized())
      assertEquals(1, slots.availablePermits)
    } finally {
      unblock.countDown()
      blocked.cancelAndJoin()
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }
}
