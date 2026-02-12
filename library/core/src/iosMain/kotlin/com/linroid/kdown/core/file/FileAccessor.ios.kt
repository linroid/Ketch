package com.linroid.kdown.core.file

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToFileOffset
import platform.Foundation.synchronizeFile
import platform.Foundation.truncateFileAtOffset
import platform.Foundation.writeData

@OptIn(ExperimentalForeignApi::class)
actual class FileAccessor actual constructor(private val path: Path) {
  private val fileManager = NSFileManager.defaultManager
  private var fileHandle: NSFileHandle? = null
  private val mutex = Mutex()

  private suspend fun getOrCreateHandle(): NSFileHandle = mutex.withLock {
    fileHandle ?: run {
      val parent = path.parent
      if (parent != null && !SystemFileSystem.exists(parent)) {
        SystemFileSystem.createDirectories(parent)
      }
      val pathStr = path.toString()
      if (!fileManager.fileExistsAtPath(pathStr)) {
        fileManager.createFileAtPath(pathStr, null, null)
      }
      NSFileHandle.fileHandleForWritingAtPath(pathStr)?.also { fileHandle = it }
        ?: throw IllegalStateException("Cannot open file for writing: $path")
    }
  }

  actual suspend fun writeAt(offset: Long, data: ByteArray) {
    withContext(Dispatchers.IO) {
      val handle = getOrCreateHandle()
      mutex.withLock {
        handle.seekToFileOffset(offset.toULong())
        data.usePinned { pinned ->
          val nsData = NSData.create(
            bytes = pinned.addressOf(0),
            length = data.size.toULong(),
          )
          handle.writeData(nsData)
        }
      }
    }
  }

  actual suspend fun flush() {
    withContext(Dispatchers.IO) {
      mutex.withLock {
        fileHandle?.synchronizeFile()
      }
    }
  }

  actual fun close() {
    fileHandle?.closeFile()
    fileHandle = null
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
      val handle = getOrCreateHandle()
      mutex.withLock {
        handle.truncateFileAtOffset(size.toULong())
      }
    }
  }
}
