package com.linroid.ketch.core.file

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * [FileAccessor] for Android content URIs (e.g. SAF documents).
 *
 * Uses [Os.lseek] + [Os.write] for random-access writes and
 * [Os.fstat] for file size. No `DocumentFile` dependency.
 *
 * @param dispatcher dispatcher for blocking I/O operations
 */
internal class ContentUriFileAccessor(
  private val context: Context,
  private val uri: Uri,
  dispatcher: CoroutineDispatcher,
) : FileAccessor {

  private val log = KetchLogger("FileAccessor")
  private val dispatcher = dispatcher.limitedParallelism(1)

  private val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
  private val fileDescriptor = pfd?.fileDescriptor
    ?: throw IOException(
      "Failed to open file descriptor for Uri=$uri; " +
        "the Uri may be invalid or permissions may be missing.",
    )

  init {
    log.d { "Opened file descriptor for uri: $uri" }
  }

  override suspend fun writeAt(offset: Long, data: ByteArray) {
    withContext(dispatcher) {
      Os.lseek(fileDescriptor, offset, OsConstants.SEEK_SET)
      if (data.isEmpty()) return@withContext

      var byteOffset = 0
      var byteCount = data.size
      try {
        while (byteCount > 0) {
          val bytesWritten =
            Os.write(fileDescriptor, data, byteOffset, byteCount)
          byteCount -= bytesWritten
          byteOffset += bytesWritten
        }
      } catch (e: ErrnoException) {
        throw IOException(e.message).apply { initCause(e) }
      }
    }
  }

  override suspend fun flush() {
    withContext(dispatcher) {
      fileDescriptor.sync()
    }
  }

  override fun close() {
    log.d { "Closing uri: $uri" }
    pfd?.close()
  }

  override suspend fun delete() {
    withContext(dispatcher) {
      close()
      log.d { "Deleting document: $uri" }
      DocumentsContract.deleteDocument(context.contentResolver, uri)
    }
  }

  override suspend fun size(): Long = withContext(dispatcher) {
    Os.fstat(fileDescriptor).st_size
  }

  override suspend fun preallocate(size: Long) {
    if (size <= 0) return
    log.d { "Preallocating $size bytes for uri: $uri" }
    writeAt(size - 1, byteArrayOf(0))
  }
}
