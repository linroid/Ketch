package com.linroid.ketch.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRequestTest {

  @Test
  fun defaultConnections_isZero() {
    val request = DownloadRequest(
      url = "https://example.com/file",
      destination = Destination("/tmp/"),
    )
    assertEquals(0, request.connections)
  }

  @Test
  fun defaultDestination_isNull() {
    val request = DownloadRequest(
      url = "https://example.com/file",
    )
    assertNull(request.destination)
  }

  @Test
  fun destination_preserved() {
    val request = DownloadRequest(
      url = "https://example.com/file",
      destination = Destination("custom.zip"),
    )
    assertEquals(Destination("custom.zip"), request.destination)
  }

  @Test
  fun blankUrl_throws() {
    assertFailsWith<IllegalArgumentException> {
      DownloadRequest(
        url = "",
        destination = Destination("/tmp/"),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      DownloadRequest(
        url = "   ",
        destination = Destination("/tmp/"),
      )
    }
  }

  @Test
  fun zeroConnections_meansUseConfigDefault() {
    val request = DownloadRequest(
      url = "https://example.com/file",
      destination = Destination("/tmp/"),
      connections = 0,
    )
    assertEquals(0, request.connections)
  }

  @Test
  fun negativeConnections_throws() {
    assertFailsWith<IllegalArgumentException> {
      DownloadRequest(
        url = "https://example.com/file",
        destination = Destination("/tmp/"),
        connections = -1,
      )
    }
  }

  @Test
  fun defaultHeaders_isEmpty() {
    val request = DownloadRequest(
      url = "https://example.com/file",
      destination = Destination("/tmp/"),
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
      destination = Destination("/tmp/"),
      headers = headers,
    )
    assertEquals(headers, request.headers)
    assertEquals("Bearer token123", request.headers["Authorization"])
    assertEquals("value", request.headers["X-Custom"])
  }

  @Test
  fun defaultSpeedLimit_isUnlimited() {
    val request = DownloadRequest(
      url = "https://example.com/file",
      destination = Destination("/tmp/"),
    )
    assertTrue(request.speedLimit.isUnlimited)
  }

  @Test
  fun customSpeedLimit_preserved() {
    val limit = SpeedLimit.mbps(10)
    val request = DownloadRequest(
      url = "https://example.com/file",
      destination = Destination("/tmp/"),
      speedLimit = limit,
    )
    assertEquals(limit, request.speedLimit)
    assertFalse(request.speedLimit.isUnlimited)
  }

  @Test
  fun serialization_withSpeedLimit_roundTrips() {
    val json = Json { ignoreUnknownKeys = true }
    val request = DownloadRequest(
      url = "https://example.com/file",
      destination = Destination("/tmp/"),
      speedLimit = SpeedLimit.kbps(512),
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
      destination = Destination("/tmp/"),
    )
    val serialized = json.encodeToString(
      DownloadRequest.serializer(), request
    )
    val deserialized = json.decodeFromString(
      DownloadRequest.serializer(), serialized
    )
    assertTrue(deserialized.speedLimit.isUnlimited)
  }

  // -- selectedFileIds --

  @Test
  fun defaultSelectedFileIds_isEmpty() {
    val request = DownloadRequest(
      url = "https://example.com/file",
    )
    assertEquals(emptySet(), request.selectedFileIds)
  }

  @Test
  fun selectedFileIds_preserved() {
    val ids = setOf("f1", "f3")
    val request = DownloadRequest(
      url = "https://example.com/file",
      selectedFileIds = ids,
    )
    assertEquals(ids, request.selectedFileIds)
  }

  @Test
  fun serialization_withSelectedFileIds_roundTrips() {
    val json = Json { ignoreUnknownKeys = true }
    val request = DownloadRequest(
      url = "https://example.com/file",
      selectedFileIds = setOf("a", "b", "c"),
    )
    val serialized = json.encodeToString(
      DownloadRequest.serializer(), request,
    )
    val deserialized = json.decodeFromString(
      DownloadRequest.serializer(), serialized,
    )
    assertEquals(
      setOf("a", "b", "c"),
      deserialized.selectedFileIds,
    )
  }

  @Test
  fun serialization_emptySelectedFileIds_roundTrips() {
    val json = Json { ignoreUnknownKeys = true }
    val request = DownloadRequest(
      url = "https://example.com/file",
    )
    val serialized = json.encodeToString(
      DownloadRequest.serializer(), request,
    )
    val deserialized = json.decodeFromString(
      DownloadRequest.serializer(), serialized,
    )
    assertEquals(emptySet(), deserialized.selectedFileIds)
  }
}
