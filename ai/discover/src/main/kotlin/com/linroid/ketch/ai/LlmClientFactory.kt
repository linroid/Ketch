package com.linroid.ketch.ai

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.modelsById
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import io.ktor.http.Url

/**
 * An LLM client paired with the model it should be called with.
 */
internal data class ResolvedLlm(
  val executor: PromptExecutor,
  val model: LLModel,
)

/**
 * Builds Koog LLM clients from user-facing [LlmSettings].
 *
 * Known model ids resolve to Koog's catalog entries (which carry
 * accurate capabilities and context limits); anything else becomes a
 * custom [LLModel] so users are not restricted to the models Koog
 * happens to ship.
 */
internal object LlmClientFactory {

  /**
   * Creates an executor and model for [settings], or returns `null`
   * when the settings are incomplete (e.g. a missing API key).
   */
  fun resolve(settings: LlmSettings): ResolvedLlm? {
    if (!settings.isComplete) return null
    val modelId = settings.effectiveModel
    val baseUrl = settings.effectiveBaseUrl
    val (client, model) = when (settings.provider) {
      // OpenAI's current models (gpt-6-astra, gpt-5.6-*) are served by
      // the Responses API, while third-party compatible servers
      // implement chat completions — so the fallback endpoint differs.
      LlmProvider.OpenAi -> openAi(
        settings.apiKey, baseUrl, modelId,
        LLMCapability.OpenAIEndpoint.Responses,
      )
      LlmProvider.OpenAiCompatible -> openAi(
        settings.apiKey, baseUrl, modelId,
        LLMCapability.OpenAIEndpoint.Completions,
      )
      LlmProvider.Anthropic ->
        anthropic(settings.apiKey, baseUrl, modelId)
      LlmProvider.Google -> google(settings.apiKey, baseUrl, modelId)
      LlmProvider.Ollama -> ollama(baseUrl, modelId)
    }
    return ResolvedLlm(MultiLLMPromptExecutor(client), model)
  }

  private fun openAi(
    apiKey: String,
    baseUrl: String,
    modelId: String,
    endpoint: LLMCapability.OpenAIEndpoint,
  ): Pair<LLMClient, LLModel> {
    val model = OpenAIModels.modelsById()[modelId]
      ?: customModel(LLMProvider.OpenAI, modelId, endpoint)
    val client = OpenAILLMClient(
      apiKey = apiKey,
      settings = openAiClientSettings(baseUrl),
    )
    return client to model
  }

  private fun anthropic(
    apiKey: String,
    baseUrl: String,
    modelId: String,
  ): Pair<LLMClient, LLModel> {
    val known = AnthropicModels.modelsById()[modelId]
    val model = known ?: customModel(LLMProvider.Anthropic, modelId)
    // The Anthropic client rejects models missing from its version map,
    // so custom ids are mapped straight through to the wire name.
    val settings = if (known != null) {
      AnthropicClientSettings(baseUrl = trimTrailingSlash(baseUrl))
    } else {
      AnthropicClientSettings(
        modelVersionsMap = mapOf(model to modelId),
        baseUrl = trimTrailingSlash(baseUrl),
      )
    }
    return AnthropicLLMClient(apiKey = apiKey, settings = settings) to model
  }

  private fun google(
    apiKey: String,
    baseUrl: String,
    modelId: String,
  ): Pair<LLMClient, LLModel> {
    val model = GoogleModels.modelsById()[modelId]
      ?: customModel(LLMProvider.Google, modelId)
    val client = GoogleLLMClient(
      apiKey = apiKey,
      settings = GoogleClientSettings(baseUrl = trimTrailingSlash(baseUrl)),
    )
    return client to model
  }

  private fun ollama(
    baseUrl: String,
    modelId: String,
  ): Pair<LLMClient, LLModel> {
    val model = OllamaModels.modelsById()[modelId]
      ?: customModel(LLMProvider.Ollama, modelId)
    return OllamaClient(baseUrl = trimTrailingSlash(baseUrl)) to model
  }

  /**
   * Builds a model definition for an id Koog does not know about, so a
   * model released after Koog's catalog still works.
   *
   * Tool calling is required — the discovery agent is useless without
   * it. Sampling is deliberately left out: the current frontier models
   * (Claude 5 family, OpenAI's newest) reject `temperature` outright,
   * and [ResourceDiscoveryService] only sends one to models that
   * advertise the capability.
   *
   * @param endpoint which OpenAI-family endpoint to use; `null` for
   *   providers with a single endpoint.
   */
  internal fun customModel(
    provider: LLMProvider,
    modelId: String,
    endpoint: LLMCapability.OpenAIEndpoint? = null,
  ): LLModel = LLModel(
    provider = provider,
    id = modelId,
    capabilities = listOfNotNull(
      LLMCapability.Tools,
      LLMCapability.ToolChoice,
      LLMCapability.Schema.JSON.Standard,
      LLMCapability.Completion,
      endpoint,
    ),
  )

  /**
   * Splits an OpenAI-compatible [endpoint] into the client's base URL and
   * the paths it appends to it.
   *
   * - A full request URL, ending in `/chat/completions`, is used as it is.
   * - An endpoint whose path names an API version, the way providers
   *   document theirs (`https://openrouter.ai/api/v1`,
   *   `https://open.bigmodel.cn/api/paas/v4`,
   *   `https://generativelanguage.googleapis.com/v1beta/openai`), is the
   *   base that `chat/completions` follows, as in OpenAI's SDKs.
   * - Any other endpoint is the server's root, which
   *   `v1/chat/completions` follows.
   */
  internal fun openAiClientSettings(endpoint: String): OpenAIClientSettings {
    val trimmed = trimTrailingSlash(endpoint)
    val base = trimmed.removeSuffix("/$CHAT_COMPLETIONS_PATH")
    // A full request URL or a versioned path already ends where the request paths start.
    val isApiBase = base != trimmed || Url(base).segments.any { it.matches(API_VERSION_SEGMENT) }
    val prefix = if (isApiBase) "" else "v1/"
    return OpenAIClientSettings(
      baseUrl = base,
      chatCompletionsPath = prefix + CHAT_COMPLETIONS_PATH,
      responsesAPIPath = prefix + RESPONSES_PATH,
    )
  }

  private fun trimTrailingSlash(raw: String): String =
    raw.trim().trimEnd('/')

  private const val CHAT_COMPLETIONS_PATH = "chat/completions"
  private const val RESPONSES_PATH = "responses"

  /** A path segment naming an API version: `v1`, `v4`, `v1beta`. */
  private val API_VERSION_SEGMENT = Regex("v\\d+[a-z0-9.]*", RegexOption.IGNORE_CASE)
}
