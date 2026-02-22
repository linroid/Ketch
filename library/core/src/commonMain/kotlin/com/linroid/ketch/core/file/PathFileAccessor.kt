package com.linroid.ketch.core.file

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.CoroutineDispatcher
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
 * @param dispatcher dispatcher for blocking I/O operations
 * @param handleFactory creates a platform-specific [RandomAccessHandle]
 */
internal class PathFileAccessor(
  path: String,
  dispatcher: CoroutineDispatcher,
  private val handleFactory: (String) -> RandomAccessHandle,
) : FileAccessor {
  private val log = KetchLogger("FileAccessor")
  private val realPath = Path(path)
  private var handle: RandomAccessHandle? = null
  private val dispatcher = dispatcher.limitedParallelism(1)

  private fun getOrCreateHandle(): RandomAccessHandle {
    return handle ?: run {
      val parent = realPath.parent
      if (parent != null && !SystemFileSystem.exists(parent)) {
        log.d { "Creating directories: $parent" }
        SystemFileSystem.createDirectories(parent)
      }
      log.d { "Opening file: $realPath" }
      handleFactory(realPath.toString()).also { handle = it }
    }
  }

  override suspend fun writeAt(offset: Long, data: ByteArray) {
    withContext(dispatcher) {
      getOrCreateHandle().writeAt(offset, data)
    }
  }

  override suspend fun flush() {
    withContext(dispatcher) {
      handle?.flush()
    }
  }

  override fun close() {
    log.d { "Closing file: $realPath" }
    handle?.close()
    handle = null
  }

  override suspend fun delete() {
    withContext(dispatcher) {
      handle?.close()
      handle = null
      if (SystemFileSystem.exists(realPath)) {
        log.d { "Deleting file: $realPath" }
        SystemFileSystem.delete(realPath)
      }
    }
  }

  override suspend fun size(): Long = withContext(dispatcher) {
    SystemFileSystem.metadataOrNull(realPath)?.size ?: 0L
  }

  override suspend fun preallocate(size: Long) {
    log.d { "Preallocating $size bytes: $realPath" }
    withContext(dispatcher) {
      getOrCreateHandle().preallocate(size)
    }
  }
}
