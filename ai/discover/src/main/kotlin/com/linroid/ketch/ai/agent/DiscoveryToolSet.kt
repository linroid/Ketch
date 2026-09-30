package com.linroid.ketch.ai.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.linroid.ketch.ai.DiscoverResult
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
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
  private val stepListener: DiscoveryStepListener,
  private val json: Json,
  private val allowedDomains: List<String>,
) : ToolSet {

  private val log = KetchLogger("DiscoveryToolSet")

  private val robotsMutex = Mutex()
  private val robotsByOrigin = mutableMapOf<String, RobotsTxtRules?>()

  /** Sources fetched during this discovery run. */
  val fetchedSources: MutableList<DiscoverResult.Source> =
    mutableListOf()

  @Tool
  @LLMDescription(
    "Search the web for pages matching a query. " +
      "Returns JSON array of {url, title, snippet}.",
  )
  suspend fun searchWeb(
    @LLMDescription("Search query text")
    query: String,
    @LLMDescription("Maximum number of results (1-10)")
    maxResults: Int = 5,
  ): String {
    log.d { "searchWeb: query=\"$query\", max=$maxResults" }
    val results = searchProvider.search(
      query = query,
      maxResults = maxResults.coerceIn(1, 10),
    )
    return json.encodeToString(
      ListSerializer(SearchResult.serializer()),
      results,
    )
  }

  @Tool
  @LLMDescription(
    "Search within specific sites for pages matching a query. " +
      "Returns JSON array of {url, title, snippet}.",
  )
  suspend fun searchSites(
    @LLMDescription("Comma-separated list of domains to search within")
    sites: String,
    @LLMDescription("Search query text")
    query: String,
    @LLMDescription("Maximum number of results (1-10)")
    maxResults: Int = 5,
  ): String {
    val siteList = sites.split(",").map { it.trim() }
      .filter { it.isNotBlank() }
    log.d { "searchSites: sites=$siteList, query=\"$query\"" }
    val results = searchProvider.search(
      query = query,
      sites = siteList,
      maxResults = maxResults.coerceIn(1, 10),
    )
    return json.encodeToString(
      ListSerializer(SearchResult.serializer()),
      results,
    )
  }

  @Tool
  @LLMDescription(
    "Fetch a web page, extract its text content and download " +
      "links. Returns JSON with text and links fields.",
  )
  suspend fun fetchPage(
    @LLMDescription("URL to fetch")
    url: String,
  ): String {
    log.d { "fetchPage: ${redactUrl(url)}" }
    val uri = when (val v = urlValidator.validate(url)) {
      is ValidationResult.Blocked -> return errorJson(v.reason)
      is ValidationResult.Valid -> v.uri
    }

    val allowance = budget.reserveBytes(fetcher.maxContentBytes)
    if (allowance == 0L) {
      return errorJson(
        "Page content budget of ${budget.maxBytes} bytes for this discovery is spent. " +
          "Stop fetching and return your results.",
      )
    }
    if (!budget.tryTakeRequest()) {
      budget.returnBytes(allowance)
      return errorJson(requestBudgetSpent())
    }
    if (!robotsAllow(uri)) {
      budget.returnBytes(allowance)
      log.d { "fetchPage: robots.txt disallows ${redactUrl(url)}" }
      return errorJson("The site's robots.txt disallows fetching this page")
    }

    val result = fetcher.fetch(url, allowance)
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
            title = title.ifEmpty { pageUrl },
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
      is FetchResult.Failed -> errorJson(result.reason)
    }
  }

  @Tool
  @LLMDescription(
    "Perform an HTTP HEAD request to get metadata " +
      "(content-type, size, last-modified) without downloading.",
  )
  suspend fun headUrl(
    @LLMDescription("URL to check")
    url: String,
  ): String {
    log.d { "headUrl: ${redactUrl(url)}" }
    when (val v = urlValidator.validate(url)) {
      is ValidationResult.Blocked -> return errorJson(v.reason)
      is ValidationResult.Valid -> { /* ok */ }
    }
    if (!budget.tryTakeRequest()) return errorJson(requestBudgetSpent())
    return when (val result = fetcher.head(url)) {
      is HeadResult.Success -> buildJsonObject {
        put("url", result.finalUrl)
        put("status", result.statusCode)
        result.contentType?.let { put("contentType", it) }
        result.contentLength?.let {
          put("contentLength", JsonPrimitive(it))
        }
        result.lastModified?.let { put("lastModified", it) }
        result.etag?.let { put("etag", it) }
      }.toString()
      is HeadResult.Failed -> errorJson(result.reason)
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
    "Validate a URL for safety (SSRF, scheme, allowlist). " +
      "Returns JSON with ok boolean and reason.",
  )
  fun validateUrl(
    @LLMDescription("URL to validate")
    url: String,
  ): String {
    val validation = urlValidator.validate(url)
    val ssrfOk = validation is ValidationResult.Valid

    val domainOk = if (allowedDomains.isEmpty()) {
      true
    } else {
      val host = try {
        java.net.URI(url).host?.lowercase() ?: ""
      } catch (_: Exception) {
        ""
      }
      allowedDomains.any { d ->
        host == d || host.endsWith(".$d")
      }
    }

    val ok = ssrfOk && domainOk
    val reason = when {
      !ssrfOk -> (validation as ValidationResult.Blocked).reason
      !domainOk -> "Domain not in allowlist"
      else -> "OK"
    }
    return buildJsonObject {
      put("ok", ok)
      put("reason", reason)
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
    stepListener.onStep(title, details)
    return "ok"
  }

  private fun errorJson(reason: String): String {
    return buildJsonObject { put("error", reason) }.toString()
  }

  private fun requestBudgetSpent(): String =
    "Request budget of ${budget.maxRequests} page fetches and HEAD requests for this " +
      "discovery is spent. Stop fetching and return your results."

  /** Whether robots.txt at the origin of [uri] lets us fetch it. */
  private suspend fun robotsAllow(uri: URI): Boolean {
    val port = if (uri.port == -1) "" else ":${uri.port}"
    val origin = "${uri.scheme.lowercase()}://${uri.host.lowercase()}$port"
    val rules = robotsMutex.withLock {
      // A missing robots.txt is cached as null, which getOrPut would treat as absent.
      if (origin in robotsByOrigin) {
        robotsByOrigin[origin]
      } else {
        siteProfiler.fetchRobotsRules(origin).also { robotsByOrigin[origin] = it }
      }
    }
    val path = uri.rawPath.ifEmpty { "/" } + uri.rawQuery?.let { "?$it" }.orEmpty()
    return siteProfiler.isAllowed(path, rules)
  }

  companion object {
    private const val MAX_TEXT_LENGTH = 30_000
    private const val MAX_LINKS = 50
    private const val SURROUNDING_TEXT_LIMIT = 100
  }
}
