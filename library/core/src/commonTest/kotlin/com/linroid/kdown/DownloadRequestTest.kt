package com.linroid.kdown

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DownloadRequestTest {

  @Test
  fun defaultConnections_isFour() {
    val request = DownloadRequest(
      url = "https://example.com/file",
      directory = Path("/tmp")
    )
    assertEquals(4, request.connections)
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
}
