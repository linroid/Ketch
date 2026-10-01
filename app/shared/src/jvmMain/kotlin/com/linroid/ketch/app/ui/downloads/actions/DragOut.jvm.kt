package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.awtTransferable
import com.linroid.ketch.app.state.TaskKey
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun dragTransferData(payload: DragPayload): DragAndDropTransferData? =
  DragAndDropTransferData(
    transferable = DragAndDropTransferable(PayloadTransferable(payload)),
    supportedActions = listOf(DragAndDropTransferAction.Copy),
  )

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun draggedTaskKeys(event: DragAndDropEvent): List<TaskKey> {
  val transferable = event.awtTransferable
  if (!transferable.isDataFlavorSupported(KeysFlavor)) return emptyList()
  val text = runCatching { transferable.getTransferData(KeysFlavor) as? String }.getOrNull()
  return text?.let(DragPayload::keysOf).orEmpty()
}

/**
 * [payload] as AWT data: files for file managers, links as `text/uri-list` and plain text, and
 * the task keys for drops inside the app.
 */
internal class PayloadTransferable(private val payload: DragPayload) : Transferable {
  private val flavors: List<DataFlavor> = listOfNotNull(
    DataFlavor.javaFileListFlavor.takeIf { payload.files.isNotEmpty() },
    UriListFlavor,
    DataFlavor.stringFlavor,
    KeysFlavor,
  )

  override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.toTypedArray()

  override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavors.any { it == flavor }

  override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
    DataFlavor.javaFileListFlavor -> payload.files.map(::File)
    UriListFlavor -> if (payload.files.isEmpty()) {
      payload.uriList
    } else {
      payload.files.joinToString("\r\n", postfix = "\r\n") { File(it).toURI().toString() }
    }
    DataFlavor.stringFlavor -> payload.text
    KeysFlavor -> payload.keysText
    else -> throw UnsupportedFlavorException(flavor)
  }
}

private val UriListFlavor = DataFlavor("text/uri-list;class=java.lang.String")

private val KeysFlavor = DataFlavor("${DragPayload.KEYS_MIME_TYPE};class=java.lang.String")
