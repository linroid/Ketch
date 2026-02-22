package com.linroid.ketch.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadRequestTest {

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
}
