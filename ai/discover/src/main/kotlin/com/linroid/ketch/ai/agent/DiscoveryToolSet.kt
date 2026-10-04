package com.linroid.ketch.ai.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.linroid.ketch.ai.DiscoverResult
import com.linroid.ketch.ai.PageAccessApprover
import com.linroid.ketch.ai.PageAccessKind
import com.linroid.ketch.ai.PageAccessRequest
import com.linroid.ketch.ai.fetch.ContentExtractor
import com.linroid.ketch.ai.fetch.FetchBudget
import com.linroid.ketch.ai.fetch.FetchResult
import com.linroid.ketch.ai.fetch.HeadResult
import com.linroid.ketch.ai.fetch.SafeFetcher
import com.linroid.ketch.ai.fetch.UrlValidator
import com.linroid.ketch.ai.fetch.ValidationResult
import com.linroid.ketch.ai.search.SearchProvider
import com.linroid.ketch.ai.search.SearchResult
import com.linroid.ketch.ai.site.RobotsTxtRules
import com.linroid.ketch.ai.site.SiteProfiler
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.config.SiteNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

/**
 * Koog [ToolSet] exposing discovery capabilities to the AI agent.
 *
 * Each `@Tool` method wraps an existing utility (search, fetch,
 * validate, etc.) and returns a JSON-encoded string the LLM can
 * reason over.
 *
 * One instance serves one discovery run: page fetches and HEAD
 * requests draw on [budget], and page fetches honor each site's
 * robots.txt, read once per origin for the run.
 *
 * Every tool call, `emitStep` included, takes one of [maxToolCalls].
 * Once they are spent, the tools answer with an error asking the agent
 * to return its results; `emitStep` still shows its step, so the agent
 * can explain them.
 *
 * While [allowlist] is restricted, searches only cover the allowed
 * sites and `fetchPage`/`headUrl` refuse URLs on other hosts with an
 * error JSON, before spending any budget. Redirects those requests
 * receive may still lead to other hosts.
 *
 * [approver] decides, before any budget is spent, whether `fetchPage`
 * and `headUrl` may contact the host they ask for, and whether a
 * redirect may lead them to a host the request has not reached yet. A
 * declined request gets an error JSON marked `"declined": true`, and the
 * host's site is refused for the rest of the run without asking again.
 * A page's approval covers its origin's robots.txt. Searches never ask:
 * they go to the search provider, not to the sites. Nor does a request
 * the spent budget would refuse anyway.
 *
 * A host is not even looked up in DNS before it is approved: the lookup
 * would already send the name, which the agent chose, to that domain's
 * DNS servers. So `fetchPage` and `headUrl` only check a URL's form
 * before asking, and `validateUrl` never looks a host up.
 *
 * Text the agent writes for the user, its steps and the titles of the
 * pages it reads, is reduced to one line of plain text first.
 */
