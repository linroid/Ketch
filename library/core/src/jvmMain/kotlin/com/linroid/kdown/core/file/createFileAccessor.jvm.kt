package com.linroid.kdown.core.file

import kotlinx.coroutines.Dispatchers
import java.io.RandomAccessFile

actual fun createFileAccessor(path: String): FileAccessor {
  return PathFileAccessor(path, Dispatchers.IO) { realPath ->
    JvmRandomAccessHandle(RandomAccessFile(realPath, "rw"))
  }
}
