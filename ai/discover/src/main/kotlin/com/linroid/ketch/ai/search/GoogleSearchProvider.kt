package com.linroid.ketch.ai.search

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

/**
 * Search provider backed by the Google Custom Search JSON API.
 *
 * @param httpClient Ktor client with `ContentNegotiation` + JSON installed
 * @param apiKey Google API key
 * @param cx Custom Search Engine ID
 */
internal class GoogleSearchProvider(
  private val httpClient: HttpClient,
  private val apiKey: String,
  private val cx: String,
) : SearchProvider {

  override suspend fun search(
    query: String,
    sites: List<String>,
    maxResults: Int,
  ): List<SearchResult> = try {
    val num = maxResults.coerceIn(1, 10)
    val response: GoogleSearchResponse = httpClient.get(API_URL) {
      parameter("key", apiKey)
      parameter("cx", cx)
      parameter("num", num)
      if (sites.size == 1) {
        parameter("q", query)
        parameter("siteSearch", sites.single())
      } else {
        parameter("q", buildMultiSiteQuery(query, sites))
      }
    }.body()
    response.items?.map { item ->
      SearchResult(
        url = item.link,
        title = item.title,
        snippet = item.snippet ?: "",
      )
    } ?: emptyList()
  } catch (_: Exception) {
    emptyList()
  }

  companion object {
    private const val API_URL =
      "https://www.googleapis.com/customsearch/v1"

    /**
     * Builds a query with `site:` operators for multiple domains.
     * For zero or one site, returns [query] unchanged.
     */
    internal fun buildMultiSiteQuery(
      query: String,
      sites: List<String>,
    ): String {
      if (sites.size <= 1) return query
      val siteOps = sites.joinToString(" OR ") { "site:$it" }
      return "$query ($siteOps)"
    }
  }
}

@Serializable
internal data class GoogleSearchResponse(
  val items: List<GoogleSearchItem>? = null,
)

@Serializable
internal data class GoogleSearchItem(
  val title: String,
  val link: String,
  val snippet: String? = null,
)
