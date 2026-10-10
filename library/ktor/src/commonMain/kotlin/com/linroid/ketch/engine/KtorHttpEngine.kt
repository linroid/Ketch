package com.linroid.ketch.engine

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.RequestHeaders
import com.linroid.ketch.core.engine.ServerInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.isSecure
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import kotlin.coroutines.cancellation.CancellationException

/**
 * [HttpEngine] implementation backed by a Ktor [HttpClient].
 *
 * Request headers pass through [RequestHeaders.sendable]: one that cannot be sent fails the
 * request with an [IllegalArgumentException], and the engine's own, such as `Range`, are ignored.
 *
 * The engine follows redirects itself, at most 20 per request, in place of its clients' redirect
 * handling, which it turns off. It refuses redirects from HTTPS to HTTP and to schemes other
 * than HTTP(S). A redirect to another scheme, host or port keeps only the headers that cannot
 * carry credentials, `User-Agent`, `Accept`, `Accept-Encoding` and `Accept-Language`, plus
 * `Referer` cut to its origin; cookies, `Authorization` and every other header, and the user
 * information of the URL, stay with the origin they were sent to. Downloads remember where the
 * redirects of a request led, so later requests for the same URL and headers, such as the
 * segments after a probe, go to the same server without following them again; they follow them
 * again when that server fails.
 *
 * Without a client of its own, the engine applies proxies ([withProxy]): each request, and each
 * hop of a redirect, goes directly, through an HTTP proxy (`CONNECT` for HTTPS) or through a
 * SOCKS5 proxy as its [ProxyConfig] says for that URL, on a client kept for that route. Proxy
 * credentials only ever go to the proxy. On the JVM, requests use CIO when they go directly and
 * OkHttp, limited to HTTP/1.1 so that segments keep their own connections, through a proxy;
 * Android uses OkHttp and iOS Darwin (`NSURLSession`). A client given to it is used as it is,
 * and only follows the system's proxy settings in its own way.
 */
