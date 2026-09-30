package com.linroid.ketch.ai.fetch

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SafeFetcherTest {

  private val validator = UrlValidator(
    resolve = fakeDns(
      "example.com" to "93.184.215.14",
      "cdn.example.net" to "151.101.1.1",
      "127.0.0.1" to "127.0.0.1",
    ),
  )

  private fun fetcher(engine: MockEngine, maxRedirects: Int = 10) = SafeFetcher(
    httpClient = HttpClient(engine) { followRedirects = false },
    urlValidator = validator,
    rateLimiter = RateLimiter(delayMs = 0),
    maxRedirects = maxRedirects,
  )

  private fun engine(handler: MockRequestHandler) = MockEngine(handler)

  private fun redirectTo(location: String) =
    headersOf(HttpHeaders.Location, location)

  private val MockEngine.requestedUrls: List<String>
    get() = requestHistory.map { it.url.toString() }

  @Test
  fun fetch_redirectToLoopback_isBlockedBeforeRequest() = runTest {
    val engine = engine { request ->
      if (request.url.host == "example.com") {
        respond("", HttpStatusCode.Found, redirectTo("http://127.0.0.1/admin"))
      } else {
        respond("internal secret")
      }
    }

    val result = fetcher(engine).fetch("https://example.com/start")

    val failed = assertIs<FetchResult.Failed>(result)
    assertTrue(failed.reason.startsWith("Redirect blocked"), failed.reason)
    assertEquals(listOf("https://example.com/start"), engine.requestedUrls)
  }

  @Test
  fun fetch_redirectChain_followsRelativeAndAbsoluteHops() = runTest {
    val engine = engine { request ->
      when (request.url.toString()) {
        "https://example.com/a" -> respond("", HttpStatusCode.MovedPermanently, redirectTo("/b"))
        "https://example.com/b" ->
          respond("", HttpStatusCode.Found, redirectTo("https://cdn.example.net/page"))
        else -> respond("hello")
      }
    }

    val result = fetcher(engine).fetch("https://example.com/a")

    val success = assertIs<FetchResult.Success>(result)
    assertEquals("https://example.com/a", success.url)
    assertEquals("https://cdn.example.net/page", success.finalUrl)
    assertEquals("hello", success.content)
  }

  @Test
  fun head_redirect_reportsFinalUrl() = runTest {
    val engine = engine { request ->
      if (request.url.host == "example.com") {
        respond(
          "",
          HttpStatusCode.TemporaryRedirect,
          redirectTo("https://cdn.example.net/file.iso"),
        )
      } else {
        respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1234"))
      }
    }

    val result = fetcher(engine).head("https://example.com/download")

    val success = assertIs<HeadResult.Success>(result)
    assertEquals("https://cdn.example.net/file.iso", success.finalUrl)
    assertEquals(1234L, success.contentLength)
    assertTrue(engine.requestHistory.all { it.method == HttpMethod.Head })
  }

  @Test
  fun fetch_redirectLoop_stopsAtHopLimit() = runTest {
    val engine = engine { respond("", HttpStatusCode.Found, redirectTo("/loop")) }

    val result = fetcher(engine, maxRedirects = 3).fetch("https://example.com/loop")

    val failed = assertIs<FetchResult.Failed>(result)
    assertTrue(failed.reason.startsWith("Too many redirects"), failed.reason)
    // The first request plus three redirects.
    assertEquals(4, engine.requestHistory.size)
  }

  @Test
  fun fetch_bodyWithoutLength_isCutAtLimit() = runTest {
    val engine = engine { respond("x".repeat(100)) }

    val result = fetcher(engine).fetch("https://example.com/page", maxBytes = 10)

    val success = assertIs<FetchResult.Success>(result)
    assertEquals(10L, success.byteCount)
    assertEquals("x".repeat(10), success.content)
  }

  @Test
  fun fetch_declaredLengthOverLimit_fails() = runTest {
    val engine = engine {
      respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "5000"))
    }

    val result = fetcher(engine).fetch("https://example.com/big", maxBytes = 100)

    val failed = assertIs<FetchResult.Failed>(result)
    assertTrue(failed.reason.startsWith("Content too large"), failed.reason)
  }

  @Test
  fun fetch_hostRebindsToLoopbackAfterValidation_neverConnects() = runTest(timeout = 20.seconds) {
    // Validation sees a public address; the client's lookup at connect time gets loopback.
    val validator = UrlValidator(resolve = rebindingDns("93.184.215.14", "127.0.0.1"))
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
      val url = "http://rebind.example:${server.localPort}/admin"

      val client = SafeFetcher.createHttpClient(validator, requestTimeoutMs = 10_000)
      val result = client.use {
        val fetcher = SafeFetcher(it, validator, RateLimiter(delayMs = 0))
        // Real sockets: keep the client's request timeout off the virtual test clock.
        withContext(Dispatchers.IO) { fetcher.fetch(url) }
      }

      val failed = assertIs<FetchResult.Failed>(result)
      assertTrue(failed.reason.contains("127.0.0.1"), failed.reason)
      // A connection would be waiting in the backlog even though nothing accepted it.
      server.soTimeout = 200
      assertFailsWith<SocketTimeoutException> { server.accept() }
    }
  }

  @Test
  fun constructor_clientFollowingRedirects_isRejected() {
    // Ktor follows redirects by default, which would skip hop validation.
    val client = HttpClient(MockEngine { respondOk() })
    assertFailsWith<IllegalArgumentException> {
      SafeFetcher(httpClient = client, urlValidator = validator)
    }
  }
}
