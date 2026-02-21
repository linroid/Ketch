@file:Suppress("UseKtx")

package com.linroid.kdown.core.file

import android.net.Uri
import com.linroid.kdown.core.AndroidContext
import kotlinx.coroutines.Dispatchers
import java.io.RandomAccessFile


/**
 * Creates a [FileAccessor] for the given [path], supporting both
 * regular file paths and content URIs.
 *
 * For content URIs (e.g. `content://...`), a [ContentUriFileAccessor]
 * backed by the Android [ContentResolver][android.content.ContentResolver]
 * is returned. For regular file paths, a [PathFileAccessor] is used.
 */
actual fun createFileAccessor(path: String): FileAccessor {
  val uri = Uri.parse(path)
  return if (uri.isRelative) {
    PathFileAccessor(path, Dispatchers.IO) { realPath ->
      JvmRandomAccessHandle(RandomAccessFile(realPath, "rw"))
    }
  } else {
    ContentUriFileAccessor(AndroidContext.get(), uri)
  }
}
