package com.linroid.ketch.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KtorHttpEngineTest {
  @Test
  fun download_ignoredPartialRange_rejectsBeforeDeliveringData() = runTest {
    withResponse(HttpStatusCode.OK, "abcdefgh", "Content-Length" to "8") { engine ->
      var delivered = false
      assertFailsWith<KetchError.Unsupported> {
        engine.download("https://example.com/file", 4L..7L) { delivered = true }
      }
      assertTrue(!delivered)
    }
  }

  @Test
  fun download_ignoredFirstSegment_rejectsBeforeDeliveringData() = runTest {
    withResponse(HttpStatusCode.OK, "abcdefgh", "Content-Length" to "8") { engine ->
      var delivered = false
      assertFailsWith<KetchError.Unsupported> {
        engine.download("https://example.com/file", 0L..3L) { delivered = true }
      }
      assertTrue(!delivered)
    }
  }

  @Test
  fun download_fullFileWithoutRangeSupport_succeeds() = runTest {
    withResponse(HttpStatusCode.OK, "abcdefgh", "Content-Length" to "8") { engine ->
      var body = ""
      engine.download("https://example.com/file", 0L..7L) { body += it.decodeToString() }
      assertEquals("abcdefgh", body)
    }
  }

  @Test
  fun download_chunkedFullResponse_validatesActualLength() = runTest {
    val chunked = "Transfer-Encoding" to "chunked"
    withResponse(HttpStatusCode.OK, "abcdefgh", chunked) { engine ->
      var body = ""
      engine.download("https://example.com/file", 0L..7L) { body += it.decodeToString() }
      assertEquals("abcdefgh", body)
    }
    withResponse(HttpStatusCode.OK, "abcd", chunked) { engine ->
      assertFailsWith<KetchError.Network> {
        engine.download("https://example.com/file", 0L..7L) {}
      }
    }
    withResponse(HttpStatusCode.OK, "abcdefghij", chunked) { engine ->
      var delivered = 0
      assertFailsWith<KetchError.Unsupported> {
        engine.download("https://example.com/file", 0L..7L) { delivered += it.size }
      }
      assertTrue(delivered <= 8)
    }
    withResponse(HttpStatusCode.OK, "abcdefgh", chunked) { engine ->
      var delivered = false
      assertFailsWith<KetchError.Unsupported> {
        engine.download("https://example.com/file", 4L..7L) { delivered = true }
      }
      assertTrue(!delivered)
    }
  }

  @Test
  fun download_invalidContentRange_rejectsBeforeDeliveringData() = runTest {
    for (range in listOf("", "bytes 0-3/8", "bytes 4-6/8", "bytes 4-7/7", "invalid")) {
      withResponse(HttpStatusCode.PartialContent, "efgh", "Content-Range" to range) { engine ->
        var delivered = false
        assertFailsWith<KetchError.Unsupported>(range) {
          engine.download("https://example.com/file", 4L..7L) { delivered = true }
        }
        assertTrue(!delivered, range)
      }
    }
  }

  @Test
  fun download_validContentRange_deliversRequestedBytes() = runTest {
    val contentRange = "Content-Range" to "bytes 4-7/8"
    withResponse(HttpStatusCode.PartialContent, "efgh", contentRange) { engine ->
      var body = ""
      engine.download("https://example.com/file", 4L..7L) { body += it.decodeToString() }
      assertEquals("efgh", body)
    }
  }

  @Test
  fun download_truncatedRange_fails() = runTest {
    withResponse(HttpStatusCode.PartialContent, "ef", "Content-Range" to "bytes 4-7/8") { engine ->
      assertFailsWith<KetchError.Network> {
        engine.download("https://example.com/file", 4L..7L) {}
      }
    }
  }

  @Test
  fun download_oversizedRange_neverDeliversExcessBytes() = runTest {
    val contentRange = "Content-Range" to "bytes 4-7/8"
    withResponse(HttpStatusCode.PartialContent, "efghij", contentRange) { engine ->
      var delivered = 0
      assertFailsWith<KetchError.Unsupported> {
        engine.download("https://example.com/file", 4L..7L) { delivered += it.size }
      }
      assertTrue(delivered <= 4)
    }
  }

  @Test
  fun headAndDownload_sensitiveResponseHeaders_areMaskedInLogs() = runTest {
    val records = mutableListOf<String>()
    KetchLogger.setLogger(object : Logger {
      override fun v(message: String) {}
      override fun d(message: String) {
        records += message
      }
      override fun i(message: String) {}
      override fun w(message: String, throwable: Throwable?) {}
      override fun e(message: String, throwable: Throwable?) {}
    })
    val engine = KtorHttpEngine(HttpClient(MockEngine {
      respond(
        "abcdefgh",
        HttpStatusCode.OK,
        headersOf("Content-Length" to listOf("8"), "Set-Cookie" to listOf("session=s3cr3t")),
      )
    }))
    try {
      engine.head("https://example.com/file", emptyMap())
      engine.download("https://example.com/file", 0L..7L) {}
    } finally {
      engine.close()
      KetchLogger.setLogger(Logger.None)
    }

    val headerLines = listOf("HEAD 200 headers:", "GET 200 headers:").map { prefix ->
      records.single { prefix in it }
    }
    headerLines.forEach { line ->
      assertTrue(line.contains("Set-Cookie=***", ignoreCase = true), line)
      assertFalse("s3cr3t" in line, line)
    }
  }

  private suspend fun withResponse(
    status: HttpStatusCode,
    body: String,
    header: Pair<String, String>,
    block: suspend (KtorHttpEngine) -> Unit,
  ) {
    val engine = KtorHttpEngine(HttpClient(MockEngine {
      respond(body, status, headersOf(header.first, header.second))
    }))
    try {
      block(engine)
    } finally {
      engine.close()
    }
  }
}
