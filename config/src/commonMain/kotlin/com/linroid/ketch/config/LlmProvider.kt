package com.linroid.ketch.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** The API an [LlmProvider] is called through. */
enum class LlmApi {
  /** OpenAI's Responses API. */
  OpenAiResponses,

  /** OpenAI's chat completions API, which most other providers serve too. */
  OpenAiChat,

  /** Anthropic's Messages API. */
  Anthropic,

  /** Google's Gemini API. */
  Google,

  /** Ollama's own API. */
  Ollama,
}

/** Where a provider's models run, which the apps group providers by. */
enum class LlmProviderGroup {
  /** Hosted services. */
  Hosted,

  /** Hosted services based in China. */
  China,

  /** Servers that run models on the user's own computer. */
  Local,

  /** Any other server that speaks OpenAI's chat completions API. */
  Custom,
}

/**
 * LLM providers that can drive AI resource discovery: the providers Ketch has presets for, and
 * [OpenAiCompatible] for any other server.
 *
 * Saved as [id]. An id this version does not know, such as a preset added later, loads as
 * [OpenAiCompatible] rather than failing the whole config file.
 *
 * @property id value stored in `config.toml`.
 * @property label human-readable provider name.
 * @property api the API it is called through.
 * @property group where its models run.
 * @property baseUrls its endpoints, the default first and then those of its other regions, such
 *   as its mainland China and international sites; empty for providers that require an explicit
 *   endpoint. An OpenAI-compatible endpoint is the base `chat/completions` follows.
 * @property models current models that can call tools, the default first. These track the
 *   providers' recommendations; any id the provider accepts can be entered instead.
 * @property envKeys environment variables that hold its API key, checked in order.
 * @property keyUrl where to create an API key; blank when it needs none.
 * @property requiresApiKey whether the provider rejects anonymous calls.
 */
