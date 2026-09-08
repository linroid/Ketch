package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileHandle
import okio.FileSystem
import okio.Path

internal expect val torrentFileSystem: FileSystem
internal expect fun torrentRandomBytes(size: Int): ByteArray

/** Serializes file operations and close; callers own directories and destination validation. */
internal class TorrentFileIo(private val handle: FileHandle) {
  private val mutex = Mutex()
  private var closed = false

  suspend fun read(offset: Long, size: Int): ByteArray = operation {
    require(offset >= 0 && size in 0..16 * 1024 * 1024 && offset <= Long.MAX_VALUE - size)
    val bytes = ByteArray(size)
    var count = 0
    while (count < size) {
      val received = handle.read(offset + count, bytes, count, size - count)
      check(received > 0) { "Truncated torrent file" }
      count += received
    }
    bytes
  }

  suspend fun write(offset: Long, bytes: ByteArray) = operation {
    require(offset >= 0 && offset <= Long.MAX_VALUE - bytes.size)
    handle.write(offset, bytes, 0, bytes.size)
  }

  suspend fun size(): Long = operation { handle.size() }
  suspend fun flush() = operation { handle.flush() }

  suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
    mutex.withLock {
      if (!closed) {
        closed = true
        handle.close()
      }
    }
  }

  private suspend fun <T> operation(block: () -> T): T = mutex.withLock {
    check(!closed) { "Torrent file is closed" }
    withContext(Dispatchers.IO) { block() }
  }

  companion object {
    suspend fun open(path: Path, fileSystem: FileSystem = torrentFileSystem): TorrentFileIo {
      var handle: FileHandle? = null
      try {
        withContext(Dispatchers.IO) { handle = fileSystem.openReadWrite(path) }
        return TorrentFileIo(checkNotNull(handle))
      } catch (e: Throwable) {
        handle?.close()
        throw e
      }
    }
  }
}
