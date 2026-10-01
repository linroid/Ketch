package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardDetectionPatternProbableWebURL
import kotlin.coroutines.resume

@Composable
actual fun rememberSystemClipboard(): SystemClipboard = IosClipboard

/**
 * The general pasteboard. iOS shows a paste banner whenever an app reads it, so [hasLink] asks
 * the system to detect a web link instead, which shows none.
 */
private object IosClipboard : SystemClipboard {
  override val pasteEvents: Flow<String> = emptyFlow()

  override suspend fun hasLink(): Boolean {
    val pasteboard = UIPasteboard.generalPasteboard
    val pattern = UIPasteboardDetectionPatternProbableWebURL ?: return false
    if (!pasteboard.hasStrings && !pasteboard.hasURLs) return false
    return suspendCancellableCoroutine { continuation ->
      pasteboard.detectPatternsForPatterns(setOf(pattern)) { patterns, _ ->
        continuation.resume(patterns?.contains(pattern) == true)
      }
    }
  }

  override suspend fun readText(): String? {
    val pasteboard = UIPasteboard.generalPasteboard
    return pasteboard.string?.ifEmpty { null } ?: pasteboard.URL?.absoluteString
  }

  override suspend fun writeText(text: String) {
    UIPasteboard.generalPasteboard.string = text
  }
}
