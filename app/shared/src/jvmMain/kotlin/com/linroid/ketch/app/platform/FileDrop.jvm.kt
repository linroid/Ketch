package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.awtTransferable
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI
import java.net.URL

@Composable
internal actual fun rememberFileDropReader(): FileDropReader = AwtFileDropReader

/** AWT reports exits to drop targets itself. */
@Composable
internal actual fun DragExitEffect(onExit: () -> Unit) = Unit

@OptIn(ExperimentalComposeUiApi::class)
private object AwtFileDropReader : FileDropReader {
  override fun accepts(event: DragAndDropEvent): Boolean = event.awtTransferable.isDroppable()

  override fun files(event: DragAndDropEvent): List<DroppedFile> =
    event.awtTransferable.droppedFiles()

  override fun text(event: DragAndDropEvent): (suspend () -> String)? =
    event.awtTransferable.droppedText()?.let { text -> { text } }
}

/**
 * Whether a drag carries files or text from another app, judged by its flavors alone. Rows
 * dragged out of the list carry their keys as [IN_APP_DRAG_TYPE] beside their files and links.
 */
internal fun Transferable.isDroppable(): Boolean =
  transferDataFlavors.orEmpty().none { it.isMimeTypeEqual(IN_APP_DRAG_TYPE) } &&
    DROP_FLAVORS.any(::isDataFlavorSupported)

/**
 * Regular files in a drop; directories are skipped. Some Linux file managers offer files only as
 * a `text/uri-list` of `file:` URIs.
 */
internal fun Transferable.droppedFiles(): List<DroppedFile> {
  val files = if (isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
    (read(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>()
  } else {
    uriList().mapNotNull(::fileOf)
  }
  return files.filter { it.isFile }.map { it.toDroppedFile() }
}

/**
 * Text in a drop without files: the links of a `text/uri-list`, a dragged URL, or plain text,
 * such as a link dragged from a browser. `null` when the drop carries no text.
 */
internal fun Transferable.droppedText(): String? {
  if (isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
  val links = uriList()
  if (links.isNotEmpty()) {
    return links.filter { fileOf(it) == null }.joinToString("\n").ifEmpty { null }
  }
  val text = when {
    isDataFlavorSupported(URL_FLAVOR) -> (read(URL_FLAVOR) as? URL)?.toString()
    isDataFlavorSupported(DataFlavor.stringFlavor) -> read(DataFlavor.stringFlavor) as? String
    else -> null
  }
  return text?.takeIf { it.isNotBlank() }
}

private fun Transferable.uriList(): List<String> {
  if (!isDataFlavorSupported(URI_LIST_FLAVOR)) return emptyList()
  return uriListEntries(read(URI_LIST_FLAVOR) as? String ?: return emptyList())
}

/** The drop's data as [flavor], or `null` when the source no longer provides it. */
private fun Transferable.read(flavor: DataFlavor): Any? = try {
  getTransferData(flavor)
} catch (e: Exception) {
  log.w { "Couldn't read a drop as ${flavor.mimeType}: ${e.describeCauses()}" }
  null
}

/** The local file a `file:` URI names, or `null` for other URIs. */
private fun fileOf(uri: String): File? {
  if (!uri.startsWith("file:", ignoreCase = true)) return null
  return runCatching { File(URI(uri)) }.getOrNull()
}

private val log = KetchLogger("FileDrop")
private val URI_LIST_FLAVOR = DataFlavor("text/uri-list;class=java.lang.String")
private val URL_FLAVOR = DataFlavor("application/x-java-url;class=java.net.URL")
private val DROP_FLAVORS = listOf(
  DataFlavor.javaFileListFlavor,
  URI_LIST_FLAVOR,
  URL_FLAVOR,
  DataFlavor.stringFlavor,
)

/** The type of the task keys that rows dragged out of the Downloads list carry. */
private const val IN_APP_DRAG_TYPE = "application/x-ketch-tasks"
