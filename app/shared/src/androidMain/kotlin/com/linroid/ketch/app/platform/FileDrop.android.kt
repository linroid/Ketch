package com.linroid.ketch.app.platform

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberFileDropReader(): FileDropReader {
  val context = LocalContext.current
  return remember(context) { AndroidFileDropReader(context) }
}

/** Android reports exits to drop targets itself. */
@Composable
internal actual fun DragExitEffect(onExit: () -> Unit) = Unit

/**
 * Reads what other apps drop, e.g. in split screen or on ChromeOS: documents as content URIs, and
 * links or selected text from a browser as text.
 */
private class AndroidFileDropReader(private val context: Context) : FileDropReader {
  override fun accepts(event: DragAndDropEvent): Boolean {
    val dragEvent = event.toAndroidDragEvent()
    // Only drags that start in this app, such as rows dragged out of the list, carry local state.
    if (dragEvent.localState != null) return false
    val description = dragEvent.clipDescription ?: return false
    return (0 until description.mimeTypeCount).any {
      description.getMimeType(it) != ClipDescription.MIMETYPE_TEXT_INTENT
    }
  }

  override fun files(event: DragAndDropEvent): List<DroppedFile> {
    val dragEvent = event.toAndroidDragEvent()
    val uris = dragEvent.clipData?.items().orEmpty().mapNotNull { item ->
      item.uri?.takeIf { it.isDocument() }
    }
    if (uris.isEmpty()) return emptyList()
    // Content URIs from other apps are readable only after this grant.
    context.findActivity()?.requestDragAndDropPermissions(dragEvent)
    return uris.map { contentFile(context, it) }
  }

  override fun text(event: DragAndDropEvent): (suspend () -> String)? {
    val items = event.toAndroidDragEvent().clipData?.items().orEmpty()
    // A link dragged from a browser can arrive as an http URI rather than as text.
    val text = items.mapNotNull { item ->
      item.text?.toString() ?: item.uri?.takeUnless { it.isDocument() }?.toString()
        ?: item.htmlText
    }.joinToString("\n")
    return if (text.isBlank()) null else ({ text })
  }

  private fun ClipData.items(): List<ClipData.Item> = (0 until itemCount).map(::getItemAt)

  private fun Uri.isDocument(): Boolean =
    scheme == ContentResolver.SCHEME_CONTENT || scheme == ContentResolver.SCHEME_FILE

  private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }
}
