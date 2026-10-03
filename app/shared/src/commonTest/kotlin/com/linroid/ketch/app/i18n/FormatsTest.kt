package com.linroid.ketch.app.i18n

import com.linroid.ketch.api.DownloadPriority
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FormatsTest {
  @Test
  fun sizeText_eachUnit_roundsToItsDecimals() = runTest {
    assertEquals("--", sizeText(-1).load())
    assertEquals("0 B", sizeText(0).load())
    assertEquals("1023 B", sizeText(1023).load())
    assertEquals("1.0 KB", sizeText(1024).load())
    assertEquals("1.5 KB", sizeText(1536).load())
    assertEquals("1.0 MB", sizeText(1024L * 1024).load())
    assertEquals("2.41 GB", sizeText((2.41 * (1L shl 30)).toLong() + 1).load())
    assertEquals("2048.00 GB", sizeText(2L shl 40).load())
  }

  @Test
  fun speedText_negative_readsZero() = runTest {
    assertEquals("0 B/s", speedText(-5).load())
    assertEquals("1.5 MB/s", speedText(3L shl 19).load())
  }

  @Test
  fun decimal_roundsHalfUp() {
    assertEquals("1.25", decimal(1.245, 2))
    assertEquals("10.0", decimal(9.96, 1))
    assertEquals("3", decimal(2.5, 0))
  }

  @Test
  fun etaText_twoLargestUnits() = runTest {
    assertEquals("", etaText(0).load())
    assertEquals("9s", etaText(9).load())
    assertEquals("4m 2s", etaText(242).load())
    assertEquals("1h 5m", etaText(3_930).load())
  }

  @Test
  fun durationText_underASecond_readsLessThanOne() = runTest {
    assertEquals("<1s", durationText(400.milliseconds).load())
    assertEquals("2m 0s", durationText(2.minutes).load())
  }

  @Test
  fun spanText_roundsUpToTheMinute() = runTest {
    assertEquals("45s", spanText(45.seconds).load())
    assertEquals("2 min", spanText(61.seconds).load())
    assertEquals("3h", spanText(3.hours).load())
    assertEquals("3h 12m", spanText(3.hours + 11.minutes + 1.seconds).load())
    assertEquals("2d", spanText(2.days).load())
    assertEquals("2d 4h", spanText(2.days + 4.hours).load())
  }

  @Test
  fun shortDateText_otherYear_addsTheYear() = runTest {
    val today = LocalDate(2026, 10, 1)
    assertEquals("Sep 28", shortDateText(LocalDate(2026, 9, 28), today).load())
    assertEquals("Sep 28, 2025", shortDateText(LocalDate(2025, 9, 28), today).load())
  }

  @Test
  fun priorityText_namesEachPriority() = runTest {
    assertEquals(
      listOf("Low", "Normal", "High", "Urgent"),
      DownloadPriority.entries.map { priorityText(it).load() },
    )
  }

  @Test
  fun joinText_skipsTheSeparatorForOnePart() = runTest {
    assertEquals(UiText.Empty, emptyList<UiText>().joinText())
    assertEquals("a", listOf(verbatim("a")).joinText().load())
    assertEquals("a · b", listOf(verbatim("a"), verbatim("b")).joinText().load())
  }
}
