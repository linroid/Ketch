package com.linroid.ketch.ai.agent

import com.linroid.ketch.ai.fetch.ContentExtractor
import com.linroid.ketch.ai.fetch.FetchBudget
import com.linroid.ketch.ai.fetch.RateLimiter
import com.linroid.ketch.ai.fetch.SafeFetcher
import com.linroid.ketch.ai.fetch.UrlValidator
import com.linroid.ketch.ai.fetch.fakeDns
import com.linroid.ketch.ai.search.DummySearchProvider
import com.linroid.ketch.ai.search.SearchProvider
import com.linroid.ketch.ai.search.SearchResult
import com.linroid.ketch.ai.site.SiteProfiler
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryToolSetTest {

  private val validator = UrlValidator(
    resolve = fakeDns(
      "example.com" to "93.184.215.14",
      "cdn.example.net" to "151.101.1.1",
      "releases.ubuntu.com" to "185.125.190.40",
      "github.com" to "140.82.112.3",
      "objects.githubusercontent.com" to "185.199.108.133",
      "evil.example" to "203.0.113.5",
    ),
  )

  private val ubuntuOnly = SiteAllowlist.of(listOf("ubuntu.com"))
  private val githubOnly = SiteAllowlist.of(listOf("github.com"))

  private fun toolSet(
    engine: MockEngine,
    budget: FetchBudget = FetchBudget(maxRequests = 25, maxBytes = 1024 * 1024),
    allowlist: SiteAllowlist = SiteAllowlist.Unrestricted,
    searchProvider: SearchProvider = DummySearchProvider(),
  ): DiscoveryToolSet {
    val fetcher = SafeFetcher(
      httpClient = HttpClient(engine) { followRedirects = false },
      urlValidator = validator,
      rateLimiter = RateLimiter(delayMs = 0),
    )
    return DiscoveryToolSet(
      searchProvider = searchProvider,
      fetcher = fetcher,
      urlValidator = validator,
      contentExtractor = ContentExtractor(),
      linkExtractor = LinkExtractor(),
      siteProfiler = SiteProfiler(fetcher),
      budget = budget,
      stepListener = DiscoveryStepListener.None,
      json = Json,
      allowlist = allowlist,
    )
  }

  /** Serves [robots] at `/robots.txt` (404 when `null`) and [page] elsewhere. */
  private fun site(robots: String?, page: String = "<html>page</html>"): MockRequestHandler =
    { request ->
      when {
        request.url.encodedPath != "/robots.txt" -> respond(page)
        robots == null -> respond("", HttpStatusCode.NotFound)
        else -> respond(robots)
      }
    }

  private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

  private fun JsonObject.error(): String? = this["error"]?.jsonPrimitive?.content

  private val MockEngine.requestedPaths: List<String>
    get() = requestHistory.map { it.url.encodedPath }

  @Test
  fun fetchPage_disallowedByRobots_isNotFetched() = runTest {
    val engine = MockEngine(site(robots = "User-agent: *\nDisallow: /private"))

    val result = parse(toolSet(engine).fetchPage("https://example.com/private/notes.html"))

    val error = assertNotNull(result.error())
    assertTrue("robots.txt" in error, error)
    assertEquals(listOf("/robots.txt"), engine.requestedPaths)
  }

  @Test
  fun fetchPage_redirectToDisallowedPath_isNotFollowed() = runTest {
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/robots.txt" -> respond("User-agent: *\nDisallow: /private")
        "/go" ->
          respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/private/notes"))
        else -> respond("<html>private</html>")
      }
    }

    val result = parse(toolSet(engine).fetchPage("https://example.com/go"))

    val error = assertNotNull(result.error())
    assertTrue("robots.txt" in error, error)
    assertEquals(listOf("/robots.txt", "/go"), engine.requestedPaths)
  }

  @Test
  fun fetchPage_missingRobots_allowsAndIsReadOncePerOrigin() = runTest {
    val engine = MockEngine(site(robots = null))
    val tools = toolSet(engine)

    assertNull(parse(tools.fetchPage("https://example.com/a")).error())
    assertNull(parse(tools.fetchPage("https://example.com/b")).error())

    assertEquals(listOf("/robots.txt", "/a", "/b"), engine.requestedPaths)
  }

  @Test
  fun fetchPage_requestBudgetSharedWithHead_stopsFetching() = runTest {
    val engine = MockEngine(site(robots = null))
    val tools = toolSet(engine, FetchBudget(maxRequests = 2, maxBytes = 1024))

    assertNull(parse(tools.fetchPage("https://example.com/a")).error())
    assertNull(parse(tools.headUrl("https://example.com/file.iso")).error())
    val error = parse(tools.fetchPage("https://example.com/b")).error()

    assertNotNull(error)
    assertTrue("/b" !in engine.requestedPaths)
  }

  @Test
  fun fetchPage_byteBudgetSpent_stopsFetching() = runTest {
    val engine = MockEngine(site(robots = null, page = "x".repeat(100)))
    val tools = toolSet(engine, FetchBudget(maxRequests = 25, maxBytes = 40))

    val first = parse(tools.fetchPage("https://example.com/a"))
    val second = parse(tools.fetchPage("https://example.com/b"))

    // The first page is cut to the 40 bytes the run had left.
    assertEquals("x".repeat(40), first["text"]?.jsonPrimitive?.content)
    assertNotNull(second.error())
    assertTrue("/b" !in engine.requestedPaths)
  }

  @Test
  fun fetchPage_redirect_resolvesLinksAgainstFinalUrl() = runTest {
    val engine = MockEngine { request ->
      when {
        request.url.encodedPath == "/robots.txt" -> respond("", HttpStatusCode.NotFound)
        request.url.host == "example.com" -> respond(
          "",
          HttpStatusCode.Found,
          headersOf(HttpHeaders.Location, "https://cdn.example.net/pub/"),
        )
        else -> respond("""<a href="tool-1.0.zip">Download</a>""")
      }
    }

    val result = parse(toolSet(engine).fetchPage("https://example.com/releases"))

    assertEquals("https://cdn.example.net/pub/", result["url"]?.jsonPrimitive?.content)
    val link = result["links"]?.jsonArray?.single()?.jsonObject
    val linkUrl = link?.get("url")?.jsonPrimitive?.content
    assertEquals("https://cdn.example.net/pub/tool-1.0.zip", linkUrl)
  }

  @Test
  fun fetchPage_urlOutsideAllowlist_isRefusedWithoutSpendingBudget() = runTest {
    val engine = MockEngine(site(robots = null))
    val tools = toolSet(engine, FetchBudget(maxRequests = 1, maxBytes = 1024), ubuntuOnly)

    val refused = parse(tools.fetchPage("https://evil.example/ubuntu.iso"))

    val error = assertNotNull(refused.error())
    assertTrue("evil.example" in error, error)
    val allowedSites = refused["allowedSites"]?.jsonArray?.map { it.jsonPrimitive.content }
    assertEquals(listOf("ubuntu.com"), allowedSites)
    assertTrue(engine.requestHistory.isEmpty())
    // The single request is still there for a page on an allowed subdomain.
    assertNull(parse(tools.fetchPage("https://releases.ubuntu.com/24.04/")).error())
  }

  @Test
  fun headUrl_allowedSiteRedirectingToCdn_reportsRequestedAndFinalUrl() = runTest {
    val engine = MockEngine { request ->
      if (request.url.host == "github.com") {
        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, CDN_URL))
      } else {
        respond("")
      }
    }
    val asset = "https://github.com/owner/repo/releases/download/v1/app.zip"

    val result = parse(toolSet(engine, allowlist = githubOnly).headUrl(asset))

    assertNull(result.error())
    assertEquals(asset, result["url"]?.jsonPrimitive?.content)
    assertEquals(CDN_URL, result["finalUrl"]?.jsonPrimitive?.content)
  }

  @Test
  fun headUrl_cdnUrlRequestedDirectly_isRefused() = runTest {
    val engine = MockEngine { respond("") }

    val result = parse(toolSet(engine, allowlist = githubOnly).headUrl(CDN_URL))

    assertNotNull(result.error())
    assertTrue(engine.requestHistory.isEmpty())
  }

  @Test
  fun searchWeb_restricted_scopesProviderAndDropsOffListResults() = runTest {
    val search = FakeSearchProvider(
      SearchResult("https://ubuntu.com/download", "Download", ""),
      SearchResult("https://evil.example/ubuntu", "Mirror", ""),
    )
    val tools = toolSet(MockEngine { respond("") }, allowlist = ubuntuOnly, searchProvider = search)

    val urls = parseUrls(tools.searchWeb("ubuntu iso"))

    assertEquals(listOf("ubuntu.com"), search.lastSites)
    assertEquals(listOf("https://ubuntu.com/download"), urls)
  }

  @Test
  fun searchWeb_unrestricted_searchesEverywhere() = runTest {
    val search = FakeSearchProvider(SearchResult("https://evil.example/ubuntu", "Mirror", ""))
    val tools = toolSet(MockEngine { respond("") }, searchProvider = search)

    val urls = parseUrls(tools.searchWeb("ubuntu iso"))

    assertEquals(emptyList(), search.lastSites)
    assertEquals(listOf("https://evil.example/ubuntu"), urls)
  }

  @Test
  fun searchSites_siteOutsideAllowlist_returnsErrorWithoutSearching() = runTest {
    val search = FakeSearchProvider()
    val tools = toolSet(MockEngine { respond("") }, allowlist = ubuntuOnly, searchProvider = search)

    val result = parse(tools.searchSites("askubuntu.com, ubuntu.com", "iso"))

    val error = assertNotNull(result.error())
    assertTrue("askubuntu.com" in error, error)
    assertNull(search.lastSites)
  }

  @Test
  fun searchSites_allowedSiteWithScheme_searchesNormalizedDomain() = runTest {
    val search = FakeSearchProvider()
    val tools = toolSet(MockEngine { respond("") }, allowlist = ubuntuOnly, searchProvider = search)

    tools.searchSites("https://releases.ubuntu.com/", "iso")

    assertEquals(listOf("releases.ubuntu.com"), search.lastSites)
  }

  @Test
  fun searchSites_blankSites_searchesAllowedSites() = runTest {
    val search = FakeSearchProvider()
    val allowlist = SiteAllowlist.of(listOf("ubuntu.com", "blender.org"))
    val tools = toolSet(MockEngine { respond("") }, allowlist = allowlist, searchProvider = search)

    tools.searchSites("", "iso")

    assertEquals(listOf("ubuntu.com", "blender.org"), search.lastSites)
  }

  @Test
  fun validateUrl_urlOutsideAllowlist_isNotOk() {
    val tools = toolSet(MockEngine { respond("") }, allowlist = ubuntuOnly)

    val offList = parse(tools.validateUrl("https://evil.example/ubuntu.iso"))
    val onList = parse(tools.validateUrl("https://releases.ubuntu.com/ubuntu.iso"))

    assertFalse(offList.getValue("ok").jsonPrimitive.boolean)
    assertTrue(onList.getValue("ok").jsonPrimitive.boolean)
  }

  private fun parseUrls(json: String): List<String> {
    return Json.parseToJsonElement(json).jsonArray
      .map { it.jsonObject.getValue("url").jsonPrimitive.content }
  }

  private class FakeSearchProvider(vararg results: SearchResult) : SearchProvider {
    private val results = results.toList()
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

  private companion object {
    const val CDN_URL = "https://objects.githubusercontent.com/assets/app.zip"
  }
}
