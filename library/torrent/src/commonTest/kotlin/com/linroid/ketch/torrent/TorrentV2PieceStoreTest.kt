package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TorrentV2PieceStoreTest {
  private val bytes = byteArrayOf(1, 2, 3)
  private val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
    "meta version" to 2L, "piece length" to 16_384L,
    "file tree" to mapOf("a" to mapOf("" to mapOf("length" to 3L,
      "pieces root" to sha256Digest(bytes))), "empty" to mapOf("" to mapOf("length" to 0L)))
  ), "piece layers" to emptyMap<String, Any>())))

  @Test
  fun commitsOnlyAuthenticatedBytesAndCleansUpOwnedFiles() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-store-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(1024)
    val store = TorrentV2PieceStore(document, root, emptySet(), "test", budget, Semaphore(1))
    try {
      store.initialize()
      assertFalse(store.commit(0, byteArrayOf(3, 2, 1)))
      assertEquals(0L, torrentFileSystem.metadata(root / "a").size)
      assertFalse(store.completed())
      assertTrue(store.commit(0, bytes))
      assertTrue(store.commit(0, bytes))
      assertEquals(mapOf("0" to 3L, "1" to 0L), store.progress())
      assertTrue(store.completed())
      assertContentEquals(bytes, torrentFileSystem.read(root / "a") { readByteArray() })
      assertEquals(0L, torrentFileSystem.metadata(root / "empty").size)
      assertEquals(0, budget.allocated)
    } finally {
      store.cleanup()
    }
    assertFalse(torrentFileSystem.exists(root))
  }

  @Test
  fun existingDestinationIsNotAdoptedOrDeleted() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-existing-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    torrentFileSystem.createDirectory(root)
    torrentFileSystem.write(root / "keep") { writeUtf8("user data") }
    val store = TorrentV2PieceStore(document, root, emptySet(), "test",
      TorrentBufferBudget(1024), Semaphore(1))
    try {
      assertFailsWith<okio.IOException> { store.initialize() }
      store.cleanup()
      assertEquals("user data", torrentFileSystem.read(root / "keep") { readUtf8() })
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun hybridCommitsFilePayloadAtItsOwnOffsetWithoutPaddingFiles() = runTest {
    val last = byteArrayOf(4, 5)
    fun leaf(value: ByteArray) = mapOf("" to mapOf("length" to value.size.toLong(),
      "pieces root" to sha256Digest(value)))
    val info = mapOf("meta version" to 2L, "piece length" to 16_384L, "name" to "pack",
      "file tree" to mapOf("a" to leaf(bytes), "z" to leaf(last)),
      "files" to listOf(mapOf("path" to listOf("a"), "length" to 3L),
        mapOf("attr" to "p", "length" to 16_381L),
        mapOf("path" to listOf("z"), "length" to 2L)),
      "pieces" to (sha1Digest(bytes + ByteArray(16_381)) + sha1Digest(last)))
    val doc = TorrentV2Document.parse(Bencode.encode(mapOf("info" to info,
      "piece layers" to emptyMap<String, Any>())))
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-hybrid-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val store = TorrentV2PieceStore(doc, root, emptySet(), "test",
      TorrentBufferBudget(1024), Semaphore(1))
    try {
      store.initialize()
      assertTrue(store.commit(1, last))
      assertFalse(store.completed())
      assertTrue(store.commit(0, bytes))
      assertEquals(mapOf("0" to 3L, "2" to 2L), store.progress())
      assertTrue(store.completed())
      assertEquals(setOf("a", "z"), torrentFileSystem.list(root).map { it.name }.toSet())
      assertContentEquals(last, torrentFileSystem.read(root / "z") { readByteArray() })
      assertEquals(3L, torrentFileSystem.metadata(root / "a").size)
    } finally {
      store.cleanup()
    }
  }

  @Test
  fun exhaustedBufferBudgetLeavesFilesAndProgressUntouched() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-budget-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(3)
    val store = TorrentV2PieceStore(document, root, emptySet(), "test", budget, Semaphore(1))
    try {
      store.initialize()
      val occupied = checkNotNull(budget.reserve(1))
      try {
        assertFailsWith<IllegalStateException> { store.commit(0, bytes) }
        assertEquals(0L, torrentFileSystem.metadata(root / "a").size)
        assertFalse(store.completed())
        assertEquals(1, budget.allocated)
      } finally {
        occupied.close()
      }
      assertTrue(store.commit(0, bytes))
      assertEquals(0, budget.allocated)
    } finally {
      store.cleanup()
    }
  }

  @Test
  fun tryReadDistinguishesNoBudgetFromRevoked() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-read-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(3)
    val store = TorrentV2PieceStore(document, root, emptySet(), "test", budget, Semaphore(1))
    try {
      store.initialize()
      assertSame(TorrentV2PieceStore.ReadOutcome.NotCommitted, store.tryRead(0))
      assertTrue(store.commit(0, bytes))
      val read = assertIs<TorrentV2PieceStore.ReadOutcome.Read>(store.tryRead(0))
      assertContentEquals(bytes, read.buffer.bytes)
      // The read holds its bytes' budget until it is closed; meanwhile nothing more fits.
      assertSame(TorrentV2PieceStore.ReadOutcome.NoBudget, store.tryRead(0))
      read.buffer.close()
      // Running out of budget is not the piece's fault: it stays committed.
      assertTrue(store.completed())
      assertEquals(0, budget.allocated)
      // A committed piece that changed on disk is revoked and must be fetched again.
      torrentFileSystem.write(root / "a") { write(byteArrayOf(9, 9, 9)) }
      assertIs<TorrentV2PieceStore.ReadOutcome.Revoked>(store.tryRead(0))
      assertFalse(store.completed())
      assertEquals(mapOf("0" to 0L, "1" to 0L), store.progress())
      assertSame(TorrentV2PieceStore.ReadOutcome.NotCommitted, store.tryRead(0))
      assertEquals(0, budget.allocated)
    } finally {
      store.cleanup()
    }
  }

  @Test
  fun countersSurviveCheckpointRoundTrip() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-counters-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(1024)
    val store = TorrentV2PieceStore(document, root, emptySet(), "test", budget, Semaphore(1))
    var restored: TorrentV2PieceStore? = null
    try {
      store.initialize()
      assertTrue(store.commit(0, bytes))
      store.recordReceived(5)
      store.recordUploaded(3)
      store.recordUploaded(4)
      assertEquals(5L, store.receivedBytes())
      assertEquals(7L, store.uploadedBytes())
      val checkpoint = TorrentV2Checkpoint.decode(store.checkpoint().encode())
      assertEquals(7L, checkpoint?.uploadedBytes)
      assertEquals(5L, checkpoint?.receivedBytes)
      store.close()
      // A new owner of the same files starts from the saved totals and keeps counting.
      val next = TorrentV2PieceStore(document, root, emptySet(), "test", budget, Semaphore(1))
      restored = next
      next.restore(checkNotNull(checkpoint))
      assertEquals(5L, next.receivedBytes())
      assertEquals(7L, next.uploadedBytes())
      next.recordUploaded(10)
      next.initialize()
      assertEquals(17L, assertNotNull(TorrentV2Checkpoint.decode(next.checkpoint().encode()))
        .uploadedBytes)
      // Explicit totals only ever raise the counters.
      assertEquals(20L, next.checkpoint(uploadedBytes = 20).uploadedBytes)
      assertEquals(20L, next.uploadedBytes())
      assertFailsWith<IllegalArgumentException> { next.checkpoint(uploadedBytes = 19) }
    } finally {
      (restored ?: store).cleanup()
    }
    assertEquals(0, budget.allocated)
  }

  @Test
  fun selectionIsEnforcedBeforeWriting() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-selected-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val store = TorrentV2PieceStore(document, root, setOf("1"), "test",
      TorrentBufferBudget(2), Semaphore(1))
    try {
      store.initialize()
      assertTrue(store.completed())
      assertFailsWith<IllegalArgumentException> { store.commit(0, bytes) }
      assertFalse(torrentFileSystem.exists(root / "a"))
      store.close()
      assertFailsWith<IllegalStateException> { store.commit(0, bytes) }
    } finally {
      store.cleanup()
    }
  }
}
