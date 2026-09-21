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
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.config.SearchProvider as SearchProviderKind
import com.linroid.ketch.config.SearchSettings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

private val log = KetchLogger("AiModule")

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
  private val httpClients: List<HttpClient> = emptyList(),
) {

  /**
   * Releases the HTTP clients this module created.
   *
   * Call it when replacing a module after a settings change; the Ktor
   * engines own thread pools that would otherwise be leaked.
   */
  fun close() {
    httpClients.forEach(HttpClient::close)
  }

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

      val searchClient = createSearchClient(config.fetcher.requestTimeoutMs)
      val resolvedSearchProvider =
        searchProvider ?: resolveSearchProvider(config.search, searchClient)

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
        httpClients = listOf(fetcherClient, searchClient),
      )
    }

    private fun createSearchClient(requestTimeoutMs: Long): HttpClient =
      HttpClient {
        install(HttpTimeout) {
          requestTimeoutMillis = requestTimeoutMs
        }
        install(ContentNegotiation) {
          json(Json { ignoreUnknownKeys = true })
        }
      }

    internal fun resolveSearchProvider(
      settings: SearchSettings,
      httpClient: HttpClient,
    ): SearchProvider = when {
      !settings.isComplete -> {
        log.w {
          "${settings.provider.label} search is missing credentials;" +
            " falling back to no-op"
        }
        DummySearchProvider()
      }
      settings.provider == SearchProviderKind.Bing ->
        BingSearchProvider(httpClient, settings.apiKey)
      settings.provider == SearchProviderKind.Google ->
        GoogleSearchProvider(httpClient, settings.apiKey, settings.cx)
      else -> DummySearchProvider()
    }
  }
}
