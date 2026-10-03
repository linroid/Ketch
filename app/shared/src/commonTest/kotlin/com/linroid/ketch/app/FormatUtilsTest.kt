package com.linroid.ketch.app

import com.linroid.ketch.app.util.extractFilename
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatUtilsTest {

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
