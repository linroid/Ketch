package com.linroid.kdown.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolvedSourceTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun serialization_roundTrip_allFields() {
    val original = ResolvedSource(
      url = "https://example.com/file.zip",
      sourceType = "http",
      totalBytes = 1024000,
      supportsResume = true,
      suggestedFileName = "file.zip",
      maxSegments = 8,
      metadata = mapOf("etag" to "\"abc\"", "lastModified" to "Wed"),
    )
    val serialized = json.encodeToString(
      ResolvedSource.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolvedSource.serializer(), serialized,
    )
    assertEquals(original, deserialized)
  }

  @Test
  fun serialization_roundTrip_defaultMetadata() {
    val original = ResolvedSource(
      url = "https://example.com/file.zip",
      sourceType = "http",
      totalBytes = 500,
      supportsResume = false,
      suggestedFileName = "file.zip",
      maxSegments = 1,
    )
    val serialized = json.encodeToString(
      ResolvedSource.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolvedSource.serializer(), serialized,
    )
    assertEquals(original, deserialized)
    assertEquals(emptyMap(), deserialized.metadata)
  }

  @Test
  fun serialization_roundTrip_nullSuggestedFileName() {
    val original = ResolvedSource(
      url = "https://example.com/path",
      sourceType = "http",
      totalBytes = 2048,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 4,
    )
    val serialized = json.encodeToString(
      ResolvedSource.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolvedSource.serializer(), serialized,
    )
    assertEquals(original, deserialized)
    assertNull(deserialized.suggestedFileName)
  }

  @Test
  fun serialization_roundTrip_unknownSize() {
    val original = ResolvedSource(
      url = "https://example.com/stream",
      sourceType = "http",
      totalBytes = -1,
      supportsResume = false,
      suggestedFileName = null,
      maxSegments = 1,
    )
    val serialized = json.encodeToString(
      ResolvedSource.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolvedSource.serializer(), serialized,
    )
    assertEquals(-1L, deserialized.totalBytes)
  }

  @Test
  fun serialization_roundTrip_emptyMetadata() {
    val original = ResolvedSource(
      url = "https://example.com/file",
      sourceType = "magnet",
      totalBytes = 100,
      supportsResume = false,
      suggestedFileName = null,
      maxSegments = 1,
      metadata = emptyMap(),
    )
    val serialized = json.encodeToString(
      ResolvedSource.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolvedSource.serializer(), serialized,
    )
    assertEquals(emptyMap(), deserialized.metadata)
  }

  @Test
  fun equality_sameFields() {
    val a = ResolvedSource(
      url = "https://example.com/f",
      sourceType = "http",
      totalBytes = 100,
      supportsResume = true,
      suggestedFileName = "f",
      maxSegments = 4,
    )
    val b = ResolvedSource(
      url = "https://example.com/f",
      sourceType = "http",
      totalBytes = 100,
      supportsResume = true,
      suggestedFileName = "f",
      maxSegments = 4,
    )
    assertEquals(a, b)
  }

  @Test
  fun equality_differentUrl_notEqual() {
    val a = ResolvedSource(
      url = "https://example.com/a",
      sourceType = "http",
      totalBytes = 100,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 1,
    )
    val b = ResolvedSource(
      url = "https://example.com/b",
      sourceType = "http",
      totalBytes = 100,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 1,
    )
    assertFalse(a == b)
  }

  @Test
  fun equality_differentMetadata_notEqual() {
    val a = ResolvedSource(
      url = "https://example.com/f",
      sourceType = "http",
      totalBytes = 100,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 1,
      metadata = mapOf("key" to "val1"),
    )
    val b = ResolvedSource(
      url = "https://example.com/f",
      sourceType = "http",
      totalBytes = 100,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 1,
      metadata = mapOf("key" to "val2"),
    )
    assertFalse(a == b)
  }

  @Test
  fun maxSegments_one_noRangeSupport() {
    val resolved = ResolvedSource(
      url = "https://example.com/file",
      sourceType = "http",
      totalBytes = 5000,
      supportsResume = false,
      suggestedFileName = null,
      maxSegments = 1,
    )
    assertEquals(1, resolved.maxSegments)
    assertFalse(resolved.supportsResume)
  }

  @Test
  fun nonHttpSource_preservesSourceType() {
    val resolved = ResolvedSource(
      url = "magnet:?xt=urn:btih:abc123",
      sourceType = "magnet",
      totalBytes = 50000,
      supportsResume = false,
      suggestedFileName = null,
      maxSegments = 1,
    )
    assertEquals("magnet", resolved.sourceType)
  }

  @Test
  fun serialization_preservesMetadataKeyOrder() {
    val metadata = mapOf(
      "etag" to "\"v1\"",
      "lastModified" to "2025-01-01",
      "acceptRanges" to "true",
    )
    val original = ResolvedSource(
      url = "https://example.com/file",
      sourceType = "http",
      totalBytes = 1000,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 4,
      metadata = metadata,
    )
    val serialized = json.encodeToString(
      ResolvedSource.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolvedSource.serializer(), serialized,
    )
    assertEquals(metadata, deserialized.metadata)
    assertTrue(deserialized.metadata.containsKey("etag"))
    assertTrue(deserialized.metadata.containsKey("lastModified"))
    assertTrue(deserialized.metadata.containsKey("acceptRanges"))
  }

  @Test
  fun suggestedFileName_fromContentDisposition() {
    val resolved = ResolvedSource(
      url = "https://example.com/download?id=123",
      sourceType = "http",
      totalBytes = 2048,
      supportsResume = true,
      suggestedFileName = "report.pdf",
      maxSegments = 4,
    )
    assertEquals("report.pdf", resolved.suggestedFileName)
  }

  // -- files / selectionMode --

  @Test
  fun defaultFiles_isEmpty() {
    val resolved = ResolvedSource(
      url = "https://example.com/file",
      sourceType = "http",
      totalBytes = 1000,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 1,
    )
    assertEquals(emptyList(), resolved.files)
  }

  @Test
  fun defaultSelectionMode_isMultiple() {
    val resolved = ResolvedSource(
      url = "https://example.com/file",
      sourceType = "http",
      totalBytes = 1000,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 1,
    )
    assertEquals(FileSelectionMode.MULTIPLE, resolved.selectionMode)
  }

  @Test
  fun serialization_roundTrip_withFiles() {
    val files = listOf(
      SourceFile(
        id = "f1",
        name = "Movie.mkv",
        size = 2000000,
        metadata = mapOf("path" to "Pack/Movie.mkv"),
      ),
      SourceFile(id = "f2", name = "Subs.srt", size = 5000),
    )
    val original = ResolvedSource(
      url = "magnet:?xt=urn:btih:abc",
      sourceType = "torrent",
      totalBytes = 2005000,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 1,
      files = files,
      selectionMode = FileSelectionMode.MULTIPLE,
    )
    val serialized = json.encodeToString(
      ResolvedSource.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolvedSource.serializer(), serialized,
    )
    assertEquals(original, deserialized)
    assertEquals(2, deserialized.files.size)
    assertEquals("f1", deserialized.files[0].id)
    assertEquals("f2", deserialized.files[1].id)
  }

  @Test
  fun serialization_roundTrip_singleSelectionMode() {
    val original = ResolvedSource(
      url = "https://example.com/video",
      sourceType = "media",
      totalBytes = 500000,
      supportsResume = false,
      suggestedFileName = "video.mp4",
      maxSegments = 1,
      files = listOf(
        SourceFile(
          id = "720p",
          name = "720p MP4",
          size = 300000,
          metadata = mapOf("quality" to "720p"),
        ),
        SourceFile(
          id = "1080p",
          name = "1080p MP4",
          size = 500000,
          metadata = mapOf("quality" to "1080p"),
        ),
      ),
      selectionMode = FileSelectionMode.SINGLE,
    )
    val serialized = json.encodeToString(
      ResolvedSource.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolvedSource.serializer(), serialized,
    )
    assertEquals(original, deserialized)
    assertEquals(FileSelectionMode.SINGLE, deserialized.selectionMode)
  }

  @Test
  fun backwardCompat_oldJsonWithoutFiles_deserializes() {
    val oldJson = """
      {
        "url": "https://example.com/file",
        "sourceType": "http",
        "totalBytes": 1000,
        "supportsResume": true,
        "suggestedFileName": null,
        "maxSegments": 1
      }
    """.trimIndent()
    val deserialized = json.decodeFromString(
      ResolvedSource.serializer(), oldJson,
    )
    assertEquals(emptyList(), deserialized.files)
    assertEquals(
      FileSelectionMode.MULTIPLE,
      deserialized.selectionMode,
    )
  }
}
