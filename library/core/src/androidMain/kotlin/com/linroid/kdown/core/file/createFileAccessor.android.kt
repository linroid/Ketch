package com.linroid.kdown.core.file

import android.net.Uri
import com.kdroid.androidcontextprovider.ContextProvider
import kotlinx.coroutines.Dispatchers
import java.io.RandomAccessFile

actual fun createFileAccessor(path: String): FileAccessor {
  val uri = Uri.parse(path)
  return if (uri.isRelative) {
    PathFileAccessor(path, Dispatchers.IO) { realPath ->
      JvmRandomAccessHandle(RandomAccessFile(realPath, "rw"))
    }
  } else {
    ContentUriFileAccessor(ContextProvider.getContext(), uri)
  }
}
