package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    val store = TorrentV2PieceStore(document, root, emptySet(), budget, Semaphore(1))
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
    val store = TorrentV2PieceStore(document, root, emptySet(),
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
    val store = TorrentV2PieceStore(doc, root, emptySet(), TorrentBufferBudget(1024), Semaphore(1))
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
    val store = TorrentV2PieceStore(document, root, emptySet(), budget, Semaphore(1))
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
  fun selectionIsEnforcedBeforeWriting() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-selected-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val store = TorrentV2PieceStore(document, root, setOf("1"),
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
