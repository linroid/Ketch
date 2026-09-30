package com.linroid.ketch.ai.search

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BraveSearchProviderTest {

  private val jsonHeaders = headersOf(
    HttpHeaders.ContentType,
    ContentType.Application.Json.toString(),
  )

  private fun engine(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
  ) = MockEngine { respond(body, status, jsonHeaders) }

  private fun provider(engine: MockEngine, apiKey: String = "test-key") =
    BraveSearchProvider(
      HttpClient(engine) {
        install(ContentNegotiation) {
          json(Json { ignoreUnknownKeys = true })
        }
      },
      apiKey,
    )

  @Test
  fun search_parsesWebResults() = runTest {
    val json = """
      {
        "type": "search",
        "web": {
          "results": [
            {
              "title": "Ubuntu Downloads",
              "url": "https://ubuntu.com/download",
              "description": "Download Ubuntu Desktop"
            },
            {
              "title": "Ubuntu Releases",
              "url": "https://releases.ubuntu.com/"
            }
          ]
        }
      }
    """.trimIndent()

    val results = provider(engine(json)).search("ubuntu iso")

    assertEquals(
      listOf(
        SearchResult("https://ubuntu.com/download", "Ubuntu Downloads", "Download Ubuntu Desktop"),
        SearchResult("https://releases.ubuntu.com/", "Ubuntu Releases", ""),
      ),
      results,
    )
  }

  @Test
  fun search_request_carriesTokenSiteQueryAndCappedCount() = runTest {
    val engine = engine("""{ "type": "search" }""")

    provider(engine, apiKey = "brave-key").search(
      query = "ubuntu iso",
      sites = listOf("ubuntu.com", "releases.ubuntu.com"),
      maxResults = 50,
    )

    val request = engine.requestHistory.single()
    assertEquals("brave-key", request.headers["X-Subscription-Token"])
    assertEquals(
      "ubuntu iso (site:ubuntu.com OR site:releases.ubuntu.com)",
      request.url.parameters["q"],
    )
    assertEquals("20", request.url.parameters["count"])
  }

  @Test
  fun search_noWebResults_returnsEmptyList() = runTest {
    val results = provider(engine("""{ "type": "search" }""")).search("nothing")
    assertTrue(results.isEmpty())
  }

  @Test
  fun search_httpError_returnsEmptyList() = runTest {
    val body = """{ "type": "ErrorResponse", "error": { "code": "SUBSCRIPTION_TOKEN_INVALID" } }"""
    val results = provider(engine(body, HttpStatusCode.UnprocessableEntity), "bad-key")
      .search("test")
    assertTrue(results.isEmpty())
  }

  @Test
  fun buildQuery_noSites_returnsQueryUnchanged() {
    assertEquals("ubuntu iso", BraveSearchProvider.buildQuery("ubuntu iso", emptyList()))
  }
}
