package com.linroid.ketch.app.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

@Composable
actual fun rememberSystemClipboard(): SystemClipboard {
  val context = LocalContext.current
  return remember(context) { AndroidClipboard(context) }
}

/**
 * The Android clipboard. Android 12 and later show a notice whenever an app reads it, so there
 * [hasLink] answers from the system's classification of the clip instead.
 */
private class AndroidClipboard(private val context: Context) : SystemClipboard {
  private val manager = context.getSystemService(ClipboardManager::class.java)

  override val pasteEvents: Flow<String> = emptyFlow()

  override suspend fun hasLink(): Boolean {
    val description = manager?.primaryClipDescription ?: return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      // Clips that were not classified, such as long text, count as no link.
      return description.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE &&
        description.getConfidenceScore(TextClassifier.TYPE_URL) >= LINK_CONFIDENCE
    }
    return readText()?.let(::holdsLink) == true
  }

  override suspend fun readText(): String? = withContext(Dispatchers.IO) {
    val clip = manager?.primaryClip?.takeIf { it.itemCount > 0 } ?: return@withContext null
    clip.getItemAt(0).coerceToText(context)?.toString()?.ifEmpty { null }
  }

  override suspend fun writeText(text: String) {
    val manager = checkNotNull(manager) { "No clipboard" }
    manager.setPrimaryClip(ClipData.newPlainText("Ketch", text))
  }

  private companion object {
    const val LINK_CONFIDENCE = 0.5f
  }
}
