package com.linroid.kdown.core.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.RandomAccessFile

actual class FileAccessor actual constructor(private val path: Path) {
  private var randomAccessFile: RandomAccessFile? = null
  private val mutex = Mutex()

  private suspend fun getOrCreateFile(): RandomAccessFile = mutex.withLock {
    randomAccessFile ?: run {
      val parent = path.parent
      if (parent != null && !SystemFileSystem.exists(parent)) {
        SystemFileSystem.createDirectories(parent)
      }
      RandomAccessFile(path.toString(), "rw").also { randomAccessFile = it }
    }
  }

  actual suspend fun writeAt(offset: Long, data: ByteArray) {
    withContext(Dispatchers.IO) {
      val raf = getOrCreateFile()
      mutex.withLock {
        raf.seek(offset)
        raf.write(data)
      }
    }
  }

  actual suspend fun flush() {
    withContext(Dispatchers.IO) {
      mutex.withLock {
        randomAccessFile?.fd?.sync()
      }
    }
  }

  actual fun close() {
    randomAccessFile?.close()
    randomAccessFile = null
  }

  actual suspend fun delete() {
    withContext(Dispatchers.IO) {
      close()
      if (SystemFileSystem.exists(path)) {
        SystemFileSystem.delete(path)
      }
    }
  }

  actual suspend fun size(): Long = withContext(Dispatchers.IO) {
    SystemFileSystem.metadataOrNull(path)?.size ?: 0L
  }

  actual suspend fun preallocate(size: Long) {
    withContext(Dispatchers.IO) {
      val raf = getOrCreateFile()
      mutex.withLock {
        raf.setLength(size)
      }
    }
  }
}
