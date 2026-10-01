package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

@Composable
actual fun rememberSystemClipboard(): SystemClipboard = AwtClipboard

/** The AWT system clipboard. Desktops show no notice when it is read. */
internal object AwtClipboard : SystemClipboard {
  private val log = KetchLogger("Clipboard")

  override val pasteEvents: Flow<String> = emptyFlow()

  override suspend fun hasLink(): Boolean = withContext(Dispatchers.IO) {
    val clipboard = systemClipboard() ?: return@withContext false
    // Only text can hold a link; checking the flavor first skips reading images and files.
    available(clipboard) && clipboard.text()?.let(::holdsLink) == true
  }

  override suspend fun readText(): String? = withContext(Dispatchers.IO) {
    systemClipboard()?.text()
  }

  override suspend fun writeText(text: String) = withContext(Dispatchers.IO) {
    val clipboard = systemClipboard() ?: throw IllegalStateException("No clipboard")
    clipboard.setContents(StringSelection(text), null)
  }

  private fun systemClipboard(): Clipboard? = try {
    Toolkit.getDefaultToolkit().systemClipboard
  } catch (e: HeadlessException) {
    log.d { "No system clipboard: ${e.describeCauses()}" }
    null
  }

  private fun available(clipboard: Clipboard): Boolean = try {
    clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
  } catch (e: IllegalStateException) {
    log.d { "Clipboard busy: ${e.describeCauses()}" }
    false
  }

  /** The text on [this] clipboard; `null` when there is none or another app holds it. */
  private fun Clipboard.text(): String? = try {
    (getData(DataFlavor.stringFlavor) as? String)?.ifEmpty { null }
  } catch (e: Exception) {
    // Another app may hold the clipboard, or the content may have changed since the check.
    log.d { "Couldn't read the clipboard: ${e.describeCauses()}" }
    null
  }
}
