package com.linroid.ketch.ai

import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.http.client.KoogHttpClientException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/**
 * Thrown when discovery cannot finish: the LLM provider failed, or the
 * agent did not answer within its step limit.
 *
 * [message] is a short explanation that can be shown to the user;
 * [cause] is the original error.
 */
class DiscoveryException internal constructor(
  override val message: String,
  cause: Throwable,
) : Exception(message, cause)

/**
 * A short explanation, for the user, of [error]: the failure of an LLM
 * request or of an agent run.
 *
 * Provider errors arrive as several lines of client name, status and
 * response body, so they are summarized by status, with the reason the
 * provider gave when its body has one.
 */
internal fun describeLlmFailure(error: Throwable): String {
  val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
  if (chain.any { it is AIAgentMaxNumberOfIterationsReachedException }) {
    return "The agent ran out of steps before it answered. Try a more specific search."
  }
  val http = chain.firstNotNullOfOrNull { it as? KoogHttpClientException }
  val status = http?.statusCode
  if (status != null) {
    val summary = when {
      status == 401 || status == 403 -> "The AI provider rejected the API token"
      status == 404 -> "The AI provider doesn't know this model or endpoint"
      status == 429 -> "The AI provider is limiting requests"
      status >= 500 -> "The AI provider had a server error"
      else -> "The AI provider refused the request"
    }
    val reason = http.errorBody?.let(::providerReason)
    return "$summary (HTTP $status)" + reason?.let { ": $it" }.orEmpty()
  }
  val io = chain.firstNotNullOfOrNull { it as? IOException }
  if (io != null) {
    return "Couldn't reach the AI provider" + io.firstLine()?.let { ": $it" }.orEmpty()
  }
  val detail = chain.asReversed().firstNotNullOfOrNull { it.firstLine() }
    ?: error::class.simpleName
  return "The AI request failed: $detail"
}

/**
 * The reason in a provider's JSON error [body]: `error.message` for
 * OpenAI, Anthropic and Gemini, `error` for Ollama.
 */
private fun providerReason(body: String): String? {
  val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    ?: return null
  val reason = when (val error = json["error"]) {
    is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
    is JsonPrimitive -> error.contentOrNull
    else -> (json["message"] as? JsonPrimitive)?.contentOrNull
  }
  return reason?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    ?.take(MAX_REASON_LENGTH)
}

private fun Throwable.firstLine(): String? =
  message?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    ?.take(MAX_REASON_LENGTH)

private const val MAX_CAUSES = 10
private const val MAX_REASON_LENGTH = 200
