package com.linroid.ketch.ai.agent

import com.linroid.ketch.ai.fetch.ContentExtractor
import com.linroid.ketch.ai.fetch.FetchBudget
import com.linroid.ketch.ai.fetch.RateLimiter
import com.linroid.ketch.ai.fetch.SafeFetcher
import com.linroid.ketch.ai.fetch.UrlValidator
import com.linroid.ketch.ai.fetch.fakeDns
import com.linroid.ketch.ai.search.DummySearchProvider
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryToolSetTest {

  private val validator = UrlValidator(
    resolve = fakeDns(
      "example.com" to "93.184.215.14",
      "cdn.example.net" to "151.101.1.1",
    ),
  )

  private fun toolSet(
    engine: MockEngine,
    budget: FetchBudget = FetchBudget(maxRequests = 25, maxBytes = 1024 * 1024),
  ): DiscoveryToolSet {
    val fetcher = SafeFetcher(
      httpClient = HttpClient(engine) { followRedirects = false },
      urlValidator = validator,
      rateLimiter = RateLimiter(delayMs = 0),
    )
    return DiscoveryToolSet(
      searchProvider = DummySearchProvider(),
      fetcher = fetcher,
      urlValidator = validator,
      contentExtractor = ContentExtractor(),
      linkExtractor = LinkExtractor(),
      siteProfiler = SiteProfiler(fetcher),
      budget = budget,
      stepListener = DiscoveryStepListener.None,
      json = Json,
      allowedDomains = emptyList(),
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
}
