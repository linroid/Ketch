package com.linroid.ketch.ai

import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmModelListerTest {

  private val requests = mutableListOf<HttpRequestData>()

  private fun lister(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
  ) = LlmModelLister {
    HttpClient(
      MockEngine { request ->
        requests += request
        handler(request)
      },
    )
  }

  private fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
  ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

  @Test
  fun `compatible servers list the chat models of their models endpoint`() = runTest {
    val ids = lister {
      json(
        """{"data":[{"id":"glm-4.6"},{"id":"embedding-3"},{"id":"glm-4.5-air"},{"id":"glm-4.6"}]}"""
      )
    }.list(LlmSettings(provider = LlmProvider.Zhipu, apiKey = "secret"))
    assertEquals(listOf("glm-4.5-air", "glm-4.6"), ids)
    val request = requests.single()
    assertEquals("https://open.bigmodel.cn/api/paas/v4/models", request.url.toString())
    assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
  }

  @Test
  fun `an endpoint without a version lists models under v1`() = runTest {
    lister { json("""{"data":[]}""") }.list(
      LlmSettings(
        provider = LlmProvider.OpenAiCompatible,
        apiKey = "k",
        baseUrl = "https://llm.example.com",
        model = "m",
      ),
    )
    assertEquals("https://llm.example.com/v1/models", requests.single().url.toString())
  }

  @Test
  fun `a full chat completions endpoint lists models beside it`() = runTest {
    lister { json("""{"data":[]}""") }
      .list(LlmSettings(provider = LlmProvider.DeepSeek, apiKey = "k"))
    assertEquals("https://api.deepseek.com/models", requests.single().url.toString())
  }

  @Test
  fun `a local server without a key sends none`() = runTest {
    val ids = lister { json("""{"data":[{"id":"qwen3-8b"}]}""") }
      .list(LlmSettings(provider = LlmProvider.LmStudio))
    assertEquals(listOf("qwen3-8b"), ids)
    assertEquals("http://localhost:1234/v1/models", requests.single().url.toString())
    assertEquals(null, requests.single().headers[HttpHeaders.Authorization])
  }

  @Test
  fun `anthropic lists its models with its own headers`() = runTest {
    val ids = lister { json("""{"data":[{"id":"claude-opus-5-5"},{"id":"claude-haiku-5-5"}]}""") }
      .list(LlmSettings(provider = LlmProvider.Anthropic, apiKey = "sk-ant"))
    assertEquals(listOf("claude-haiku-5-5", "claude-opus-5-5"), ids)
    val request = requests.single()
    assertEquals("https://api.anthropic.com/v1/models?limit=1000", request.url.toString())
    assertEquals("sk-ant", request.headers["x-api-key"])
    assertEquals("2023-06-01", request.headers["anthropic-version"])
  }

  @Test
  fun `gemini lists the models that chat across pages`() = runTest {
    val ids = lister { request ->
      if (request.url.parameters["pageToken"] == null) {
        json(
          """{"models":[
            {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-embedding-001","supportedGenerationMethods":["embedContent"]}
          ],"nextPageToken":"next"}"""
        )
      } else {
        json(
          """{"models":[
            {"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent"]}
          ]}"""
        )
      }
    }.list(LlmSettings(provider = LlmProvider.Google, apiKey = "AIza"))
    assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.8-flash"), ids)
    assertEquals(2, requests.size)
    // The key goes in a header, never in a URL an error could quote.
    assertTrue(requests.all { it.headers["x-goog-api-key"] == "AIza" })
    assertFalse(requests.any { "AIza" in it.url.toString() })
  }

  @Test
  fun `ollama lists the models it has pulled`() = runTest {
    val ids = lister { json("""{"models":[{"name":"qwen3:8b"},{"name":"gemma4:e4b"}]}""") }
      .list(LlmSettings(provider = LlmProvider.Ollama, baseUrl = "http://nas.local:11434/"))
    assertEquals(listOf("gemma4:e4b", "qwen3:8b"), ids)
    assertEquals("http://nas.local:11434/api/tags", requests.single().url.toString())
  }

  @Test
  fun `a refusal says why without the provider's reply`() = runTest {
    val error = assertFailsWith<DiscoveryException> {
      lister {
        json(
          """{"error":{"message":"Incorrect API key: sk-leaked"}}""",
          HttpStatusCode.Unauthorized
        )
      }.list(LlmSettings(provider = LlmProvider.OpenAi, apiKey = "sk-leaked"))
    }
    assertEquals("The AI provider rejected the API token (HTTP 401)", error.message)
    assertFalse("sk-leaked" in error.brief)
  }

  @Test
  fun `a reply that is not a model list is reported as such`() = runTest {
    val error = assertFailsWith<DiscoveryException> {
      lister { json("<html>login</html>") }
        .list(LlmSettings(provider = LlmProvider.Mistral, apiKey = "k"))
    }
    assertEquals("The AI provider sent a model list Ketch can't read", error.message)
  }

  @Test
  fun `a provider that needs a key is not asked without one`() = runTest {
    assertFailsWith<IllegalArgumentException> {
      lister { json("""{"data":[]}""") }.list(LlmSettings(provider = LlmProvider.Groq))
    }
    assertTrue(requests.isEmpty())
  }
}
