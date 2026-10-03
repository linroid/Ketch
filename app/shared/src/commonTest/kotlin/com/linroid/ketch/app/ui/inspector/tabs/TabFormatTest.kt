package com.linroid.ketch.app.ui.inspector.tabs

import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.util.clockTime
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TabFormatTest {
  @Test
  fun byteRangeText_eachUnit_sharesTheUnitOfTheEnd() = runTest {
    assertEquals("1.4–2.1 GB", byteRangeText(1_503_238_554, 2_254_857_830).load())
    assertEquals("0–512 KB", byteRangeText(0, 524_288).load())
    assertEquals("640–960 MB", byteRangeText(640L shl 20, 960L shl 20).load())
    assertEquals("0–100 B", byteRangeText(0, 100).load())
    assertEquals("10.6–11.4 GB", byteRangeText(11_381_663_334, 12_240_656_794).load())
  }

  @Test
  fun compactSpeedText_compactByUnit() = runTest {
    assertEquals("0 B/s", compactSpeedText(0).load())
    assertEquals("512 B/s", compactSpeedText(512).load())
    assertEquals("957 KB/s", compactSpeedText(980_000).load())
    assertEquals("6.4 MB/s", compactSpeedText(6_710_886).load())
    assertEquals("39.1 MB/s", compactSpeedText(41_000_000).load())
  }

  @Test
  fun compactSizeText_roundingUp_movesToTheNextUnitOrAWholeNumber() = runTest {
    assertEquals("1.0 MB", compactSizeText(1_048_100).load())
    assertEquals("1023 KB", compactSizeText(1_047_552).load())
    assertEquals("100 MB", compactSizeText(104_830_000).load())
    assertEquals("99.9 MB", compactSizeText(104_752_742).load())
    assertEquals("1.0 GB", compactSizeText((1L shl 30) - 1).load())
  }

  @Test
  fun bytesOfText_partOfTotal_usesTheTotalsUnit() = runTest {
    assertEquals("3.2 of 7.9 GB", bytesOfText(3_435_973_837, 8_482_560_410).load())
    assertEquals("0 of 12.0 MB", bytesOfText(0, 12L shl 20).load())
  }

  @Test
  fun sameByteUnit_byTheUnitEachReadsIn() {
    assertTrue(sameByteUnit(2_576_980_378, 6_120_328_397))
    assertFalse(sameByteUnit(497_025_024, 1_342_177_280))
  }

  @Test
  fun compactSizeText_compactByUnit() = runTest {
    assertEquals("512 B", compactSizeText(512).load())
    assertEquals("1.2 GB", compactSizeText(1_288_490_189).load())
    assertEquals("284 MB", compactSizeText(284L shl 20).load())
  }

  @Test
  fun middleEllipsis_tooLong_keepsMoreOfTheEnd() {
    val name = "The.Show.S01E09.1080p.mkv"

    assertEquals(name, middleEllipsis(name) { true })
    assertEquals("The.Show…1E09.1080p.mkv", middleEllipsis(name) { it.length <= 23 })
    assertEquals("…", middleEllipsis(name) { it.length <= 1 })
  }

  @Test
  fun stallText_secondsThenMinutes() = runTest {
    assertEquals("Stalled 6 s", stallText(6.seconds).load())
    assertEquals("Stalled 59 s", stallText(59.seconds).load())
    assertEquals("Stalled 2 min", stallText(125.seconds).load())
  }

  @Test
  fun clockTime_withAndWithoutSeconds() {
    val at = Instant.parse("2026-10-01T09:05:07Z")

    assertEquals("09:05", clockTime(at, TimeZone.UTC))
    assertEquals("09:05:07", clockTime(at, TimeZone.UTC, seconds = true))
  }
}
