package com.linroid.ketch.app.platform

import android.app.Activity
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
internal actual fun rememberFileDropReader(): FileDropReader {
  val context = LocalContext.current
  return remember(context) { AndroidFileDropReader(context) }
}

/** Android reports exits to drop targets itself. */
@Composable
internal actual fun DragExitEffect(onExit: () -> Unit) = Unit

/** Reads content URIs dropped from other apps, e.g. in split screen or on ChromeOS. */
private class AndroidFileDropReader(private val context: Context) : FileDropReader {
  override fun hasFiles(event: DragAndDropEvent): Boolean {
    val description = event.toAndroidDragEvent().clipDescription ?: return false
    return (0 until description.mimeTypeCount).any {
      description.getMimeType(it) !in TEXT_MIME_TYPES
    }
  }

  override fun files(event: DragAndDropEvent): List<DroppedFile> {
    val dragEvent = event.toAndroidDragEvent()
    val clip = dragEvent.clipData ?: return emptyList()
    // Content URIs from other apps are readable only after this grant.
    context.findActivity()?.requestDragAndDropPermissions(dragEvent)
    return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.map { uri ->
      val name = displayName(uri)
      DroppedFile(name) { maxBytes -> withContext(Dispatchers.IO) { read(uri, name, maxBytes) } }
    }
  }

  private fun displayName(uri: Uri): String {
    val queried = runCatching {
      context.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
      )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
      }
    }.getOrNull()
    return queried ?: uri.lastPathSegment ?: uri.toString()
  }

  private fun read(uri: Uri, name: String, maxBytes: Long): ByteArray {
    val input = context.contentResolver.openInputStream(uri)
      ?: throw IllegalArgumentException("Cannot open $name")
    return input.use { stream ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        if (output.size() > maxBytes) fileTooLarge(name, maxBytes)
      }
      output.toByteArray()
    }
  }

  private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

  private companion object {
    val TEXT_MIME_TYPES = setOf(
      ClipDescription.MIMETYPE_TEXT_PLAIN,
      ClipDescription.MIMETYPE_TEXT_HTML,
      ClipDescription.MIMETYPE_TEXT_INTENT,
    )
  }
}
