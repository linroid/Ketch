package com.linroid.ketch.app.theme

import com.linroid.ketch.remote.ConnectionState
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

  @Test fun healthColor_eachConnectionState_usesItsStatusColor() {
    for (colors in listOf(lightKetchColors(), darkKetchColors())) {
      val status = colors.status
      assertEquals(status.completed.color, colors.healthColor(null))
      assertEquals(status.completed.color, colors.healthColor(ConnectionState.Connected))
      assertEquals(status.paused.color, colors.healthColor(ConnectionState.Connecting))
      assertEquals(status.failed.color, colors.healthColor(ConnectionState.Disconnected()))
      assertEquals(status.failed.color, colors.healthColor(ConnectionState.Unauthorized))
    }
  }

  @Test fun inverseAccent_everyAccent_isTheOtherThemesAccentText() {
    for (accent in KetchAccent.entries) {
      assertEquals(darkKetchColors(accent).accentText, lightKetchColors(accent).inverseAccent)
      assertEquals(lightKetchColors(accent).accentText, darkKetchColors(accent).inverseAccent)
    }
  }
}
