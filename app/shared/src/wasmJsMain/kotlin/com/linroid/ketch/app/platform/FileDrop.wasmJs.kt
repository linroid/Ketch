package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.domDataTransferOrNull
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.w3c.dom.DataTransfer
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
  override fun hasFiles(event: DragAndDropEvent): Boolean {
    // File names are hidden until the drop; only the "Files" type is visible.
    val types = event.dataTransfer?.types ?: return false
    return (0 until types.length).any { types[it]?.toString() == "Files" }
  }

  override fun files(event: DragAndDropEvent): List<DroppedFile> {
    val files = event.dataTransfer?.files ?: return emptyList()
    return (0 until files.length).mapNotNull { files.item(it) }.map { file ->
      DroppedFile(file.name) { maxBytes -> file.read(maxBytes) }
    }
  }

  @OptIn(ExperimentalComposeUiApi::class)
  private val DragAndDropEvent.dataTransfer: DataTransfer?
    get() = transferData?.domDataTransferOrNull
}

private suspend fun File.read(maxBytes: Long): ByteArray {
  if (size.toDouble() > maxBytes) fileTooLarge(name, maxBytes)
  val buffer = suspendCancellableCoroutine { continuation ->
    val reader = FileReader()
    reader.onload = { continuation.resume(reader.result!!.unsafeCast<ArrayBuffer>()) }
    reader.onerror = {
      continuation.resumeWithException(IllegalStateException("Cannot read $name"))
    }
    continuation.invokeOnCancellation { reader.abort() }
    reader.readAsArrayBuffer(this)
  }
  return Int8Array(buffer).toByteArray()
}
