package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalWindowInfo
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val log = KetchLogger("Accessibility")

private val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

/** The macOS setting as last read; `null` until the first read. */
@Volatile
private var lastReduceMotion: Boolean? = null

@Composable
actual fun rememberReduceMotion(): Boolean {
  if (!isMac) return false
  val focused = LocalWindowInfo.current.isWindowFocused
  return produceState(lastReduceMotion ?: false, focused) {
    if (focused || lastReduceMotion == null) {
      value = withContext(Dispatchers.IO) { readMacReduceMotion() }
      lastReduceMotion = value
    }
  }.value
}

private fun readMacReduceMotion(): Boolean {
  return try {
    val process = ProcessBuilder("defaults", "read", "com.apple.universalaccess", "reduceMotion")
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    // The key is missing, and `defaults` fails, until the user changes the setting.
    process.waitFor() == 0 && output == "1"
  } catch (e: Exception) {
    log.d { "Could not read the macOS Reduce motion setting: ${e.describeCauses()}" }
    false
  }
}
