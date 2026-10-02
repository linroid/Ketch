package com.linroid.ketch.app.ui.inspector.tabs

import com.linroid.ketch.app.util.clockTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TabFormatTest {
  @Test
  fun formatByteRange_eachUnit_sharesTheUnitOfTheEnd() {
    assertEquals("1.4–2.1 GB", formatByteRange(1_503_238_554, 2_254_857_830))
    assertEquals("0–512 KB", formatByteRange(0, 524_288))
    assertEquals("640–960 MB", formatByteRange(640L shl 20, 960L shl 20))
    assertEquals("0–100 B", formatByteRange(0, 100))
    assertEquals("10.6–11.4 GB", formatByteRange(11_381_663_334, 12_240_656_794))
  }

  @Test
  fun formatSpeed_compactByUnit() {
    assertEquals("0 B/s", formatSpeed(0))
    assertEquals("512 B/s", formatSpeed(512))
    assertEquals("957 KB/s", formatSpeed(980_000))
    assertEquals("6.4 MB/s", formatSpeed(6_710_886))
    assertEquals("39.1 MB/s", formatSpeed(41_000_000))
  }

  @Test
  fun formatSize_roundingUp_movesToTheNextUnitOrAWholeNumber() {
    assertEquals("1.0 MB", formatSize(1_048_100))
    assertEquals("1023 KB", formatSize(1_047_552))
    assertEquals("100 MB", formatSize(104_830_000))
    assertEquals("99.9 MB", formatSize(104_752_742))
    assertEquals("1.0 GB", formatSize((1L shl 30) - 1))
  }

  @Test
  fun formatBytesOf_partOfTotal_usesTheTotalsUnit() {
    assertEquals("3.2 of 7.9 GB", formatBytesOf(3_435_973_837, 8_482_560_410))
    assertEquals("0 of 12.0 MB", formatBytesOf(0, 12L shl 20))
  }

  @Test
  fun formatSize_compactByUnit() {
    assertEquals("512 B", formatSize(512))
    assertEquals("1.2 GB", formatSize(1_288_490_189))
    assertEquals("284 MB", formatSize(284L shl 20))
  }

  @Test
  fun middleEllipsis_tooLong_keepsMoreOfTheEnd() {
    val name = "The.Show.S01E09.1080p.mkv"

    assertEquals(name, middleEllipsis(name) { true })
    assertEquals("The.Show…1E09.1080p.mkv", middleEllipsis(name) { it.length <= 23 })
    assertEquals("…", middleEllipsis(name) { it.length <= 1 })
  }

  @Test
  fun formatStall_secondsThenMinutes() {
    assertEquals("Stalled 6 s", formatStall(6.seconds))
    assertEquals("Stalled 59 s", formatStall(59.seconds))
    assertEquals("Stalled 2 min", formatStall(125.seconds))
  }

  @Test
  fun clockTime_withAndWithoutSeconds() {
    val at = Instant.parse("2026-10-01T09:05:07Z")

    assertEquals("09:05", clockTime(at, TimeZone.UTC))
    assertEquals("09:05:07", clockTime(at, TimeZone.UTC, seconds = true))
  }

  @Test
  fun plural_oneAndMany() {
    assertEquals("1 file", plural(1, "file"))
    assertEquals("14 files", plural(14, "file"))
  }
}
