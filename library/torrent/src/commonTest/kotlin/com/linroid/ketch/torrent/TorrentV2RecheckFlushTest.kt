package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentV2RecheckFlushTest {
  @Test
  fun flushesOncePerFileAndRevokesAllTentativePiecesIfFlushFails() = runTest {
    val payload = ByteArray(49_153) { it.toByte() }
    val pieces = (0..3).map {
      payload.copyOfRange(it * 16_384, minOf((it + 1) * 16_384, payload.size))
    }
    val hashes = pieces.fold(ByteArray(0)) { result, bytes -> result + sha256Digest(bytes) }
    val hash = TorrentMerkleRoot(payload.size.toLong()).update(payload).digest().toByteString()
    val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
      "meta version" to 2L, "piece length" to 16_384L,
      "file tree" to mapOf("payload" to mapOf("" to mapOf("length" to payload.size.toLong(),
        "pieces root" to hash)))
    ), "piece layers" to mapOf(hash to hashes))))
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-v2-flush-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    var flushes = 0
    var failFlush = false
    val provider = object : ForwardingFileSystem(torrentFileSystem) {
      override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        val delegate = super.openReadWrite(file, mustCreate, mustExist)
        return object : FileHandle(readWrite = true) {
          override fun protectedRead(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
          ) =
            delegate.read(fileOffset, array, arrayOffset, byteCount)
          override fun protectedWrite(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
          ) =
            delegate.write(fileOffset, array, arrayOffset, byteCount)
          override fun protectedFlush() {
            flushes++
            if (failFlush) throw IOException("Injected flush failure")
            delegate.flush()
          }
          override fun protectedResize(size: Long) = delegate.resize(size)
          override fun protectedSize() = delegate.size()
          override fun protectedClose() = delegate.close()
        }
      }
    }
    val store = TorrentV2PieceStore(document, root, emptySet(), "test",
      TorrentBufferBudget(65_536), Semaphore(1), provider)
    try {
      store.initialize()
      pieces.forEachIndexed { index, bytes -> assertTrue(store.commit(index, bytes)) }
      flushes = 0
      assertContentEquals(BooleanArray(4) { true }, store.recheck())
      assertEquals(1, flushes)
      assertTrue(store.completed())
      failFlush = true
      assertContentEquals(BooleanArray(4), store.recheck())
      assertFalse(store.completed())
      assertEquals(mapOf("0" to 0L), store.progress())
    } finally {
      store.cleanup()
    }
  }
}
