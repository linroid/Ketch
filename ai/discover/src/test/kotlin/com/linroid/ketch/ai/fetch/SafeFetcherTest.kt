package com.linroid.ketch.ai.fetch

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.engine.mock.respondRedirect
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SafeFetcherTest {

  private val dns = fakeDns(
    "github.com" to "203.0.113.1",
    "objects.githubusercontent.com" to "203.0.113.2",
    "example.com" to "203.0.113.3",
    "metadata.example" to "169.254.169.254",
  )

  private val requested = mutableListOf<String>()

  private fun fetcher(handler: MockRequestHandler): SafeFetcher {
    val engine = MockEngine { request ->
      requested += request.url.toString()
      handler(request)
    }
    return SafeFetcher(HttpClient(engine) { followRedirects = false }, UrlValidator(dns))
  }

  @Test
  fun head_crossDomainRedirect_reportsRequestedAndFinalUrl() = runTest {
    val asset = "https://github.com/owner/repo/releases/download/v1/app.zip"
    val cdn = "https://objects.githubusercontent.com/assets/app.zip"
    val fetcher = fetcher { request ->
      if (request.url.host == "github.com") {
        respondRedirect(cdn)
      } else {
        respond("", headers = headersOf(HttpHeaders.ContentLength, "1024"))
      }
    }

    val result = assertIs<HeadResult.Success>(fetcher.head(asset))

    assertEquals(asset, result.url)
    assertEquals(cdn, result.finalUrl)
    assertEquals(1024L, result.contentLength)
  }

  @Test
  fun fetch_relativeRedirect_resolvesAgainstCurrentUrl() = runTest {
    val fetcher = fetcher { request ->
      if (request.url.encodedPath == "/a/b") respondRedirect("c") else respond("final page")
    }

    val result = assertIs<FetchResult.Success>(fetcher.fetch("https://example.com/a/b"))

    assertEquals("https://example.com/a/c", result.finalUrl)
    assertEquals("final page", result.content)
  }

  @Test
  fun fetch_redirectToPrivateAddress_isBlockedBeforeRequest() = runTest {
    val fetcher = fetcher { respondRedirect("http://metadata.example/latest/meta-data") }

    val result = assertIs<FetchResult.Failed>(fetcher.fetch("http://example.com/go"))

    assertTrue(result.reason.startsWith("Redirect blocked"), result.reason)
    assertEquals(listOf("http://example.com/go"), requested)
  }

  @Test
  fun fetch_httpsToHttpRedirect_isRefused() = runTest {
    val fetcher = fetcher { respondRedirect("http://example.com/plain") }

    assertIs<FetchResult.Failed>(fetcher.fetch("https://example.com/"))
    assertEquals(listOf("https://example.com/"), requested)
  }

  @Test
  fun fetch_redirectLoop_stopsAfterMaxRedirects() = runTest {
    val fetcher = fetcher { respondRedirect("https://example.com/loop") }

    val result = assertIs<FetchResult.Failed>(fetcher.fetch("https://example.com/loop"))

    assertTrue(result.reason.startsWith("Too many redirects"), result.reason)
    // The first request plus five redirects.
    assertEquals(6, requested.size)
  }

  @Test
  fun constructor_clientFollowingRedirects_isRejected() {
    val client = HttpClient(MockEngine { respondOk() })
    assertFailsWith<IllegalArgumentException> {
      SafeFetcher(client, UrlValidator(dns))
    }
  }
}
