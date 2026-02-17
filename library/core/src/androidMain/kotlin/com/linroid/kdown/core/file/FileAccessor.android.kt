package com.linroid.kdown.core.file

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import com.kdroid.androidcontextprovider.ContextProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.IOException
import java.io.RandomAccessFile


actual class FileAccessor actual constructor(private val path: String) {
  private var fileHolder: FileHolder<*, *>? = null
  private val mutex = Mutex()

  private suspend fun getOrCreateFile(): FileHolder<*, *> = mutex.withLock {
    fileHolder ?: run {
      val uri = Uri.parse(path)
      val isFile = uri.isRelative

      if (isFile) {
        val filePath = Path(path)

        val parent = filePath.parent
        if (parent != null && !SystemFileSystem.exists(parent)) {
          SystemFileSystem.createDirectories(parent)
        }
        FileHolder.RandomAccessFileHolder(
          file = RandomAccessFile(filePath.toString(), "rw"),
          path = filePath,
        )
      } else {
        FileHolder.OutputStreamHolder(
          context = ContextProvider.getContext(),
          file = uri,
          path = path,
        )
      }.also { fileHolder = it }
    }
  }

  actual suspend fun writeAt(offset: Long, data: ByteArray) {
    withContext(Dispatchers.IO) {
      val raf = getOrCreateFile()
      mutex.withLock {
        raf.writeAt(offset, data)
      }
    }
  }

  actual suspend fun flush() {
    withContext(Dispatchers.IO) {
      mutex.withLock {
        fileHolder?.flush()
      }
    }
  }

  actual fun close() {
    fileHolder?.close()
    fileHolder = null
  }

  actual suspend fun delete() {
    withContext(Dispatchers.IO) {
      fileHolder?.delete()
    }
  }

  actual suspend fun size(): Long = withContext(Dispatchers.IO) {
    getOrCreateFile().size()
  }

  actual suspend fun preallocate(size: Long) {
    withContext(Dispatchers.IO) {
      val raf = getOrCreateFile()
      mutex.withLock {
        raf.preallocate(size)
      }
    }
  }

  actual suspend fun canSegment(): Boolean {
    return withContext(Dispatchers.IO) {
      getOrCreateFile() is FileHolder.RandomAccessFileHolder
    }
  }
}

sealed interface FileHolder<T, P> {
  val file: T
  val path: P

  fun writeAt(offset: Long, data: ByteArray)
  fun flush()
  fun close()
  fun delete()
  fun size(): Long
  fun preallocate(size: Long)

  data class RandomAccessFileHolder(
    override val file: RandomAccessFile,
    override val path: Path,
  ) : FileHolder<RandomAccessFile, Path> {
    override fun writeAt(offset: Long, data: ByteArray) {
      file.seek(offset)
      file.write(data)
    }

    override fun flush() {
      file.fd?.sync()
    }

    override fun close() {
      file.close()
    }

    override fun delete() {
      close()
      if (SystemFileSystem.exists(path)) {
        SystemFileSystem.delete(path)
      }
    }

    override fun size(): Long {
      return SystemFileSystem.metadataOrNull(path)?.size ?: 0L
    }

    override fun preallocate(size: Long) {
      file.setLength(size)
    }
  }

  data class OutputStreamHolder(
    val context: Context,
    override val file: Uri,
    override val path: String,
  ): FileHolder<Uri, String> {
    private val pfd = context.contentResolver.openFileDescriptor(file, "rw")
    private val fileDescriptor = pfd?.fileDescriptor ?:
      throw IOException("Failed to open file descriptor for Uri=$file; the Uri may be invalid or permissions may be missing.")
    private val documentFile = DocumentFile.fromSingleUri(context, file) ?:
      throw IOException("Failed to open DocumentFile for Uri=$file; the Uri may be invalid or permissions may be missing.")

    override fun writeAt(offset: Long, data: ByteArray) {
      Os.lseek(fileDescriptor, offset, OsConstants.SEEK_SET)

      if (data.isEmpty()) {
        return
      }

      var byteOffset = 0
      var byteCount = data.size

      try {
        while (byteCount > 0) {
          val bytesWritten = Os.write(fileDescriptor, data, byteOffset, byteCount)
          byteCount -= bytesWritten
          byteOffset += bytesWritten
        }
      } catch (errnoException: ErrnoException) {
        val newException = IOException(errnoException.message)
        newException.initCause(errnoException)

        throw newException
      }
    }

    override fun flush() {
      fileDescriptor.sync()
    }

    override fun close() {
      pfd?.close()
    }

    override fun delete() {
      documentFile.delete()
    }

    override fun size(): Long {
      return documentFile.length()
    }

    override fun preallocate(size: Long) {}
  }
}
