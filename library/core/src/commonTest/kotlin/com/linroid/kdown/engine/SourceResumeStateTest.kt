package com.linroid.kdown.engine

import com.linroid.kdown.core.engine.HttpDownloadSource
import com.linroid.kdown.core.engine.SourceResumeState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceResumeStateTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun serialization_roundTrips() {
    val state = SourceResumeState(
      sourceType = "http",
      data = """{"etag":"abc","totalBytes":1000}"""
    )
    val serialized = json.encodeToString(
      SourceResumeState.serializer(), state
    )
    val deserialized = json.decodeFromString(
      SourceResumeState.serializer(), serialized
    )
    assertEquals(state, deserialized)
  }

  @Test
  fun serialization_withEmptyData() {
    val state = SourceResumeState(sourceType = "torrent", data = "")
    val serialized = json.encodeToString(
      SourceResumeState.serializer(), state
    )
    val deserialized = json.decodeFromString(
      SourceResumeState.serializer(), serialized
    )
    assertEquals(state, deserialized)
  }

  @Test
  fun httpResumeState_serialization_roundTrips() {
    val state = HttpDownloadSource.HttpResumeState(
      etag = "\"abc123\"",
      lastModified = "Wed, 21 Oct 2023 07:28:00 GMT",
      totalBytes = 2048
    )
    val serialized = Json.encodeToString(
      HttpDownloadSource.HttpResumeState.serializer(), state
    )
    val deserialized = Json.decodeFromString(
      HttpDownloadSource.HttpResumeState.serializer(), serialized
    )
    assertEquals(state, deserialized)
  }

  @Test
  fun httpResumeState_serialization_withNullFields() {
    val state = HttpDownloadSource.HttpResumeState(
      etag = null,
      lastModified = null,
      totalBytes = 500
    )
    val serialized = Json.encodeToString(
      HttpDownloadSource.HttpResumeState.serializer(), state
    )
    val deserialized = Json.decodeFromString(
      HttpDownloadSource.HttpResumeState.serializer(), serialized
    )
    assertEquals(state, deserialized)
    assertNull(deserialized.etag)
    assertNull(deserialized.lastModified)
  }

  @Test
  fun httpResumeState_serialization_onlyEtag() {
    val state = HttpDownloadSource.HttpResumeState(
      etag = "\"etag-only\"",
      lastModified = null,
      totalBytes = 1024
    )
    val serialized = Json.encodeToString(
      HttpDownloadSource.HttpResumeState.serializer(), state
    )
    val deserialized = Json.decodeFromString(
      HttpDownloadSource.HttpResumeState.serializer(), serialized
    )
    assertEquals("\"etag-only\"", deserialized.etag)
    assertNull(deserialized.lastModified)
    assertEquals(1024, deserialized.totalBytes)
  }

  @Test
  fun buildResumeState_producesValidSourceResumeState() {
    val resumeState = HttpDownloadSource.buildResumeState(
      etag = "\"abc\"",
      lastModified = "Thu, 01 Jan 2024 00:00:00 GMT",
      totalBytes = 4096
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
      totalBytes = 0
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
