package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.awtTransferable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

@Composable
internal actual fun rememberFileDropReader(): FileDropReader = AwtFileDropReader

/** AWT reports exits to drop targets itself. */
@Composable
internal actual fun DragExitEffect(onExit: () -> Unit) = Unit

@OptIn(ExperimentalComposeUiApi::class)
private object AwtFileDropReader : FileDropReader {
  override fun hasFiles(event: DragAndDropEvent): Boolean =
    event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

  override fun files(event: DragAndDropEvent): List<DroppedFile> =
    event.awtTransferable.droppedFiles()
}

/** Regular files in a file-list transfer; directories are skipped. */
internal fun Transferable.droppedFiles(): List<DroppedFile> {
  if (!isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
  val files = getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: return emptyList()
  return files.filterIsInstance<File>().filter { it.isFile }.map { file ->
    DroppedFile(file.name) { maxBytes ->
      withContext(Dispatchers.IO) {
        if (file.length() > maxBytes) fileTooLarge(file.name, maxBytes)
        file.readBytes().also {
          // The file may have grown after the length check.
          if (it.size > maxBytes) fileTooLarge(file.name, maxBytes)
        }
      }
    }
  }
}
