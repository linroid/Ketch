@file:OptIn(ExperimentalWasmJsInterop::class)

package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.domDataTransferOrNull
import kotlinx.browser.window
import org.w3c.dom.DataTransfer
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.unsafeCast

@Composable
internal actual fun rememberFileDropReader(): FileDropReader = BrowserFileDropReader

@Composable
internal actual fun DragExitEffect(onExit: () -> Unit) {
  val currentOnExit by rememberUpdatedState(onExit)
  DisposableEffect(Unit) {
    // The canvas fills the page, so only leaving the window has no related target.
    val listener: (Event) -> Unit = { event ->
      if (event.unsafeCast<MouseEvent>().relatedTarget == null) currentOnExit()
    }
    window.addEventListener("dragleave", listener)
    onDispose { window.removeEventListener("dragleave", listener) }
  }
}

private object BrowserFileDropReader : FileDropReader {
  override fun accepts(event: DragAndDropEvent): Boolean {
    // Names and content are hidden until the drop; only the types are visible.
    val types = event.dataTransfer?.types ?: return false
    return (0 until types.length).any { types[it]?.toString() in DROP_TYPES }
  }

  override fun files(event: DragAndDropEvent): List<DroppedFile> {
    val files = event.dataTransfer?.files ?: return emptyList()
    return (0 until files.length).mapNotNull { files.item(it) }.map { it.toDroppedFile() }
  }

  override fun text(event: DragAndDropEvent): (suspend () -> String)? {
    val transfer = event.dataTransfer ?: return null
    // A dragged link carries its address in the URI list and its title in the plain text.
    val links = uriListEntries(transfer.getData(URI_LIST_TYPE)).joinToString("\n")
    val text = links.ifEmpty { transfer.getData(TEXT_TYPE) }
    return if (text.isBlank()) null else ({ text })
  }

  @OptIn(ExperimentalComposeUiApi::class)
  private val DragAndDropEvent.dataTransfer: DataTransfer?
    get() = transferData?.domDataTransferOrNull

  private const val FILES_TYPE = "Files"
  private const val URI_LIST_TYPE = "text/uri-list"
  private const val TEXT_TYPE = "text/plain"
  private val DROP_TYPES = setOf(FILES_TYPE, URI_LIST_TYPE, TEXT_TYPE)
}
