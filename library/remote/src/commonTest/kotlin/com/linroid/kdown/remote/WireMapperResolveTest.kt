package com.linroid.kdown.remote

import com.linroid.kdown.api.FileSelectionMode
import com.linroid.kdown.api.ResolvedSource
import com.linroid.kdown.api.SourceFile
import com.linroid.kdown.endpoints.model.ResolveUrlResponse
import com.linroid.kdown.endpoints.model.SourceFileResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireMapperResolveTest {

  // -- toResolvedSource --

  @Test
  fun toResolvedSource_mapsAllFields() {
    val wire = ResolveUrlResponse(
      url = "https://example.com/file.zip",
      sourceType = "http",
      totalBytes = 10000,
      supportsResume = true,
      suggestedFileName = "file.zip",
      maxSegments = 8,
      metadata = mapOf(
        "etag" to "\"abc\"",
        "lastModified" to "Wed, 01 Jan 2025",
      ),
    )
    val resolved = WireMapper.toResolvedSource(wire)
    assertEquals("https://example.com/file.zip", resolved.url)
    assertEquals("http", resolved.sourceType)
    assertEquals(10000L, resolved.totalBytes)
    assertTrue(resolved.supportsResume)
    assertEquals("file.zip", resolved.suggestedFileName)
    assertEquals(8, resolved.maxSegments)
    assertEquals("\"abc\"", resolved.metadata["etag"])
    assertEquals(
      "Wed, 01 Jan 2025",
      resolved.metadata["lastModified"],
    )
  }

  @Test
  fun toResolvedSource_nullFileName() {
    val wire = ResolveUrlResponse(
      url = "https://example.com/path",
      sourceType = "http",
      totalBytes = 2048,
      supportsResume = false,
      suggestedFileName = null,
      maxSegments = 1,
    )
    val resolved = WireMapper.toResolvedSource(wire)
    assertNull(resolved.suggestedFileName)
    assertFalse(resolved.supportsResume)
    assertEquals(1, resolved.maxSegments)
  }

  @Test
  fun toResolvedSource_emptyMetadata() {
    val wire = ResolveUrlResponse(
      url = "https://example.com/file",
      sourceType = "magnet",
      totalBytes = 100,
      supportsResume = false,
      maxSegments = 1,
      metadata = emptyMap(),
    )
    val resolved = WireMapper.toResolvedSource(wire)
    assertEquals(emptyMap(), resolved.metadata)
  }

  @Test
  fun toResolvedSource_unknownSize() {
    val wire = ResolveUrlResponse(
      url = "https://example.com/stream",
      sourceType = "http",
      totalBytes = -1,
      supportsResume = false,
      maxSegments = 1,
    )
    val resolved = WireMapper.toResolvedSource(wire)
    assertEquals(-1L, resolved.totalBytes)
  }

  // -- toResolveUrlResponse --

  @Test
  fun toResolveUrlResponse_mapsAllFields() {
    val resolved = ResolvedSource(
      url = "https://example.com/file.zip",
      sourceType = "http",
      totalBytes = 10000,
      supportsResume = true,
      suggestedFileName = "file.zip",
      maxSegments = 8,
      metadata = mapOf(
        "etag" to "\"abc\"",
        "lastModified" to "Wed",
      ),
    )
    val wire = WireMapper.toResolveUrlResponse(resolved)
    assertEquals("https://example.com/file.zip", wire.url)
    assertEquals("http", wire.sourceType)
    assertEquals(10000L, wire.totalBytes)
    assertTrue(wire.supportsResume)
    assertEquals("file.zip", wire.suggestedFileName)
    assertEquals(8, wire.maxSegments)
    assertEquals("\"abc\"", wire.metadata["etag"])
    assertEquals("Wed", wire.metadata["lastModified"])
  }

  @Test
  fun toResolveUrlResponse_nullFileName() {
    val resolved = ResolvedSource(
      url = "https://example.com/path",
      sourceType = "http",
      totalBytes = 500,
      supportsResume = false,
      suggestedFileName = null,
      maxSegments = 1,
    )
    val wire = WireMapper.toResolveUrlResponse(resolved)
    assertNull(wire.suggestedFileName)
  }

  @Test
  fun toResolveUrlResponse_emptyMetadata() {
    val resolved = ResolvedSource(
      url = "https://example.com/file",
      sourceType = "magnet",
      totalBytes = 100,
      supportsResume = false,
      suggestedFileName = null,
      maxSegments = 1,
      metadata = emptyMap(),
    )
    val wire = WireMapper.toResolveUrlResponse(resolved)
    assertEquals(emptyMap(), wire.metadata)
  }

  // -- Round-trip --

  @Test
  fun roundTrip_resolvedUrl_toWire_andBack() {
    val original = ResolvedSource(
      url = "https://example.com/file.zip",
      sourceType = "http",
      totalBytes = 50000,
      supportsResume = true,
      suggestedFileName = "data.csv",
      maxSegments = 4,
      metadata = mapOf(
        "etag" to "\"v2\"",
        "acceptRanges" to "true",
      ),
    )
    val wire = WireMapper.toResolveUrlResponse(original)
    val backToResolved = WireMapper.toResolvedSource(wire)
    assertEquals(original, backToResolved)
  }

  @Test
  fun roundTrip_withNulls() {
    val original = ResolvedSource(
      url = "https://example.com/stream",
      sourceType = "http",
      totalBytes = -1,
      supportsResume = false,
      suggestedFileName = null,
      maxSegments = 1,
    )
    val wire = WireMapper.toResolveUrlResponse(original)
    val backToResolved = WireMapper.toResolvedSource(wire)
    assertEquals(original, backToResolved)
  }

  // -- toCreateWire includes resolvedUrl --

  @Test
  fun toCreateWire_withResolvedSource_includesIt() {
    val resolved = ResolvedSource(
      url = "https://example.com/file.zip",
      sourceType = "http",
      totalBytes = 5000,
      supportsResume = true,
      suggestedFileName = "file.zip",
      maxSegments = 4,
    )
    val request = com.linroid.kdown.api.DownloadRequest(
      url = "https://example.com/file.zip",
      directory = "/tmp",
      resolvedUrl = resolved,
    )
    val wire = WireMapper.toCreateWire(request)
    val wireResolved = wire.resolvedUrl
    assertEquals("https://example.com/file.zip", wireResolved?.url)
    assertEquals("http", wireResolved?.sourceType)
    assertEquals(5000L, wireResolved?.totalBytes)
    assertTrue(wireResolved?.supportsResume == true)
  }

  @Test
  fun toCreateWire_withoutResolvedSource_isNull() {
    val request = com.linroid.kdown.api.DownloadRequest(
      url = "https://example.com/file.zip",
      directory = "/tmp",
    )
    val wire = WireMapper.toCreateWire(request)
    assertNull(wire.resolvedUrl)
  }

  // -- files / selectionMode --

  @Test
  fun toResolvedSource_mapsFiles() {
    val wire = ResolveUrlResponse(
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
          metadata = mapOf("path" to "Pack/Movie.mkv"),
        ),
        SourceFileResponse(id = "f2", name = "Subs.srt", size = 5000),
      ),
      selectionMode = "MULTIPLE",
    )
    val resolved = WireMapper.toResolvedSource(wire)
    assertEquals(2, resolved.files.size)
    assertEquals("f1", resolved.files[0].id)
    assertEquals("Movie.mkv", resolved.files[0].name)
    assertEquals(4000000L, resolved.files[0].size)
    assertEquals("Pack/Movie.mkv", resolved.files[0].metadata["path"])
    assertEquals("f2", resolved.files[1].id)
    assertEquals(FileSelectionMode.MULTIPLE, resolved.selectionMode)
  }

  @Test
  fun toResolvedSource_singleSelectionMode() {
    val wire = ResolveUrlResponse(
      url = "https://example.com/video",
      sourceType = "hls",
      totalBytes = 500000,
      supportsResume = false,
      maxSegments = 1,
      selectionMode = "SINGLE",
      files = listOf(
        SourceFileResponse(id = "720p", name = "720p", size = 300000),
      ),
    )
    val resolved = WireMapper.toResolvedSource(wire)
    assertEquals(FileSelectionMode.SINGLE, resolved.selectionMode)
    assertEquals(1, resolved.files.size)
  }

  @Test
  fun toResolvedSource_unknownSelectionMode_fallsBackToMultiple() {
    val wire = ResolveUrlResponse(
      url = "https://example.com/file",
      sourceType = "http",
      totalBytes = 1000,
      supportsResume = true,
      maxSegments = 1,
      selectionMode = "UNKNOWN_MODE",
    )
    val resolved = WireMapper.toResolvedSource(wire)
    assertEquals(FileSelectionMode.MULTIPLE, resolved.selectionMode)
  }

  @Test
  fun toResolvedSource_emptyFiles() {
    val wire = ResolveUrlResponse(
      url = "https://example.com/file",
      sourceType = "http",
      totalBytes = 1000,
      supportsResume = true,
      maxSegments = 4,
    )
    val resolved = WireMapper.toResolvedSource(wire)
    assertEquals(emptyList(), resolved.files)
  }

  @Test
  fun toResolveUrlResponse_mapsFiles() {
    val resolved = ResolvedSource(
      url = "magnet:?xt=urn:btih:abc",
      sourceType = "torrent",
      totalBytes = 2000000,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 1,
      files = listOf(
        SourceFile(
          id = "f1",
          name = "Movie.mkv",
          size = 2000000,
          metadata = mapOf("path" to "Movie.mkv"),
        ),
      ),
      selectionMode = FileSelectionMode.MULTIPLE,
    )
    val wire = WireMapper.toResolveUrlResponse(resolved)
    assertEquals(1, wire.files.size)
    assertEquals("f1", wire.files[0].id)
    assertEquals("Movie.mkv", wire.files[0].name)
    assertEquals(2000000L, wire.files[0].size)
    assertEquals("MULTIPLE", wire.selectionMode)
  }

  @Test
  fun toResolveUrlResponse_singleMode() {
    val resolved = ResolvedSource(
      url = "https://example.com/video",
      sourceType = "hls",
      totalBytes = 500000,
      supportsResume = false,
      suggestedFileName = null,
      maxSegments = 1,
      selectionMode = FileSelectionMode.SINGLE,
    )
    val wire = WireMapper.toResolveUrlResponse(resolved)
    assertEquals("SINGLE", wire.selectionMode)
  }

  @Test
  fun roundTrip_withFiles_preservesAll() {
    val original = ResolvedSource(
      url = "magnet:?xt=urn:btih:abc",
      sourceType = "torrent",
      totalBytes = 3000000,
      supportsResume = true,
      suggestedFileName = null,
      maxSegments = 1,
      metadata = mapOf("infoHash" to "abc123"),
      files = listOf(
        SourceFile(
          id = "f1",
          name = "Movie.mkv",
          size = 2500000,
          metadata = mapOf("path" to "Pack/Movie.mkv"),
        ),
        SourceFile(id = "f2", name = "Readme.txt", size = 500),
      ),
      selectionMode = FileSelectionMode.MULTIPLE,
    )
    val wire = WireMapper.toResolveUrlResponse(original)
    val backToResolved = WireMapper.toResolvedSource(wire)
    assertEquals(original, backToResolved)
  }

  // -- selectedFileIds --

  @Test
  fun toCreateWire_withSelectedFileIds() {
    val request = com.linroid.kdown.api.DownloadRequest(
      url = "magnet:?xt=urn:btih:abc",
      directory = "/downloads",
      selectedFileIds = setOf("f1", "f3"),
    )
    val wire = WireMapper.toCreateWire(request)
    assertEquals(setOf("f1", "f3"), wire.selectedFileIds)
  }

  @Test
  fun toCreateWire_emptySelectedFileIds() {
    val request = com.linroid.kdown.api.DownloadRequest(
      url = "https://example.com/file",
      directory = "/tmp",
    )
    val wire = WireMapper.toCreateWire(request)
    assertEquals(emptySet(), wire.selectedFileIds)
  }
}
