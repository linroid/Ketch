package com.linroid.kdown.core.file

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Shared [FileAccessor] for path-based file systems.
 *
 * All directory creation, deletion, and size queries use `kotlinx-io`'s
 * [SystemFileSystem]. The only platform-specific piece is the
 * [RandomAccessHandle] created by [handleFactory].
 *
 * @param path file system path to write to
 * @param ioDispatcher dispatcher for blocking I/O operations
 * @param handleFactory creates a platform-specific [RandomAccessHandle]
 */
internal class PathFileAccessor(
  path: String,
  private val ioDispatcher: CoroutineDispatcher,
  private val handleFactory: (String) -> RandomAccessHandle,
) : FileAccessor {

  private val realPath = Path(path)
  private var handle: RandomAccessHandle? = null
  private val mutex = Mutex()

  private fun getOrCreateHandle(): RandomAccessHandle {
    return handle ?: run {
      val parent = realPath.parent
      if (parent != null && !SystemFileSystem.exists(parent)) {
        SystemFileSystem.createDirectories(parent)
      }
      handleFactory(realPath.toString()).also { handle = it }
    }
  }

  override suspend fun writeAt(offset: Long, data: ByteArray) {
    withContext(ioDispatcher) {
      mutex.withLock {
        getOrCreateHandle().writeAt(offset, data)
      }
    }
  }

  override suspend fun flush() {
    withContext(ioDispatcher) {
      mutex.withLock {
        handle?.flush()
      }
    }
  }

  override fun close() {
    handle?.close()
    handle = null
  }

  override suspend fun delete() {
    withContext(ioDispatcher) {
      mutex.withLock {
        handle?.close()
        handle = null
        if (SystemFileSystem.exists(realPath)) {
          SystemFileSystem.delete(realPath)
        }
      }
    }
  }

  override suspend fun size(): Long = withContext(ioDispatcher) {
    SystemFileSystem.metadataOrNull(realPath)?.size ?: 0L
  }

  override suspend fun preallocate(size: Long) {
    withContext(ioDispatcher) {
      mutex.withLock {
        getOrCreateHandle().preallocate(size)
      }
    }
  }
}
