package com.linroid.ketch.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

class ThemeContrastTest {
  @Test fun primaryAndSecondaryTextRemainReadableInBothThemes() {
    for (colors in listOf(lightKetchColors(), darkKetchColors())) {
      for (text in listOf(colors.onBackground, colors.onSurfaceVariant, colors.onSurfaceDim)) {
        assertTrue(contrast(text, colors.surface) >= 4.5f, "Text contrast in dark=${colors.isDark}")
      }
      val buttonText = if (colors.isDark) colors.background else Color.White
      assertTrue(contrast(buttonText, colors.primary) >= 4.5f, "Primary button contrast")
    }
  }

  private fun contrast(a: Color, b: Color): Float {
    val x = a.luminance()
    val y = b.luminance()
    return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
  }
}
