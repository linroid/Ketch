package com.linroid.kdown

import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRequestTest {

  @Test
  fun defaultConnections_isOne() {
    val request = DownloadRequest(
      url = "https://example.com/file",
      directory = Path("/tmp")
    )
    assertEquals(1, request.connections)
  }

  @Test
  fun defaultFileName_isNull() {
    val request = DownloadRequest(
      url = "https://example.com/file",
      directory = Path("/tmp")
    )
    assertNull(request.fileName)
  }

  @Test
  fun customFileName_preserved() {
    val request = DownloadRequest(
      url = "https://example.com/file",
      directory = Path("/tmp"),
      fileName = "custom.zip"
    )
    assertEquals("custom.zip", request.fileName)
  }

  @Test
  fun blankUrl_throws() {
    assertFailsWith<IllegalArgumentException> {
      DownloadRequest(url = "", directory = Path("/tmp"))
    }
    assertFailsWith<IllegalArgumentException> {
      DownloadRequest(url = "   ", directory = Path("/tmp"))
    }
  }

  @Test
  fun zeroConnections_throws() {
    assertFailsWith<IllegalArgumentException> {
      DownloadRequest(
        url = "https://example.com/file",
        directory = Path("/tmp"),
        connections = 0
      )
    }
  }

  @Test
  fun negativeConnections_throws() {
    assertFailsWith<IllegalArgumentException> {
      DownloadRequest(
        url = "https://example.com/file",
        directory = Path("/tmp"),
        connections = -1
      )
    }
  }

  @Test
  fun defaultHeaders_isEmpty() {
    val request = DownloadRequest(
      url = "https://example.com/file",
      directory = Path("/tmp")
    )
    assertEquals(emptyMap(), request.headers)
  }

  @Test
  fun customHeaders_preserved() {
    val headers = mapOf(
      "Authorization" to "Bearer token123",
      "X-Custom" to "value"
    )
    val request = DownloadRequest(
      url = "https://example.com/file",
      directory = Path("/tmp"),
      headers = headers
    )
    assertEquals(headers, request.headers)
    assertEquals("Bearer token123", request.headers["Authorization"])
    assertEquals("value", request.headers["X-Custom"])
  }

  @Test
  fun defaultSpeedLimit_isUnlimited() {
    val request = DownloadRequest(
      url = "https://example.com/file",
      directory = Path("/tmp")
    )
    assertTrue(request.speedLimit.isUnlimited)
  }

  @Test
  fun customSpeedLimit_preserved() {
    val limit = SpeedLimit.mbps(10)
    val request = DownloadRequest(
      url = "https://example.com/file",
      directory = Path("/tmp"),
      speedLimit = limit
    )
    assertEquals(limit, request.speedLimit)
    assertFalse(request.speedLimit.isUnlimited)
  }

  @Test
  fun serialization_withSpeedLimit_roundTrips() {
    val json = Json { ignoreUnknownKeys = true }
    val request = DownloadRequest(
      url = "https://example.com/file",
      directory = Path("/tmp"),
      speedLimit = SpeedLimit.kbps(512)
    )
    val serialized = json.encodeToString(
      DownloadRequest.serializer(), request
    )
    val deserialized = json.decodeFromString(
      DownloadRequest.serializer(), serialized
    )
    assertEquals(request.speedLimit, deserialized.speedLimit)
    assertEquals(
      512 * 1024L,
      deserialized.speedLimit.bytesPerSecond
    )
  }

  @Test
  fun serialization_withUnlimitedSpeedLimit_roundTrips() {
    val json = Json { ignoreUnknownKeys = true }
    val request = DownloadRequest(
      url = "https://example.com/file",
      directory = Path("/tmp")
    )
    val serialized = json.encodeToString(
      DownloadRequest.serializer(), request
    )
    val deserialized = json.decodeFromString(
      DownloadRequest.serializer(), serialized
    )
    assertTrue(deserialized.speedLimit.isUnlimited)
  }
}
