package com.linroid.ketch.core.file

import kotlinx.coroutines.CoroutineDispatcher
import java.io.RandomAccessFile

actual fun createFileAccessor(
  path: String,
  dispatcher: CoroutineDispatcher,
): FileAccessor {
  return PathFileAccessor(path, dispatcher) { realPath ->
    JvmRandomAccessHandle(RandomAccessFile(realPath, "rw"))
  }
}
