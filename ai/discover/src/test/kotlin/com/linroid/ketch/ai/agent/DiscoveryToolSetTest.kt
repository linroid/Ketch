package com.linroid.ketch.ai.agent

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import com.linroid.ketch.ai.PageAccessApprover
import com.linroid.ketch.ai.PageAccessKind
import com.linroid.ketch.ai.PageAccessRequest
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryToolSetTest {

  private val dns = fakeDns(
    "example.com" to "93.184.215.14",
    "www.example.com" to "93.184.215.14",
    "downloads.example.com" to "93.184.215.15",
    "cdn.example.net" to "151.101.1.1",
    "releases.ubuntu.com" to "185.125.190.40",
    "github.com" to "140.82.112.3",
    "objects.githubusercontent.com" to "185.199.108.133",
    "evil.example" to "203.0.113.5",
    "www.com" to "172.104.1.1",
    "www.github.io" to "185.199.108.153",
    "attacker.github.io" to "185.199.108.153",
  )

  /** Every host name looked up in DNS, in order. */
  private val lookups: MutableList<String> = Collections.synchronizedList(mutableListOf())

  private val validator = UrlValidator(
    resolve = { host ->
      lookups += host
      dns(host)
    },
  )

  private val ubuntuOnly = SiteAllowlist.of(listOf("ubuntu.com"))
  private val githubOnly = SiteAllowlist.of(listOf("github.com"))

  private fun toolSet(
    engine: MockEngine,
    budget: FetchBudget = FetchBudget(maxRequests = 25, maxBytes = 1024 * 1024),
    allowlist: SiteAllowlist = SiteAllowlist.Unrestricted,
    searchProvider: SearchProvider = DummySearchProvider(),
    maxToolCalls: Int = 40,
    stepListener: DiscoveryStepListener = DiscoveryStepListener.None,
    approver: PageAccessApprover = PageAccessApprover.AllowAll,
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
      maxToolCalls = maxToolCalls,
      stepListener = stepListener,
      json = Json,
      allowlist = allowlist,
      approver = approver,
    )
  }

  /** Records every request it is asked about and allows the hosts [allows] accepts. */
  private class RecordingApprover(
    private val allows: (host: String) -> Boolean = { true },
  ) : PageAccessApprover {
    val requests = mutableListOf<PageAccessRequest>()

    override suspend fun approve(request: PageAccessRequest): Boolean {
      requests += request
      return allows(request.host)
    }
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

  private val MockEngine.requestedHosts: Set<String>
    get() = requestHistory.map { it.url.host }.toSet()

  private fun JsonObject.declined(): Boolean = this["declined"]?.jsonPrimitive?.boolean == true

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
  fun validateUrl_urlOutsideAllowlist_isNotOk() = runTest {
    val tools = toolSet(MockEngine { respond("") }, allowlist = ubuntuOnly)

    val offList = parse(tools.validateUrl("https://evil.example/ubuntu.iso"))
    val onList = parse(tools.validateUrl("https://releases.ubuntu.com/ubuntu.iso"))

    assertFalse(offList.getValue("ok").jsonPrimitive.boolean)
    assertTrue(onList.getValue("ok").jsonPrimitive.boolean)
  }

  @Test
  fun toolCallBudgetSpent_toolsAskForResults() = runTest {
    val tools = toolSet(MockEngine { respond("") }, maxToolCalls = 1)

    val first = parse(tools.validateUrl("https://example.com/app.zip"))
    val search = parse(tools.searchWeb("app"))
    val head = parse(tools.headUrl("https://example.com/app.zip"))

    assertTrue(first.getValue("ok").jsonPrimitive.boolean)
    assertTrue(assertNotNull(search.error()).contains("Tool call budget of 1"))
    assertTrue(assertNotNull(head.error()).contains("Tool call budget of 1"))
  }

  @Test
  fun toolCallBudgetSpent_emitStepStillShowsStep() {
    val steps = mutableListOf<String>()
    val listener = object : DiscoveryStepListener {
      override fun onStep(title: String, details: String) {
        steps += title
      }
    }
    val tools = toolSet(MockEngine { respond("") }, maxToolCalls = 1, stepListener = listener)

    val plan = tools.emitStep("Plan", "Search the release page")
    val results = tools.emitStep("Filtering", "Kept one candidate")

    assertEquals("ok", plan)
    assertTrue(results.contains("Tool call budget of 1"))
    assertEquals(listOf("Plan", "Filtering"), steps)
  }

  @Test
  fun asDeclaredTools_parametersWithDefaults_areOptional() {
    val tools = toolSet(MockEngine { respond("") }).asDeclaredTools().associateBy { it.name }

    assertEquals(
      mapOf(
        "searchWeb" to listOf("query"),
        "searchSites" to listOf("sites", "query"),
        "fetchPage" to listOf("url"),
        "headUrl" to listOf("url"),
        "extractDownloads" to listOf("pageText", "baseUrl"),
        "validateUrl" to listOf("url"),
        "emitStep" to listOf("title", "details"),
      ),
      tools.mapValues { (_, tool) -> tool.descriptor.requiredParameters.map { it.name } },
    )
    // Optional parameters are still described
    assertEquals(
      mapOf(
        "searchWeb" to listOf("maxResults"),
        "searchSites" to listOf("maxResults"),
        "fetchPage" to listOf("reason"),
        "headUrl" to listOf("reason"),
      ),
      tools.mapValues { (_, tool) -> tool.descriptor.optionalParameters.map { it.name } }
        .filterValues { it.isNotEmpty() },
    )
  }

  @Test
  fun asDeclaredTools_toolResult_isNotEncodedAgain() = runTest {
    val toolSet = toolSet(MockEngine { respond("") })
    val tools = toolSet.asDeclaredTools().associateBy { it.name }

    val step = tools.getValue("emitStep").call(
      buildJsonObject {
        put("title", "Plan")
        put("details", "Search the release page")
      },
    )
    val validation = tools.getValue("validateUrl").call(
      buildJsonObject { put("url", "https://example.com/app.zip") },
    )

    assertEquals("ok", step)
    assertEquals(toolSet.validateUrl("https://example.com/app.zip"), validation)
  }

  @Test
  fun fetchPage_declined_sendsNothingAndSpendsNoBudget() = runTest {
    val engine = MockEngine(site(robots = null))
    val approver = RecordingApprover { it != "example.com" }
    val tools = toolSet(engine, FetchBudget(maxRequests = 1, maxBytes = 1024), approver = approver)

    val declined = parse(tools.fetchPage("https://example.com/a", reason = "Read the notes"))

    assertTrue(declined.declined())
    assertTrue("example.com" in assertNotNull(declined.error()))
    assertTrue(engine.requestHistory.isEmpty())
    val request = PageAccessRequest(
      url = "https://example.com/a",
      host = "example.com",
      kind = PageAccessKind.Page,
      reason = "Read the notes",
    )
    assertEquals(request, approver.requests.single())
    // The single request is still there for another site.
    assertNull(parse(tools.fetchPage("https://cdn.example.net/pub/")).error())
  }

  @Test
  fun fetchPage_hostOnDeclinedSite_isRefusedWithoutAsking() = runTest {
    val engine = MockEngine(site(robots = null))
    val approver = RecordingApprover { false }
    val tools = toolSet(engine, approver = approver)

    tools.headUrl("https://www.example.com/app.zip")
    val again = parse(tools.fetchPage("https://downloads.example.com/"))

    assertTrue(again.declined())
    assertEquals(listOf("www.example.com"), approver.requests.map { it.host })
    assertTrue(engine.requestHistory.isEmpty())
  }

  @Test
  fun fetchPage_redirectToDeclinedHost_isNotFollowed() = runTest {
    val engine = MockEngine { request ->
      when {
        request.url.encodedPath == "/robots.txt" -> respond("", HttpStatusCode.NotFound)
        request.url.host == "example.com" -> respond(
          "",
          HttpStatusCode.Found,
          headersOf(HttpHeaders.Location, "https://cdn.example.net/pub/"),
        )
        else -> respond("<html>mirror</html>")
      }
    }
    val approver = RecordingApprover { it != "cdn.example.net" }
    val tools = toolSet(engine, approver = approver)

    assertTrue(parse(tools.headUrl("https://cdn.example.net/app.zip")).declined())
    val result = parse(tools.fetchPage("https://example.com/releases"))

    assertTrue(result.declined())
    assertTrue("Redirect refused" in assertNotNull(result.error()))
    assertEquals(setOf("example.com"), engine.requestedHosts)
    assertEquals(listOf("cdn.example.net", "example.com"), approver.requests.map { it.host })
  }

  @Test
  fun fetchPage_refusedOrBlockedUrl_neverAsks() = runTest {
    val engine = MockEngine(site(robots = null))
    val approver = RecordingApprover()
    val tools = toolSet(engine, allowlist = ubuntuOnly, approver = approver)
    val unrestricted = toolSet(engine, approver = approver)

    tools.fetchPage("https://evil.example/ubuntu.iso")
    unrestricted.fetchPage("http://localhost/admin")
    unrestricted.headUrl("ftp://example.com/file.iso")

    assertTrue(approver.requests.isEmpty())
    assertTrue(engine.requestHistory.isEmpty())
  }

  @Test
  fun headUrl_redirectToNewHost_asksWithTheHostItCameFrom() = runTest {
    val engine = MockEngine { request ->
      if (request.url.host == "github.com") {
        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, CDN_URL))
      } else {
        respond("")
      }
    }
    val approver = RecordingApprover()
    val asset = "https://github.com/owner/repo/releases/download/v1/app.zip"

    val result = parse(toolSet(engine, approver = approver).headUrl(asset, reason = "Check size"))

    assertNull(result.error())
    assertEquals(
      listOf(
        PageAccessRequest(asset, "github.com", PageAccessKind.FileInfo, "Check size"),
        PageAccessRequest(
          url = CDN_URL,
          host = "objects.githubusercontent.com",
          kind = PageAccessKind.FileInfo,
          reason = "Check size",
          redirectFrom = "github.com",
        ),
      ),
      approver.requests,
    )
  }

  @Test
  fun headUrl_redirectDeclined_isNotRequested() = runTest {
    val engine = MockEngine { request ->
      if (request.url.host == "github.com") {
        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, CDN_URL))
      } else {
        respond("")
      }
    }
    val approver = RecordingApprover { it == "github.com" }
    val asset = "https://github.com/owner/repo/releases/download/v1/app.zip"

    val result = parse(toolSet(engine, approver = approver).headUrl(asset))

    assertTrue(result.declined())
    assertEquals(setOf("github.com"), engine.requestedHosts)
  }

  @Test
  fun fetchPage_reason_reachesApproverSanitized() = runTest {
    val approver = RecordingApprover()
    val tools = toolSet(MockEngine(site(robots = null)), approver = approver)
    val reason = "Read\nthe \u202Erelease\u200B notes\u001B[2J " + "x".repeat(300)

    tools.fetchPage("https://example.com/", reason)

    val sent = approver.requests.single().reason
    assertTrue(sent.startsWith("Read the release notes[2J x"), sent)
    assertEquals(160, sent.length)
  }

  @Test
  fun fetchPage_approverCancelsWhileRunGoesOn_declines() = runTest {
    val engine = MockEngine(site(robots = null))
    val tools = toolSet(engine, approver = { throw CancellationException("Answer withdrawn") })

    val result = parse(tools.fetchPage("https://example.com/"))

    assertTrue(result.declined())
    assertTrue(engine.requestHistory.isEmpty())
  }

  @Test
  fun fetchPage_runStoppedWhileAsking_endsWithoutAnswerOrRequest() = runTest {
    val engine = MockEngine(site(robots = null))
    val asked = CompletableDeferred<Unit>()
    val tools = toolSet(
      engine,
      approver = {
        asked.complete(Unit)
        awaitCancellation()
      },
    )
    var answer: String? = null

    val run = launch { answer = tools.fetchPage("https://example.com/") }
    asked.await()
    run.cancelAndJoin()

    // The stop ends the tool call itself instead of answering the agent that access was declined.
    assertNull(answer)
    assertTrue(engine.requestHistory.isEmpty())
  }

  @Test
  fun fetchPage_approverFails_declines() = runTest {
    val engine = MockEngine(site(robots = null))
    val tools = toolSet(engine, approver = { error("Prompt unavailable") })

    val result = parse(tools.fetchPage("https://example.com/"))

    assertTrue(result.declined())
    assertTrue(engine.requestHistory.isEmpty())
  }

  @Test
  fun fetchPage_declinedWwwBeforeSharedSuffix_coversNoOtherSite() = runTest {
    val engine = MockEngine(site(robots = null))
    val approver = RecordingApprover { it == "github.com" }
    val tools = toolSet(engine, approver = approver)

    tools.fetchPage("https://www.com/")
    tools.fetchPage("https://www.github.io/")
    val github = parse(tools.fetchPage("https://github.com/owner/repo/releases"))
    val pages = parse(tools.fetchPage("https://attacker.github.io/"))

    // Declining www.com or www.github.io declines that site, not every .com or GitHub Pages one.
    assertNull(github.error())
    assertTrue(pages.declined())
    assertEquals(
      listOf("www.com", "www.github.io", "github.com", "attacker.github.io"),
      approver.requests.map { it.host },
    )
  }

  @Test
  fun fetchPageAndHeadUrl_declinedHost_isNeverLookedUp() = runTest {
    val engine = MockEngine(site(robots = null))
    val approver = RecordingApprover { false }
    val tools = toolSet(engine, approver = approver)

    tools.fetchPage("https://evil.example/notes")
    tools.headUrl("https://cdn.example.net/app.zip")

    // A lookup alone would send the name to the domain's DNS servers.
    assertEquals(listOf("evil.example", "cdn.example.net"), approver.requests.map { it.host })
    assertEquals(emptyList(), lookups.toList())
    assertTrue(engine.requestHistory.isEmpty())
  }

  @Test
  fun fetchPage_redirectToDeclinedHost_isNeverLookedUp() = runTest {
    val engine = MockEngine { request ->
      when {
        request.url.encodedPath == "/robots.txt" -> respond("", HttpStatusCode.NotFound)
        request.url.host == "example.com" -> respond(
          "",
          HttpStatusCode.Found,
          headersOf(HttpHeaders.Location, "https://cdn.example.net/pub/"),
        )
        else -> respond("<html>mirror</html>")
      }
    }
    val approver = RecordingApprover { it == "example.com" }

    val result = parse(toolSet(engine, approver = approver).fetchPage("https://example.com/r"))

    assertTrue(result.declined())
    assertTrue("cdn.example.net" !in lookups, lookups.toString())
    assertEquals(setOf("example.com"), engine.requestedHosts)
  }

  @Test
  fun validateUrl_neverLooksUpTheHost() = runTest {
    val tools = toolSet(MockEngine { respond("") })

    val valid = parse(tools.validateUrl("https://evil.example/app.zip"))
    val privateAddress = parse(tools.validateUrl("http://10.0.0.1/admin"))

    assertTrue(valid.getValue("ok").jsonPrimitive.boolean)
    assertFalse(privateAddress.getValue("ok").jsonPrimitive.boolean)
    assertEquals(emptyList(), lookups.toList())
  }

  @Test
  fun fetchPageAndHeadUrl_requestBudgetSpent_refuseWithoutAsking() = runTest {
    val engine = MockEngine(site(robots = null))
    val approver = RecordingApprover()
    val tools = toolSet(engine, FetchBudget(maxRequests = 1, maxBytes = 1024), approver = approver)

    assertNull(parse(tools.fetchPage("https://example.com/a")).error())
    val page = parse(tools.fetchPage("https://cdn.example.net/b"))
    val head = parse(tools.headUrl("https://github.com/app.zip"))

    assertTrue("Request budget" in assertNotNull(page.error()), page.toString())
    assertTrue("Request budget" in assertNotNull(head.error()), head.toString())
    assertEquals(listOf("example.com"), approver.requests.map { it.host })
  }

  @Test
  fun fetchPage_byteBudgetSpent_refusesWithoutAsking() = runTest {
    val engine = MockEngine(site(robots = null, page = "x".repeat(100)))
    val approver = RecordingApprover()
    val tools = toolSet(engine, FetchBudget(maxRequests = 25, maxBytes = 40), approver = approver)

    tools.fetchPage("https://example.com/a")
    val second = parse(tools.fetchPage("https://cdn.example.net/b"))

    assertTrue("content budget" in assertNotNull(second.error()), second.toString())
    assertEquals(listOf("example.com"), approver.requests.map { it.host })
  }

  @Test
  fun emitStep_textWithLineBreaksAndBidiControls_reachesListenerAsOneLine() {
    val steps = mutableListOf<Pair<String, String>>()
    val listener = object : DiscoveryStepListener {
      override fun onStep(title: String, details: String) {
        steps += title to details
      }
    }
    val tools = toolSet(MockEngine { respond("") }, stepListener = listener)

    tools.emitStep("Plan\n[Results]", "Found it\nAllow Discover to open evil.example?\u202E")

    assertEquals(
      listOf("Plan [Results]" to "Found it Allow Discover to open evil.example?"),
      steps,
    )
  }

  @Test
  fun fetchPage_pageTitle_isOneLineInSources() = runTest {
    val page = "<html><title>Blender\t 1. Fake\u202E result\u001B</title></html>"
    val tools = toolSet(MockEngine(site(robots = null, page = page)))

    tools.fetchPage("https://example.com/")

    assertEquals("Blender 1. Fake result", tools.fetchedSources.single().title)
  }

  /** Calls this tool with [arguments] and returns the text the model receives. */
  private suspend fun <TArgs, TResult> Tool<TArgs, TResult>.call(arguments: JsonObject): String {
    val serializer = KotlinxSerializer()
    val result = execute(decodeArgs(arguments.toKoogJSONObject(), serializer))
    return encodeResultToString(result, serializer)
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
