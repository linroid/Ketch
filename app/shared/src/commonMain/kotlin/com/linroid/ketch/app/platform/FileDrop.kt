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

/** Extracts files from platform drag-and-drop events. */
internal interface FileDropReader {
  /**
   * Whether a drag may carry files. Called when the drag starts, before
   * the platform exposes file names or content.
   */
  fun hasFiles(event: DragAndDropEvent): Boolean

  /** Files carried by a completed drop. */
  fun files(event: DragAndDropEvent): List<DroppedFile>
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