@Serializable(with = LlmProviderSerializer::class)
enum class LlmProvider(
  val id: String,
  val label: String,
  val api: LlmApi,
  val group: LlmProviderGroup,
  val baseUrls: List<String>,
  val models: List<String>,
  val envKeys: List<String>,
  val keyUrl: String,
  val requiresApiKey: Boolean = true,
) {
  OpenAi(
    id = "openai",
    label = "OpenAI",
    api = LlmApi.OpenAiResponses,
    group = LlmProviderGroup.Hosted,
    baseUrls = listOf("https://api.openai.com"),
    models = listOf("gpt-5.6-terra", "gpt-6-astra", "gpt-6.1-sol", "gpt-6-luna"),
    envKeys = listOf("OPENAI_API_KEY"),
    keyUrl = "https://platform.openai.com/settings/organization/api-keys",
  ),
  Anthropic(
    id = "anthropic",
    label = "Anthropic",
    api = LlmApi.Anthropic,
    group = LlmProviderGroup.Hosted,
    baseUrls = listOf("https://api.anthropic.com"),
    models = listOf(
      "claude-opus-5-5",
      "claude-sonnet-5-5",
      "claude-haiku-5-5",
      "claude-fable-5-1",
    ),
    envKeys = listOf("ANTHROPIC_API_KEY"),
    keyUrl = "https://platform.claude.com/settings/keys",
  ),
  Google(
    id = "google",
    label = "Google Gemini",
    api = LlmApi.Google,
    group = LlmProviderGroup.Hosted,
    baseUrls = listOf("https://generativelanguage.googleapis.com"),
    models = listOf(
      "gemini-3.8-flash",
      "gemini-3.1-pro-preview",
      "gemini-3.6-flash",
      "gemini-3.5-flash-lite",
    ),
    envKeys = listOf("GEMINI_API_KEY", "GOOGLE_API_KEY"),
    keyUrl = "https://aistudio.google.com/apikey",
  ),
  OpenRouter(
    id = "openrouter",
    label = "OpenRouter",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.Hosted,
    baseUrls = listOf("https://openrouter.ai/api/v1"),
    models = listOf(
      "anthropic/claude-sonnet-5.5",
      "openai/gpt-6.1-sol",
      "google/gemini-3.8-flash",
      "moonshotai/kimi-k3",
    ),
    envKeys = listOf("OPENROUTER_API_KEY"),
    keyUrl = "https://openrouter.ai/settings/keys",
  ),
  DeepSeek(
    id = "deepseek",
    label = "DeepSeek",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.Hosted,
    // As documented: the OpenAI SDKs call chat/completions right after the host.
    baseUrls = listOf("https://api.deepseek.com/chat/completions"),
    models = listOf("deepseek-flash", "deepseek-v4-pro"),
    envKeys = listOf("DEEPSEEK_API_KEY"),
    keyUrl = "https://platform.deepseek.com/api_keys",
  ),
  Xai(
    id = "xai",
    label = "xAI",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.Hosted,
    baseUrls = listOf("https://api.x.ai/v1"),
    models = listOf("grok-4.7", "grok-4.6", "grok-4.3"),
    envKeys = listOf("XAI_API_KEY"),
    keyUrl = "https://console.x.ai/team/default/api-keys",
  ),
  Mistral(
    id = "mistral",
    label = "Mistral",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.Hosted,
    baseUrls = listOf("https://api.mistral.ai/v1"),
    models = listOf("mistral-medium-latest", "mistral-large-latest", "mistral-small-latest"),
    envKeys = listOf("MISTRAL_API_KEY"),
    keyUrl = "https://console.mistral.ai/api-keys",
  ),
  Groq(
    id = "groq",
    label = "Groq",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.Hosted,
    baseUrls = listOf("https://api.groq.com/openai/v1"),
    models = listOf(
      "openai/gpt-oss-120b",
      "qwen/qwen3.8-27b",
      "minimaxai/minimax-m2.7",
      "openai/gpt-oss-20b",
    ),
    envKeys = listOf("GROQ_API_KEY"),
    keyUrl = "https://console.groq.com/keys",
  ),
  Together(
    id = "together",
    label = "Together AI",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.Hosted,
    baseUrls = listOf("https://api.together.ai/v1"),
    models = listOf(
      "moonshotai/Kimi-K3",
      "zai-org/GLM-5.3",
      "MiniMaxAI/MiniMax-M3",
      "zai-org/GLM-5.3-Flash",
    ),
    envKeys = listOf("TOGETHER_API_KEY"),
    keyUrl = "https://api.together.xyz/settings/api-keys",
  ),
  Fireworks(
    id = "fireworks",
    label = "Fireworks AI",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.Hosted,
    baseUrls = listOf("https://api.fireworks.ai/inference/v1"),
    models = listOf(
      "accounts/fireworks/models/kimi-k3",
      "accounts/fireworks/models/glm-5p3",
      "accounts/fireworks/models/deepseek-v4p1-flash",
      "accounts/fireworks/models/minimax-m3",
    ),
    envKeys = listOf("FIREWORKS_API_KEY"),
    keyUrl = "https://app.fireworks.ai/settings/users/api-keys",
  ),
  Zhipu(
    id = "zhipu",
    label = "Zhipu AI",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.China,
    baseUrls = listOf("https://open.bigmodel.cn/api/paas/v4", "https://api.z.ai/api/paas/v4"),
    models = listOf("glm-5.3", "glm-5.2", "glm-4.7", "glm-4.7-flash"),
    envKeys = listOf("ZAI_API_KEY", "ZHIPUAI_API_KEY"),
    keyUrl = "https://bigmodel.cn/usercenter/proj-mgmt/apikeys",
  ),
  Moonshot(
    id = "moonshot",
    label = "Moonshot AI",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.China,
    baseUrls = listOf("https://api.moonshot.cn/v1", "https://api.moonshot.ai/v1"),
    models = listOf("kimi-k3", "kimi-k2.6", "kimi-k2.7-code"),
    envKeys = listOf("MOONSHOT_API_KEY"),
    keyUrl = "https://platform.kimi.com/console/api-keys",
  ),
  Qwen(
    id = "qwen",
    label = "Alibaba Model Studio",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.China,
    baseUrls = listOf(
      "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
    ),
    models = emptyList(),
    envKeys = listOf("DASHSCOPE_API_KEY"),
    keyUrl = "https://bailian.console.aliyun.com/?tab=model#/api-key",
  ),
  Doubao(
    id = "doubao",
    label = "Volcengine Ark",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.China,
    baseUrls = listOf("https://ark.cn-beijing.volces.com/api/v3"),
    models = emptyList(),
    envKeys = listOf("ARK_API_KEY"),
    keyUrl = "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey",
  ),
  SiliconFlow(
    id = "siliconflow",
    label = "SiliconFlow",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.China,
    baseUrls = listOf("https://api.siliconflow.cn/v1", "https://api.siliconflow.com/v1"),
    models = emptyList(),
    envKeys = listOf("SILICONFLOW_API_KEY"),
    keyUrl = "https://cloud.siliconflow.cn/account/ak",
  ),
  MiniMax(
    id = "minimax",
    label = "MiniMax",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.China,
    baseUrls = listOf("https://api.minimax.cn/v1", "https://api.minimax.io/v1"),
    models = listOf("MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.7-highspeed"),
    envKeys = listOf("MINIMAX_API_KEY"),
    keyUrl = "https://platform.minimax.cn/user-center/basic-information/interface-key",
  ),
  Ollama(
    id = "ollama",
    label = "Ollama",
    api = LlmApi.Ollama,
    group = LlmProviderGroup.Local,
    baseUrls = listOf("http://localhost:11434"),
    models = listOf("qwen3", "qwen3.6", "gpt-oss", "gemma4", "llama3.1:8b"),
    envKeys = emptyList(),
    keyUrl = "",
    requiresApiKey = false,
  ),
  LmStudio(
    id = "lmstudio",
    label = "LM Studio",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.Local,
    baseUrls = listOf("http://localhost:1234/v1"),
    models = emptyList(),
    envKeys = emptyList(),
    keyUrl = "",
    requiresApiKey = false,
  ),
  OpenAiCompatible(
    id = "openai-compatible",
    label = "OpenAI-compatible",
    api = LlmApi.OpenAiChat,
    group = LlmProviderGroup.Custom,
    baseUrls = emptyList(),
    models = emptyList(),
    envKeys = listOf("OPENAI_API_KEY"),
    keyUrl = "",
  ),
  ;

  /** Model used when an entry names none; blank when the user has to choose one. */
  val defaultModel: String
    get() = models.firstOrNull().orEmpty()

  /** Endpoint used when an entry names none; blank when the user has to enter one. */
  val defaultBaseUrl: String
    get() = baseUrls.firstOrNull().orEmpty()

  /** Whether the user must supply an endpoint. */
  val requiresBaseUrl: Boolean
    get() = baseUrls.isEmpty()

  companion object {
    /** The provider saved as [id], or `null` when this version knows none by it. */
    fun byId(id: String): LlmProvider? = entries.firstOrNull { it.id == id }
  }
}

internal object LlmProviderSerializer : KSerializer<LlmProvider> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.config.LlmProvider", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: LlmProvider) {
    encoder.encodeString(value.id)
  }

  override fun deserialize(decoder: Decoder): LlmProvider =
    LlmProvider.byId(decoder.decodeString()) ?: LlmProvider.OpenAiCompatible
}
