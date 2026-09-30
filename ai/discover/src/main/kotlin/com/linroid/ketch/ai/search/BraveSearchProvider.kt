package com.linroid.ketch.ai.search

import com.linroid.ketch.api.log.KetchLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

/**
 * Search provider backed by the Brave Search API (web search).
 *
 * @param httpClient Ktor client with `ContentNegotiation` + JSON installed
 * @param apiKey Brave Search API subscription token
 */
internal class BraveSearchProvider(
  private val httpClient: HttpClient,
  private val apiKey: String,
) : SearchProvider {

  private val log = KetchLogger("BraveSearch")

  override suspend fun search(
    query: String,
    sites: List<String>,
    maxResults: Int,
  ): List<SearchResult> = try {
    val response = httpClient.get(API_URL) {
      header(SUBSCRIPTION_TOKEN_HEADER, apiKey)
      header(HttpHeaders.Accept, ContentType.Application.Json.toString())
      parameter("q", buildQuery(query, sites))
      parameter("count", maxResults.coerceIn(1, MAX_COUNT))
    }
    if (!response.status.isSuccess()) {
      // A bad token or spent quota would otherwise look like "no results".
      log.w { "Brave search failed: HTTP ${response.status.value}" }
      emptyList()
    } else {
      response.body<BraveSearchResponse>().web?.results.orEmpty().map { result ->
        SearchResult(
          url = result.url,
          title = result.title,
          snippet = result.description,
        )
      }
    }
  } catch (e: Exception) {
    if (e is CancellationException) currentCoroutineContext().ensureActive()
    log.w(e) { "Brave search failed for query: $query" }
    emptyList()
  }

  companion object {
    private const val API_URL = "https://api.search.brave.com/res/v1/web/search"
    private const val SUBSCRIPTION_TOKEN_HEADER = "X-Subscription-Token"
    private const val MAX_COUNT = 20

    /**
     * Builds a Brave search query with optional `site:` operators.
     */
    internal fun buildQuery(
      query: String,
      sites: List<String>,
    ): String {
      if (sites.isEmpty()) return query
      val siteOps = sites.joinToString(" OR ") { "site:$it" }
      return "$query ($siteOps)"
    }
  }
}

@Serializable
internal data class BraveSearchResponse(
  val web: BraveWebResults? = null,
)

@Serializable
internal data class BraveWebResults(
  val results: List<BraveWebResult> = emptyList(),
)

@Serializable
internal data class BraveWebResult(
  val title: String,
  val url: String,
  val description: String = "",
)
