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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentStorageCancellationTest {
  @Test
  fun canceledWriteDoesNotPublishAvailabilityUntilRechecked() = runTest {
    val entered = CompletableDeferred<Unit>()
    val unblock = CountDownLatch(1)
    val slots = Semaphore(1)
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-commit-canceled-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val output = root / "payload"
    val payload = byteArrayOf(1, 2, 3)
    val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
      "name" to "payload", "piece length" to 16_384L, "length" to payload.size.toLong(),
      "pieces" to sha1Digest(payload)
    ))))
    var blockWrites = false
    val provider = object : ForwardingFileSystem(torrentFileSystem) {
      override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        if (blockWrites && file.name == output.name) {
          entered.complete(Unit)
          check(unblock.await(10, TimeUnit.SECONDS)) { "Test did not release blocked write" }
        }
        return super.openReadWrite(file, mustCreate, mustExist)
      }
    }
    val store = TorrentPieceStore(metadata, output, emptySet(), "test", provider, slots)
    try {
      store.initialize()
      blockWrites = true
      val commit = async { store.commit(0, payload) }
      try {
        entered.await()
        commit.cancel()
        assertFalse(commit.isCompleted)
        assertEquals(0, slots.availablePermits)
        unblock.countDown()
        commit.join()
        assertFalse(store.completed())
        assertContentEquals(booleanArrayOf(false), store.verifiedPieces())
        assertContentEquals(longArrayOf(0), store.progress())
        assertEquals(1, slots.availablePermits)
        // The provider may have written all bytes despite cancellation. A fresh scan proves them.
        assertContentEquals(booleanArrayOf(true), store.recheck())
        assertTrue(store.completed())
        assertContentEquals(longArrayOf(payload.size.toLong()), store.progress())
      } finally {
        unblock.countDown()
        commit.cancelAndJoin()
      }
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

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
