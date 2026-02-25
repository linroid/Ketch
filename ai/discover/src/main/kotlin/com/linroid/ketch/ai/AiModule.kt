package com.linroid.ketch.ai

import com.linroid.ketch.ai.agent.DiscoveryStepListener
import com.linroid.ketch.ai.fetch.ContentExtractor
import com.linroid.ketch.ai.fetch.SafeFetcher
import com.linroid.ketch.ai.fetch.UrlValidator
import com.linroid.ketch.ai.search.BingSearchProvider
import com.linroid.ketch.ai.search.DummySearchProvider
import com.linroid.ketch.ai.search.GoogleSearchProvider
import com.linroid.ketch.ai.search.SearchProvider
import com.linroid.ketch.ai.site.SiteProfileStore
import com.linroid.ketch.ai.site.SiteProfiler
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

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
     * @param searchProvider custom search provider, or `null` to use
     *   the default no-op provider
     * @param stepListener optional listener for agent progress steps
     */
    fun create(
      config: AiConfig,
      searchProvider: SearchProvider? = null,
      stepListener: DiscoveryStepListener = DiscoveryStepListener.None,
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
      val contentExtractor = ContentExtractor()
      val siteProfileStore = SiteProfileStore()
      val siteProfiler = SiteProfiler(fetcher)

      val resolvedSearchProvider =
        searchProvider ?: resolveSearchProvider(
          config.search,
          createSearchClient(),
        )

      val discoveryService = ResourceDiscoveryService(
        searchProvider = resolvedSearchProvider,
        fetcher = fetcher,
        urlValidator = urlValidator,
        contentExtractor = contentExtractor,
        config = config,
        stepListener = stepListener,
      )

      return AiModule(
        discoveryService = discoveryService,
        siteProfiler = siteProfiler,
        siteProfileStore = siteProfileStore,
      )
    }

    private fun createSearchClient(): HttpClient = HttpClient {
      install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
      }
    }

    internal fun resolveSearchProvider(
      config: SearchConfig,
      httpClient: HttpClient,
    ): SearchProvider = when (config.provider.lowercase()) {
      "bing" -> BingSearchProvider(httpClient, config.apiKey)
      "google" -> GoogleSearchProvider(
        httpClient,
        config.apiKey,
        config.cx,
      )
      else -> DummySearchProvider()
    }
  }
}
