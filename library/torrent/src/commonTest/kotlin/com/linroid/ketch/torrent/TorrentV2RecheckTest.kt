package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentV2RecheckTest {
  private val payload = ByteArray(80_001) { (it * 13).toByte() }
  private val rootHash = TorrentMerkleRoot(payload.size.toLong()).update(payload).digest()
  private val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
    "meta version" to 2L, "piece length" to 131_072L,
    "file tree" to mapOf("file" to mapOf("" to mapOf("length" to payload.size.toLong(),
      "pieces root" to rootHash)))
  ), "piece layers" to emptyMap<String, Any>())))

  @Test
  fun returnedPayloadRetainsBudgetUntilConsumerClosesIt() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-read-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(payload.size)
    val store = TorrentV2PieceStore(document, root, emptySet(), budget, Semaphore(1))
    try {
      store.initialize()
      assertFailsWith<IllegalStateException> { store.read(0) }
      assertEquals(0, budget.allocated)
      assertTrue(store.commit(0, payload))
      val read = store.read(0)
      try {
        assertContentEquals(payload, read.bytes)
        assertEquals(payload.size, budget.allocated)
        assertFailsWith<IllegalStateException> { store.read(0) }
        assertTrue(store.completed())
      } finally {
        read.close()
      }
      assertEquals(0, budget.allocated)
    } finally {
      store.cleanup()
    }
  }

  @Test
  fun replacedFileRevokesAvailabilityWithoutReadingOrDeletingTheReplacement() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-replaced-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(payload.size)
    val store = TorrentV2PieceStore(document, root, emptySet(), budget, Semaphore(1))
    try {
      store.initialize()
      assertTrue(store.commit(0, payload))
      torrentFileSystem.atomicMove(root / "file", root / "moved")
      torrentFileSystem.write(root / "file") { writeUtf8("replacement") }
      assertFailsWith<IllegalArgumentException> { store.read(0) }
      assertFalse(store.completed())
      assertEquals(mapOf("0" to 0L), store.progress())
      assertEquals(0, budget.allocated)
      store.cleanup()
      assertEquals("replacement", torrentFileSystem.read(root / "file") { readUtf8() })
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun changedBytesAreNotReturnedAndRecheckRestoresOnlyAuthenticatedContent() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-recheck-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(payload.size)
    val store = TorrentV2PieceStore(document, root, emptySet(), budget, Semaphore(1))
    try {
      store.initialize()
      assertTrue(store.commit(0, payload))
      torrentFileSystem.openReadWrite(root / "file", mustExist = true).use { handle ->
        handle.write(0, byteArrayOf(99), 0, 1)
        handle.flush()
      }
      assertFailsWith<IOException> { store.read(0) }
      assertFalse(store.completed())
      assertEquals(mapOf("0" to 0L), store.progress())
      assertEquals(0, budget.allocated)
      assertContentEquals(booleanArrayOf(false), store.recheck())
      torrentFileSystem.openReadWrite(root / "file", mustExist = true).use { handle ->
        handle.write(0, payload, 0, payload.size)
        handle.flush()
      }
      // Leave only 64 KiB available; recheck must not allocate a whole piece.
      val occupied = checkNotNull(budget.reserve(payload.size - 65_536))
      try {
        assertContentEquals(booleanArrayOf(true), store.recheck())
        assertTrue(store.completed())
        assertEquals(mapOf("0" to payload.size.toLong()), store.progress())
      } finally {
        occupied.close()
      }
      torrentFileSystem.openReadWrite(root / "file", mustExist = true).use { it.resize(1) }
      assertContentEquals(booleanArrayOf(false), store.recheck())
      assertFalse(store.completed())
      assertEquals(0, budget.allocated)
    } finally {
      store.cleanup()
    }
  }
}
