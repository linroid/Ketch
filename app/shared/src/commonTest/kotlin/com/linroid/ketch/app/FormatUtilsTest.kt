package com.linroid.ketch.app

import com.linroid.ketch.app.util.extractFilename
import com.linroid.ketch.app.util.formatBytes
import com.linroid.ketch.app.util.formatEta
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatUtilsTest {

  // -----------------------------------------------------------
  // formatBytes
  // -----------------------------------------------------------

  @Test
  fun formatBytes_zero() {
    assertEquals("0 B", formatBytes(0))
  }

  @Test
  fun formatBytes_oneByte() {
    assertEquals("1 B", formatBytes(1))
  }

  @Test
  fun formatBytes_belowOneKB() {
    assertEquals("512 B", formatBytes(512))
    assertEquals("1023 B", formatBytes(1023))
  }

  @Test
  fun formatBytes_exactlyOneKB() {
    assertEquals("1.0 KB", formatBytes(1024))
  }

  @Test
  fun formatBytes_oneAndHalfKB() {
    assertEquals("1.5 KB", formatBytes(1536))
  }

  @Test
  fun formatBytes_largeKB() {
    // 999 KB = 999 * 1024 = 1_022_976
    val result = formatBytes(1_022_976)
    assertEquals("999.0 KB", result)
  }

  @Test
  fun formatBytes_exactlyOneMB() {
    assertEquals("1.0 MB", formatBytes(1_048_576))
  }

  @Test
  fun formatBytes_oneAndHalfMB() {
    assertEquals("1.5 MB", formatBytes(1_572_864))
  }

  @Test
  fun formatBytes_largeMB() {
    // 500 MB
    assertEquals("500.0 MB", formatBytes(524_288_000))
  }

  @Test
  fun formatBytes_exactlyOneGB() {
    assertEquals("1.00 GB", formatBytes(1_073_741_824))
  }

  @Test
  fun formatBytes_oneAndHalfGB() {
    assertEquals("1.50 GB", formatBytes(1_610_612_736))
  }

  @Test
  fun formatBytes_largeGB() {
    // 10 GB
    assertEquals("10.00 GB", formatBytes(10_737_418_240))
  }

  @Test
  fun formatBytes_negativeValue() {
    assertEquals("--", formatBytes(-1))
    assertEquals("--", formatBytes(-100))
    assertEquals("--", formatBytes(Long.MIN_VALUE))
  }

  // -----------------------------------------------------------
  // formatEta
  // -----------------------------------------------------------

  @Test
  fun formatEta_zero() {
    assertEquals("", formatEta(0))
  }

  @Test
  fun formatEta_negative() {
    assertEquals("", formatEta(-1))
    assertEquals("", formatEta(-100))
  }

  @Test
  fun formatEta_oneSecond() {
    assertEquals("1s", formatEta(1))
  }

  @Test
  fun formatEta_59Seconds() {
    assertEquals("59s", formatEta(59))
  }

  @Test
  fun formatEta_exactlyOneMinute() {
    assertEquals("1m 0s", formatEta(60))
  }

  @Test
  fun formatEta_minutesAndSeconds() {
    assertEquals("5m 30s", formatEta(330))
  }

  @Test
  fun formatEta_59Minutes59Seconds() {
    assertEquals("59m 59s", formatEta(3599))
  }

  @Test
  fun formatEta_exactlyOneHour() {
    assertEquals("1h 0m", formatEta(3600))
  }

  @Test
  fun formatEta_oneHourOneMinuteOneSecond() {
    // 3661 = 1h 1m 1s, but format is "Xh Xm" (no seconds)
    assertEquals("1h 1m", formatEta(3661))
  }

  @Test
  fun formatEta_largeValue() {
    // 24 hours = 86400 seconds
    assertEquals("24h 0m", formatEta(86400))
  }

  @Test
  fun formatEta_multipleHoursAndMinutes() {
    // 2h 30m = 9000 seconds
    assertEquals("2h 30m", formatEta(9000))
  }

  // -----------------------------------------------------------
  // extractFilename
  // -----------------------------------------------------------

  @Test
  fun extractFilename_emptyUrl() {
    assertEquals("", extractFilename(""))
  }

  @Test
  fun extractFilename_simpleUrl() {
    assertEquals(
      "file.zip",
      extractFilename("https://example.com/file.zip")
    )
  }

  @Test
  fun extractFilename_urlWithQueryParams() {
    assertEquals(
      "file.zip",
      extractFilename(
        "https://example.com/file.zip?token=abc&v=2"
      )
    )
  }

  @Test
  fun extractFilename_urlWithFragment() {
    assertEquals(
      "file.zip",
      extractFilename("https://example.com/file.zip#section")
    )
  }

  @Test
  fun extractFilename_urlWithQueryAndFragment() {
    assertEquals(
      "file.zip",
      extractFilename(
        "https://example.com/file.zip?v=1#top"
      )
    )
  }

  @Test
  fun extractFilename_urlWithTrailingSlash() {
    assertEquals(
      "downloads",
      extractFilename("https://example.com/downloads/")
    )
  }

  @Test
  fun extractFilename_urlWithDeepPath() {
    assertEquals(
      "archive.tar.gz",
      extractFilename(
        "https://cdn.example.com/a/b/c/archive.tar.gz"
      )
    )
  }

  @Test
  fun extractFilename_urlWithNoPath() {
    // No path component after host, substringAfterLast("/")
    // returns the hostname portion
    assertEquals(
      "example.com",
      extractFilename("https://example.com")
    )
  }

  @Test
  fun extractFilename_urlWithOnlySlash() {
    // Trailing slash is trimmed, falls back to hostname
    assertEquals(
      "example.com",
      extractFilename("https://example.com/")
    )
  }

  @Test
  fun extractFilename_whitespaceUrl() {
    assertEquals("", extractFilename("   "))
  }

  @Test
  fun extractFilename_urlWithSpacePadding() {
    assertEquals(
      "file.zip",
      extractFilename("  https://example.com/file.zip  ")
    )
  }
}
