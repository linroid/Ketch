package com.linroid.ketch.engine

import com.linroid.ketch.core.engine.HttpDownloadSource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceResumeStateTest {

  @Test
  fun buildResumeState_producesValidSourceResumeState() {
    val resumeState = HttpDownloadSource.buildResumeState(
      etag = "\"abc\"",
      lastModified = "Thu, 01 Jan 2024 00:00:00 GMT",
      totalBytes = 4096,
    )
    assertEquals("http", resumeState.sourceType)

    // Verify the data can be deserialized back
    val httpState = Json.decodeFromString<HttpDownloadSource.HttpResumeState>(
      resumeState.data
    )
    assertEquals("\"abc\"", httpState.etag)
    assertEquals("Thu, 01 Jan 2024 00:00:00 GMT", httpState.lastModified)
    assertEquals(4096, httpState.totalBytes)
  }

  @Test
  fun buildResumeState_withNulls() {
    val resumeState = HttpDownloadSource.buildResumeState(
      etag = null,
      lastModified = null,
      totalBytes = 0,
    )
    assertEquals("http", resumeState.sourceType)

    val httpState = Json.decodeFromString<HttpDownloadSource.HttpResumeState>(
      resumeState.data
    )
    assertNull(httpState.etag)
    assertNull(httpState.lastModified)
    assertEquals(0, httpState.totalBytes)
  }
}
