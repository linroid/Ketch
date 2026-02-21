package com.linroid.kdown.api
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KDownErrorTest {

  @Test
  fun network_isRetryable() {
    assertTrue(KDownError.Network().isRetryable)
  }

  @Test
  fun http500_isRetryable() {
    assertTrue(KDownError.Http(500).isRetryable)
  }

  @Test
  fun http502_isRetryable() {
    assertTrue(KDownError.Http(502).isRetryable)
  }

  @Test
  fun http503_isRetryable() {
    assertTrue(KDownError.Http(503).isRetryable)
  }

  @Test
  fun http599_isRetryable() {
    assertTrue(KDownError.Http(599).isRetryable)
  }

  @Test
  fun http429_isRetryable() {
    assertTrue(KDownError.Http(429).isRetryable)
  }

  @Test
  fun http429_retryAfterSeconds() {
    val error = KDownError.Http(429, "Too Many Requests", 30)
    assertTrue(error.isRetryable)
    assertEquals(30L, error.retryAfterSeconds)
  }

  @Test
  fun http404_isNotRetryable() {
    assertFalse(KDownError.Http(404).isRetryable)
  }

  @Test
  fun http403_isNotRetryable() {
    assertFalse(KDownError.Http(403).isRetryable)
  }

  @Test
  fun http400_isNotRetryable() {
    assertFalse(KDownError.Http(400).isRetryable)
  }

  @Test
  fun disk_isNotRetryable() {
    assertFalse(KDownError.Disk().isRetryable)
  }

  @Test
  fun unsupported_isNotRetryable() {
    assertFalse(KDownError.Unsupported.isRetryable)
  }

  @Test
  fun validationFailed_isNotRetryable() {
    assertFalse(KDownError.ValidationFailed("etag mismatch").isRetryable)
  }

  @Test
  fun canceled_isNotRetryable() {
    assertFalse(KDownError.Canceled.isRetryable)
  }

  @Test
  fun unknown_isNotRetryable() {
    assertFalse(KDownError.Unknown().isRetryable)
  }

  @Test
  fun sourceError_isNotRetryable() {
    assertFalse(KDownError.SourceError("torrent").isRetryable)
  }

  @Test
  fun sourceError_containsSourceType() {
    val error = KDownError.SourceError("torrent")
    assertTrue(error.message!!.contains("torrent"))
  }
}
