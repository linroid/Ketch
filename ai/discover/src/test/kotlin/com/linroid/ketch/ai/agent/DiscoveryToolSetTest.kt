package com.linroid.ketch.ai.agent

import com.linroid.ketch.ai.fetch.ContentExtractor
import com.linroid.ketch.ai.fetch.SafeFetcher
import com.linroid.ketch.ai.fetch.UrlValidator
import com.linroid.ketch.ai.fetch.fakeDns
import com.linroid.ketch.ai.search.SearchProvider
import com.linroid.ketch.ai.search.SearchResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondRedirect
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryToolSetTest {

  private val dns = fakeDns(
    "ubuntu.com" to "203.0.113.1",
    "releases.ubuntu.com" to "203.0.113.2",
    "github.com" to "203.0.113.3",
    "objects.githubusercontent.com" to "203.0.113.4",
    "evil.example" to "203.0.113.5",
  )

  private val requested = mutableListOf<String>()
  private val search = FakeSearchProvider()

  private val engine = MockEngine { request ->
    requested += request.url.toString()
    when (request.url.host) {
      "github.com" -> respondRedirect(CDN_URL)
      else -> respond("<html><head><title>Page</title></head><body>ok</body></html>")
    }
  }

  private fun toolSet(vararg sites: String): DiscoveryToolSet {
    val validator = UrlValidator(dns)
    return DiscoveryToolSet(
      searchProvider = search,
      fetcher = SafeFetcher(HttpClient(engine) { followRedirects = false }, validator),
      urlValidator = validator,
      contentExtractor = ContentExtractor(),
      linkExtractor = LinkExtractor(),
      stepListener = DiscoveryStepListener.None,
      json = Json,
      allowlist = SiteAllowlist.of(sites.toList()),
    )
  }

  private fun parseObject(output: String): JsonObject = Json.parseToJsonElement(output).jsonObject

  private fun parseUrls(output: String): List<String> {
    return Json.parseToJsonElement(output).jsonArray
      .map { it.jsonObject.getValue("url").jsonPrimitive.content }
  }

  @Test
  fun fetchPage_urlOutsideAllowlist_returnsErrorWithoutRequest() = runTest {
    val result = parseObject(toolSet("ubuntu.com").fetchPage("https://evil.example/ubuntu.iso"))

    assertTrue(result.getValue("error").jsonPrimitive.content.contains("evil.example"))
    val allowedSites = result.getValue("allowedSites").jsonArray.map { it.jsonPrimitive.content }
    assertEquals(listOf("ubuntu.com"), allowedSites)
    assertTrue(requested.isEmpty())
  }

  @Test
  fun fetchPage_subdomainOfAllowedSite_fetchesPage() = runTest {
    val result = parseObject(toolSet("ubuntu.com").fetchPage("https://releases.ubuntu.com/24.04/"))

    assertNull(result["error"])
    assertEquals("Page", result.getValue("title").jsonPrimitive.content)
  }

  @Test
  fun headUrl_allowedSiteRedirectingToCdn_followsRedirect() = runTest {
    val asset = "https://github.com/owner/repo/releases/download/v1/app.zip"

    val result = parseObject(toolSet("github.com").headUrl(asset))

    assertNull(result["error"])
    assertEquals(asset, result.getValue("url").jsonPrimitive.content)
    assertEquals(CDN_URL, result.getValue("finalUrl").jsonPrimitive.content)
  }

  @Test
  fun headUrl_cdnUrlRequestedDirectly_isRefused() = runTest {
    val result = parseObject(toolSet("github.com").headUrl(CDN_URL))

    assertTrue(result.containsKey("error"))
    assertTrue(requested.isEmpty())
  }

  @Test
  fun searchWeb_restricted_scopesProviderAndDropsOffListResults() = runTest {
    search.results = listOf(
      SearchResult("https://ubuntu.com/download", "Download", ""),
      SearchResult("https://evil.example/ubuntu", "Mirror", ""),
    )

    val urls = parseUrls(toolSet("ubuntu.com").searchWeb("ubuntu iso"))

    assertEquals(listOf("ubuntu.com"), search.lastSites)
    assertEquals(listOf("https://ubuntu.com/download"), urls)
  }

  @Test
  fun searchWeb_unrestricted_searchesEverywhere() = runTest {
    search.results = listOf(SearchResult("https://evil.example/ubuntu", "Mirror", ""))

    val urls = parseUrls(toolSet().searchWeb("ubuntu iso"))

    assertEquals(emptyList(), search.lastSites)
    assertEquals(listOf("https://evil.example/ubuntu"), urls)
  }

  @Test
  fun searchSites_siteOutsideAllowlist_returnsErrorWithoutSearching() = runTest {
    val result = parseObject(toolSet("ubuntu.com").searchSites("askubuntu.com, ubuntu.com", "iso"))

    assertTrue(result.getValue("error").jsonPrimitive.content.contains("askubuntu.com"))
    assertNull(search.lastSites)
  }

  @Test
  fun searchSites_allowedSiteWithScheme_searchesNormalizedDomain() = runTest {
    toolSet("ubuntu.com").searchSites("https://releases.ubuntu.com/", "iso")

    assertEquals(listOf("releases.ubuntu.com"), search.lastSites)
  }

  @Test
  fun searchSites_blankSites_searchesAllowedSites() = runTest {
    toolSet("ubuntu.com", "blender.org").searchSites("", "iso")

    assertEquals(listOf("ubuntu.com", "blender.org"), search.lastSites)
  }

  @Test
  fun validateUrl_urlOutsideAllowlist_isNotOk() {
    val toolSet = toolSet("ubuntu.com")

    val offList = parseObject(toolSet.validateUrl("https://evil.example/ubuntu.iso"))
    val onList = parseObject(toolSet.validateUrl("https://releases.ubuntu.com/ubuntu.iso"))

    assertFalse(offList.getValue("ok").jsonPrimitive.boolean)
    assertTrue(onList.getValue("ok").jsonPrimitive.boolean)
  }

  private companion object {
    const val CDN_URL = "https://objects.githubusercontent.com/assets/app.zip"
  }

  private class FakeSearchProvider : SearchProvider {
    var results: List<SearchResult> = emptyList()
    var lastSites: List<String>? = null

    override suspend fun search(
      query: String,
      sites: List<String>,
      maxResults: Int,
    ): List<SearchResult> {
      lastSites = sites
      return results
    }
  }
}
