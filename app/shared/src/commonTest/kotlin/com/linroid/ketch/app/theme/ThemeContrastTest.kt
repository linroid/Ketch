package com.linroid.ketch.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeContrastTest {
  @Test fun contrastRatio_blackOnWhite_is21() {
    assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
    assertEquals(1f, contrastRatio(Color.White, Color.White), 0.01f)
  }

  @Test fun accent_everyAccentAndTheme_readsOnItsFills() = forEachTheme { colors ->
    assertReadable("onAccent on accent", colors.onAccent, colors.accent)
    assertReadable("accentText on surface", colors.accentText, colors.surface)
    assertReadable("accentText on accentSoft", colors.accentText, colors.accentSoft)
    assertReadable("white on dangerFill", Color.White, colors.dangerFill)
  }

  @Test fun status_everyAccentAndTheme_readsOnSurfacesAndSoftFill() = forEachTheme { colors ->
    val status = colors.status
    val named = mapOf(
      "queued" to status.queued,
      "scheduled" to status.scheduled,
      "paused" to status.paused,
      "completed" to status.completed,
      "failed" to status.failed,
      "seeding" to status.seeding,
    )
    for ((name, color) in named) {
      assertReadable("$name on surface", color.color, colors.surface)
      assertReadable("$name on surfaceSunken", color.color, colors.surfaceSunken)
      assertReadable("$name on its soft fill", color.color, color.soft)
    }
  }

  @Test fun text_everyAccentAndTheme_readsWhereItIsAllowed() = forEachTheme { colors ->
    assertReadable("textTertiary on surface", colors.textTertiary, colors.surface)
    assertReadable("textTertiary on surfaceSunken", colors.textTertiary, colors.surfaceSunken)
    assertReadable("textTertiary on surfaceHover", colors.textTertiary, colors.surfaceHover)
    assertReadable("textSecondary on rowSelected", colors.textSecondary, colors.rowSelected)
    assertReadable(
      "textSecondary on rowSelectedFocused",
      colors.textSecondary,
      colors.rowSelectedFocused,
    )
  }

  @Test fun inverse_everyAccentAndTheme_readsInTooltipsAndToasts() = forEachTheme { colors ->
    assertReadable("inverseOnSurface", colors.inverseOnSurface, colors.inverseSurface)
    assertReadable("inverseAccent", colors.inverseAccent, colors.inverseSurface)
  }

  @Test fun textSecondary_everyAccentAndTheme_readsOnTheWash() = forEachTheme { colors ->
    val wash = colors.wash
    for ((name, stop) in listOf("start" to wash.start, "mid" to wash.mid, "end" to wash.end)) {
      assertReadable("textSecondary on wash.$name", colors.textSecondary, stop)
      val selected = colors.sidebarItemSelected.compositeOver(stop)
      assertReadable("textSecondary on selected over $name", colors.textSecondary, selected)
      val glow = wash.ember.compositeOver(stop)
      assertReadable("textSecondary on ember over $name", colors.textSecondary, glow)
      val selectedOnGlow = colors.sidebarItemSelected.compositeOver(glow)
      val pair = "textSecondary on selected over ember over $name"
      assertReadable(pair, colors.textSecondary, selectedOnGlow)
    }
  }

  @Test fun deviceHues_everyAccentAndTheme_carryWhiteMonograms() = forEachTheme { colors ->
    for (hue in colors.deviceHues) {
      assertReadable("white on the ${hue.name} pennant", Color.White, hue.light)
    }
  }

  @Test fun fileTypeGlyphs_bothThemes_standOutFromTheirTiles() {
    for (colors in listOf(lightKetchColors(), darkKetchColors())) {
      for (hue in FileTypeHue.entries) {
        val glyph = if (colors.isDark) hue.dark else hue.light
        for (base in listOf(colors.canvas, colors.surface)) {
          val tile = glyph.copy(alpha = FileTypeHue.TILE_ALPHA).compositeOver(base)
          val message = "${hue.name} tile contrast in dark=${colors.isDark}"
          assertTrue(contrastRatio(glyph, tile) >= 3f, message)
        }
      }
    }
  }

  private class Theme(val name: String) {
    fun assertReadable(pair: String, text: Color, fill: Color) {
      val ratio = contrastRatio(text, fill)
      assertTrue(ratio >= 4.5f, "$pair is $ratio:1 in $name")
    }
  }

  private fun forEachTheme(block: Theme.(KetchColors) -> Unit) {
    for (accent in KetchAccent.entries) {
      Theme("${accent.name} light").block(lightKetchColors(accent))
      Theme("${accent.name} dark").block(darkKetchColors(accent))
    }
  }
}
