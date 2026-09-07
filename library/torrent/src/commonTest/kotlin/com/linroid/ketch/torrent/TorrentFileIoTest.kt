package com.linroid.ketch.torrent

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TorrentFileIoTest {
  @Test
  fun file_positionalWritesFlushReopenAndRejectReadsPastEnd() = runTest {
    val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-io-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    torrentFileSystem.createDirectories(directory)
    val path = directory / "payload"
    try {
      val file = TorrentFileIo.open(path)
      try {
        file.write(3, byteArrayOf(4, 5))
        file.write(0, byteArrayOf(1, 2, 3))
        file.flush()
        assertEquals(5L, file.size())
      } finally {
        withContext(NonCancellable) { file.close() }
      }
      val reopened = TorrentFileIo.open(path)
      try {
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), reopened.read(0, 5))
        assertFailsWith<IllegalStateException> { reopened.read(4, 2) }
        assertFailsWith<IllegalArgumentException> { reopened.read(Long.MAX_VALUE, 2) }
      } finally {
        withContext(NonCancellable) { reopened.close() }
      }
    } finally {
      torrentFileSystem.deleteRecursively(directory)
    }
  }
}
