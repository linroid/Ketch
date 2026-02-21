package com.linroid.kdown.core.file

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.create
import platform.Foundation.seekToFileOffset
import platform.Foundation.synchronizeFile
import platform.Foundation.closeFile
import platform.Foundation.truncateFileAtOffset
import platform.Foundation.writeData

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosRandomAccessHandle(
  private val fileHandle: NSFileHandle,
) : RandomAccessHandle {

  override fun writeAt(offset: Long, data: ByteArray) {
    fileHandle.seekToFileOffset(offset.toULong())
    data.usePinned { pinned ->
      val nsData = NSData.create(
        bytes = pinned.addressOf(0),
        length = data.size.toULong(),
      )
      fileHandle.writeData(nsData)
    }
  }

  override fun flush() {
    fileHandle.synchronizeFile()
  }

  override fun close() {
    fileHandle.closeFile()
  }

  override fun preallocate(size: Long) {
    fileHandle.truncateFileAtOffset(size.toULong())
  }
}
