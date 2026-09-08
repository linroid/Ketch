package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.FileHandle
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TorrentPieceStoreTest {
  @Test
  fun canceledRecheckCanRecommitPreviouslyVerifiedPieces() = runTest {
    var readsBeforeCancellation = Int.MAX_VALUE
    val fs = object : ForwardingFileSystem(torrentFileSystem) {
      override fun openReadOnly(file: Path): FileHandle {
        if (--readsBeforeCancellation == 0) throw CancellationException("Interrupted scan")
        return super.openReadOnly(file)
      }
    }
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-recheck-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val store = TorrentPieceStore(metadata, root / "pack", emptySet(), "test", fs)
    try {
      store.initialize()
      for (index in 0..2) {
        store.commit(index, bytes.copyOfRange(index * 4, minOf(index * 4 + 4, bytes.size)))
      }
      readsBeforeCancellation = 3
      assertFailsWith<CancellationException> { store.recheck() }
      assertFalse(store.completed())
      readsBeforeCancellation = Int.MAX_VALUE
      for (index in 0..2) {
        store.commit(index, bytes.copyOfRange(index * 4, minOf(index * 4 + 4, bytes.size)))
      }
      assertTrue(store.completed())
      assertContentEquals(longArrayOf(3, 4, 3, 0), store.progress())
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

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

  @Test
  fun selection_boundaryPiecesAndVerifiedProgress() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-piece-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val store = TorrentPieceStore(metadata, root / "pack", setOf(0, 2, 3), "test")
    try {
      store.initialize()
      assertFalse(store.commit(0, "bad!".encodeToByteArray()))
      assertContentEquals(longArrayOf(0, 0, 0, 0), store.progress())
      assertTrue(store.commit(0, bytes.copyOfRange(0, 4)))
      assertContentEquals(longArrayOf(3, 0, 0, 0), store.progress())
      assertFalse(store.completed())
      assertTrue(store.commit(1, bytes.copyOfRange(4, 8)))
      assertTrue(store.commit(2, bytes.copyOfRange(8, 10)))
      assertTrue(store.completed())
      assertContentEquals(longArrayOf(3, 0, 3, 0), store.progress())
      assertFalse(torrentFileSystem.exists(root / "pack/skip"))
      assertTrue(torrentFileSystem.exists(root / "pack/empty"))
      assertEquals("012", torrentFileSystem.read(root / "pack/a") { readUtf8() })
      assertEquals("789", torrentFileSystem.read(root / "pack/b") { readUtf8() })
      assertContentEquals(bytes.copyOfRange(4, 8), store.read(1))
      torrentFileSystem.write(root / "pack/b") { writeUtf8("bad") }
      assertContentEquals(booleanArrayOf(true, false, false), store.recheck())
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun cleanup_preservesPreexistingFilesAndUnrelatedNeighbors() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-owned-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    torrentFileSystem.createDirectories(root / "pack")
    torrentFileSystem.write(root / "pack/a") { writeUtf8("012") }
    torrentFileSystem.write(root / "neighbor") { writeUtf8("keep") }
    val store = TorrentPieceStore(metadata, root / "pack", setOf(0, 2), "test")
    try {
      store.initialize()
      store.commit(0, bytes.copyOfRange(0, 4))
      store.cleanup()
      assertTrue(torrentFileSystem.exists(root / "pack/a"))
      assertTrue(torrentFileSystem.exists(root / "neighbor"))
      assertFalse(torrentFileSystem.exists(root / "pack/b"))
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }
  @Test
  fun symlinkOutput_isRejectedWithoutTouchingTarget() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-link-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    torrentFileSystem.createDirectories(root / "outside")
    torrentFileSystem.createSymlink(root / "pack", root / "outside")
    try {
      val store = TorrentPieceStore(metadata, root / "pack", setOf(0), "test")
      assertFailsWith<IllegalArgumentException> { store.initialize() }
      assertTrue(torrentFileSystem.list(root / "outside").isEmpty())
    } finally {
      torrentFileSystem.delete(root / "pack")
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun finish_truncatesVerifiedOutputAndCreatesSelectedEmptyFiles() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-finish-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    torrentFileSystem.createDirectories(root / "pack")
    torrentFileSystem.write(root / "pack/a") { writeUtf8("012trailing") }
    try {
      val store = TorrentPieceStore(metadata, root / "pack", setOf(0, 3), "test")
      store.initialize()
      assertFailsWith<IllegalStateException> { store.finish() }
      store.commit(0, bytes.copyOfRange(0, 4))
      store.finish()
      assertEquals("012", torrentFileSystem.read(root / "pack/a") { readUtf8() })
      assertEquals(0L, torrentFileSystem.metadata(root / "pack/empty").size)
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun diskFailure_neverAdvancesVerifiedProgress() = runTest {
    var failWrites = false
    val fs = object : ForwardingFileSystem(torrentFileSystem) {
      override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        if (failWrites) throw IOException("disk full")
        return super.openReadWrite(file, mustCreate, mustExist)
      }
    }
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-disk-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val store = TorrentPieceStore(metadata, root / "pack", setOf(0), "test", fs)
    try {
      store.initialize()
      failWrites = true
      assertFailsWith<IOException> { store.commit(0, bytes.copyOfRange(0, 4)) }
      assertContentEquals(longArrayOf(0, 0, 0, 0), store.progress())
      assertFalse(store.completed())
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

}
