@file:Suppress("UseKtx")

package com.linroid.ketch.core.file

import android.net.Uri
import com.linroid.ketch.core.AndroidContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Creates a [FileAccessor] for the given [path], supporting both
 * regular file paths and content URIs.
 *
 * For content URIs (e.g. `content://...`), a [ContentUriFileAccessor]
 * backed by the Android [ContentResolver][android.content.ContentResolver]
 * is returned. For regular file paths, a [PathFileAccessor] is used.
 */
actual fun createFileAccessor(
  path: String,
  ioDispatcher: CoroutineDispatcher,
): FileAccessor {
  // A URI scheme requires a colon. Keep ordinary paths independent of Android APIs.
  if (':' !in path) return PathFileAccessor(path, ioDispatcher)
  val uri = Uri.parse(path)
  return if (uri.isRelative) {
    PathFileAccessor(path, ioDispatcher)
  } else {
    ContentUriFileAccessor(AndroidContext.get(), uri, ioDispatcher)
  }
}
