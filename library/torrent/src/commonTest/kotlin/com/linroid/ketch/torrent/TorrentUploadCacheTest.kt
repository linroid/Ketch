package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TorrentUploadCacheTest {
  @Test
  fun blockBurstReadsOnePieceAndReleasesItsReservation() = runTest {
    val bytes = ByteArray(32768) { it.toByte() }
    val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
      "name" to "file", "length" to bytes.size.toLong(),
      "piece length" to bytes.size.toLong(), "pieces" to sha1Digest(bytes)
    ))))
    var reads = 0
    val fs = object : ForwardingFileSystem(torrentFileSystem) {
      override fun openReadOnly(file: Path): FileHandle {
        reads++
        return super.openReadOnly(file)
      }
    }
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-upload-cache-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(bytes.size)
    val store = TorrentPieceStore(metadata, root / "file", emptySet(), "test", fs)
    val cache = TorrentUploadCache(store, budget)
    try {
      store.initialize()
      store.commit(0, bytes)
      val before = reads
      for (begin in listOf(0, 16384)) {
        val piece = assertNotNull(cache.read(0))
        assertContentEquals(bytes.copyOfRange(begin, begin + 16384),
          piece.copyOfRange(begin, begin + 16384))
      }
      assertEquals(1, reads - before)
      assertEquals(bytes.size, budget.allocated)
      cache.close()
      assertEquals(0, budget.allocated)
    } finally {
      cache.close()
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }
}
