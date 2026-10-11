package com.linroid.ketch.app.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrafficColorsTest {
  @Test
  fun downloadAndUpload_everyAccentAndTheme_lookApart() = forEachTheme { name, colors ->
    val traffic = colors.traffic
    val distance = deltaE(traffic.download.last(), traffic.upload.last())
    assertTrue(distance >= MIN_DELTA_E, "$name: download and upload are only $distance apart")
  }

  @Test
  fun ramps_everyAccentAndTheme_haveFourStepsGettingStronger() = forEachTheme { name, colors ->
    val traffic = colors.traffic
    for (ramp in listOf(traffic.download, traffic.upload)) {
      assertEquals(KetchTrafficColors.LEVELS, ramp.size, name)
      val distances = ramp.map { deltaE(it, colors.surfaceSunken) }
      assertEquals(distances.sorted(), distances, "$name: the ramp must move away from the track")
    }
  }

  @Test
  fun download_everyAccentAndTheme_isTheDownloadingColor() = forEachTheme { name, colors ->
    assertEquals(colors.status.downloading.color, colors.traffic.download.last(), name)
  }

  @Test
  fun upload_tealGreenAndBlueAccents_switchToPurple() {
    for (accent in KetchAccent.entries) {
      for (colors in listOf(lightKetchColors(accent), darkKetchColors(accent))) {
        val purple = KetchPalette.accentTone(KetchAccent.Purple, colors.isDark).fill
        val expected = when (accent) {
          KetchAccent.Teal, KetchAccent.Green, KetchAccent.Blue -> purple
          else -> colors.status.seeding.color
        }
        assertEquals(expected, colors.traffic.upload.last(), accent.name)
      }
    }
  }

  private fun forEachTheme(block: (String, KetchColors) -> Unit) {
    for (accent in KetchAccent.entries) {
      block("${accent.name} light", lightKetchColors(accent))
      block("${accent.name} dark", darkKetchColors(accent))
    }
  }

  /** CIE76 distance of two opaque colors in CIELAB (D65). */
  private fun deltaE(a: Color, b: Color): Double {
    val x = lab(a)
    val y = lab(b)
    return sqrt((x[0] - y[0]).pow(2) + (x[1] - y[1]).pow(2) + (x[2] - y[2]).pow(2))
  }

  private fun lab(color: Color): DoubleArray {
    fun linear(c: Float): Double {
      val v = c.toDouble()
      return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    val r = linear(color.red)
    val g = linear(color.green)
    val b = linear(color.blue)
    val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
    fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
    return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
  }

  private companion object {
    const val MIN_DELTA_E = 20.0
  }
}
