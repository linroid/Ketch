package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import okio.Path
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
      assertEquals(CommitOutcome.CORRUPT, store.commit(0, byteArrayOf(3, 2, 1)))
      assertEquals(0L, torrentFileSystem.metadata(root / "a").size)
      assertFalse(store.completed())
      assertEquals(CommitOutcome.VERIFIED, store.commit(0, bytes))
      assertEquals(CommitOutcome.VERIFIED, store.commit(0, bytes))
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
      assertEquals(CommitOutcome.VERIFIED, store.commit(1, last))
      assertFalse(store.completed())
      assertEquals(CommitOutcome.VERIFIED, store.commit(0, bytes))
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
      assertEquals(CommitOutcome.VERIFIED, store.commit(0, bytes))
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
      assertEquals(CommitOutcome.VERIFIED, store.commit(0, bytes))
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
      assertEquals(CommitOutcome.VERIFIED, store.commit(0, bytes))
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
      // A piece of a file that is not selected is discarded, not held against its sender.
      assertEquals(CommitOutcome.NOT_WANTED, store.commit(0, bytes))
      assertFalse(torrentFileSystem.exists(root / "a"))
      assertFailsWith<IllegalArgumentException> { store.commit(0, byteArrayOf(1)) }
      store.close()
      assertFailsWith<IllegalStateException> { store.commit(0, bytes) }
    } finally {
      store.cleanup()
    }
  }

  // Pieces of 32 KiB: a ("0") spans pieces 0 and 1, b ("1") is piece 2, c ("2") is piece 3.
  private val pack = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5, "c" to 20_000))

  /** Runs [block] with a new directory for a store's payload and creation log. */
  private fun packTest(block: suspend (Path) -> Unit) = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-selection-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    torrentFileSystem.createDirectories(root)
    try { block(root) } finally { torrentFileSystem.deleteRecursively(root, mustExist = false) }
  }

  private fun packStore(
    root: Path,
    selected: Set<String>,
    budget: TorrentBufferBudget = TorrentBufferBudget(1024 * 1024),
    log: Boolean = false,
  ) = TorrentV2PieceStore(pack.document, root / "payload", selected, "selection", budget,
    Semaphore(1), creationLogPath = if (log) root / "creation" else null)

  @Test
  fun changeSelection_expand_createsOwnedFileAndLogsIt() = packTest { root ->
    val budget = TorrentBufferBudget(1024 * 1024)
    try {
      val store = packStore(root, setOf("0"), budget, log = true)
      store.initialize()
      assertFalse(torrentFileSystem.exists(root / "payload" / "b"))
      store.changeSelection(setOf("0", "1"))
      assertEquals(0L, torrentFileSystem.metadata(root / "payload" / "b").size)
      assertEquals(setOf("0", "1"), store.selectedIds())
      assertEquals(40_005L, store.totalSelectedBytes)
      assertEquals(CommitOutcome.VERIFIED, store.commit(2, pack.v2Piece(2)))
      store.close()
      // The creation log holds the new file, so a store without a checkpoint adopts and deletes it.
      val reopened = packStore(root, setOf("0", "1"), budget, log = true)
      reopened.initialize()
      assertContentEquals(booleanArrayOf(false, false, true, false), reopened.recheck())
      reopened.cleanup()
      assertFalse(torrentFileSystem.exists(root / "payload"))
      assertEquals(0, budget.allocated)
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun changeSelection_shrink_keepsPiecesReadable() = packTest { root ->
    val budget = TorrentBufferBudget(1024 * 1024)
    val store = packStore(root, emptySet(), budget)
    try {
      store.initialize()
      pack.seed(store)
      store.changeSelection(setOf("0"))
      assertEquals(mapOf("0" to 40_000L), store.progress())
      assertTrue(store.completed())
      assertFalse(store.selectsAll())
      // The deselected file keeps its verified piece, which peers may still read.
      assertTrue(store.verifiedPieces()[3])
      val read = assertIs<TorrentV2PieceStore.ReadOutcome.Read>(store.tryRead(3))
      try {
        assertContentEquals(pack.v2Piece(3), read.buffer.bytes)
      } finally { read.buffer.close() }
      assertEquals(setOf("0"), store.checkpoint().selected)
    } finally {
      store.cleanup()
    }
    assertEquals(0, budget.allocated)
  }

  @Test
  fun completed_ignoresDeselectedProgress() = packTest { root ->
    val store = packStore(root, emptySet())
    try {
      store.initialize()
      pack.seed(store, listOf(0, 1, 3))
      assertFalse(store.completed())
      // a is complete, and c's progress no longer counts once only a is selected.
      store.changeSelection(setOf("0"))
      assertTrue(store.completed())
      store.changeSelection(setOf("0", "1"))
      assertFalse(store.completed())
    } finally {
      store.cleanup()
    }
  }

  @Test
  fun commit_deselectedFile_returnsNotWanted() = packTest { root ->
    val budget = TorrentBufferBudget(1024 * 1024)
    val store = packStore(root, setOf("0"), budget)
    try {
      store.initialize()
      assertEquals(CommitOutcome.NOT_WANTED, store.commit(3, pack.v2Piece(3)))
      assertFalse(torrentFileSystem.exists(root / "payload" / "c"))
      assertFalse(store.verifiedPieces()[3])
      assertEquals(0, budget.allocated)
    } finally {
      store.cleanup()
    }
  }

  @Test
  fun changeSelection_reselectAfterRestart_rechecksTheRetainedFile() = packTest { root ->
    val budget = TorrentBufferBudget(1024 * 1024)
    val first = packStore(root, emptySet(), budget)
    var second: TorrentV2PieceStore? = null
    try {
      first.initialize()
      pack.seed(first)
      first.changeSelection(setOf("0", "1"))
      val checkpoint = first.checkpoint()
      first.close()
      // After a restart c is owned but not selected, so nothing checks it.
      val next = packStore(root, setOf("0", "1"), budget)
      second = next
      next.restore(checkpoint)
      next.initialize()
      assertContentEquals(booleanArrayOf(true, true, true, false), next.recheck())
      next.changeSelection(setOf("0", "1", "2"))
      // Selected again, its piece is verified from disk rather than downloaded.
      assertTrue(next.verifiedPieces()[3])
      assertTrue(next.completed())
      assertEquals(0, budget.allocated)
    } finally {
      (second ?: first).cleanup()
    }
    assertFalse(torrentFileSystem.exists(root / "payload"))
  }

  @Test
  fun changeSelection_unownedPathInTheWay_failsWithoutPublishing() = packTest { root ->
    val store = packStore(root, setOf("0"))
    try {
      store.initialize()
      torrentFileSystem.write(root / "payload" / "b") { writeUtf8("user data") }
      val failure = assertFailsWith<IOException> { store.changeSelection(setOf("0", "1")) }
      assertFalse(failure.message.orEmpty().contains("payload"))
      assertEquals(setOf("0"), store.selectedIds())
      assertEquals(0L, store.checkpoint().selectionGeneration)
      store.cleanup()
      // Not ours: neither adopted nor deleted.
      assertEquals("user data", torrentFileSystem.read(root / "payload" / "b") { readUtf8() })
    } finally {
      store.cleanup()
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun changeSelection_bumpsSelectionGeneration() = packTest { root ->
    val store = packStore(root, setOf("0"))
    try {
      // Before any I/O the change only replaces the selection.
      store.changeSelection(setOf("0", "2"))
      assertFalse(torrentFileSystem.exists(root / "payload"))
      store.initialize()
      assertTrue(torrentFileSystem.exists(root / "payload" / "c"))
      assertEquals(1L, store.checkpoint().selectionGeneration)
      store.changeSelection(setOf("0", "2"))
      assertEquals(1L, store.checkpoint().selectionGeneration)
      store.changeSelection(setOf("2"))
      val checkpoint = store.checkpoint()
      assertEquals(2L, checkpoint.selectionGeneration)
      assertEquals(setOf("2"), checkpoint.selected)
      assertFailsWith<IllegalArgumentException> { store.changeSelection(emptySet()) }
      assertFailsWith<IllegalArgumentException> { store.changeSelection(setOf("9")) }
    } finally {
      store.cleanup()
    }
  }
}
