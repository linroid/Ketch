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

class BingSearchProviderIntegrationTest {

  private val jsonHeaders = headersOf(
    HttpHeaders.ContentType,
    ContentType.Application.Json.toString(),
  )

  private fun createClient(
    respondJson: String,
    status: HttpStatusCode = HttpStatusCode.OK,
  ): HttpClient = HttpClient(MockEngine) {
    engine {
      addHandler {
        respond(respondJson, status, jsonHeaders)
      }
    }
    install(ContentNegotiation) {
      json(Json { ignoreUnknownKeys = true })
    }
  }

  @Test
  fun search_parsesResults() = runTest {
    val json = """
      {
        "webPages": {
          "value": [
            {
              "name": "Ubuntu Downloads",
              "url": "https://ubuntu.com/download",
              "snippet": "Download Ubuntu Desktop"
            },
            {
              "name": "Ubuntu Releases",
              "url": "https://releases.ubuntu.com/",
              "snippet": "All Ubuntu releases"
            }
          ]
        }
      }
    """.trimIndent()

    val provider = BingSearchProvider(createClient(json), "test-key")
    val results = provider.search("ubuntu iso")

    assertEquals(2, results.size)
    assertEquals("Ubuntu Downloads", results[0].title)
    assertEquals("https://ubuntu.com/download", results[0].url)
    assertEquals("Download Ubuntu Desktop", results[0].snippet)
    assertEquals("Ubuntu Releases", results[1].title)
  }

  @Test
  fun search_emptyWebPages_returnsEmptyList() = runTest {
    val json = """{ "webPages": { "value": [] } }"""
    val provider = BingSearchProvider(createClient(json), "test-key")
    val results = provider.search("nothing")
    assertTrue(results.isEmpty())
  }

  @Test
  fun search_noWebPagesField_returnsEmptyList() = runTest {
    val json = """{ "_type": "SearchResponse" }"""
    val provider = BingSearchProvider(createClient(json), "test-key")
    val results = provider.search("nothing")
    assertTrue(results.isEmpty())
  }

  @Test
  fun search_httpError_returnsEmptyList() = runTest {
    val provider = BingSearchProvider(
      createClient("{}", HttpStatusCode.Unauthorized),
      "bad-key",
    )
    val results = provider.search("test")
    assertTrue(results.isEmpty())
  }
}
