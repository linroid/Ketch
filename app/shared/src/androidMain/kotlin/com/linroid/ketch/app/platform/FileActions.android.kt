package com.linroid.ketch.app.platform

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun rememberFileActions(): FileActions? {
  val context = LocalContext.current
  return remember(context) { AndroidFileActions(context) }
}

/** Hands downloads to other apps. Android has no file manager to reveal them in. */
private class AndroidFileActions(private val context: Context) : FileActions {
  override val revealLabel: String? = null

  override val canShare: Boolean = true

  override val canTrash: Boolean = false

  override suspend fun open(path: String) {
    start(withContext(Dispatchers.IO) { openFileIntent(context, path) }, path)
  }

  override suspend fun reveal(path: String) {
    throw FileActionException("Android has no file manager for Ketch to show files in")
  }

  override suspend fun share(path: String) {
    start(withContext(Dispatchers.IO) { shareFileIntent(context, path) }, path)
  }

  override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
    if (path.startsWith(CONTENT_SCHEME)) {
      // A deleted document, or one whose permission was revoked, cannot be queried.
      runCatching { queryName(context, Uri.parse(path)) != null }.getOrDefault(false)
    } else {
      File(path).exists()
    }
  }

  override suspend fun moveToTrash(path: String) {
    throw FileActionException("Android has no Trash for downloads")
  }

  private fun start(intent: Intent, path: String) {
    try {
      context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
      throw FileActionException("No app can open ${fileName(path)}", e)
    }
  }
}

/**
 * An intent that opens the download at [path] in the app the user picks. Reads the file's
 * details, so call it off the main thread.
 *
 * @throws FileActionException when the file is gone, is a folder, or cannot be handed out.
 */
internal fun openFileIntent(context: Context, path: String): Intent {
  val file = DownloadFile.of(context, path)
  return Intent(Intent.ACTION_VIEW)
    .setDataAndType(file.uri, file.mimeType ?: "*/*")
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * An intent that offers the download at [path] to other apps through the share sheet. Reads the
 * file's details, so call it off the main thread.
 *
 * @throws FileActionException when the file is gone, is a folder, or cannot be handed out.
 */
internal fun shareFileIntent(context: Context, path: String): Intent {
  val file = DownloadFile.of(context, path)
  val send = Intent(Intent.ACTION_SEND)
    .setType(file.mimeType ?: "application/octet-stream")
    .putExtra(Intent.EXTRA_STREAM, file.uri)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  // The chooser's preview reads the file through the clip, which carries the grant.
  send.clipData = ClipData.newRawUri(file.name, file.uri)
  return Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** A download as other apps receive it. */
private class DownloadFile(val uri: Uri, val name: String, val mimeType: String?) {
  companion object {
    fun of(context: Context, path: String): DownloadFile {
      if (path.startsWith(CONTENT_SCHEME)) {
        val uri = Uri.parse(path)
        val name = try {
          queryName(context, uri)
        } catch (e: Exception) {
          throw FileActionException("${fileName(path)} was moved or deleted", e)
        } ?: throw FileActionException("${fileName(path)} was moved or deleted")
        // Ketch creates documents as application/octet-stream, so the name says more.
        val type = mimeTypeOf(name) ?: context.contentResolver.getType(uri)
        return DownloadFile(uri, name, type)
      }
      val file = File(path)
      if (!file.exists()) throw FileActionException("${file.name} was moved or deleted")
      if (file.isDirectory) {
        throw FileActionException("Open the files in ${file.name} from a file manager")
      }
      return DownloadFile(downloadUri(context, path), file.name, mimeTypeOf(file.name))
    }
  }
}

/**
 * Display name of the document at [uri], or `null` when the provider has no such document.
 * Throws when the document is gone or Ketch lost permission to it, depending on the provider.
 */
private fun queryName(context: Context, uri: Uri): String? =
  context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    ?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0) ?: fileName(uri.toString()) else null
    }

/** Name of the download at [path] for messages, without asking its provider. */
private fun fileName(path: String): String {
  if (!path.startsWith(CONTENT_SCHEME)) return File(path).name
  // A document id ends in the file's path, such as "primary:Download/ubuntu.iso".
  val id = Uri.parse(path).lastPathSegment.orEmpty()
  return id.substringAfterLast('/').substringAfterLast(':').ifEmpty { "The file" }
}

private fun mimeTypeOf(name: String): String? {
  val extension = name.substringAfterLast('.', "").lowercase()
  if (extension.isEmpty()) return null
  return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
}
