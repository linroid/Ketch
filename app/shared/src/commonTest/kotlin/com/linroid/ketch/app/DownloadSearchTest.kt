package com.linroid.ketch.app

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.app.util.matchesSearch
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadSearchTest {
  private val request = DownloadRequest("https://example.com/assets.zip", Destination("Design package.zip"))

  @Test fun blankSearchIncludesDownloads() { assertTrue(request.matchesSearch("  ")) }
  @Test fun matchesRenamedFileIgnoringCaseAndWhitespace() { assertTrue(request.matchesSearch(" DESIGN PACKAGE ")) }
  @Test fun matchesOriginalUrl() { assertTrue(request.matchesSearch("ASSETS.ZIP")) }
  @Test fun excludesUnrelatedFiles() { assertFalse(request.matchesSearch("holiday")) }
  @Test fun searchesDownloadsWithoutDestination() {
    assertTrue(DownloadRequest("https://example.com/image.png").matchesSearch("image"))
    assertFalse(DownloadRequest("https://example.com/image.png").matchesSearch("archive"))
  }
}
