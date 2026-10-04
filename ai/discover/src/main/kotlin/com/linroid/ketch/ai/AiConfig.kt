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
 * `GEMINI_API_KEY`/`GOOGLE_API_KEY`). When [autoConfigure] is on and
 * [base] is still at its defaults, any of those variables also selects
 * the provider and switches discovery on, which keeps the "export a key
 * and go" flow working for the CLI (discovery is on by default, and a
 * config that switched it off is not untouched). The apps turn it off, so the
 * environment never picks a provider their settings page does not show.
 *
 * Search works the same way: the selected provider's blank key (and,
 * for Google, engine id) are filled from `BRAVE_SEARCH_API_KEY`, or
 * `GOOGLE_SEARCH_API_KEY` / `GOOGLE_SEARCH_CX`, keeping every saved
 * value and never switching providers. Only untouched settings, with
 * [autoConfigure], let the environment pick a search provider.
 *
 * @param base settings loaded from the config file
 * @param getenv environment lookup, overridable for testing
 * @param autoConfigure whether untouched settings may take their
 *   providers from the environment
 */
fun resolveAiSettingsFromEnv(
  base: AiSettings = AiSettings(),
  getenv: (String) -> String? = System::getenv,
  autoConfigure: Boolean = true,
): AiSettings {
  val untouched = autoConfigure && base.engineSettings == AiSettings()
  val llm = resolveLlmFromEnv(base.llm, untouched, getenv)
  val search = resolveSearchFromEnv(base.search, untouched, getenv)
  return base.copy(llm = llm, search = search)
}

private fun resolveLlmFromEnv(
  llm: LlmSettings,
  untouched: Boolean,
  getenv: (String) -> String?,
): LlmSettings {
  if (llm.apiKey.isNotBlank()) return llm
  val configured = envKeyFor(llm.provider, getenv)
  if (configured != null) return llm.copy(apiKey = configured)
  // Untouched settings: let any provider key pick the provider.
  if (!untouched) return llm
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
  search: SearchSettings,
  untouched: Boolean,
  getenv: (String) -> String?,
): SearchSettings {
  fun env(name: String): String? = getenv(name)?.takeIf { it.isNotBlank() }
  return when (search.provider) {
    // A chosen provider only has its own blanks filled; saved values
    // and the choice itself always win.
    SearchProvider.Brave -> search.copy(
      apiKey = search.apiKey.ifBlank { env(BRAVE_KEY).orEmpty() },
    )
    SearchProvider.Google -> search.copy(
      apiKey = search.apiKey.ifBlank { env(GOOGLE_KEY).orEmpty() },
      cx = search.cx.ifBlank { env(GOOGLE_CX).orEmpty() },
    )
    // "None" is also the default, so it only yields to the environment
    // while nothing has been configured — the CLI's "export and go".
    SearchProvider.None -> {
      if (!untouched) return search
      val brave = env(BRAVE_KEY)
      val googleKey = env(GOOGLE_KEY)
      val googleCx = env(GOOGLE_CX)
      when {
        brave != null ->
          SearchSettings(provider = SearchProvider.Brave, apiKey = brave)
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

private const val BRAVE_KEY = "BRAVE_SEARCH_API_KEY"
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
 * @param maxContentBytes max page body bytes read per fetch
 * @param requestTimeoutMs request timeout in milliseconds
 * @param maxFetchesPerRequest page fetches and HEAD requests the agent
 *   may make in one discovery run; the default fits the 10 page
 *   fetches and 15 HEAD requests the agent is told to stay within
 * @param maxTotalBytesPerRequest page body bytes the agent may read in
 *   one discovery run
 */
data class FetcherConfig(
  val maxContentBytes: Long = 2L * 1024 * 1024,
  val requestTimeoutMs: Long = 15_000,
  val maxFetchesPerRequest: Int = 25,
  val maxTotalBytesPerRequest: Long = 20L * 1024 * 1024,
)

/**
 * Discovery orchestration limits.
 *
 * @param maxConcurrentRequests max page and HEAD requests in flight at
 *   once, across all discovery runs of an [AiModule]
 * @param userAgent User-Agent string for fetching; the part before `/`
 *   is the token matched against robots.txt
 * @param allowedDomains domains every discovery is limited to, subdomains
 *   included; empty = all public sites. [DiscoverQuery.sites] can only
 *   narrow this list, never widen it.
 */
data class DiscoveryConfig(
  val maxConcurrentRequests: Int = 3,
  val userAgent: String = "KetchBot/1.0",
  val allowedDomains: List<String> = emptyList(),
)

/**
 * Agent execution configuration.
 *
 * @param maxToolCalls tool calls the agent may make in one discovery
 *   run, progress steps included; once they are spent, every tool asks
 *   the agent to return what it found. The default fits the searches,
 *   page fetches and HEAD requests the agent is told to stay within,
 *   plus its progress steps
 * @param temperature LLM sampling temperature
 */
data class AgentConfig(
  val maxToolCalls: Int = 40,
  val temperature: Double = 0.2,
)
