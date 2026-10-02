package com.linroid.ketch.ai

import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.clients.LLMClientException
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryExceptionTest {

  private fun httpError(status: Int, body: String?) = KoogHttpClientException(
    clientName = "OpenAIResponsesLLMClient",
    statusCode = status,
    errorBody = body,
  )

  @Test
  fun describeLlmFailure_maxIterations_suggestsNarrowerSearch() {
    assertEquals(
      "The agent ran out of steps before it answered. Try a more specific search.",
      describeLlmFailure(AIAgentMaxNumberOfIterationsReachedException(89)),
    )
  }

  @Test
  fun describeLlmFailure_unauthorizedWrappedByClient_givesProviderReason() {
    val body = """{"error": {"message": "Incorrect API key provided.", "type": "auth"}}"""
    val error = LLMClientException("OpenAI", "Request failed", httpError(401, body))

    assertEquals(
      "The AI provider rejected the API token (HTTP 401): Incorrect API key provided.",
      describeLlmFailure(error),
    )
  }

  @Test
  fun describeLlmFailure_ollamaStringError_givesProviderReason() {
    assertEquals(
      "The AI provider doesn't know this model or endpoint (HTTP 404): model 'qwen' not found",
      describeLlmFailure(httpError(404, """{"error": "model 'qwen' not found"}""")),
    )
  }

  @Test
  fun describeLlmFailure_nonJsonBody_givesStatusOnly() {
    assertEquals(
      "The AI provider had a server error (HTTP 502)",
      describeLlmFailure(httpError(502, "<html><body>Bad gateway</body></html>")),
    )
  }

  @Test
  fun describeLlmFailure_connectionRefused_saysProviderUnreachable() {
    val refused = ConnectException("Connection refused")
    val error = LLMClientException("Ollama", "Request failed", refused)

    assertEquals("Couldn't reach the AI provider: Connection refused", describeLlmFailure(error))
  }

  @Test
  fun describeLlmFailure_otherError_givesFirstLineOfRootCause() {
    val root = IllegalArgumentException("Bad tool call\nat line 3")
    val error = IllegalStateException("wrapper", root)

    assertEquals("The AI request failed: Bad tool call", describeLlmFailure(error))
  }
}
