package com.linroid.ketch.ai.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceSafetyFilterTest {

  private val filter = DeviceSafetyFilter()

  @Test
  fun evaluate_httpsOfficialDomain_highScore() {
    val result = filter.evaluate(
      url = "https://github.com/user/repo/releases/v1.0.zip",
    )
    assertFalse(result.blocked)
    assertTrue(result.score > 0.8f)
  }

  @Test
  fun evaluate_httpUrl_penalized() {
    val https = filter.evaluate(
      url = "https://example.com/file.zip",
    )
    val http = filter.evaluate(
      url = "http://example.com/file.zip",
    )
    assertTrue(https.score > http.score)
  }

  @Test
  fun evaluate_urlShortener_blocked() {
    val result = filter.evaluate(url = "https://bit.ly/abc123")
    assertTrue(result.blocked)
  }

  @Test
  fun evaluate_aggregatorDomain_blocked() {
    val result = filter.evaluate(
      url = "https://freedownload-site.com/file.zip",
    )
    assertTrue(result.blocked)
  }

  @Test
  fun evaluate_highRiskExtFromTrusted_allowed() {
    val result = filter.evaluate(
      url = "https://github.com/app/releases/setup.exe",
    )
    assertFalse(result.blocked)
  }

  @Test
  fun evaluate_highRiskExtFromUntrusted_blocked() {
    val result = filter.evaluate(
      url = "https://random-site.xyz/setup.exe",
    )
    assertTrue(result.blocked)
  }

  @Test
  fun evaluate_piracySignalInContext_blocked() {
    val result = filter.evaluate(
      url = "https://example.com/software.zip",
      context = "Download crack and keygen included",
    )
    assertTrue(result.blocked)
  }

  @Test
  fun evaluate_piracySignalInUrl_blocked() {
    val result = filter.evaluate(
      url = "https://example.com/keygen-v2.zip",
    )
    assertTrue(result.blocked)
  }

  @Test
  fun evaluate_contentTypeMismatch_penalized() {
    val matched = filter.evaluate(
      url = "https://example.com/doc.pdf",
      contentType = "application/pdf",
    )
    val mismatched = filter.evaluate(
      url = "https://example.com/doc.pdf",
      contentType = "text/html",
    )
    assertTrue(matched.score > mismatched.score)
  }

  @Test
  fun evaluate_cleanPdfFromOfficial_highScore() {
    val result = filter.evaluate(
      url = "https://downloads.apache.org/docs/guide.pdf",
      contentType = "application/pdf",
    )
    assertFalse(result.blocked)
    assertTrue(result.score > 0.8f)
  }
}
