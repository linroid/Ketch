package com.linroid.kdown.endpoints.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResolveModelsTest {

  private val json = Json { ignoreUnknownKeys = true }

  // -- ResolveUrlRequest --

  @Test
  fun resolveUrlRequest_serialization_roundTrip() {
    val original = ResolveUrlRequest(
      url = "https://example.com/file.zip",
      headers = mapOf("Authorization" to "Bearer token"),
    )
    val serialized = json.encodeToString(
      ResolveUrlRequest.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolveUrlRequest.serializer(), serialized,
    )
    assertEquals(original, deserialized)
  }

  @Test
  fun resolveUrlRequest_defaultHeaders_empty() {
    val original = ResolveUrlRequest(
      url = "https://example.com/file",
    )
    assertEquals(emptyMap(), original.headers)
  }

  @Test
  fun resolveUrlRequest_serialization_withEmptyHeaders() {
    val original = ResolveUrlRequest(
      url = "https://example.com/file",
      headers = emptyMap(),
    )
    val serialized = json.encodeToString(
      ResolveUrlRequest.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolveUrlRequest.serializer(), serialized,
    )
    assertEquals(original, deserialized)
  }

  // -- ResolveUrlResponse --

  @Test
  fun resolveUrlResponse_serialization_allFields() {
    val original = ResolveUrlResponse(
      url = "https://example.com/file.zip",
      sourceType = "http",
      totalBytes = 1024000,
      supportsResume = true,
      suggestedFileName = "file.zip",
      maxSegments = 8,
      metadata = mapOf(
        "etag" to "\"abc\"",
        "lastModified" to "Wed",
      ),
    )
    val serialized = json.encodeToString(
      ResolveUrlResponse.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolveUrlResponse.serializer(), serialized,
    )
    assertEquals(original, deserialized)
  }

  @Test
  fun resolveUrlResponse_serialization_nullFileName() {
    val original = ResolveUrlResponse(
      url = "https://example.com/path",
      sourceType = "http",
      totalBytes = 2048,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 4,
    )
    val serialized = json.encodeToString(
      ResolveUrlResponse.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolveUrlResponse.serializer(), serialized,
    )
    assertNull(deserialized.suggestedFileName)
    assertEquals(original, deserialized)
  }

  @Test
  fun resolveUrlResponse_serialization_unknownSize() {
    val original = ResolveUrlResponse(
      url = "https://example.com/stream",
      sourceType = "http",
      totalBytes = -1,
      supportsResume = false,
      maxSegments = 1,
    )
    val serialized = json.encodeToString(
      ResolveUrlResponse.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolveUrlResponse.serializer(), serialized,
    )
    assertEquals(-1L, deserialized.totalBytes)
  }

  @Test
  fun resolveUrlResponse_serialization_emptyMetadata() {
    val original = ResolveUrlResponse(
      url = "https://example.com/file",
      sourceType = "magnet",
      totalBytes = 100,
      supportsResume = false,
      maxSegments = 1,
      metadata = emptyMap(),
    )
    val serialized = json.encodeToString(
      ResolveUrlResponse.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolveUrlResponse.serializer(), serialized,
    )
    assertEquals(emptyMap(), deserialized.metadata)
  }

  // -- CreateDownloadRequest with resolvedUrl --

  @Test
  fun createDownloadRequest_serialization_withResolvedSource() {
    val resolvedUrl = ResolveUrlResponse(
      url = "https://example.com/file.zip",
      sourceType = "http",
      totalBytes = 5000,
      supportsResume = true,
      suggestedFileName = "file.zip",
      maxSegments = 4,
      metadata = mapOf("etag" to "\"v1\""),
    )
    val original = CreateDownloadRequest(
      url = "https://example.com/file.zip",
      directory = "/tmp",
      resolvedUrl = resolvedUrl,
    )
    val serialized = json.encodeToString(
      CreateDownloadRequest.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      CreateDownloadRequest.serializer(), serialized,
    )
    assertEquals(original, deserialized)
    assertEquals(resolvedUrl, deserialized.resolvedUrl)
  }

  @Test
  fun createDownloadRequest_serialization_nullResolvedSource() {
    val original = CreateDownloadRequest(
      url = "https://example.com/file.zip",
      directory = "/tmp",
    )
    val serialized = json.encodeToString(
      CreateDownloadRequest.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      CreateDownloadRequest.serializer(), serialized,
    )
    assertNull(deserialized.resolvedUrl)
  }

  @Test
  fun createDownloadRequest_defaultResolvedSource_isNull() {
    val request = CreateDownloadRequest(
      url = "https://example.com/file",
      directory = "/tmp",
    )
    assertNull(request.resolvedUrl)
  }

  // -- SourceFileResponse --

  @Test
  fun sourceFileResponse_serialization_roundTrip() {
    val original = SourceFileResponse(
      id = "f1",
      name = "Movie.mkv",
      size = 2000000,
      metadata = mapOf("path" to "Pack/Movie.mkv"),
    )
    val serialized = json.encodeToString(
      SourceFileResponse.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      SourceFileResponse.serializer(), serialized,
    )
    assertEquals(original, deserialized)
  }

  @Test
  fun sourceFileResponse_serialization_defaults() {
    val original = SourceFileResponse(
      id = "track-1",
      name = "1080p",
    )
    val serialized = json.encodeToString(
      SourceFileResponse.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      SourceFileResponse.serializer(), serialized,
    )
    assertEquals(-1L, deserialized.size)
    assertEquals(emptyMap(), deserialized.metadata)
  }

  // -- ResolveUrlResponse with files --

  @Test
  fun resolveUrlResponse_serialization_withFiles() {
    val original = ResolveUrlResponse(
      url = "magnet:?xt=urn:btih:abc",
      sourceType = "torrent",
      totalBytes = 5000000,
      supportsResume = true,
      maxSegments = 1,
      files = listOf(
        SourceFileResponse(
          id = "f1",
          name = "Movie.mkv",
          size = 4000000,
        ),
        SourceFileResponse(
          id = "f2",
          name = "Subs.srt",
          size = 5000,
        ),
      ),
      selectionMode = "MULTIPLE",
    )
    val serialized = json.encodeToString(
      ResolveUrlResponse.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      ResolveUrlResponse.serializer(), serialized,
    )
    assertEquals(original, deserialized)
    assertEquals(2, deserialized.files.size)
  }

  @Test
  fun resolveUrlResponse_defaultFiles_isEmpty() {
    val response = ResolveUrlResponse(
      url = "https://example.com/file",
      sourceType = "http",
      totalBytes = 1000,
      supportsResume = true,
      maxSegments = 4,
    )
    assertEquals(emptyList(), response.files)
    assertEquals("MULTIPLE", response.selectionMode)
  }

  @Test
  fun resolveUrlResponse_singleSelectionMode() {
    val response = ResolveUrlResponse(
      url = "https://example.com/video",
      sourceType = "hls",
      totalBytes = 500000,
      supportsResume = false,
      maxSegments = 1,
      selectionMode = "SINGLE",
      files = listOf(
        SourceFileResponse(
          id = "720p",
          name = "720p",
          size = 300000,
        ),
      ),
    )
    val serialized = json.encodeToString(
      ResolveUrlResponse.serializer(), response,
    )
    val deserialized = json.decodeFromString(
      ResolveUrlResponse.serializer(), serialized,
    )
    assertEquals("SINGLE", deserialized.selectionMode)
  }

  // -- CreateDownloadRequest with selectedFileIds --

  @Test
  fun createDownloadRequest_serialization_withSelectedFileIds() {
    val original = CreateDownloadRequest(
      url = "magnet:?xt=urn:btih:abc",
      directory = "/downloads",
      selectedFileIds = setOf("f1", "f3"),
    )
    val serialized = json.encodeToString(
      CreateDownloadRequest.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      CreateDownloadRequest.serializer(), serialized,
    )
    assertEquals(setOf("f1", "f3"), deserialized.selectedFileIds)
  }

  @Test
  fun createDownloadRequest_defaultSelectedFileIds_isEmpty() {
    val request = CreateDownloadRequest(
      url = "https://example.com/file",
      directory = "/tmp",
    )
    assertEquals(emptySet(), request.selectedFileIds)
  }
}
