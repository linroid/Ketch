package com.linroid.ketch.ai.search

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Search provider backed by the Bing Web Search API v7.
 *
 * @param httpClient Ktor client with `ContentNegotiation` + JSON installed
 * @param apiKey Bing Search API subscription key
 * @param market market code for result localization (e.g. `"en-US"`)
 */
internal class BingSearchProvider(
  private val httpClient: HttpClient,
  private val apiKey: String,
  private val market: String = "en-US",
) : SearchProvider {

  override suspend fun search(
    query: String,
    sites: List<String>,
    maxResults: Int,
  ): List<SearchResult> = try {
    val fullQuery = buildQuery(query, sites)
    val response: BingSearchResponse = httpClient.get(API_URL) {
      header("Ocp-Apim-Subscription-Key", apiKey)
      parameter("q", fullQuery)
      parameter("count", maxResults.coerceIn(1, 50))
      parameter("mkt", market)
    }.body()
    response.webPages?.value?.map { page ->
      SearchResult(
        url = page.url,
        title = page.name,
        snippet = page.snippet,
      )
    } ?: emptyList()
  } catch (_: Exception) {
    emptyList()
  }

  companion object {
    private const val API_URL =
      "https://api.bing.microsoft.com/v7.0/search"

    /**
     * Builds a Bing search query with optional `site:` operators.
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
internal data class BingSearchResponse(
  val webPages: BingWebPages? = null,
)

@Serializable
internal data class BingWebPages(
  val value: List<BingWebPage> = emptyList(),
)

@Serializable
internal data class BingWebPage(
  val name: String,
  val url: String,
  val snippet: String = "",
  @SerialName("dateLastCrawled")
  val dateLastCrawled: String? = null,
)
