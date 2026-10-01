package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.draganddrop.DragAndDropEvent

/**
 * A file dropped onto the app from another application. The content is
 * read only on demand, so dropping a large file costs nothing until then.
 */
class DroppedFile(
  /** File name, including its extension. */
  val name: String,
  private val reader: suspend (maxBytes: Long) -> ByteArray,
) {
  /**
   * Reads the whole file off the main thread.
   *
   * @throws IllegalArgumentException if the file is larger than [maxBytes]
   */
  suspend fun readBytes(maxBytes: Long): ByteArray = reader(maxBytes)
}

/** Extracts files and text from platform drag-and-drop events. */
internal interface FileDropReader {
  /**
   * Whether a drag may carry files or text from another app. Called when the drag starts, before
   * the platform exposes file names or content. A drag that starts inside Ketch, such as rows
   * dragged out of the list, is not accepted, so it never reads as new downloads.
   */
  fun accepts(event: DragAndDropEvent): Boolean

  /** Files carried by a completed drop. */
  fun files(event: DragAndDropEvent): List<DroppedFile>

  /**
   * Text carried by a completed drop, such as links dragged from a browser or selected text, or
   * `null` when it carries none. Called from the drop handler, because platforms hide the data
   * once it returns; the returned reader may finish later.
   */
  fun text(event: DragAndDropEvent): (suspend () -> String)?
}

@Composable
internal expect fun rememberFileDropReader(): FileDropReader

/**
 * Calls [onExit] when a drag leaves the window without dropping, on
 * platforms whose drop targets are not told (Compose for Web).
 */
@Composable
internal expect fun DragExitEffect(onExit: () -> Unit)

internal fun fileTooLarge(name: String, maxBytes: Long): Nothing =
  throw IllegalArgumentException("$name is larger than ${maxBytes / (1024 * 1024)} MiB")

/** Name of [droppedLinkList] files, which reach handlers that take files as a list of links. */
internal const val DROPPED_TEXT_NAME = "dropped-links.txt"

/** Dropped [text] as a `.txt` list of links, for drop handlers that take only files. */
internal fun droppedLinkList(text: String): DroppedFile {
  val bytes = text.encodeToByteArray()
  return DroppedFile(DROPPED_TEXT_NAME) { maxBytes ->
    if (bytes.size > maxBytes) fileTooLarge(DROPPED_TEXT_NAME, maxBytes)
    bytes
  }
}

/** The lines of a `text/uri-list`, without its `#` comment lines. */
internal fun uriListEntries(uriList: String): List<String> =
  uriList.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
