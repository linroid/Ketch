package com.linroid.ketch.ai

/**
 * Configuration for the AI resource discovery feature.
 *
 * @param enabled master switch; when `false`, AI endpoints return 404
 * @param llm LLM provider settings
 * @param search search provider settings
 * @param fetcher fetcher security settings
 * @param discovery discovery orchestration limits
 */
data class AiConfig(
  val enabled: Boolean = false,
  val llm: LlmConfig = LlmConfig(),
  val search: SearchConfig = SearchConfig(),
  val fetcher: FetcherConfig = FetcherConfig(),
  val discovery: DiscoveryConfig = DiscoveryConfig(),
)

/**
 * LLM provider configuration.
 *
 * @param provider provider type: "openai", "anthropic", "google"
 * @param apiKey API key (from env var or config)
 * @param model model name (e.g., "gpt-4o")
 * @param maxTokens max tokens for LLM response
 * @param baseUrl base URL override for compatible APIs
 */
data class LlmConfig(
  val provider: String = "openai",
  val apiKey: String = "",
  val model: String = "gpt-4o",
  val maxTokens: Int = 4096,
  val baseUrl: String? = null,
)

/**
 * Search provider configuration.
 *
 * @param provider search API provider type
 * @param apiKey search API key if needed
 */
data class SearchConfig(
  val provider: String = "llm",
  val apiKey: String = "",
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
