package com.linroid.ketch.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * LLM providers that can drive AI resource discovery.
 *
 * @property label human-readable provider name.
 * @property defaultModel model used when [LlmSettings.model] is blank.
 *   Blank for providers that have no meaningful default. These track the
 *   providers' current recommended models; any id the provider accepts
 *   can be entered instead.
 * @property defaultBaseUrl endpoint used when [LlmSettings.baseUrl] is
 *   blank. Blank for providers that require an explicit endpoint.
 * @property requiresApiKey whether the provider rejects anonymous calls.
 * @property requiresBaseUrl whether the user must supply an endpoint.
 */
@Serializable
enum class LlmProvider(
  val label: String,
  val defaultModel: String,
  val defaultBaseUrl: String,
  val requiresApiKey: Boolean,
  val requiresBaseUrl: Boolean,
) {
  @SerialName("openai")
  OpenAi(
    label = "OpenAI",
    defaultModel = "gpt-5.6-terra",
    defaultBaseUrl = "https://api.openai.com",
    requiresApiKey = true,
    requiresBaseUrl = false,
  ),

  @SerialName("anthropic")
  Anthropic(
    label = "Anthropic",
    defaultModel = "claude-opus-5",
    defaultBaseUrl = "https://api.anthropic.com",
    requiresApiKey = true,
    requiresBaseUrl = false,
  ),

  @SerialName("google")
  Google(
    label = "Google Gemini",
    defaultModel = "gemini-3.8-flash",
    defaultBaseUrl = "https://generativelanguage.googleapis.com",
    requiresApiKey = true,
    requiresBaseUrl = false,
  ),

  @SerialName("ollama")
  Ollama(
    label = "Ollama",
    defaultModel = "qwen3",
    defaultBaseUrl = "http://localhost:11434",
    requiresApiKey = false,
    requiresBaseUrl = false,
  ),

  @SerialName("openai-compatible")
  OpenAiCompatible(
    label = "OpenAI-compatible",
    defaultModel = "",
    defaultBaseUrl = "",
    requiresApiKey = true,
    requiresBaseUrl = true,
  ),
}

/**
 * Web search providers used by the discovery agent's search tools.
 *
 * @property label human-readable provider name.
 * @property requiresApiKey whether the provider needs a key.
 * @property requiresCx whether the provider needs a search engine id.
 */
@Serializable
enum class SearchProvider(
  val label: String,
  val requiresApiKey: Boolean,
  val requiresCx: Boolean,
) {
  @SerialName("none")
  None(label = "None", requiresApiKey = false, requiresCx = false),

  @SerialName("bing")
  Bing(label = "Bing", requiresApiKey = true, requiresCx = false),

  @SerialName("google")
  Google(label = "Google", requiresApiKey = true, requiresCx = true),
}

/**
 * LLM connection settings for AI discovery.
 *
 * @property provider which LLM provider to call.
 * @property apiKey provider API key. Stored as plain text in the config
 *   file, like [ServerConfig.apiToken].
 * @property model model id; blank means [LlmProvider.defaultModel].
 * @property baseUrl endpoint override; blank means
 *   [LlmProvider.defaultBaseUrl].
 */
@Serializable
data class LlmSettings(
  val provider: LlmProvider = LlmProvider.OpenAi,
  val apiKey: String = "",
  val model: String = "",
  val baseUrl: String = "",
) {
  /** Model id to call, falling back to the provider default. */
  val effectiveModel: String
    get() = model.ifBlank { provider.defaultModel }

  /** Endpoint to call, falling back to the provider default. */
  val effectiveBaseUrl: String
    get() = baseUrl.ifBlank { provider.defaultBaseUrl }

  /** `true` when every field the provider needs has a value. */
  val isComplete: Boolean
    get() = (!provider.requiresApiKey || apiKey.isNotBlank()) &&
      effectiveBaseUrl.isNotBlank() &&
      effectiveModel.isNotBlank()
}

/**
 * Web search settings for AI discovery.
 *
 * Without a search provider the agent can still fetch pages it is
 * pointed at, but it cannot search the web.
 *
 * @property provider which search API to use.
 * @property apiKey Bing subscription key or Google API key.
 * @property cx Google Programmable Search engine id.
 */
@Serializable
data class SearchSettings(
  val provider: SearchProvider = SearchProvider.None,
  val apiKey: String = "",
  val cx: String = "",
) {
  /** `true` when every field the provider needs has a value. */
  val isComplete: Boolean
    get() = provider == SearchProvider.None ||
      ((!provider.requiresApiKey || apiKey.isNotBlank()) &&
        (!provider.requiresCx || cx.isNotBlank()))
}

/**
 * User-facing AI discovery settings, persisted under `[ai]`.
 *
 * @property enabled master switch for the feature.
 * @property llm LLM connection settings.
 * @property search web search settings.
 */
@Serializable
data class AiSettings(
  val enabled: Boolean = false,
  val llm: LlmSettings = LlmSettings(),
  val search: SearchSettings = SearchSettings(),
) {
  /** `true` when discovery is switched on and fully configured. */
  val isUsable: Boolean
    get() = enabled && llm.isComplete && search.isComplete
}
