package com.linroid.ketch.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
  fun httpMessage_withoutStatusMessage_omitsNull() {
    assertEquals("HTTP error 403", KetchError.Http(403).message)
  }

  @Test
  fun httpMessage_withStatusMessage_appendsIt() {
    assertEquals("HTTP error 404: Not Found", KetchError.Http(404, "Not Found").message)
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
    assertFalse(KetchError.Unsupported().isRetryable)
  }

  @Test
  fun fileChanged_isNotRetryable() {
    assertFalse(
      KetchError.FileChanged("etag mismatch").isRetryable,
    )
  }

  @Test
  fun corruptResumeState_isNotRetryable() {
    assertFalse(KetchError.CorruptResumeState().isRetryable)
  }

  @Test
  fun canceled_isNotRetryable() {
    assertFalse(KetchError.Canceled().isRetryable)
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

  @Test
  fun authenticationFailed_isNotRetryable() {
    assertFalse(
      KetchError.AuthenticationFailed("ftp").isRetryable
    )
  }

  @Test
  fun authenticationFailed_containsSourceType() {
    val error = KetchError.AuthenticationFailed("ftp")
    assertTrue(error.message!!.contains("ftp"))
  }

  @Test
  fun serializedForm_isUnchanged() {
    assertEquals(
      """{"type":"network","message":"Network error occurred"}""",
      Json.encodeToString(KetchError.tolerantSerializer(), KetchError.Network()),
    )
    assertEquals(
      Json.encodeToString(KetchError.serializer(), KetchError.Http(404, "Not Found")),
      Json.encodeToString(KetchError.tolerantSerializer(), KetchError.Http(404, "Not Found")),
    )
  }

  @Test
  fun tolerantSerializer_readsKnownErrors() {
    val error =
      KetchError.ChecksumMismatch(ChecksumAlgorithm.SHA_256, "a".repeat(64), "b".repeat(64))
    val text = Json.encodeToString(KetchError.serializer(), error)
    assertEquals(error, Json.decodeFromString(KetchError.tolerantSerializer(), text))
  }

  @Test
  fun tolerantSerializer_readsUnknownTypeAsUnknown() {
    val text = """{"type":"quota_exceeded","message":"Disk quota exceeded","limit":5}"""
    val error = Json.decodeFromString(KetchError.tolerantSerializer(), text)
    assertIs<KetchError.Unknown>(error)
    assertEquals("Disk quota exceeded", error.message)
  }

  @Test
  fun tolerantSerializer_readsUndecodableKnownTypeAsUnknown() {
    val error = Json.decodeFromString(KetchError.tolerantSerializer(), """{"type":"http"}""")
    assertIs<KetchError.Unknown>(error)
  }

  @Test
  fun failedState_withUnknownError_decodes() {
    val text = """{"type":"failed","error":{"type":"quota_exceeded","message":"Over quota"}}"""
    val state = Json.decodeFromString(DownloadState.serializer(), text)
    val error = assertIs<KetchError.Unknown>(assertIs<DownloadState.Failed>(state).error)
    assertEquals("Over quota", error.message)
  }

  @Test
  fun failedState_roundTrips() {
    val state = DownloadState.Failed(KetchError.SourceError("ftp", detail = "Refused"))
    val text = Json.encodeToString(DownloadState.serializer(), state)
    assertEquals(state, Json.decodeFromString(DownloadState.serializer(), text))
  }
}
