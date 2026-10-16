package com.linroid.ketch.ai

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.config.LlmApi
import com.linroid.ketch.config.LlmSettings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/**
 * Lists the models an LLM provider offers, for picking one in settings: `/models` of the
 * OpenAI-compatible APIs, the models of Anthropic's and Google's APIs, and the models Ollama has
 * pulled.
 *
 * Models that cannot chat, such as embedding, speech and image models, are left out where the
 * provider says which they are (Gemini) or their ids do. An id typed by hand still works when the
 * list lacks it.
 */
class LlmModelLister internal constructor(
  private val createClient: () -> HttpClient,
) {
  /** A lister that makes its own HTTP client for each call. */
  constructor() : this(::defaultClient)

  private val log = KetchLogger("LlmModels")

  /**
   * The ids of the models [settings]' provider offers, sorted, each once.
   *
   * @throws IllegalArgumentException if the settings name no endpoint, or the provider needs a
   *   key they lack
   * @throws DiscoveryException if the provider cannot be reached or refuses the request; its
   *   message never quotes the provider's reply
   */
  suspend fun list(settings: LlmSettings): List<String> {
    val provider = settings.provider
    val endpoint = LlmClientFactory.trimTrailingSlash(settings.effectiveBaseUrl)
    require(endpoint.isNotEmpty()) { "${provider.label} needs an endpoint" }
    require(!provider.requiresApiKey || settings.apiKey.isNotBlank()) {
      "${provider.label} needs an API key"
    }
    log.i { "Listing the models of ${provider.label}" }
    // The HTTP engine is set up off the caller's thread, which may be a UI thread.
    return withContext(Dispatchers.Default) { fetch(settings, endpoint) }
  }

  private suspend fun fetch(settings: LlmSettings, endpoint: String): List<String> {
    val provider = settings.provider
    val client = createClient()
    try {
      val ids = when (provider.api) {
        LlmApi.OpenAiResponses, LlmApi.OpenAiChat -> openAi(client, endpoint, settings.apiKey)
        LlmApi.Anthropic -> anthropic(client, endpoint, settings.apiKey)
        LlmApi.Google -> google(client, endpoint, settings.apiKey)
        LlmApi.Ollama -> ollama(client, endpoint)
      }
      return ids.filter { it.isNotBlank() && !it.isNotForChat() }.distinct().sorted()
    } catch (e: CancellationException) {
      throw e
    } catch (e: DiscoveryException) {
      log.w { "Couldn't list the models of ${provider.label}: ${e.brief}" }
      throw e
    } catch (e: Exception) {
      val message = describeListFailure(e)
      log.w { "Couldn't list the models of ${provider.label}: $message" }
      throw DiscoveryException(message, e)
    } finally {
      client.close()
    }
  }

  private suspend fun openAi(client: HttpClient, endpoint: String, apiKey: String): List<String> {
    val response = client.get(LlmClientFactory.openAiModelsUrl(endpoint)) {
      if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
    }
    return response.json().array("data").ids("id")
  }

  private suspend fun anthropic(
    client: HttpClient,
    endpoint: String,
    apiKey: String,
  ): List<String> {
    val response = client.get("$endpoint/v1/models") {
      header("x-api-key", apiKey)
      header("anthropic-version", ANTHROPIC_VERSION)
      parameter("limit", MAX_PAGE_SIZE)
    }
    return response.json().array("data").ids("id")
  }

  private suspend fun google(client: HttpClient, endpoint: String, apiKey: String): List<String> {
    val ids = mutableListOf<String>()
    var pageToken: String? = null
    repeat(MAX_PAGES) {
      val response = client.get("$endpoint/v1beta/models") {
        // In a header, so the key never shows in a URL that an error might quote.
        header("x-goog-api-key", apiKey)
        parameter("pageSize", MAX_PAGE_SIZE)
        pageToken?.let { parameter("pageToken", it) }
      }
      val page = response.json()
      page.array("models").filterIsInstance<JsonObject>()
        .filter { model ->
          // Only the models that answer a chat; embedding models have other methods.
          model.array("supportedGenerationMethods").any { it.string() == "generateContent" }
        }
        .mapNotNullTo(ids) { it["name"]?.string()?.removePrefix("models/") }
      pageToken = page["nextPageToken"]?.string()?.takeIf { it.isNotBlank() } ?: return ids
    }
    return ids
  }

  private suspend fun ollama(client: HttpClient, endpoint: String): List<String> {
    val response = client.get("$endpoint/api/tags")
    return response.json().array("models").ids("name")
  }

  /** The body of a successful [HttpResponse] as a JSON object; a failure throws. */
  private suspend fun HttpResponse.json(): JsonObject {
    if (!status.isSuccess()) {
      throw DiscoveryException(describeProviderStatus(status.value), IOException("HTTP $status"))
    }
    return Json.parseToJsonElement(bodyAsText()) as? JsonObject
      ?: throw DiscoveryException(UNREADABLE_LIST, IOException("Not a JSON object"))
  }

  private fun JsonObject.array(name: String): JsonArray =
    this[name] as? JsonArray ?: JsonArray(emptyList())

  private fun JsonArray.ids(field: String): List<String> =
    filterIsInstance<JsonObject>().mapNotNull { it[field]?.string() }

  private fun JsonElement.string(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

  /** Whether this model id names a model that cannot chat, such as `text-embedding-3-small`. */
  private fun String.isNotForChat(): Boolean {
    val id = lowercase()
    return NOT_FOR_CHAT.any { it in id }
  }

  private companion object {
    const val ANTHROPIC_VERSION = "2023-06-01"
    const val MAX_PAGE_SIZE = 1000
    const val MAX_PAGES = 5
    const val TIMEOUT_MS = 15_000L

    /** Parts of the ids of models that embed, speak, listen, draw or moderate. */
    val NOT_FOR_CHAT = listOf(
      "embed", "tts", "whisper", "transcribe", "dall-e", "-image", "moderation", "rerank",
    )

    fun defaultClient(): HttpClient = HttpClient {
      install(HttpTimeout) { requestTimeoutMillis = TIMEOUT_MS }
    }
  }
}

private const val UNREADABLE_LIST = "The AI provider sent a model list Ketch can't read"

/** A short explanation of why listing models failed, without the provider's reply. */
private fun describeListFailure(error: Throwable): String = when {
  error is IOException || error.cause is IOException -> "Couldn't reach the AI provider"
  error is SerializationException -> UNREADABLE_LIST
  else -> describeLlmFailure(error, withProviderReason = false)
}
