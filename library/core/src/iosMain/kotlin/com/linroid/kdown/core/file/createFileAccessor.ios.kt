package com.linroid.kdown.core.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.fileHandleForWritingAtPath

actual fun createFileAccessor(path: String): FileAccessor {
  return PathFileAccessor(path, Dispatchers.IO) { realPath ->
    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(realPath)) {
      fileManager.createFileAtPath(realPath, null, null)
    }
    val handle = NSFileHandle.fileHandleForWritingAtPath(realPath)
      ?: throw IllegalStateException(
        "Cannot open file for writing: $realPath"
      )
    IosRandomAccessHandle(handle)
  }
}
