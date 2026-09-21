package com.linroid.ketch.engine

import com.linroid.ketch.api.KetchError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
