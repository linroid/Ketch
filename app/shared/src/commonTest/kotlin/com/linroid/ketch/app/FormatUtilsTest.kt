package com.linroid.ketch.app

import com.linroid.ketch.app.util.extractFilename
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatUtilsTest {

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
