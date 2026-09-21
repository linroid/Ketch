package com.linroid.ketch.ai

import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.config.SearchProvider
import com.linroid.ketch.config.SearchSettings

/**
 * Configuration for the AI resource discovery feature.
 *
 * [settings] holds everything the user configures (and what the apps
 * persist in `config.toml`); the remaining sections are engine tuning
 * knobs that are not exposed in the UI.
 *
 * @param settings user-facing LLM and search settings
 * @param fetcher fetcher security settings
 * @param discovery discovery orchestration limits
 * @param agent agent execution limits
 */
data class AiConfig(
  val settings: AiSettings = AiSettings(),
  val fetcher: FetcherConfig = FetcherConfig(),
  val discovery: DiscoveryConfig = DiscoveryConfig(),
  val agent: AgentConfig = AgentConfig(),
) {
  /** Master switch; when `false`, discovery returns no candidates. */
  val enabled: Boolean get() = settings.enabled

  /** LLM connection settings. */
  val llm: LlmSettings get() = settings.llm

  /** Web search settings. */
  val search: SearchSettings get() = settings.search
}

/**
 * Fills blank credentials in [base] from environment variables.
 *
 * The API key for the configured provider is read from that provider's
 * conventional variable (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`,
 * `GEMINI_API_KEY`/`GOOGLE_API_KEY`). When [base] is still at its
 * defaults, any of those variables also selects the provider and
 * switches discovery on, which keeps the "export a key and go" flow
 * working for the CLI. The apps have an explicit Enable switch, so they
 * only resolve settings the user has switched on.
 *
 * Search works the same way: the selected provider's blank key (and,
 * for Google, engine id) are filled from `BING_SEARCH_API_KEY`, or
 * `GOOGLE_SEARCH_API_KEY` / `GOOGLE_SEARCH_CX`, keeping every saved
 * value and never switching providers. Only untouched settings let the
 * environment pick a search provider.
 *
 * @param base settings loaded from the config file
 * @param getenv environment lookup, overridable for testing
 */
fun resolveAiSettingsFromEnv(
  base: AiSettings = AiSettings(),
  getenv: (String) -> String? = System::getenv,
): AiSettings {
  val llm = resolveLlmFromEnv(base, getenv)
  val search = resolveSearchFromEnv(base, getenv)
  val enabled = base.enabled ||
    (base == AiSettings() && llm.apiKey.isNotBlank())
  return base.copy(enabled = enabled, llm = llm, search = search)
}

private fun resolveLlmFromEnv(
  base: AiSettings,
  getenv: (String) -> String?,
): LlmSettings {
  val llm = base.llm
  if (llm.apiKey.isNotBlank()) return llm
  val configured = envKeyFor(llm.provider, getenv)
  if (configured != null) return llm.copy(apiKey = configured)
  // Untouched settings: let any provider key pick the provider.
  if (base != AiSettings()) return llm
  for (provider in ENV_PROVIDER_ORDER) {
    val key = envKeyFor(provider, getenv) ?: continue
    return llm.copy(provider = provider, apiKey = key)
  }
  return llm
}

private fun envKeyFor(
  provider: LlmProvider,
  getenv: (String) -> String?,
): String? = when (provider) {
  LlmProvider.OpenAi,
  LlmProvider.OpenAiCompatible,
  -> getenv("OPENAI_API_KEY")
  LlmProvider.Anthropic -> getenv("ANTHROPIC_API_KEY")
  LlmProvider.Google ->
    getenv("GEMINI_API_KEY") ?: getenv("GOOGLE_API_KEY")
  LlmProvider.Ollama -> null
}?.takeIf { it.isNotBlank() }

private fun resolveSearchFromEnv(
  base: AiSettings,
  getenv: (String) -> String?,
): SearchSettings {
  val search = base.search
  fun env(name: String): String? = getenv(name)?.takeIf { it.isNotBlank() }
  return when (search.provider) {
    // A chosen provider only has its own blanks filled; saved values
    // and the choice itself always win.
    SearchProvider.Bing -> search.copy(
      apiKey = search.apiKey.ifBlank { env(BING_KEY).orEmpty() },
    )
    SearchProvider.Google -> search.copy(
      apiKey = search.apiKey.ifBlank { env(GOOGLE_KEY).orEmpty() },
      cx = search.cx.ifBlank { env(GOOGLE_CX).orEmpty() },
    )
    // "None" is also the default, so it only yields to the environment
    // while nothing has been configured — the CLI's "export and go".
    SearchProvider.None -> {
      if (base != AiSettings()) return search
      val bing = env(BING_KEY)
      val googleKey = env(GOOGLE_KEY)
      val googleCx = env(GOOGLE_CX)
      when {
        bing != null ->
          SearchSettings(provider = SearchProvider.Bing, apiKey = bing)
        googleKey != null && googleCx != null -> SearchSettings(
          provider = SearchProvider.Google,
          apiKey = googleKey,
          cx = googleCx,
        )
        else -> search
      }
    }
  }
}

private const val BING_KEY = "BING_SEARCH_API_KEY"
private const val GOOGLE_KEY = "GOOGLE_SEARCH_API_KEY"
private const val GOOGLE_CX = "GOOGLE_SEARCH_CX"

private val ENV_PROVIDER_ORDER = listOf(
  LlmProvider.OpenAi,
  LlmProvider.Anthropic,
  LlmProvider.Google,
)

/**
 * Fetcher security settings.
 *
 * @param maxContentBytes max content size per fetch in bytes
 * @param requestTimeoutMs request timeout in milliseconds
 * @param maxFetchesPerRequest max fetches per discovery request
 * @param maxTotalBytesPerRequest max total bytes per discovery
 */
data class FetcherConfig(
  val maxContentBytes: Long = 2L * 1024 * 1024,
  val requestTimeoutMs: Long = 15_000,
  val maxFetchesPerRequest: Int = 20,
  val maxTotalBytesPerRequest: Long = 20L * 1024 * 1024,
)

/**
 * Discovery orchestration limits.
 *
 * @param maxConcurrentRequests max concurrent discovery requests
 * @param userAgent User-Agent string for fetching
 * @param allowedDomains allowlisted domains; empty = allow all public
 */
data class DiscoveryConfig(
  val maxConcurrentRequests: Int = 3,
  val userAgent: String = "KetchBot/1.0",
  val allowedDomains: List<String> = emptyList(),
)

/**
 * Agent execution configuration.
 *
 * @param maxIterations maximum agent tool-call iterations
 * @param temperature LLM sampling temperature
 */
data class AgentConfig(
  val maxIterations: Int = 30,
  val temperature: Double = 0.2,
)
