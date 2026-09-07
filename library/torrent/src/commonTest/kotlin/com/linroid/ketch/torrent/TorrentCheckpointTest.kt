package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentCheckpointTest {
  private val bytes = "0123456789".encodeToByteArray()
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "pack", "piece length" to 4L,
    "pieces" to (sha1Digest(bytes.copyOfRange(0, 4)) + sha1Digest(bytes.copyOfRange(4, 8)) +
      sha1Digest(bytes.copyOfRange(8, 10))),
    "files" to listOf(
      mapOf("length" to 3L, "path" to listOf("first")),
      mapOf("length" to 4L, "path" to listOf("skip")),
      mapOf("length" to 3L, "path" to listOf("last"))
    )
  ))))

  @Test
  fun restart_rehashesChangedFilesAndRestoresNoncontiguousSelection() = runTest {
    val root = root()
    val store = store(root)
    try {
      store.initialize()
      for (piece in 0..2) store.commit(piece, bytes.copyOfRange(piece * 4, minOf(piece * 4 + 4, 10)))
      val snapshot = assertNotNull(TorrentCheckpoint.decode(store.persistCheckpoint()))
      assertEquals(setOf(0, 2), snapshot.selected)
      torrentFileSystem.write(root / "pack/last") { writeUtf8("bad") }
      val restarted = store(root)
      restarted.restore(snapshot)
      restarted.initialize()
      assertContentEquals(booleanArrayOf(true, false, false), restarted.recheck())
      assertContentEquals(longArrayOf(3, 0, 0), restarted.progress())
      assertFalse(restarted.completed())
      for (piece in 1..2) restarted.commit(piece,
        bytes.copyOfRange(piece * 4, minOf(piece * 4 + 4, 10)))
      restarted.finish()
      assertTrue(restarted.completed())
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun journal_recoversBeforeTaskStoreCheckpointAndDiscardsTornTail() = runTest {
    val root = root()
    try {
      val initial = store(root)
      initial.initialize()
      initial.commit(0, bytes.copyOfRange(0, 4))
      val journal = root / ".ketch-${metadata.infoHash.hex}-test/ownership"
      torrentFileSystem.openReadWrite(journal, mustExist = true).use { handle ->
        val torn = Buffer().writeInt(100).writeUtf8("partial").readByteArray()
        handle.write(handle.size(), torn, 0, torn.size)
        handle.flush()
      }
      val restarted = store(root)
      restarted.initialize()
      assertContentEquals(booleanArrayOf(true, false, false), restarted.recheck())
      restarted.persistCheckpoint()
      val deleting = store(root)
      deleting.recoverOwnership()
      deleting.cleanup()
      assertFalse(torrentFileSystem.exists(root / "pack/first"))
      assertFalse(torrentFileSystem.exists(root / "pack/last"))
      assertFalse(torrentFileSystem.exists(journal))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun offlineCleanup_preservesReplacedAndPreexistingFiles() = runTest {
    val root = root()
    torrentFileSystem.createDirectories(root / "pack")
    torrentFileSystem.write(root / "pack/first") { writeUtf8("preexisting") }
    try {
      val initial = store(root)
      initial.initialize()
      val snapshot = assertNotNull(TorrentCheckpoint.decode(initial.persistCheckpoint()))
      // Keep the original inode alive, making the replacement's different identity explicit.
      torrentFileSystem.atomicMove(root / "pack/last", root / "original-owned-file")
      torrentFileSystem.write(root / "pack/last") { writeUtf8("replacement") }
      val deleting = store(root)
      deleting.restore(snapshot)
      deleting.recoverOwnership()
      deleting.cleanup()
      assertEquals("preexisting", torrentFileSystem.read(root / "pack/first") { readUtf8() })
      assertEquals("replacement", torrentFileSystem.read(root / "pack/last") { readUtf8() })
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun checkpoint_rejectsWrongIdentityAndEscapingOwnershipAndRecognizesLegacy() = runTest {
    val root = root()
    try {
      val initial = store(root)
      initial.initialize()
      val snapshot = initial.checkpoint()
      assertFailsWith<IllegalArgumentException> {
        store(root).restore(snapshot.copy(taskId = "another-task"))
      }
      assertFailsWith<IllegalArgumentException> {
        store(root).restore(snapshot.copy(files = listOf(TorrentOwnedPath("/outside", "fake"))))
      }
      assertNull(TorrentCheckpoint.decode("legacy-native-data".encodeToByteArray()))
      assertFailsWith<IllegalArgumentException> {
        TorrentCheckpoint.decode(snapshot.encode().dropLast(1).toByteArray())
      }
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  private fun root(): Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-checkpoint-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"

  private fun store(root: Path): TorrentPieceStore =
    TorrentPieceStore(metadata, root / "pack", setOf(0, 2), "test")
}
