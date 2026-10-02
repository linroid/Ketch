package com.linroid.ketch.app.theme

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont

// Fonts are fetched asynchronously; until then `Font()` returns a fallback. Preloading caches
// them, so the theme's `Font()` calls get the real fonts on their first frame.
@OptIn(ExperimentalResourceApi::class)
@Composable
internal actual fun rememberFontsPreloaded(fonts: List<KetchFont>): Boolean {
  var loaded = true
  for (font in fonts) {
    if (preloadFont(font.resource, font.weight).value == null) loaded = false
  }
  return loaded
}
