package com.linroid.ketch.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.RangeSupportDetector
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.TransferStalledException
import com.linroid.ketch.core.engine.TransferWatchdog
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RangeSupportDetectorTest {

  @Test
  fun detect_returnsServerInfo() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 5000,
        acceptRanges = true,
        etag = "\"etag-value\"",
        lastModified = "Mon, 01 Jan 2024 00:00:00 GMT",
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
  fun detect_serverNeverAnswering_failsRetryablyOnceBothRequestsWaitedTheTimeout() = runTest {
    val engine = SilentEngine(probeAnswers = false)

    val error = assertFailsWith<KetchError.Network> {
      RangeSupportDetector(engine, TransferWatchdog(30.seconds, 0)).detect("https://example.com/file")
    }

    assertIs<TransferStalledException>(error.cause)
    assertEquals(60_000, currentTime)
    assertEquals(listOf("HEAD", "GET"), engine.requests)
  }

  @Test
  fun detect_headNeverAnswered_probesWithAGet() = runTest {
    val engine = SilentEngine(probeAnswers = true)

    val info = RangeSupportDetector(engine, TransferWatchdog(30.seconds, 0)).detect("https://example.com/file")

    assertEquals(1000, info.contentLength)
    assertEquals(listOf("HEAD", "GET"), engine.requests)
  }

  /** Never answers `HEAD`, and a probe only with [probeAnswers]. */
  private class SilentEngine(private val probeAnswers: Boolean) : HttpEngine {
    val requests = mutableListOf<String>()

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
      requests += "HEAD"
      awaitCancellation()
    }

    override suspend fun probe(url: String, headers: Map<String, String>): ServerInfo {
      requests += "GET"
      if (!probeAnswers) awaitCancellation()
      return ServerInfo(contentLength = 1000, acceptRanges = true, etag = null, lastModified = null)
    }

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) = error("unused")

    override fun close() = Unit
  }

  @Test
  fun detect_serverWithoutRangeSupport() = runTest {
    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(
        contentLength = 3000,
        acceptRanges = false,
        etag = null,
        lastModified = null,
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

    assertFailsWith<KetchError.Network> {
      detector.detect("https://example.com/file")
    }
    // The ranged GET was tried as well.
    assertEquals(1, engine.probeCallCount)
  }

  @Test
  fun detect_headDroppedOnTheNetwork_probesWithGet() = runTest {
    // As Hetzner's speed test servers answer HEAD: an empty reply, while GET works.
    val engine = FakeHttpEngine(failOnHead = true, failOnProbe = false)

    val info = RangeSupportDetector(engine).detect("https://example.com/file")

    assertEquals(engine.serverInfo, info)
    assertEquals(1, engine.probeCallCount)
  }

  @Test
  fun detect_propagatesHttpError() = runTest {
    val engine = FakeHttpEngine(httpErrorCode = 404)
    val detector = RangeSupportDetector(engine)

    assertFailsWith<KetchError.Http> {
      detector.detect("https://example.com/file")
    }
  }

  @Test
  fun detect_headRefused_probesWithGet() = runTest {
    for (code in listOf(400, 403, 404, 405, 500, 501)) {
      val engine = FakeHttpEngine(headErrorCode = code)
      val headers = mapOf("Cookie" to "sid=1")

      val info = RangeSupportDetector(engine).detect("https://example.com/file", headers)

      assertEquals(engine.serverInfo, info, "HEAD $code")
      assertEquals(1, engine.probeCallCount, "HEAD $code")
      assertEquals(headers, engine.lastProbeHeaders, "HEAD $code")
    }
  }

  @Test
  fun detect_headFailsWithServerError_doesNotProbe() = runTest {
    val engine = FakeHttpEngine(headErrorCode = 503)

    val error = assertFailsWith<KetchError.Http> {
      RangeSupportDetector(engine).detect("https://example.com/file")
    }

    assertEquals(503, error.code)
    assertEquals(0, engine.probeCallCount)
  }

  @Test
  fun detect_headRefusedByEngineWithoutProbe_throwsHeadError() = runTest {
    val engine = object : HttpEngine {
      override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
        throw KetchError.Http(405, "Method Not Allowed")

      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) = error("Not used")

      override fun close() {}
    }

    val error = assertFailsWith<KetchError.Http> {
      RangeSupportDetector(engine).detect("https://example.com/file")
    }

    assertEquals(405, error.code)
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
