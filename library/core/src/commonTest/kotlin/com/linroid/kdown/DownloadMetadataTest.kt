package com.linroid.kdown

import com.linroid.kdown.model.DownloadMetadata
import com.linroid.kdown.model.Segment
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadMetadataTest {

  private val json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
  }

  private fun createMetadata(
    segments: List<Segment> = listOf(
      Segment(index = 0, start = 0, end = 499, downloadedBytes = 0),
      Segment(index = 1, start = 500, end = 999, downloadedBytes = 0)
    ),
    etag: String? = "\"abc123\"",
    lastModified: String? = "Wed, 21 Oct 2023 07:28:00 GMT"
  ) = DownloadMetadata(
    taskId = "test-task-1",
    url = "https://example.com/file.bin",
    destPath = Path("/tmp/file.bin"),
    totalBytes = 1000,
    acceptRanges = true,
    etag = etag,
    lastModified = lastModified,
    segments = segments,
    createdAt = 1000L,
    updatedAt = 2000L
  )

  @Test
  fun serializationRoundTrip() {
    val original = createMetadata()
    val serialized = json.encodeToString(DownloadMetadata.serializer(), original)
    val deserialized = json.decodeFromString<DownloadMetadata>(serialized)
    assertEquals(original, deserialized)
  }

  @Test
  fun serializationRoundTrip_withNullEtag() {
    val original = createMetadata(etag = null, lastModified = null)
    val serialized = json.encodeToString(DownloadMetadata.serializer(), original)
    val deserialized = json.decodeFromString<DownloadMetadata>(serialized)
    assertEquals(original, deserialized)
  }

  @Test
  fun deserialization_ignoresUnknownKeys() {
    val jsonStr = """
      {
        "taskId": "t1",
        "url": "https://example.com/f",
        "destPath": "/tmp/f",
        "totalBytes": 100,
        "acceptRanges": false,
        "etag": null,
        "lastModified": null,
        "segments": [],
        "createdAt": 0,
        "updatedAt": 0,
        "unknownField": "should be ignored"
      }
    """.trimIndent()
    val metadata = json.decodeFromString<DownloadMetadata>(jsonStr)
    assertEquals("t1", metadata.taskId)
  }

  @Test
  fun downloadedBytes_sumsSegments() {
    val metadata = createMetadata(
      segments = listOf(
        Segment(index = 0, start = 0, end = 499, downloadedBytes = 200),
        Segment(index = 1, start = 500, end = 999, downloadedBytes = 300)
      )
    )
    assertEquals(500, metadata.downloadedBytes)
  }

  @Test
  fun downloadedBytes_zeroWhenNoProgress() {
    val metadata = createMetadata()
    assertEquals(0, metadata.downloadedBytes)
  }

  @Test
  fun isComplete_whenAllSegmentsComplete() {
    val metadata = createMetadata(
      segments = listOf(
        Segment(index = 0, start = 0, end = 499, downloadedBytes = 500),
        Segment(index = 1, start = 500, end = 999, downloadedBytes = 500)
      )
    )
    assertTrue(metadata.isComplete)
  }

  @Test
  fun isComplete_falseWhenPartial() {
    val metadata = createMetadata(
      segments = listOf(
        Segment(index = 0, start = 0, end = 499, downloadedBytes = 500),
        Segment(index = 1, start = 500, end = 999, downloadedBytes = 100)
      )
    )
    assertFalse(metadata.isComplete)
  }

  @Test
  fun withUpdatedSegment_updatesCorrectSegment() {
    val metadata = createMetadata()
    val updated = metadata.withUpdatedSegment(segmentIndex = 0, downloadedBytes = 250, currentTime = 3000L)
    assertEquals(250, updated.segments[0].downloadedBytes)
    assertEquals(0, updated.segments[1].downloadedBytes)
    assertEquals(3000L, updated.updatedAt)
  }

  @Test
  fun withUpdatedSegment_doesNotMutateOriginal() {
    val metadata = createMetadata()
    metadata.withUpdatedSegment(segmentIndex = 0, downloadedBytes = 250, currentTime = 3000L)
    assertEquals(0, metadata.segments[0].downloadedBytes)
    assertEquals(2000L, metadata.updatedAt)
  }
}
