package com.linroid.kdown.engine

import com.linroid.kdown.core.engine.RangeSupportDetector
import com.linroid.kdown.core.engine.ServerInfo
import com.linroid.kdown.api.KDownError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RangeSupportDetectorTest {

  @Test
  fun detect_returnsServerInfo() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 5000,
        acceptRanges = true,
        etag = "\"etag-value\"",
        lastModified = "Mon, 01 Jan 2024 00:00:00 GMT"
      )
    )
    val detector = RangeSupportDetector(engine)
    val info = detector.detect("https://example.com/file")

    assertEquals(5000, info.contentLength)
    assertTrue(info.acceptRanges)
    assertEquals("\"etag-value\"", info.etag)
    assertEquals("Mon, 01 Jan 2024 00:00:00 GMT", info.lastModified)
  }

  @Test
  fun detect_serverWithoutRangeSupport() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 3000,
        acceptRanges = false,
        etag = null,
        lastModified = null
      )
    )
    val detector = RangeSupportDetector(engine)
    val info = detector.detect("https://example.com/file")

    assertFalse(info.acceptRanges)
    assertFalse(info.supportsResume)
  }

  @Test
  fun detect_propagatesNetworkError() = runTest {
    val engine = FakeHttpEngine(failOnHead = true)
    val detector = RangeSupportDetector(engine)

    assertFailsWith<KDownError.Network> {
      detector.detect("https://example.com/file")
    }
  }

  @Test
  fun detect_propagatesHttpError() = runTest {
    val engine = FakeHttpEngine(httpErrorCode = 404)
    val detector = RangeSupportDetector(engine)

    assertFailsWith<KDownError.Http> {
      detector.detect("https://example.com/file")
    }
  }

  @Test
  fun detect_callsHeadOnEngine() = runTest {
    val engine = FakeHttpEngine()
    val detector = RangeSupportDetector(engine)
    detector.detect("https://example.com/file")
    assertEquals(1, engine.headCallCount)
  }

  @Test
  fun detect_passesCustomHeaders() = runTest {
    val engine = FakeHttpEngine()
    val detector = RangeSupportDetector(engine)
    val headers = mapOf("Authorization" to "Bearer token", "X-Custom" to "value")
    detector.detect("https://example.com/file", headers)
    assertEquals(headers, engine.lastHeadHeaders)
  }
}