class KtorHttpEngine private constructor(
  private val transports: HttpTransports,
  private val proxy: ProxyConfig,
  private val redirects: RedirectCache,
  private val logRequests: Boolean,
  private val userAgent: String?,
  // Views from withProxy share the transports of the engine that made them, which closes them.
  private val ownsTransports: Boolean,
) : HttpEngine {
  /**
   * @param client the Ktor HTTP client to use, or `null` for the engine's own clients, with
   *   infinite timeouts (suitable for large downloads)
   * @param logRequests whether request URLs, response headers, and transport errors may be logged
   * @param userAgent the `User-Agent` of requests whose headers name none, [DEFAULT_USER_AGENT]
   *   by default; `null` leaves it to [client]
   */
  constructor(
    client: HttpClient? = null,
    logRequests: Boolean = true,
    userAgent: String? = DEFAULT_USER_AGENT,
  ) : this(client?.let(::FixedTransports) ?: defaultTransports(), logRequests, userAgent)

  internal constructor(
    transports: HttpTransports,
    logRequests: Boolean,
    userAgent: String?,
  ) : this(transports, ProxyConfig.System, RedirectCache(), logRequests, userAgent, true)

  private val log = KetchLogger("KtorHttpEngine")

  init {
    userAgent?.let { RequestHeaders.requireValid(mapOf(HttpHeaders.UserAgent to it)) }
  }

  override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
    val requestHeaders = requestHeaders(headers)
    return transport {
      if (logRequests) log.d { "HEAD request: ${redactUrl(url)}" }
      send(HttpMethod.Head, url, requestHeaders, range = null, reuseTarget = false) { response ->
        logResponse("HEAD", response)
        // Callers may still reach the resource with a GET (probe), so this is no failure yet.
        if (!response.status.isSuccess()) throw httpError("HEAD", response, fatal = false)
        serverInfo(
          response,
          contentLength = response.contentLength(),
          acceptRanges = response.headers[HttpHeaders.AcceptRanges]
            ?.contains("bytes", ignoreCase = true) == true,
        )
      }
    }
  }

  override suspend fun probe(url: String, headers: Map<String, String>): ServerInfo {
    val requestHeaders = requestHeaders(headers)
    return transport {
      if (logRequests) log.d { "GET probe: ${redactUrl(url)}, range=0-0" }
      send(HttpMethod.Get, url, requestHeaders, PROBE_RANGE, reuseTarget = false) { response ->
        logResponse("Probe", response)
        // The body is never read; leaving this block discards it.
        val status = response.status
        when {
          status == HttpStatusCode.PartialContent -> {
            val contentRange = parseContentRange(response.headers[HttpHeaders.ContentRange])
              ?.takeIf { it.first == 0L && it.last == 0L }
              ?: throw KetchError.Unsupported(
                IllegalStateException("Server returned a different range"),
              )
            serverInfo(response, contentLength = contentRange.total, acceptRanges = true)
          }
          // An empty resource has no first byte to send.
          status == HttpStatusCode.RequestedRangeNotSatisfiable &&
            response.headers[HttpHeaders.ContentRange]?.trim()
              .equals("bytes */0", ignoreCase = true) ->
            serverInfo(response, contentLength = 0, acceptRanges = false)
          // The server ignored the range and started sending the whole resource.
          status.isSuccess() ->
            serverInfo(response, contentLength = response.contentLength(), acceptRanges = false)
          else -> throw httpError("GET", response)
        }
      }
    }
  }

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    transfer(url, range, headers, onData)
  }

  override suspend fun downloadResource(
    url: String,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ): String = transfer(url, null, headers, onData)

  private suspend fun transfer(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ): String {
    val requestHeaders = requestHeaders(headers)
    return transport {
      if (range != null) {
        if (logRequests) log.d {
          "GET request: ${redactUrl(url)}, range=${range.first}-${range.last}"
        }
      } else {
        if (logRequests) log.d { "GET request: ${redactUrl(url)} (no range)" }
      }
      send(HttpMethod.Get, url, requestHeaders, range, reuseTarget = true) { response ->
        val status = response.status
        logResponse("GET", response)
        if (!status.isSuccess()) throw httpError("GET", response)

        val expectedBytes = range?.let { it.last - it.first + 1 }
          ?: response.contentLength()
        if (range != null) {
          val validRange = when (status) {
            HttpStatusCode.PartialContent -> matchesRange(
              response.headers[HttpHeaders.ContentRange], range
            )
            // A server may ignore Range. Only a complete response starting at zero is safe.
            HttpStatusCode.OK -> range.first == 0L &&
              (response.contentLength() == null || response.contentLength() == expectedBytes)
            else -> false
          }
          if (!validRange) {
            throw KetchError.Unsupported(IllegalStateException("Server returned a different range"))
          }
        }
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var receivedBytes = 0L

        while (!channel.isClosedForRead) {
          val bytesRead = channel.readAvailable(buffer)
          if (bytesRead > 0) {
            if (expectedBytes != null && bytesRead > expectedBytes - receivedBytes) {
              throw KetchError.Unsupported(
                cause = IllegalStateException("Response exceeds requested length"),
              )
            }
            receivedBytes += bytesRead
            val data = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
            onData(data)
          }
        }
        channel.closedCause?.let { throw it }
        if (expectedBytes != null && receivedBytes != expectedBytes) {
          throw KetchError.Network(
            cause = IllegalStateException("Response ended before requested length"),
          )
        }
        response.call.request.url.toString()
      }
    }
  }

  /** `false` when this engine was given its own client, which only follows the system's. */
  override val supportsProxies: Boolean get() = transports.supports(ProxyConfig.Direct)

  /**
   * An engine sharing this one's clients and remembered redirects, whose requests reach their
   * servers as [proxy] says; the redirects followed through each proxy are remembered apart.
   *
   * @throws KetchError.Unsupported when this engine was given its own client and [proxy] is not
   *   the system's
   */
  override fun withProxy(proxy: ProxyConfig): HttpEngine {
    if (proxy == this.proxy) return this
    if (!transports.supports(proxy)) {
      throw KetchError.Unsupported(
        cause = UnsupportedOperationException("A custom HttpClient cannot change its proxy"),
      )
    }
    return KtorHttpEngine(transports, proxy, redirects, logRequests, userAgent, false)
  }

  override fun close() {
    if (ownsTransports) transports.close()
  }

  /** [headers] as they are sent: checked, without the engine's own, with the user agent. */
  private fun requestHeaders(headers: Map<String, String>): Map<String, String> {
    val sendable = RequestHeaders.sendable(headers)
    val agent = userAgent ?: return sendable
    if (sendable.keys.any { it.equals(HttpHeaders.UserAgent, ignoreCase = true) }) return sendable
    return sendable + (HttpHeaders.UserAgent to agent)
  }

  /** Runs [block], reporting transport failures as [KetchError.Network]. */
  private inline fun <T> transport(block: () -> T): T {
    try {
      return block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: KetchError) {
      throw e
    } catch (e: Exception) {
      if (logRequests) log.e { "Network error: ${e.describeCauses()}" }
      throw KetchError.Network(e)
    }
  }

  /**
   * Sends [method] for [url] with [headers] and [range], following redirects, and passes the
   * final response to [handle]. With [reuseTarget], the request goes straight to where the
   * redirects of the same request led last time, and follows them again if that fails.
   */
  private suspend fun <T> send(
    method: HttpMethod,
    url: String,
    headers: Map<String, String>,
    range: LongRange?,
    reuseTarget: Boolean,
    handle: suspend (HttpResponse) -> T,
  ): T {
    val key = RedirectCache.Key(url, headers, proxy)
    val cached = if (reuseTarget) redirects.get(key) else null
    if (cached != null) {
      val result = try {
        exchange(method, cached.url, cached.headers, range) { response ->
          // Expired signatures and retired mirrors answer with an error before any data.
          if (response.status.isSuccess()) Hop.Final(handle(response)) else null
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // The next attempt follows the redirects again.
        redirects.remove(key)
        throw e
      }
      if (result != null) return result.value
      redirects.remove(key)
      if (logRequests) log.d { "Following redirects again for ${redactUrl(url)}" }
    }

    var target = Url(url)
    var hopHeaders = headers
    var redirectCount = 0
    while (true) {
      val hop = exchange(method, target, hopHeaders, range) { response ->
        redirectOf(response) ?: Hop.Final(handle(response))
      }
      when (hop) {
        is Hop.Final -> {
          if (redirectCount > 0) {
            redirects.put(key, RedirectCache.Target(target, hopHeaders))
          } else if (!reuseTarget) {
            // The origin answers itself now, so later requests go there too.
            redirects.remove(key)
          }
          return hop.value
        }
        is Hop.Redirect -> {
          if (++redirectCount > MAX_REDIRECTS) {
            throw KetchError.Http(hop.status.value, "Too many redirects")
          }
          val next = redirectTarget(target, hop)
          if (logRequests) log.d {
            "Redirect ${hop.status.value}: ${redactUrl(target.toString())} -> " +
              redactUrl(next.toString())
          }
          if (!next.sameOrigin(target)) {
            val kept = crossOriginHeaders(hopHeaders, next)
            val dropped = hopHeaders.keys.filter { it !in kept }
            if (logRequests && dropped.isNotEmpty()) log.d {
              "Not sending ${dropped.joinToString()} to another origin"
            }
            hopHeaders = kept
          }
          target = next
        }
      }
    }
  }

  private suspend fun <R> exchange(
    method: HttpMethod,
    target: Url,
    headers: Map<String, String>,
    range: LongRange?,
    onResponse: suspend (HttpResponse) -> R,
  ): R {
    val routed = transports.clientFor(target, proxy)
    if (logRequests && routed.route.isProxy) log.d {
      "Sending ${method.value} for ${redactUrl(target.toString())} through ${routed.route}"
    }
    return routed.client.prepareRequest {
      this.method = method
      url(target)
      headers.forEach { (name, value) -> header(name, value) }
      if (range != null) header(HttpHeaders.Range, "bytes=${range.first}-${range.last}")
    }.execute { onResponse(it) }
  }

  private fun logResponse(label: String, response: HttpResponse) {
    if (logRequests) log.d {
      "$label ${response.status.value} headers: ${describeHeaders(response.headers)}"
    }
  }

  /**
   * The error for a non-success [response], with the rate limit it reports on 429. Logged as an
   * error when [fatal], else at debug level for callers that may still recover.
   */
  private fun httpError(
    method: String,
    response: HttpResponse,
    fatal: Boolean = true,
  ): KetchError.Http {
    val status = response.status
    if (logRequests) {
      val message = "HTTP error ${status.value}: ${status.description} " +
        "for $method ${redactUrl(response.request.url.toString())}"
      if (fatal) log.e { message } else log.d { message }
    }
    if (status.value != 429) return KetchError.Http(status.value, status.description)
    val retryAfter = parseRetryAfter(response.headers[HttpHeaders.RetryAfter])
      ?: findRateLimitReset(response.headers)
    val remaining = findRateLimitRemaining(response.headers)
    if (logRequests) log.d { "Rate limit headers: retryAfter=$retryAfter, remaining=$remaining" }
    return KetchError.Http(status.value, status.description, retryAfter, remaining)
  }

  /** How one request of a redirect chain ended. */
  private sealed interface Hop<out T> {
    /** The final response, handled. */
    class Final<T>(val value: T) : Hop<T>

    /** A redirect to [location]. */
    class Redirect(val status: HttpStatusCode, val location: String) : Hop<Nothing>
  }

  private class ContentRange(val first: Long, val last: Long, val total: Long?)

  companion object {
    /** `User-Agent` of requests that name none: `Ketch/` and the library version. */
    const val DEFAULT_USER_AGENT: String = "Ketch/${KetchApi.VERSION}"

    private const val DEFAULT_BUFFER_SIZE = 8192
    private const val MAX_REDIRECTS = 20
    private val PROBE_RANGE = 0L..0L
    private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    private val HTTP_SCHEMES = setOf("http", "https")

    /** Lowercase names of the request headers a redirect to another origin keeps. */
    private val CROSS_ORIGIN_HEADERS = setOf(
      "user-agent", "accept", "accept-encoding", "accept-language",
    )
    private val SENSITIVE_HEADERS = setOf("set-cookie", "cookie", "authorization")
    private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

    /** [headers] for a debug log line, with the values of [SENSITIVE_HEADERS] masked. */
    private fun describeHeaders(headers: Headers): String =
      headers.entries().joinToString { (name, values) ->
        // Cookies are session credentials; their presence is enough for troubleshooting.
        val value = if (name.lowercase() in SENSITIVE_HEADERS) "***" else values.joinToString(",")
        "$name=$value"
      }

    private fun parseContentRange(value: String?): ContentRange? {
      val match = value?.trim()?.let { CONTENT_RANGE.matchEntire(it) } ?: return null
      val first = match.groupValues[1].toLongOrNull() ?: return null
      val last = match.groupValues[2].toLongOrNull() ?: return null
      val total = match.groupValues[3].let { if (it == "*") null else it.toLongOrNull() }
      if (total != null && total <= last) return null
      return ContentRange(first, last, total)
    }

    private fun matchesRange(value: String?, range: LongRange): Boolean {
      val contentRange = parseContentRange(value) ?: return false
      return contentRange.first == range.first && contentRange.last == range.last
    }

    private fun serverInfo(
      response: HttpResponse,
      contentLength: Long?,
      acceptRanges: Boolean,
    ): ServerInfo = ServerInfo(
      contentLength = contentLength,
      acceptRanges = acceptRanges,
      etag = response.headers[HttpHeaders.ETag],
      lastModified = response.headers[HttpHeaders.LastModified],
      contentDisposition = response.headers[HttpHeaders.ContentDisposition],
      rateLimitRemaining = findRateLimitRemaining(response.headers),
      rateLimitReset = findRateLimitReset(response.headers),
      contentType = response.headers[HttpHeaders.ContentType],
    )

    private fun redirectOf(response: HttpResponse): Hop.Redirect? {
      if (response.status.value !in REDIRECT_STATUSES) return null
      // Without a target, a redirect is an ordinary error response.
      val location = response.headers[HttpHeaders.Location] ?: return null
      return Hop.Redirect(response.status, location)
    }

    /** Where [redirect] leads from [from], refusing targets that are not HTTP(S) or less safe. */
    private fun redirectTarget(from: Url, redirect: Hop.Redirect): Url {
      val status = redirect.status.value
      val builder = try {
        URLBuilder(from).apply {
          parameters.clear()
          fragment = ""
          takeFrom(redirect.location)
        }
      } catch (e: Exception) {
        throw KetchError.Http(status, "Invalid redirect location", cause = e)
      }
      if (builder.protocol.name !in HTTP_SCHEMES) {
        throw KetchError.Http(status, "Redirect to an unsupported scheme")
      }
      if (from.protocol.isSecure() && !builder.protocol.isSecure()) {
        throw KetchError.Http(status, "Redirect from HTTPS to HTTP refused")
      }
      val next = builder.build()
      if (next.sameOrigin(from)) return next
      // User information belongs to the origin it was written for.
      return URLBuilder(next).apply {
        user = null
        password = null
      }.build()
    }

    private fun Url.sameOrigin(other: Url): Boolean =
      protocol.name == other.protocol.name &&
        host.equals(other.host, ignoreCase = true) &&
        port == other.port

    /**
     * The [headers] a request to another origin, [target], may carry: those that cannot hold
     * credentials, and `Referer` cut to its origin, unless it would go from HTTPS to HTTP.
     */
    private fun crossOriginHeaders(headers: Map<String, String>, target: Url): Map<String, String> =
      buildMap {
        for ((name, value) in headers) {
          when (name.lowercase()) {
            in CROSS_ORIGIN_HEADERS -> put(name, value)
            "referer" -> refererOrigin(value, target)?.let { put(name, it) }
          }
        }
      }

    private fun refererOrigin(referer: String, target: Url): String? {
      val url = try {
        Url(referer)
      } catch (_: Exception) {
        return null
      }
      if (url.protocol.name !in HTTP_SCHEMES || url.host.isEmpty()) return null
      if (url.protocol.isSecure() && !target.protocol.isSecure()) return null
      val port = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"
      return "${url.protocol.name}://${url.host}$port/"
    }

    private val ProxyRoute.isProxy: Boolean
      get() = this is ProxyRoute.Http || this is ProxyRoute.Socks5

    /**
     * Parses the `Retry-After` header value as a number of seconds.
     * Returns `null` if the value is absent or not a valid integer.
     * HTTP-date format is not supported and will return `null`.
     */
    private fun parseRetryAfter(value: String?): Long? {
      return value?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    }

    /**
     * Parses a rate limit header value as a non-negative long.
     * Accepts `0` (unlike [parseRetryAfter] which requires > 0).
     */
    private fun parseRateLimitLong(value: String?): Long? {
      return value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    }

    // Header name variants for RateLimit-Remaining:
    //   draft-polli-02:  RateLimit-Remaining
    //   non-standard:    X-RateLimit-Remaining, X-Rate-Limit-Remaining
    //   draft-ietf-10:   RateLimit (combined, ;r= parameter)
    private val REMAINING_HEADERS = listOf(
      "RateLimit-Remaining",
      "X-RateLimit-Remaining",
      "X-Rate-Limit-Remaining",
    )

    // Header name variants for RateLimit-Reset:
    //   draft-polli-02:  RateLimit-Reset
    //   non-standard:    X-RateLimit-Reset, X-Rate-Limit-Reset
    //   draft-ietf-10:   RateLimit (combined, ;t= parameter)
    private val RESET_HEADERS = listOf(
      "RateLimit-Reset",
      "X-RateLimit-Reset",
      "X-Rate-Limit-Reset",
    )

    /**
     * Finds `remaining` from any known rate limit header variant.
     * Checks separate headers first, then the combined `RateLimit`
     * structured header (`;r=` parameter).
     */
    private fun findRateLimitRemaining(headers: Headers): Long? {
      for (name in REMAINING_HEADERS) {
        parseRateLimitLong(headers[name])?.let { return it }
      }
      return parseStructuredParam(headers["RateLimit"], 'r')
    }

    /**
     * Finds `reset` (seconds) from any known rate limit header variant.
     * Checks separate headers first, then the combined `RateLimit`
     * structured header (`;t=` parameter).
     */
    private fun findRateLimitReset(headers: Headers): Long? {
      for (name in RESET_HEADERS) {
        parseRateLimitLong(headers[name])?.let { return it }
      }
      return parseStructuredParam(headers["RateLimit"], 't')
    }

    /**
     * Extracts a numeric parameter from a structured field
     * value like `"default";r=50;t=30`.
     */
    private fun parseStructuredParam(
      value: String?,
      param: Char,
    ): Long? {
      if (value == null) return null
      val pattern = Regex(""";$param=(\d+)""")
      return pattern.find(value)
        ?.groupValues?.get(1)
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
    }
  }
}
