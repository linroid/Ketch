package com.linroid.ketch.app.platform

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Lets other apps read downloaded files, for Open and Share in the app and in notifications. A
 * subclass, so that it cannot clash with another `FileProvider` in the merged manifest. Its
 * folders are the manifest's `FILE_PROVIDER_PATHS` meta-data, which [downloadUri] needs.
 */
internal class DownloadFileProvider : FileProvider()

/**
 * A URI through which other apps can read the download at [path]: a `content://` document URI
 * as it is, a file through [DownloadFileProvider]. Grant read permission with the intent that
 * carries it.
 *
 * @throws FileActionException when the file lies outside the folders the provider serves.
 */
internal fun downloadUri(context: Context, path: String): Uri {
  if (path.startsWith(CONTENT_SCHEME)) return Uri.parse(path)
  val file = File(path)
  return try {
    FileProvider.getUriForFile(context, "${context.packageName}.downloads", file)
  } catch (e: IllegalArgumentException) {
    throw FileActionException("Other apps can't open files in ${file.parent}", e)
  }
}

internal const val CONTENT_SCHEME = "content://"
