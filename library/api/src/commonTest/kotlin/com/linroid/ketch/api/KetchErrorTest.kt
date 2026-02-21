package com.linroid.ketch.api
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KetchErrorTest {

  @Test
  fun network_isRetryable() {
    assertTrue(KetchError.Network().isRetryable)
  }

  @Test
  fun http500_isRetryable() {
    assertTrue(KetchError.Http(500).isRetryable)
  }

  @Test
  fun http502_isRetryable() {
    assertTrue(KetchError.Http(502).isRetryable)
  }

  @Test
  fun http503_isRetryable() {
    assertTrue(KetchError.Http(503).isRetryable)
  }

  @Test
  fun http599_isRetryable() {
    assertTrue(KetchError.Http(599).isRetryable)
  }

  @Test
  fun http429_isRetryable() {
    assertTrue(KetchError.Http(429).isRetryable)
  }

  @Test
  fun http429_retryAfterSeconds() {
    val error = KetchError.Http(429, "Too Many Requests", 30)
    assertTrue(error.isRetryable)
    assertEquals(30L, error.retryAfterSeconds)
  }

  @Test
  fun http404_isNotRetryable() {
    assertFalse(KetchError.Http(404).isRetryable)
  }

  @Test
  fun http403_isNotRetryable() {
    assertFalse(KetchError.Http(403).isRetryable)
  }

  @Test
  fun http400_isNotRetryable() {
    assertFalse(KetchError.Http(400).isRetryable)
  }

  @Test
  fun disk_isNotRetryable() {
    assertFalse(KetchError.Disk().isRetryable)
  }

  @Test
  fun unsupported_isNotRetryable() {
    assertFalse(KetchError.Unsupported.isRetryable)
  }

  @Test
  fun validationFailed_isNotRetryable() {
    assertFalse(KetchError.ValidationFailed("etag mismatch").isRetryable)
  }

  @Test
  fun canceled_isNotRetryable() {
    assertFalse(KetchError.Canceled.isRetryable)
  }

  @Test
  fun unknown_isNotRetryable() {
    assertFalse(KetchError.Unknown().isRetryable)
  }

  @Test
  fun sourceError_isNotRetryable() {
    assertFalse(KetchError.SourceError("torrent").isRetryable)
  }

  @Test
  fun sourceError_containsSourceType() {
    val error = KetchError.SourceError("torrent")
    assertTrue(error.message!!.contains("torrent"))
  }
}
