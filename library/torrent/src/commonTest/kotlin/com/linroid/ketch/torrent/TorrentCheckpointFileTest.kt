package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentCheckpointFileTest {
  private val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
    "meta version" to 2L, "piece length" to 16_384L,
    "file tree" to mapOf("file" to mapOf("" to mapOf("length" to 1L,
      "pieces root" to sha256Digest(byteArrayOf(1)))))),
    "piece layers" to emptyMap<String, Any>())))

  private fun root(): Path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-checkpoint-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}").also {
    torrentFileSystem.createDirectory(it)
  }

  private suspend fun snapshot(root: Path): TorrentV2Checkpoint {
    val store = TorrentV2PieceStore(document, root / "payload", emptySet(), "task",
      TorrentBufferBudget(65_536), Semaphore(1))
    store.initialize()
    store.commit(0, byteArrayOf(1))
    val checkpoint = store.checkpoint(TorrentContentCatalog(root / "catalog"))
    store.close()
    return checkpoint
  }

  private fun counter(snapshot: TorrentV2Checkpoint, received: Long) = TorrentV2Checkpoint.create(
    document, snapshot.taskId, snapshot.output, snapshot.selected, snapshot.owned,
    snapshot.verifiedHint, receivedBytes = received,
  )

  @Test
  fun reopenedSnapshotAuthenticatesCatalogAndRecoversWithoutTrustingHints() = runTest {
    val root = root()
    try {
      val catalog = TorrentContentCatalog(root / "catalog")
      val file = TorrentCheckpointFile(root / "state", "task", catalog)
      assertNull(file.load())
      val snapshot = snapshot(root)
      file.save(document, counter(snapshot, 7))
      val loaded = assertNotNull(TorrentCheckpointFile(root / "state", "task", catalog).load())
      assertEquals(7L, loaded.checkpoint.receivedBytes)
      val store = TorrentV2PieceStore(loaded.document, root / "payload", emptySet(), "task",
        TorrentBufferBudget(65_536), Semaphore(1))
      store.restore(loaded.checkpoint)
      store.initialize()
      assertContentEquals(booleanArrayOf(false), store.verifiedPieces())
      assertContentEquals(booleanArrayOf(true), store.recheck())
      store.cleanup()
      torrentFileSystem.delete(root / "catalog" / "${document.info.hash.hex}.torrent")
      assertFailsWith<IllegalArgumentException> { file.load() }
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun failedReplacementPreservesCompletePreviousStateAndCanRetry() = runTest {
    val root = root()
    try {
      val catalog = TorrentContentCatalog(root / "catalog")
      val snapshot = snapshot(root)
      val path = root / "state"
      TorrentCheckpointFile(path, "task", catalog).save(document, counter(snapshot, 3))
      val previous = torrentFileSystem.read(path) { readByteArray() }
      var fail = true
      val provider = object : ForwardingFileSystem(torrentFileSystem) {
        override fun atomicMove(source: Path, target: Path) {
          assertContentEquals(previous, torrentFileSystem.read(target) { readByteArray() })
          val staged = assertNotNull(TorrentV2Checkpoint.decode(
            torrentFileSystem.read(source) { readByteArray() }))
          assertEquals(4L, staged.receivedBytes)
          if (fail) throw IOException("Injected replacement failure")
          super.atomicMove(source, target)
        }
      }
      val file = TorrentCheckpointFile(path, "task", catalog, provider)
      assertFailsWith<IOException> { file.save(document, counter(snapshot, 4)) }
      assertContentEquals(previous, torrentFileSystem.read(path) { readByteArray() })
      assertTrue(torrentFileSystem.list(root).none { it.name.endsWith(".tmp") })
      fail = false
      file.save(document, counter(snapshot, 4))
      assertEquals(4L, assertNotNull(file.load()).checkpoint.receivedBytes)
      assertFailsWith<IllegalArgumentException> { file.save(document, counter(snapshot, 3)) }
      assertEquals(4L, assertNotNull(file.load()).checkpoint.receivedBytes)
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun failedFlushNeverPublishesTheStagedSnapshot() = runTest {
    val root = root()
    try {
      val catalog = TorrentContentCatalog(root / "catalog")
      val snapshot = snapshot(root)
      val path = root / "state"
      TorrentCheckpointFile(path, "task", catalog).save(document, snapshot)
      val previous = torrentFileSystem.read(path) { readByteArray() }
      val provider = object : ForwardingFileSystem(torrentFileSystem) {
        override fun openReadWrite(
          file: Path,
          mustCreate: Boolean,
          mustExist: Boolean,
        ): FileHandle {
          val delegate = super.openReadWrite(file, mustCreate, mustExist)
          return object : FileHandle(readWrite = true) {
            override fun protectedRead(
              fileOffset: Long,
              array: ByteArray,
              arrayOffset: Int,
              byteCount: Int,
            ) = delegate.read(fileOffset, array, arrayOffset, byteCount)
            override fun protectedWrite(
              fileOffset: Long,
              array: ByteArray,
              arrayOffset: Int,
              byteCount: Int,
            ) = delegate.write(fileOffset, array, arrayOffset, byteCount)
            override fun protectedFlush() { throw IOException("Injected flush failure") }
            override fun protectedResize(size: Long) = delegate.resize(size)
            override fun protectedSize() = delegate.size()
            override fun protectedClose() = delegate.close()
          }
        }
      }
      val file = TorrentCheckpointFile(path, "task", catalog, provider)
      assertFailsWith<IOException> { file.save(document, counter(snapshot, 1)) }
      assertContentEquals(previous, torrentFileSystem.read(path) { readByteArray() })
      assertTrue(torrentFileSystem.list(root).none { it.name.endsWith(".tmp") })
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun malformedForeignAndOversizedStateIsNeverSilentlyReplaced() = runTest {
    val root = root()
    try {
      val catalog = TorrentContentCatalog(root / "catalog")
      val snapshot = snapshot(root)
      val path = root / "state"
      val file = TorrentCheckpointFile(path, "task", catalog)
      val foreign = TorrentV2Checkpoint.create(document, "other", snapshot.output,
        snapshot.selected, snapshot.owned, snapshot.verifiedHint).encode()
      for (bytes in listOf("broken".encodeToByteArray(), foreign)) {
        torrentFileSystem.write(path) { write(bytes) }
        assertFailsWith<IllegalArgumentException> { file.load() }
        assertFailsWith<IllegalArgumentException> { file.save(document, snapshot) }
        assertContentEquals(bytes, torrentFileSystem.read(path) { readByteArray() })
      }
      torrentFileSystem.openReadWrite(path, mustExist = true).use {
        it.resize(TorrentV2Checkpoint.MAX_BYTES.toLong() + 1)
      }
      assertFailsWith<IllegalArgumentException> { file.load() }
      assertFailsWith<IllegalArgumentException> { file.save(document, snapshot) }
      assertEquals(TorrentV2Checkpoint.MAX_BYTES.toLong() + 1,
        torrentFileSystem.metadata(path).size)
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }
}
