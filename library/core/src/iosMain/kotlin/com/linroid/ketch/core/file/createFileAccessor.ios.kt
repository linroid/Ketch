package com.linroid.ketch.core.file

import kotlinx.coroutines.CoroutineDispatcher
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.fileHandleForWritingAtPath

actual fun createFileAccessor(
  path: String,
  dispatcher: CoroutineDispatcher,
): FileAccessor {
  return PathFileAccessor(path, dispatcher) { realPath ->
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
