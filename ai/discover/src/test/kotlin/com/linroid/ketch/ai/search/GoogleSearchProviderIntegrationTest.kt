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

class GoogleSearchProviderIntegrationTest {

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
        "items": [
          {
            "title": "FFmpeg Download",
            "link": "https://ffmpeg.org/download.html",
            "snippet": "Download FFmpeg builds"
          },
          {
            "title": "FFmpeg Releases",
            "link": "https://ffmpeg.org/releases/",
            "snippet": "Source releases"
          }
        ]
      }
    """.trimIndent()

    val provider = GoogleSearchProvider(
      createClient(json),
      "test-key",
      "test-cx",
    )
    val results = provider.search("ffmpeg download")

    assertEquals(2, results.size)
    assertEquals("FFmpeg Download", results[0].title)
    assertEquals("https://ffmpeg.org/download.html", results[0].url)
    assertEquals("Download FFmpeg builds", results[0].snippet)
    assertEquals("FFmpeg Releases", results[1].title)
  }

  @Test
  fun search_noItems_returnsEmptyList() = runTest {
    val json = """{ "searchInformation": { "totalResults": "0" } }"""
    val provider = GoogleSearchProvider(
      createClient(json),
      "test-key",
      "test-cx",
    )
    val results = provider.search("nothing")
    assertTrue(results.isEmpty())
  }

  @Test
  fun search_emptyItems_returnsEmptyList() = runTest {
    val json = """{ "items": [] }"""
    val provider = GoogleSearchProvider(
      createClient(json),
      "test-key",
      "test-cx",
    )
    val results = provider.search("nothing")
    assertTrue(results.isEmpty())
  }

  @Test
  fun search_httpError_returnsEmptyList() = runTest {
    val provider = GoogleSearchProvider(
      createClient("{}", HttpStatusCode.Forbidden),
      "bad-key",
      "test-cx",
    )
    val results = provider.search("test")
    assertTrue(results.isEmpty())
  }

  @Test
  fun search_nullSnippet_defaultsToEmpty() = runTest {
    val json = """
      {
        "items": [
          {
            "title": "No Snippet Page",
            "link": "https://example.com"
          }
        ]
      }
    """.trimIndent()

    val provider = GoogleSearchProvider(
      createClient(json),
      "test-key",
      "test-cx",
    )
    val results = provider.search("test")

    assertEquals(1, results.size)
    assertEquals("", results[0].snippet)
  }
}
