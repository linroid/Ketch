@file:OptIn(ExperimentalWasmJsInterop::class)

package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.clipboard.ClipboardEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget
import kotlin.js.ExperimentalWasmJsInterop

@Composable
actual fun rememberSystemClipboard(): SystemClipboard = BrowserClipboard

/**
 * The browser clipboard. Reading it asks for permission, so [hasLink] cannot peek and paste
 * shortcuts take their text from [pasteEvents] instead.
 */
private object BrowserClipboard : SystemClipboard {
  private val log = KetchLogger("Clipboard")

  override val readsSilently: Boolean = false

  override val pasteEvents: Flow<String> = callbackFlow {
    val listener: (Event) -> Unit = { event ->
      // Text fields handle their own pastes.
      if (!isEditable(event.target)) {
        val text = event.unsafeCast<ClipboardEvent>().clipboardData?.getData("text/plain")
        if (!text.isNullOrBlank()) trySend(text)
      }
    }
    document.addEventListener("paste", listener)
    awaitClose { document.removeEventListener("paste", listener) }
  }

  override suspend fun hasLink(): Boolean = false

  override suspend fun readText(): String? {
    if (!hasClipboardApi()) return null
    return try {
      window.navigator.clipboard.readText().await<JsString>().toString().ifEmpty { null }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      // The user denied the permission, or the browser lets only extensions read.
      log.d { "Couldn't read the clipboard: ${e.describeCauses()}" }
      null
    }
  }

  override suspend fun writeText(text: String) {
    if (hasClipboardApi()) {
      window.navigator.clipboard.writeText(text).await<JsAny?>()
    } else {
      copyThroughSelection(text)
    }
  }

  /** Copies without the Clipboard API, which pages served over plain HTTP do not have. */
  private fun copyThroughSelection(text: String) {
    val focused = document.activeElement
    val area = document.createElement("textarea").unsafeCast<HTMLTextAreaElement>()
    area.value = text
    area.style.position = "fixed"
    area.style.opacity = "0"
    document.body?.appendChild(area)
    try {
      area.select()
      check(document.execCommand("copy")) { "The browser refused to copy" }
    } finally {
      area.remove()
      focused?.unsafeCast<HTMLElement>()?.focus()
    }
  }
}

/** Whether the async Clipboard API exists; browsers offer it to HTTPS and localhost pages. */
private fun hasClipboardApi(): Boolean = js("typeof navigator.clipboard !== 'undefined'")

private fun isEditable(target: EventTarget?): Boolean =
  js("!!target && (target.isContentEditable || /^(INPUT|TEXTAREA)$/.test(target.tagName))")
