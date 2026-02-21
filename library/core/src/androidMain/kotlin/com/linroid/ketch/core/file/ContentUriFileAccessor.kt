package com.linroid.ketch.core.file

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * [FileAccessor] for Android content URIs (e.g. SAF documents).
 *
 * Uses [Os.lseek] + [Os.write] for random-access writes and
 * [Os.fstat] for file size. No `DocumentFile` dependency.
 */
internal class ContentUriFileAccessor(
  private val context: Context,
  private val uri: Uri,
) : FileAccessor {

  private val mutex = Mutex()

  private val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
  private val fileDescriptor = pfd?.fileDescriptor
    ?: throw IOException(
      "Failed to open file descriptor for Uri=$uri; " +
        "the Uri may be invalid or permissions may be missing.",
    )

  override suspend fun writeAt(offset: Long, data: ByteArray) {
    withContext(Dispatchers.IO) {
      mutex.withLock {
        Os.lseek(fileDescriptor, offset, OsConstants.SEEK_SET)
        if (data.isEmpty()) return@withLock

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
  }

  override suspend fun flush() {
    withContext(Dispatchers.IO) {
      mutex.withLock {
        fileDescriptor.sync()
      }
    }
  }

  override fun close() {
    pfd?.close()
  }

  override suspend fun delete() {
    withContext(Dispatchers.IO) {
      mutex.withLock {
        close()
        DocumentsContract.deleteDocument(context.contentResolver, uri)
      }
    }
  }

  override suspend fun size(): Long = withContext(Dispatchers.IO) {
    Os.fstat(fileDescriptor).st_size
  }

  override suspend fun preallocate(size: Long) {
    if (size <= 0) return
    writeAt(size - 1, byteArrayOf(0))
  }
}
