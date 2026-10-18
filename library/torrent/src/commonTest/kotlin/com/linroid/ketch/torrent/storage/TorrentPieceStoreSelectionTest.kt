package com.linroid.ketch.torrent.storage

import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Files `a` (3 bytes), `skip` (4), `b` (3) and `empty` (0) in pieces of 4 bytes: piece 0 holds
 * `a` and the first byte of `skip`, piece 1 the rest of `skip` and the first byte of `b`, piece 2
 * the rest of `b`.
 */
class TorrentPieceStoreSelectionTest {
  private val bytes = "0123456789".encodeToByteArray()
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "pack", "piece length" to 4L,
    "pieces" to (sha1Digest(bytes.copyOfRange(0, 4)) + sha1Digest(bytes.copyOfRange(4, 8)) +
      sha1Digest(bytes.copyOfRange(8, 10))),
    "files" to listOf(
      mapOf("length" to 3L, "path" to listOf("a")),
      mapOf("length" to 4L, "path" to listOf("skip")),
      mapOf("length" to 3L, "path" to listOf("b")),
      mapOf("length" to 0L, "path" to listOf("empty"))
    )
  ))))

  private fun piece(index: Int) = bytes.copyOfRange(index * 4, minOf(index * 4 + 4, bytes.size))

  private fun root(): Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-selection-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"

  private fun sidecar(root: Path, index: Int): Path =
    root / ".ketch-${metadata.infoHash.hex}-test" / "$index.piece"

  private fun text(path: Path): String = torrentFileSystem.read(path) { readUtf8() }

  /**
   * Rewrites [path] in place with [text], as an editor would, late enough that its modification
   * time tells the edit apart from the last write.
   */
  private suspend fun edit(path: Path, text: String) {
    val before = torrentFileSystem.metadata(path).lastModifiedAtMillis
    while (true) {
      torrentFileSystem.write(path) { writeUtf8(text) }
      if (torrentFileSystem.metadata(path).lastModifiedAtMillis != before) return
      withContext(Dispatchers.Default) { delay(5) }
    }
  }

  /** What a store of every file saved once it completed, which a restarted store restores. */
  private suspend fun completedCheckpoint(root: Path): TorrentCheckpoint {
    val store = completeStore(root)
    store.finish()
    return assertNotNull(TorrentCheckpoint.decode(store.persistCheckpoint()))
  }

  private suspend fun completeStore(
    root: Path,
    selected: Set<Int> = emptySet(),
  ): TorrentPieceStore {
    val store = TorrentPieceStore(metadata, root / "pack", selected, "test")
    store.initialize()
    for (index in 0..2) if (store.needed(index)) store.commit(index, piece(index))
    return store
  }

  @Test
  fun changeSelection_shrink_keepsVerifiedPiecesAndFiles() = runTest {
    val root = root()
    try {
      val store = completeStore(root)
      assertTrue(store.completed())
      val view = store.changeSelection(setOf(0))
      assertContentEquals(booleanArrayOf(true, true, true), view.verified)
      assertContentEquals(booleanArrayOf(true, false, false), view.wanted)
      assertTrue(store.completed())
      assertEquals(3L, store.totalSelectedBytes)
      assertFalse(store.selectsAllFiles())
      assertEquals("3456", text(root / "pack/skip"))
      assertEquals("789", text(root / "pack/b"))
      // Shrinking moves nothing: the deselected files still serve their pieces in place.
      assertContentEquals(piece(1), store.read(1))
      assertContentEquals(piece(2), store.read(2))
      store.finish()
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun changeSelection_expand_copiesSidecarSpanIntoTheNewFile() = runTest {
    val root = root()
    try {
      val store = completeStore(root, setOf(0))
      assertTrue(store.completed())
      assertTrue(torrentFileSystem.exists(sidecar(root, 0)))
      assertFalse(torrentFileSystem.exists(root / "pack/skip"))
      val view = store.changeSelection(setOf(0, 1))
      assertContentEquals(booleanArrayOf(true, false, false), view.verified)
      assertEquals('3'.code.toByte(), torrentFileSystem.read(root / "pack/skip") { readByte() })
      assertContentEquals(longArrayOf(3, 1, 0, 0), store.progress())
      assertFalse(store.completed())
      assertEquals(CommitOutcome.VERIFIED, store.commit(1, piece(1)))
      assertTrue(store.completed())
      store.finish()
      assertEquals("3456", text(root / "pack/skip"))
      assertContentEquals(piece(0), store.read(0))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun changeSelection_expandCorruptSidecar_leavesThePieceUnverified() = runTest {
    val root = root()
    try {
      val store = completeStore(root, setOf(0))
      torrentFileSystem.write(sidecar(root, 0)) { writeUtf8("XXXX") }
      val view = store.changeSelection(setOf(0, 1))
      assertContentEquals(booleanArrayOf(false, false, false), view.verified)
      assertContentEquals(longArrayOf(0, 0, 0, 0), store.progress())
      assertFalse(store.completed())
      assertEquals(CommitOutcome.VERIFIED, store.commit(0, piece(0)))
      assertEquals(CommitOutcome.VERIFIED, store.commit(1, piece(1)))
      store.finish()
      assertEquals("3456", text(root / "pack/skip"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun changeSelection_expandEmptyFile_createsItSoFinishSucceeds() = runTest {
    val root = root()
    try {
      val store = completeStore(root, setOf(0))
      store.finish()
      assertFalse(torrentFileSystem.exists(root / "pack/empty"))
      store.changeSelection(setOf(0, 3))
      assertTrue(store.completed())
      store.finish()
      assertEquals(0L, torrentFileSystem.metadata(root / "pack/empty").size)
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun changeSelection_uninitializedStore_appliesWithoutIo() = runTest {
    val root = root()
    var io = false
    val fs = object : ForwardingFileSystem(torrentFileSystem) {
      override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
        if (io) throw IOException("Unexpected I/O")
        return path
      }
    }
    try {
      val store = TorrentPieceStore(metadata, root / "pack", emptySet(), "test", fs)
      io = true
      val view = store.changeSelection(setOf(2))
      assertContentEquals(booleanArrayOf(false, true, true), view.wanted)
      assertEquals(setOf(2), store.selectedIndices)
      assertEquals(3L, store.totalSelectedBytes)
      assertFalse(store.needed(0))
      io = false
      store.initialize()
      assertFalse(torrentFileSystem.exists(root / "pack/a"))
      assertTrue(torrentFileSystem.exists(root / "pack/b"))
    } finally {
      io = false
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun changeSelection_invalidSelection_isRejected() = runTest {
    val store = TorrentPieceStore(metadata, root() / "pack", emptySet(), "test")
    assertFailsWith<IllegalArgumentException> { store.changeSelection(emptySet()) }
    assertFailsWith<IllegalArgumentException> { store.changeSelection(setOf(4)) }
  }

  @Test
  fun commit_pieceNoLongerWanted_returnsNotWanted() = runTest {
    val root = root()
    try {
      val store = TorrentPieceStore(metadata, root / "pack", emptySet(), "test")
      store.initialize()
      store.changeSelection(setOf(0))
      assertEquals(CommitOutcome.NOT_WANTED, store.commit(2, piece(2)))
      assertEquals(CommitOutcome.NOT_WANTED, store.commit(2, "xx".encodeToByteArray()))
      assertEquals(0L, torrentFileSystem.metadata(root / "pack/b").size)
      assertContentEquals(booleanArrayOf(false, false, false), store.verifiedPieces())
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun commit_deletedDeselectedFile_isNotRecreated() = runTest {
    val root = root()
    try {
      val store = TorrentPieceStore(metadata, root / "pack", emptySet(), "test")
      store.initialize()
      store.changeSelection(setOf(0, 1))
      torrentFileSystem.delete(root / "pack/b")
      assertEquals(CommitOutcome.NOT_WANTED, store.commit(2, piece(2)))
      // Piece 1 is still wanted for skip; its byte of b goes to a sidecar instead.
      assertEquals(CommitOutcome.VERIFIED, store.commit(1, piece(1)))
      assertFalse(torrentFileSystem.exists(root / "pack/b"))
      assertTrue(torrentFileSystem.exists(sidecar(root, 1)))
      assertContentEquals(piece(1), store.read(1))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun read_deselectedFileDeleted_revokesThePiece() = runTest {
    val root = root()
    try {
      val store = completeStore(root)
      store.changeSelection(setOf(0))
      torrentFileSystem.delete(root / "pack/b")
      assertEquals(2, assertFailsWith<PieceRevokedException> { store.read(2) }.index)
      assertEquals(1, assertFailsWith<PieceRevokedException> { store.read(1) }.index)
      assertContentEquals(booleanArrayOf(true, false, false), store.verifiedPieces())
      // The selection is unaffected and complete.
      assertTrue(store.completed())
      assertContentEquals(piece(0), store.read(0))
      assertFalse(torrentFileSystem.exists(root / "pack/b"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  /** A completed task that dropped skip, kept in place: a checked store of a and b. */
  private suspend fun droppedInPlace(root: Path, seedOnly: Boolean): TorrentPieceStore {
    completeStore(root).finish()
    val store = TorrentPieceStore(metadata, root / "pack", setOf(0, 2), "test",
      seedOnly = seedOnly)
    store.initialize()
    assertContentEquals(booleanArrayOf(true, true, true), store.recheck())
    return store
  }

  @Test
  fun read_seedOnlyDroppedFileDeleted_setsTheSharedPiecesAside() = runTest {
    val root = root()
    try {
      val store = droppedInPlace(root, seedOnly = true)
      torrentFileSystem.delete(root / "pack/skip")
      assertTrue(assertFailsWith<PieceRevokedException> { store.read(0) }.setAside)
      assertTrue(assertFailsWith<PieceRevokedException> { store.read(1) }.setAside)
      assertTrue(store.completed())
      val view = store.selectionView()
      assertContentEquals(booleanArrayOf(false, false, true), view.wanted)
      assertContentEquals(booleanArrayOf(false, false, true), view.verified)
      assertContentEquals(piece(2), store.read(2))
      assertFalse(torrentFileSystem.exists(root / "pack/skip"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun revoke_seedOnlyPieceSetAside_saysSoAtEveryRevoke() = runTest {
    val root = root()
    try {
      val store = droppedInPlace(root, seedOnly = true)
      torrentFileSystem.delete(root / "pack/skip")
      assertTrue(assertFailsWith<PieceRevokedException> { store.read(0) }.setAside)
      // Peers that asked for it before are refused the same way, so no swarm wants it again.
      assertTrue(assertFailsWith<PieceRevokedException> { store.read(0) }.setAside)
      assertTrue(assertNotNull(store.revoke(0)).setAside)
      assertTrue(store.completed())
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun revoke_seedOnlyDroppedFileEdited_setsTheSharedPieceAsideAndLeavesIt() = runTest {
    val root = root()
    try {
      val store = droppedInPlace(root, seedOnly = true)
      edit(root / "pack/skip", "WXYZ")
      // Read back for a peer, the piece no longer matches its hash.
      assertEquals("012W", store.read(0).decodeToString())
      assertTrue(assertNotNull(store.revoke(0)).setAside)
      assertTrue(store.completed())
      assertContentEquals(booleanArrayOf(false, true, true), store.selectionView().wanted)
      assertEquals("WXYZ", text(root / "pack/skip"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun revoke_seedOnlyChosenFileEditedInASharedPiece_wantsThePieceAgain() = runTest {
    val root = root()
    try {
      val store = droppedInPlace(root, seedOnly = true)
      // The owner edits a, which piece 0 shares with skip; skip is as the check found it.
      edit(root / "pack/a", "01X")
      assertEquals("01X3", store.read(0).decodeToString())
      assertFalse(assertNotNull(store.revoke(0)).setAside)
      assertFalse(store.completed())
      assertContentEquals(booleanArrayOf(true, true, true), store.selectionView().wanted)
      assertEquals("01X", text(root / "pack/a"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun read_seedOnlySelectedFileCutShort_wantsThePieceAgain() = runTest {
    val root = root()
    try {
      val store = droppedInPlace(root, seedOnly = true)
      torrentFileSystem.delete(root / "pack/skip")
      torrentFileSystem.write(root / "pack/a") { writeUtf8("01") }
      // a lost its part of piece 0 too: the selection changed, which seeding cannot serve around.
      assertFalse(assertFailsWith<PieceRevokedException> { store.read(0) }.setAside)
      assertFalse(store.completed())
      assertContentEquals(booleanArrayOf(true, true, true), store.selectionView().wanted)
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun read_downloadingStoreDroppedFileDeleted_wantsTheSharedPieceAgain() = runTest {
    val root = root()
    try {
      val store = droppedInPlace(root, seedOnly = false)
      torrentFileSystem.delete(root / "pack/skip")
      assertFalse(assertFailsWith<PieceRevokedException> { store.read(0) }.setAside)
      assertFalse(store.completed())
      assertEquals(CommitOutcome.VERIFIED, store.commit(0, piece(0)))
      assertTrue(store.completed())
      assertFalse(torrentFileSystem.exists(root / "pack/skip"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  /** A completed task whose file skip was deleted, then dropped: a store of a and b, checked. */
  private suspend fun droppedAfterDeletion(root: Path): TorrentPieceStore {
    completeStore(root).finish()
    torrentFileSystem.delete(root / "pack/skip")
    val store = TorrentPieceStore(metadata, root / "pack", setOf(0, 2), "test")
    store.initialize()
    assertContentEquals(booleanArrayOf(false, false, true), store.recheck())
    return store
  }

  @Test
  fun setAsideLostPieces_droppedFileDeletedFirst_completesWithoutItsPieces() = runTest {
    val root = root()
    try {
      val store = droppedAfterDeletion(root)
      assertFalse(store.completed())
      assertEquals(2, store.setAsideLostPieces())
      assertTrue(store.completed())
      val view = store.selectionView()
      assertContentEquals(booleanArrayOf(false, false, true), view.wanted)
      assertContentEquals(booleanArrayOf(false, false, true), view.verified)
      assertEquals(CommitOutcome.NOT_WANTED, store.commit(0, piece(0)))
      assertFalse(torrentFileSystem.exists(root / "pack/skip"))
      // Truncating and flushing the selected files works, as when a seeder completes.
      store.finish()
      assertEquals("012", text(root / "pack/a"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun setAsideLostPieces_deletedFileCreatedAgainEmpty_completesWithoutItsPieces() = runTest {
    val root = root()
    try {
      completeStore(root).finish()
      torrentFileSystem.delete(root / "pack/skip")
      // A check of every file creates skip again, empty, before the drop reaches it.
      val store = TorrentPieceStore(metadata, root / "pack", emptySet(), "test")
      store.initialize()
      assertEquals(0L, torrentFileSystem.metadata(root / "pack/skip").size)
      store.changeSelection(setOf(0, 2))
      assertContentEquals(booleanArrayOf(false, false, true), store.recheck())
      assertEquals(2, store.setAsideLostPieces())
      assertTrue(store.completed())
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun setAsideLostPieces_droppedFileEdited_setsItsPiecesAsideAndLeavesIt() = runTest {
    val root = root()
    try {
      val saved = completedCheckpoint(root)
      // The owner edits skip in place once it is dropped: Ketch still owns it.
      edit(root / "pack/skip", "WXYZ")
      val store = TorrentPieceStore(metadata, root / "pack", setOf(0, 2), "test")
      store.restore(saved)
      store.initialize()
      assertContentEquals(booleanArrayOf(false, false, true), store.recheck())
      assertEquals(2, store.setAsideLostPieces())
      assertTrue(store.completed())
      // Adding a file wants them again, and downloading them never writes skip.
      store.changeSelection(setOf(0, 2, 3))
      assertEquals(CommitOutcome.VERIFIED, store.commit(0, piece(0)))
      assertEquals(CommitOutcome.VERIFIED, store.commit(1, piece(1)))
      store.finish()
      assertContentEquals(piece(0), store.read(0))
      assertContentEquals(piece(1), store.read(1))
      val restarted = TorrentPieceStore(metadata, root / "pack", setOf(0, 2, 3), "test")
      restarted.initialize()
      assertContentEquals(booleanArrayOf(true, true, true), restarted.recheck())
      assertEquals("WXYZ", text(root / "pack/skip"))
      assertEquals("012", text(root / "pack/a"))
      assertEquals("789", text(root / "pack/b"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun recheck_droppedFileEditedWithSidecar_readsItsSpanFromTheSidecarAndLeavesIt() = runTest {
    val root = root()
    try {
      // Piece 0 is stored while skip is skipped, then copied into skip once it is chosen.
      val store = completeStore(root, setOf(0))
      store.changeSelection(setOf(0, 1))
      assertEquals(CommitOutcome.VERIFIED, store.commit(1, piece(1)))
      store.changeSelection(setOf(0))
      torrentFileSystem.write(root / "pack/skip") { writeUtf8("WXYZ") }
      assertContentEquals(piece(0), store.read(0))
      val restarted = TorrentPieceStore(metadata, root / "pack", setOf(0), "test")
      restarted.initialize()
      assertContentEquals(booleanArrayOf(true, false, false), restarted.recheck())
      assertTrue(restarted.completed())
      assertEquals("WXYZ", text(root / "pack/skip"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun commit_droppedFileInPlace_keepsItsSpanInTheSidecarUntilChosenAgain() = runTest {
    val root = root()
    try {
      val store = TorrentPieceStore(metadata, root / "pack", emptySet(), "test")
      store.initialize()
      // Dropped before any piece verified: skip stays in place, empty, and is never written.
      store.changeSelection(setOf(0, 2))
      for (index in 0..2) assertEquals(CommitOutcome.VERIFIED, store.commit(index, piece(index)))
      store.finish()
      assertEquals("", text(root / "pack/skip"))
      assertTrue(torrentFileSystem.exists(sidecar(root, 0)))
      assertTrue(torrentFileSystem.exists(sidecar(root, 1)))
      assertContentEquals(piece(0), store.read(0))
      assertContentEquals(piece(1), store.read(1))
      // Chosen again, skip takes its spans from the sidecars.
      val view = store.changeSelection(setOf(0, 1, 2))
      assertContentEquals(booleanArrayOf(true, true, true), view.verified)
      assertTrue(store.completed())
      store.finish()
      assertEquals("3456", text(root / "pack/skip"))
      assertFalse(torrentFileSystem.exists(sidecar(root, 0)))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun setAsideLostPieces_selectionChanges_keepsThemAsideUntilAFileIsAdded() = runTest {
    val root = root()
    try {
      val store = droppedAfterDeletion(root)
      store.setAsideLostPieces()
      // Dropping a: piece 0 is no longer wanted, piece 1 stays aside for b.
      store.changeSelection(setOf(2))
      assertTrue(store.completed())
      assertContentEquals(booleanArrayOf(false, false, true), store.selectionView().wanted)
      // Adding a back wants both again.
      store.changeSelection(setOf(0, 2))
      assertFalse(store.completed())
      assertContentEquals(booleanArrayOf(true, true, true), store.selectionView().wanted)
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun setAsideLostPieces_recheck_wantsThemAgain() = runTest {
    val root = root()
    try {
      val store = droppedAfterDeletion(root)
      store.setAsideLostPieces()
      assertContentEquals(booleanArrayOf(false, false, true), store.recheck())
      assertFalse(store.completed())
      assertContentEquals(booleanArrayOf(true, true, true), store.selectionView().wanted)
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun setAsideLostPieces_chosenFileEditedInASharedPiece_setsNothingAside() = runTest {
    val root = root()
    try {
      val saved = completedCheckpoint(root)
      // Piece 0 holds a and the first byte of skip: a changed, skip did not.
      edit(root / "pack/a", "01X")
      val store = TorrentPieceStore(metadata, root / "pack", setOf(0, 2), "test")
      store.restore(saved)
      store.initialize()
      assertContentEquals(booleanArrayOf(false, true, true), store.recheck())
      assertEquals(0, store.setAsideLostPieces())
      assertFalse(store.completed())
      assertEquals("01X", text(root / "pack/a"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun setAsideLostPieces_unknownFilesEdited_setsNothingAside() = runTest {
    val root = root()
    try {
      completeStore(root).finish()
      // Without a checkpoint nothing tells whether skip or a changed.
      edit(root / "pack/skip", "WXYZ")
      val store = TorrentPieceStore(metadata, root / "pack", setOf(0, 2), "test")
      store.initialize()
      assertContentEquals(booleanArrayOf(false, false, true), store.recheck())
      assertEquals(0, store.setAsideLostPieces())
      assertFalse(store.completed())
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun setAsideLostPieces_selectedFileChanged_setsNothingAside() = runTest {
    val root = root()
    try {
      completeStore(root).finish()
      torrentFileSystem.delete(root / "pack/skip")
      torrentFileSystem.write(root / "pack/b") { writeUtf8("78X") }
      val store = TorrentPieceStore(metadata, root / "pack", setOf(0, 2), "test")
      store.initialize()
      assertContentEquals(booleanArrayOf(false, false, false), store.recheck())
      // Piece 2 holds b alone: b changed, which no seeder can serve around.
      assertEquals(0, store.setAsideLostPieces())
      assertFalse(store.completed())
      assertContentEquals(booleanArrayOf(true, true, true), store.selectionView().wanted)
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun setAsideLostPieces_selectedFileCutShort_setsNothingAside() = runTest {
    val root = root()
    try {
      completeStore(root).finish()
      torrentFileSystem.delete(root / "pack/skip")
      torrentFileSystem.write(root / "pack/a") { writeUtf8("01") }
      val store = TorrentPieceStore(metadata, root / "pack", setOf(0, 2), "test")
      store.initialize()
      assertContentEquals(booleanArrayOf(false, false, true), store.recheck())
      // Piece 0 lost skip's byte, but a is short too: the file changed.
      assertEquals(0, store.setAsideLostPieces())
      assertFalse(store.completed())
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun recheck_changeWhilePaused_healsFromSidecar() = runTest {
    val root = root()
    try {
      completeStore(root, setOf(0)).persistCheckpoint()
      // The selection changed while no store ran: the next one finds the new file empty.
      val restarted = TorrentPieceStore(metadata, root / "pack", setOf(0, 1), "test")
      restarted.initialize()
      assertContentEquals(booleanArrayOf(true, false, false), restarted.recheck())
      assertEquals('3'.code.toByte(), torrentFileSystem.read(root / "pack/skip") { readByte() })
      assertContentEquals(longArrayOf(3, 1, 0, 0), restarted.progress())
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun verifyExisting_reselectedOwnedFile_verifiesWithoutDownload() = runTest {
    val root = root()
    try {
      completeStore(root).persistCheckpoint()
      val restarted = TorrentPieceStore(metadata, root / "pack", setOf(0), "test")
      restarted.initialize()
      assertContentEquals(booleanArrayOf(true, false, false), restarted.recheck())
      val view = restarted.changeSelection(setOf(0, 2))
      assertContentEquals(intArrayOf(1, 2), view.recheck)
      assertFalse(restarted.completed())
      val handed = restarted.selectionView()
      assertContentEquals(intArrayOf(1, 2), handed.recheck)
      assertContentEquals(IntArray(0), restarted.selectionView().recheck)
      assertEquals(2, restarted.verifyExisting(handed.recheck))
      assertTrue(restarted.completed())
      assertContentEquals(longArrayOf(3, 0, 3, 0), restarted.progress())
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun finish_obsoleteSidecars_areDeleted() = runTest {
    val root = root()
    try {
      val store = completeStore(root, setOf(0))
      assertTrue(torrentFileSystem.exists(sidecar(root, 0)))
      store.changeSelection(setOf(0, 1, 2, 3))
      assertEquals(CommitOutcome.VERIFIED, store.commit(1, piece(1)))
      assertEquals(CommitOutcome.VERIFIED, store.commit(2, piece(2)))
      store.finish()
      assertFalse(torrentFileSystem.exists(sidecar(root, 0)))
      assertFalse(torrentFileSystem.exists(sidecar(root, 1)))
      assertContentEquals(booleanArrayOf(true, true, true), store.recheck())
      assertEquals("012", text(root / "pack/a"))
      assertEquals("3456", text(root / "pack/skip"))
      assertEquals("789", text(root / "pack/b"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun restore_checkpointOfOtherSelection_adoptsOwnership() = runTest {
    val root = root()
    try {
      val checkpoint = assertNotNull(TorrentCheckpoint.decode(completeStore(root)
        .persistCheckpoint()))
      assertEquals(setOf(0, 1, 2, 3), checkpoint.selected)
      val restarted = TorrentPieceStore(metadata, root / "pack", setOf(0), "test")
      restarted.restore(checkpoint)
      restarted.initialize()
      val (files, _) = restarted.ownedPaths()
      assertTrue(files.any { it.endsWith("b") })
      restarted.cleanup()
      assertFalse(torrentFileSystem.exists(root / "pack"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun cleanup_afterShrink_deletesOwnedDeselectedFiles() = runTest {
    val root = root()
    try {
      val store = completeStore(root)
      store.changeSelection(setOf(0))
      store.cleanup()
      assertFalse(torrentFileSystem.exists(root / "pack/b"))
      assertFalse(torrentFileSystem.exists(root / "pack/skip"))
      assertFalse(torrentFileSystem.exists(root / "pack"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun progress_deselectedFile_countsNothing() = runTest {
    val root = root()
    try {
      val store = completeStore(root)
      assertContentEquals(longArrayOf(3, 4, 3, 0), store.progress())
      store.changeSelection(setOf(0))
      assertContentEquals(longArrayOf(3, 0, 0, 0), store.progress())
      store.changeSelection(setOf(0, 2))
      assertContentEquals(longArrayOf(3, 0, 3, 0), store.progress())
      assertTrue(store.completed())
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }
}
