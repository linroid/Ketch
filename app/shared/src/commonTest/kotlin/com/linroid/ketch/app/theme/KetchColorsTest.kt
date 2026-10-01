package com.linroid.ketch.app.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KetchColorsTest {
  @Test fun deviceHue_knownIds_pickTheSameHueOnEveryPlatform() {
    val colors = lightKetchColors()
    assertEquals(FileTypeHue.Lime, colors.deviceHue("local"))
    assertEquals(FileTypeHue.Slate, colors.deviceHue("nas.local"))
    assertEquals(FileTypeHue.Sky, colors.deviceHue(""))
  }

  @Test fun deviceHue_negativeHash_staysInRange() {
    assertEquals(FileTypeHue.Magenta, darkKetchColors().deviceHue("laptop"))
  }

  @Test fun lanes_everyAccentAndTheme_areEightOpaqueStepsFromTheAccent() {
    for (accent in KetchAccent.entries) {
      for (colors in listOf(lightKetchColors(accent), darkKetchColors(accent))) {
        assertEquals(8, colors.lanes.size)
        assertEquals(colors.accent, colors.lanes.first())
        assertEquals(8, colors.lanes.toSet().size)
        assertTrue(colors.lanes.all { it.alpha == 1f })
      }
    }
  }

  @Test fun laneRamp_pausedColor_startsAtThePausedColor() {
    val colors = darkKetchColors()
    val paused = colors.laneRamp(colors.status.paused.color)
    assertEquals(colors.status.paused.color, paused.first())
    assertTrue(paused.none { it in colors.lanes })
  }

  @Test fun inverseAccent_everyAccent_isTheOtherThemesAccentText() {
    for (accent in KetchAccent.entries) {
      assertEquals(darkKetchColors(accent).accentText, lightKetchColors(accent).inverseAccent)
      assertEquals(lightKetchColors(accent).accentText, darkKetchColors(accent).inverseAccent)
    }
  }
}
