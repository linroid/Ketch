package com.linroid.ketch.ai

import com.linroid.ketch.ai.fetch.ContentExtractor
import com.linroid.ketch.ai.fetch.RateLimiter
import com.linroid.ketch.ai.fetch.SafeFetcher
import com.linroid.ketch.ai.fetch.UrlValidator
import com.linroid.ketch.ai.llm.DummyLlmProvider
import com.linroid.ketch.ai.llm.KoogLlmProvider
import com.linroid.ketch.ai.llm.LlmProvider
import com.linroid.ketch.ai.search.DummySearchProvider
import com.linroid.ketch.ai.search.SearchProvider
import com.linroid.ketch.ai.site.SiteProfileStore
import com.linroid.ketch.ai.site.SiteProfiler
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

/**
 * Pre-built AI module components ready for integration.
 *
 * Can be used standalone (CLI, custom server, programmatic) or
 * wired into the Ketch daemon server.
 *
 * Use [AiModule.create] for convenient construction from an [AiConfig],
 * or construct directly with custom providers.
 */
class AiModule(
  val discoveryService: ResourceDiscoveryService,
  val siteProfiler: SiteProfiler,
  val siteProfileStore: SiteProfileStore,
) {

  companion object {
    /**
     * Creates a fully wired AI module from [config].
     *
     * @param config AI configuration settings
     * @param llmProvider custom LLM provider, or `null` to auto-detect
     *   from [config] (uses Koog/OpenAI when API key is set, otherwise
     *   a no-op dummy)
     * @param searchProvider custom search provider, or `null` to use
     *   the default no-op provider
     */
    fun create(
      config: AiConfig,
      llmProvider: LlmProvider? = null,
      searchProvider: SearchProvider? = null,
    ): AiModule {
      val urlValidator = UrlValidator()
      val fetcherClient = HttpClient {
        install(HttpTimeout) {
          requestTimeoutMillis = config.fetcher.requestTimeoutMs
        }
      }
      val fetcher = SafeFetcher(
        httpClient = fetcherClient,
        urlValidator = urlValidator,
        maxContentBytes = config.fetcher.maxContentBytes,
        userAgent = config.discovery.userAgent,
      )
      val rateLimiter = RateLimiter(
        maxGlobalConcurrent =
          config.discovery.maxConcurrentRequests,
      )
      val contentExtractor = ContentExtractor()
      val siteProfileStore = SiteProfileStore()
      val siteProfiler = SiteProfiler(fetcher)

      val resolvedLlmProvider = llmProvider ?: run {
        if (config.llm.apiKey.isNotBlank()) {
          KoogLlmProvider(
            apiKey = config.llm.apiKey,
            model = config.llm.model,
            maxTokens = config.llm.maxTokens,
            urlValidator = urlValidator,
          )
        } else {
          DummyLlmProvider()
        }
      }

      val resolvedSearchProvider =
        searchProvider ?: DummySearchProvider()

      val discoveryService = ResourceDiscoveryService(
        llmProvider = resolvedLlmProvider,
        searchProvider = resolvedSearchProvider,
        fetcher = fetcher,
        urlValidator = urlValidator,
        contentExtractor = contentExtractor,
        siteProfileStore = siteProfileStore,
        rateLimiter = rateLimiter,
        config = config,
      )

      return AiModule(
        discoveryService = discoveryService,
        siteProfiler = siteProfiler,
        siteProfileStore = siteProfileStore,
      )
    }
  }
}