@LLMDescription("Resource discovery tools for finding downloadable files")
internal class DiscoveryToolSet(
  private val searchProvider: SearchProvider,
  private val fetcher: SafeFetcher,
  private val urlValidator: UrlValidator,
  private val contentExtractor: ContentExtractor,
  private val linkExtractor: LinkExtractor,
  private val siteProfiler: SiteProfiler,
  private val budget: FetchBudget,
  private val maxToolCalls: Int,
  private val stepListener: DiscoveryStepListener,
  private val json: Json,
  private val allowlist: SiteAllowlist,
  private val approver: PageAccessApprover = PageAccessApprover.AllowAll,
) : ToolSet {

  private val log = KetchLogger("DiscoveryToolSet")

  private val toolCalls = AtomicInteger()

  private val robotsMutex = Mutex()
  private val robotsByOrigin = mutableMapOf<String, RobotsTxtRules?>()

  /** Sites the user declined in this run, as [SiteNames.normalize] leaves them. */
  private val declinedSites: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** Sources fetched during this discovery run. */
  val fetchedSources: MutableList<DiscoverResult.Source> =
    mutableListOf()

  @Tool
  @LLMDescription(
    "Search the web for pages matching a query. When the run is " +
      "limited to allowed sites, only those sites are searched. " +
      "Returns JSON array of {url, title, snippet}.",
  )
  suspend fun searchWeb(
    @LLMDescription("Search query text")
    query: String,
    @LLMDescription("Maximum number of results (1-10)")
    maxResults: Int = 5,
  ): String {
    if (!takeToolCall("searchWeb")) return errorJson(toolBudgetSpent())
    log.d { "searchWeb: query=\"$query\", max=$maxResults" }
    val results = searchProvider.search(
      query = query,
      sites = allowlist.domains,
      maxResults = maxResults.coerceIn(1, 10),
    )
    return encodeResults(results)
  }

  @Tool
  @LLMDescription(
    "Search within specific sites for pages matching a query. " +
      "When the run is limited to allowed sites, the sites must be " +
      "among them. Returns JSON array of {url, title, snippet}.",
  )
  suspend fun searchSites(
    @LLMDescription("Comma-separated list of domains to search within")
    sites: String,
    @LLMDescription("Search query text")
    query: String,
    @LLMDescription("Maximum number of results (1-10)")
    maxResults: Int = 5,
  ): String {
    if (!takeToolCall("searchSites")) return errorJson(toolBudgetSpent())
    val siteList = sites.split(",").map(SiteNames::normalize)
      .filter { it.isNotEmpty() }
    log.d { "searchSites: sites=$siteList, query=\"$query\"" }
    val outside = siteList.filterNot(allowlist::allowsHost)
    if (outside.isNotEmpty()) {
      log.d { "searchSites refused: $outside outside allowed sites" }
      return outsideAllowlistJson(outside.joinToString())
    }
    val results = searchProvider.search(
      query = query,
      sites = siteList.ifEmpty { allowlist.domains },
      maxResults = maxResults.coerceIn(1, 10),
    )
    return encodeResults(results)
  }

  @Tool
  @LLMDescription(
    "Fetch a web page, extract its text content and download " +
      "links. Returns JSON with text and links fields.",
  )
  suspend fun fetchPage(
    @LLMDescription("URL to fetch")
    url: String,
    @LLMDescription("One short sentence telling the user why you need this page")
    reason: String = "",
  ): String {
    if (!takeToolCall("fetchPage")) return errorJson(toolBudgetSpent())
    log.d { "fetchPage: ${redactUrl(url)}" }
    if (!allowlist.allows(url)) {
      log.d { "fetchPage refused: ${redactUrl(url)} outside allowed sites" }
      return outsideAllowlistJson(url)
    }
    // The host is looked up once the user allows it, in the fetcher.
    val host = when (val v = urlValidator.check(url)) {
      is ValidationResult.Blocked -> return errorJson(v.reason)
      is ValidationResult.Valid -> v.uri.host.lowercase()
    }
    // A spent budget refuses without asking; a declined request spends nothing.
    if (!budget.hasBytesLeft()) return errorJson(byteBudgetSpent())
    if (!budget.hasRequestLeft()) return errorJson(requestBudgetSpent())
    val access = RequestAccess(PageAccessKind.Page, reason)
    if (!access.allows(url, host)) return declinedJson(declinedMessage(host))

    val allowance = budget.reserveBytes(fetcher.maxContentBytes)
    if (allowance == 0L) return errorJson(byteBudgetSpent())
    if (!budget.tryTakeRequest()) {
      budget.returnBytes(allowance)
      return errorJson(requestBudgetSpent())
    }

    // Every hop is checked: a redirect to another host needs the user's
    // approval before its robots.txt is read, and an allowed URL may
    // redirect to a path robots.txt disallows.
    val result = fetcher.fetch(
      url = url,
      maxBytes = allowance,
      checkHop = { hop -> access.refusal(hop) ?: robotsRefusal(hop, access::sideRefusal) },
    )
    budget.returnBytes(allowance - ((result as? FetchResult.Success)?.byteCount ?: 0))
    return when (result) {
      is FetchResult.Success -> {
        // Relative links resolve against the page that was served.
        val pageUrl = result.finalUrl
        val title = contentExtractor.extractTitle(result.content)
        val text = contentExtractor.extract(result.content)
        val links = linkExtractor.extract(result.content, pageUrl)

        fetchedSources.add(
          DiscoverResult.Source(
            url = pageUrl,
            title = sanitizeAgentText(title, MAX_SOURCE_TITLE_LENGTH).ifEmpty { pageUrl },
            fetchedAt = Instant.fromEpochMilliseconds(
              System.currentTimeMillis()
            ),
          )
        )

        buildJsonObject {
          put("url", pageUrl)
          put("title", title)
          put("text", text.take(MAX_TEXT_LENGTH))
          put("links", buildJsonArray {
            for (l in links.take(MAX_LINKS)) {
              add(buildJsonObject {
                put("url", l.url)
                put("text", l.anchorText)
              })
            }
          })
        }.toString()
      }
      is FetchResult.Failed -> failureJson(result.reason, access.declined)
    }
  }

  @Tool
  @LLMDescription(
    "Perform an HTTP HEAD request to get metadata " +
      "(content-type, size, last-modified) without downloading. " +
      "Follows redirects; after one, finalUrl is where the file is " +
      "served from, but report url as the candidate.",
  )
  suspend fun headUrl(
    @LLMDescription("URL to check")
    url: String,
    @LLMDescription("One short sentence telling the user why you need to check this file")
    reason: String = "",
  ): String {
    if (!takeToolCall("headUrl")) return errorJson(toolBudgetSpent())
    log.d { "headUrl: ${redactUrl(url)}" }
    if (!allowlist.allows(url)) {
      log.d { "headUrl refused: ${redactUrl(url)} outside allowed sites" }
      return outsideAllowlistJson(url)
    }
    // The host is looked up once the user allows it, in the fetcher.
    val host = when (val v = urlValidator.check(url)) {
      is ValidationResult.Blocked -> return errorJson(v.reason)
      is ValidationResult.Valid -> v.uri.host.lowercase()
    }
    if (!budget.hasRequestLeft()) return errorJson(requestBudgetSpent())
    val access = RequestAccess(PageAccessKind.FileInfo, reason)
    if (!access.allows(url, host)) return declinedJson(declinedMessage(host))
    if (!budget.tryTakeRequest()) return errorJson(requestBudgetSpent())
    return when (val result = fetcher.head(url, checkHop = access::refusal)) {
      is HeadResult.Success -> buildJsonObject {
        // The requested URL stays the candidate: a CDN a listed site
        // redirects to is outside the allowlist, and its URLs often expire.
        put("url", result.url)
        if (result.finalUrl != result.url) put("finalUrl", result.finalUrl)
        put("status", result.statusCode)
        result.contentType?.let { put("contentType", it) }
        result.contentLength?.let {
          put("contentLength", JsonPrimitive(it))
        }
        result.lastModified?.let { put("lastModified", it) }
        result.etag?.let { put("etag", it) }
      }.toString()
      is HeadResult.Failed -> failureJson(result.reason, access.declined)
    }
  }

  @Tool
  @LLMDescription(
    "Extract download links from page text content. " +
      "Returns JSON array of {url, anchorText, surroundingText}.",
  )
  fun extractDownloads(
    @LLMDescription("HTML or text content of a page")
    pageText: String,
    @LLMDescription("Base URL for resolving relative links")
    baseUrl: String,
  ): String {
    if (!takeToolCall("extractDownloads")) return errorJson(toolBudgetSpent())
    val links = linkExtractor.extract(pageText, baseUrl)
    return buildJsonArray {
      for (l in links.take(MAX_LINKS)) {
        add(buildJsonObject {
          put("url", l.url)
          put("anchorText", l.anchorText)
          put(
            "surroundingText",
            l.surroundingText.take(SURROUNDING_TEXT_LIMIT),
          )
        })
      }
    }.toString()
  }

  @Tool
  @LLMDescription(
    "Check a URL's form for safety (scheme, internal hosts and addresses, " +
      "allowed sites) without contacting or looking up its host. " +
      "Returns JSON with ok boolean and reason.",
  )
  fun validateUrl(
    @LLMDescription("URL to validate")
    url: String,
  ): String {
    if (!takeToolCall("validateUrl")) return errorJson(toolBudgetSpent())
    // No DNS lookup: it would reach the domain's servers before the user allowed the site.
    val reason = if (!allowlist.allows(url)) {
      outsideAllowlistMessage(url)
    } else {
      (urlValidator.check(url) as? ValidationResult.Blocked)?.reason
    }
    return buildJsonObject {
      put("ok", reason == null)
      put("reason", reason ?: "OK")
    }.toString()
  }

  @Tool
  @LLMDescription(
    "Emit a progress step visible to the user. " +
      "Use to report what you're doing.",
  )
  fun emitStep(
    @LLMDescription("Short step title")
    title: String,
    @LLMDescription("Step details or explanation")
    details: String,
  ): String {
    stepListener.onStep(
      sanitizeAgentText(title, MAX_STEP_TITLE_LENGTH),
      sanitizeAgentText(details, MAX_STEP_DETAILS_LENGTH),
    )
    return if (takeToolCall("emitStep")) "ok" else toolBudgetSpent()
  }

  /**
   * Whether the user lets this run make [request]. A host on a site the user declined is
   * refused without asking; a site declined now is remembered for the rest of the run.
   */
  private suspend fun mayContact(request: PageAccessRequest): Boolean {
    if (declinedSites.any { SiteNames.covers(it, request.host) }) return false
    val allowed = try {
      approver.approve(request)
    } catch (_: CancellationException) {
      // A stopped run ends here; an approver that gives up while the run goes on declines.
      currentCoroutineContext().ensureActive()
      false
    } catch (e: Exception) {
      log.w(e) { "Page access check failed for ${request.host}; declining it" }
      false
    }
    if (!allowed) {
      // A site, so www.example.com covers download.example.com; www.github.io stays itself.
      declinedSites += SiteNames.normalize(request.host)
      log.i { "Page access declined: ${request.host}" }
    }
    return allowed
  }

  /** Takes one tool call from [maxToolCalls], or returns `false` once they are spent. */
  private fun takeToolCall(tool: String): Boolean {
    val spent = toolCalls.incrementAndGet() > maxToolCalls
    if (spent) log.d { "$tool: tool call budget of $maxToolCalls spent" }
    return !spent
  }

  /**
   * Encodes search [results], dropping hits outside the allowed sites:
   * providers apply `site:` filters loosely.
   */
  private fun encodeResults(results: List<SearchResult>): String {
    return json.encodeToString(
      ListSerializer(SearchResult.serializer()),
      results.filter { allowlist.allows(it.url) },
    )
  }

  private fun errorJson(reason: String): String {
    return buildJsonObject { put("error", reason) }.toString()
  }

  private fun declinedJson(reason: String): String {
    return buildJsonObject {
      put("error", reason)
      put("declined", true)
    }.toString()
  }

  /**
   * The error JSON of a request that failed for [reason]; [declined] when it ended at a host
   * the user declined, such as one a redirect led to.
   */
  private fun failureJson(reason: String, declined: Boolean): String =
    if (declined) declinedJson(reason) else errorJson(reason)

  private fun declinedMessage(host: String): String =
    "The user declined access to $host. Do not request $host again in this search; " +
      "use other sources or return your results."

  private fun outsideAllowlistJson(subject: String): String {
    return buildJsonObject {
      put("error", outsideAllowlistMessage(subject))
      put("allowedSites", buildJsonArray { allowlist.domains.forEach { add(it) } })
    }.toString()
  }

  private fun outsideAllowlistMessage(subject: String): String {
    return "Not on the allowed sites: $subject. This run may only use " +
      "${allowlist.domains.joinToString()} (subdomains included)."
  }

  private fun byteBudgetSpent(): String =
    "Page content budget of ${budget.maxBytes} bytes for this discovery is spent. " +
      "Stop fetching and return your results."

  private fun requestBudgetSpent(): String =
    "Request budget of ${budget.maxRequests} page fetches and HEAD requests for this " +
      "discovery is spent. Stop fetching and return your results."

  private fun toolBudgetSpent(): String =
    "Tool call budget of $maxToolCalls for this discovery is spent. " +
      "Call no more tools, except one last emitStep, and return your results."

  /**
   * Why robots.txt forbids fetching [uri], or `null` when it allows it. A redirect that reading
   * robots.txt meets to another host goes through [checkRedirect], the page access of the
   * request [uri] belongs to.
   */
  private suspend fun robotsRefusal(
    uri: URI,
    checkRedirect: suspend (URI) -> String?,
  ): String? {
    val port = if (uri.port == -1) "" else ":${uri.port}"
    val origin = "${uri.scheme.lowercase()}://${uri.host.lowercase()}$port"
    val rules = robotsMutex.withLock {
      // A missing robots.txt is cached as null, which getOrPut would treat as absent.
      if (origin in robotsByOrigin) {
        robotsByOrigin[origin]
      } else {
        siteProfiler.fetchRobotsRules(origin, checkRedirect).also { robotsByOrigin[origin] = it }
      }
    }
    val path = uri.rawPath.ifEmpty { "/" } + uri.rawQuery?.let { "?$it" }.orEmpty()
    return if (siteProfiler.isAllowed(path, rules)) {
      null
    } else {
      // No path: SafeFetcher logs the reason, and a query can carry tokens.
      "robots.txt at $origin disallows this page"
    }
  }

  /**
   * The page access checks of one request: every host it reaches, the requested one first,
   * needs the user's approval.
   *
   * @param kind what the request is for
   * @param reason why the agent says it needs the request, as it wrote it
   */
  private inner class RequestAccess(
    private val kind: PageAccessKind,
    reason: String,
  ) {
    private val reason = sanitizeAgentText(reason, MAX_REASON_LENGTH)

    /** Hosts this request has been allowed to reach. */
    private val allowedHosts = mutableSetOf<String>()

    /** The host of the latest hop, which a redirect comes from. */
    private var previousHost = ""

    /** Whether the request ended at a host the user declined. */
    var declined = false
      private set

    /** Whether the request may go on to [url] on [host], asking the user when it has to. */
    suspend fun allows(url: String, host: String): Boolean {
      val from = previousHost
      previousHost = host
      return mayReach(url, host, from)
    }

    /** Why the request must not go on to [hop], or `null` when it may; for `checkHop`. */
    suspend fun refusal(hop: URI): String? {
      val host = hop.host.lowercase()
      return if (allows(hop.toString(), host)) null else declinedMessage(host)
    }

    /**
     * Why a read the request makes on the side, such as robots.txt, must not follow a redirect
     * to [hop], or `null` when it may. It asks as [refusal] does, without moving the request's
     * own hops on, and a refusal leaves the request itself going.
     */
    suspend fun sideRefusal(hop: URI): String? {
      val host = hop.host.lowercase()
      return if (mayReach(hop.toString(), host, previousHost, declines = false)) {
        null
      } else {
        declinedMessage(host)
      }
    }

    private suspend fun mayReach(
      url: String,
      host: String,
      from: String,
      declines: Boolean = true,
    ): Boolean {
      if (host in allowedHosts) return true
      val request = PageAccessRequest(url, host, kind, reason, redirectFrom = from)
      val allowed = mayContact(request)
      if (allowed) allowedHosts += host else if (declines) declined = true
      return allowed
    }
  }

  companion object {
    private const val MAX_REASON_LENGTH = 160
    private const val MAX_STEP_TITLE_LENGTH = 120
    private const val MAX_STEP_DETAILS_LENGTH = 600
    private const val MAX_SOURCE_TITLE_LENGTH = 200
    private const val MAX_TEXT_LENGTH = 30_000
    private const val MAX_LINKS = 50
    private const val SURROUNDING_TEXT_LIMIT = 100
  }
}
