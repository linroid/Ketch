package com.linroid.ketch.app.components

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KetchSpeedChartTest {
  @Test
  fun niceSpeedCeiling_speeds_roundUpToOneTwoOrFiveOfAUnit() {
    assertEquals(10 * MIB, niceSpeedCeiling((9.8 * MIB).toLong()))
    assertEquals(50 * MIB, niceSpeedCeiling((31.2 * MIB).toLong()))
    assertEquals(2 * MIB, niceSpeedCeiling(2 * MIB))
    assertEquals(2 * KIB, niceSpeedCeiling(1500))
  }

  @Test
  fun niceSpeedCeiling_aboveFiveHundredOfAUnit_roundsToOneOfTheNext() {
    assertEquals(MIB, niceSpeedCeiling(700 * KIB))
  }

  @Test
  fun niceSpeedCeiling_idle_keepsAOneKilobyteScale() {
    assertEquals(KIB, niceSpeedCeiling(0))
  }

  @Test
  fun speedChartCeiling_limitAbovePeak_reachesTheLimit() {
    val limits = listOf(SpeedLimitLine(5 * MIB), SpeedLimitLine(2 * MIB))

    assertEquals(5 * MIB, speedChartCeiling(peak = MIB, limits = limits))
  }

  @Test
  fun stackedTotals_bandsOfDifferentLengths_lineUpAtTheLatestSample() {
    val bands = listOf(
      SpeedBand(listOf(1L, 2L, 3L), Color.Black),
      SpeedBand(listOf(10L, 20L), Color.Black),
      SpeedBand(listOf(-5L), Color.Black),
    )

    assertEquals(listOf(1L, 12L, 23L), stackedTotals(bands, slots = 3).toList())
  }

  @Test
  fun stackedTotals_bandLongerThanTheSlots_keepsItsLatestSamples() {
    val bands = listOf(SpeedBand(listOf(1L, 2L, 3L, 4L), Color.Black))

    assertEquals(listOf(3L, 4L), stackedTotals(bands, slots = 2).toList())
  }

  @Test
  fun formatSpeedCeiling_roundValues_dropTheirDecimals() {
    assertEquals("10 MB/s", formatSpeedCeiling(10 * MIB))
    assertEquals("1 GB/s", formatSpeedCeiling(1024 * MIB))
    assertEquals("512 B/s", formatSpeedCeiling(512))
  }

  @Test
  fun slotAt_positions_mapToTheNearestSample() {
    assertEquals(0, slotAt(x = 0f, width = 300f, slots = 61))
    assertEquals(30, slotAt(x = 151f, width = 300f, slots = 61))
    assertEquals(60, slotAt(x = 400f, width = 300f, slots = 61))
    assertNull(slotAt(x = 10f, width = 300f, slots = 1))
  }

  private companion object {
    const val KIB = 1024L
    const val MIB = KIB * KIB
  }
}
