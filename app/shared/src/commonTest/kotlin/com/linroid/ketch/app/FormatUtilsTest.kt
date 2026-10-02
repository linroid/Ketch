package com.linroid.ketch.app

import com.linroid.ketch.app.util.extractFilename
import com.linroid.ketch.app.util.formatBytes
import com.linroid.ketch.app.util.formatEta
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatUtilsTest {

  @Test
  fun formatBytes_eachRange_usesItsUnit() {
    val cases = listOf(
      0L to "0 B",
      1L to "1 B",
      512L to "512 B",
      1023L to "1023 B",
      1024L to "1.0 KB",
      1536L to "1.5 KB",
      1_022_976L to "999.0 KB",
      1_048_576L to "1.0 MB",
      1_572_864L to "1.5 MB",
      524_288_000L to "500.0 MB",
      1_073_741_824L to "1.00 GB",
      1_610_612_736L to "1.50 GB",
      10_737_418_240L to "10.00 GB",
    )
    for ((bytes, expected) in cases) {
      assertEquals(expected, formatBytes(bytes), "formatBytes($bytes)")
    }
  }

  @Test
  fun formatBytes_negativeValue_isUnknown() {
    for (bytes in listOf(-1L, -100L, Long.MIN_VALUE)) {
      assertEquals("--", formatBytes(bytes), "formatBytes($bytes)")
    }
  }

  @Test
  fun formatEta_eachRange_usesItsUnits() {
    val cases = listOf(
      1L to "1s",
      59L to "59s",
      60L to "1m 0s",
      330L to "5m 30s",
      3599L to "59m 59s",
      3600L to "1h 0m",
      // Hours leave the seconds out.
      3661L to "1h 1m",
      86400L to "24h 0m",
      9000L to "2h 30m",
    )
    for ((seconds, expected) in cases) {
      assertEquals(expected, formatEta(seconds), "formatEta($seconds)")
    }
  }

  @Test
  fun formatEta_notPositive_isEmpty() {
    for (seconds in listOf(0L, -1L, -100L)) {
      assertEquals("", formatEta(seconds), "formatEta($seconds)")
    }
  }

  @Test
  fun extractFilename_url_isItsLastPathSegment() {
    val cases = listOf(
      "https://example.com/file.zip" to "file.zip",
      "https://example.com/file.zip?token=abc&v=2" to "file.zip",
      "https://example.com/file.zip#section" to "file.zip",
      "https://example.com/file.zip?v=1#top" to "file.zip",
      "https://example.com/downloads/" to "downloads",
      "https://cdn.example.com/a/b/c/archive.tar.gz" to "archive.tar.gz",
      "  https://example.com/file.zip  " to "file.zip",
      // Without a path, with or without a trailing slash, the host is all there is.
      "https://example.com" to "example.com",
      "https://example.com/" to "example.com",
    )
    for ((url, expected) in cases) {
      assertEquals(expected, extractFilename(url), "extractFilename(\"$url\")")
    }
  }

  @Test
  fun extractFilename_blank_isEmpty() {
    for (url in listOf("", "   ")) {
      assertEquals("", extractFilename(url), "extractFilename(\"$url\")")
    }
  }
}
