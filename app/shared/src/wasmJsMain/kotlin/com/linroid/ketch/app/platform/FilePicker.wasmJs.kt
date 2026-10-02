@file:OptIn(ExperimentalWasmJsInterop::class)

package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.unsafeCast

@Composable
actual fun rememberFilePicker(): FilePicker = BrowserFilePicker

/**
 * The browser's file input. Pages cannot choose folders on the device, and downloads run on
 * other devices anyway.
 */
private object BrowserFilePicker : FilePicker {
  override val canPickFolder: Boolean = false

  override suspend fun pickFolder(initialFolder: String?): String? = null

  /** Must run soon after a click or key press, or the browser blocks the dialog. */
  override suspend fun pickTorrentFiles(): List<DroppedFile> =
    suspendCancellableCoroutine { continuation ->
      val input = document.createElement("input").unsafeCast<HTMLInputElement>()
      input.type = "file"
      input.accept = ".torrent,application/x-bittorrent"
      input.multiple = true
      input.style.display = "none"
      val finish = { files: List<DroppedFile> ->
        input.remove()
        if (continuation.isActive) continuation.resume(files)
      }
      input.addEventListener("change", { _: Event ->
        val chosen = input.files
        val count = chosen?.length ?: 0
        finish((0 until count).mapNotNull { chosen?.item(it) }.map { it.toDroppedFile() })
      })
      input.addEventListener("cancel", { _: Event -> finish(emptyList()) })
      continuation.invokeOnCancellation { input.remove() }
      document.body?.appendChild(input)
      input.click()
    }
}

/** [this] browser file as a [DroppedFile], read on demand with its size bounded. */
internal fun File.toDroppedFile(): DroppedFile = DroppedFile(name) { maxBytes ->
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
  Int8Array(buffer).toByteArray()
}
