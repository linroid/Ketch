package com.linroid.ketch.core.file

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileHandle
import okio.Path.Companion.toPath

/**
 * Shared [FileAccessor] for path-based file systems.
 *
 * All directory creation, deletion, and size queries use okio's
 * [platformFileSystem]. Random-access writes use okio's [FileHandle],
 * which provides built-in positional read/write support on all platforms.
 *
 * @param path file system path to write to
 * @param dispatcher dispatcher for blocking I/O operations
 */
internal class PathFileAccessor(
  path: String,
  dispatcher: CoroutineDispatcher,
) : FileAccessor {
  private val log = KetchLogger("FileAccessor")
  private val realPath = path.toPath()
  private var handle: FileHandle? = null
  private val dispatcher = dispatcher.limitedParallelism(1)

  private fun getOrCreateHandle(): FileHandle {
    return handle ?: run {
      val parent = realPath.parent
      if (parent != null && !platformFileSystem.exists(parent)) {
        log.d { "Creating directories: $parent" }
        platformFileSystem.createDirectories(parent)
      }
      log.d { "Opening file: $realPath" }
      platformFileSystem.openReadWrite(realPath).also { handle = it }
    }
  }

  override suspend fun writeAt(offset: Long, data: ByteArray) {
    withContext(dispatcher) {
      getOrCreateHandle().write(offset, data, 0, data.size)
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
      if (platformFileSystem.exists(realPath)) {
        log.d { "Deleting file: $realPath" }
        platformFileSystem.delete(realPath)
      }
    }
  }

  override suspend fun size(): Long = withContext(dispatcher) {
    platformFileSystem.metadata(realPath).size ?: 0L
  }

  override suspend fun preallocate(size: Long) {
    if (size <= 0) return
    log.d { "Preallocating $size bytes: $realPath" }
    withContext(dispatcher) {
      val handle = getOrCreateHandle()
      val current = handle.size()
      when {
        size > current -> {
          // okio 3.16.4 JvmFileHandle.protectedResize() grows by
          // allocating ByteArray((size - current).toInt()) and writing
          // it whole — this overflows Int and crashes with
          // NegativeArraySizeException once delta exceeds 2 GB. Extend
          // via a single sparse-byte write to avoid the bug and the
          // gratuitous allocation.
          handle.write(size - 1, byteArrayOf(0), 0, 1)
        }
        size < current -> handle.resize(size)
      }
    }
  }
}
