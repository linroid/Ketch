package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TorrentLargeFileTest {
  @Test
  fun sparseFileBeyondTwoGiB_usesLongOffsetsWithoutAllocatingTheFile() = runTest {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-large-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val length = 2L * 1024 * 1024 * 1024 + 3
    val tail = byteArrayOf(1, 2, 3)
    val hashes = ByteArray(2049 * 20)
    sha1Digest(tail).copyInto(hashes, 2048 * 20)
    val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
      "name" to "large", "length" to length, "piece length" to 1024 * 1024L, "pieces" to hashes
    ))))
    val store = TorrentPieceStore(metadata, root / "large", emptySet(), "large-task")
    try {
      store.initialize()
      store.commit(2048, tail)
      assertEquals(length, torrentFileSystem.metadata(root / "large").size)
      assertContentEquals(tail, store.read(2048))
      assertEquals(3L, store.progress().single())
      assertFalse(store.completed())
    } finally {
      store.cleanup()
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }
}
