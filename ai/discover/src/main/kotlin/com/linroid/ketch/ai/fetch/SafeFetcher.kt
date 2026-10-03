package com.linroid.ketch.ai.fetch

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.net.URI

/**
 * SSRF-protected HTTP fetcher that validates every URL it requests
 * and enforces content size limits.
 *
 * Redirects are followed here, one hop at a time, so that each target
 * is validated before it is requested: a public URL must not be able
 * to redirect to a private or loopback address. [httpClient] must
 * therefore be built with `followRedirects = false`.
 *
 * Validation resolves the host, and so does the client when it
 * connects. [createHttpClient] builds a client that resolves through
 * [urlValidator] as well, so a host that passes validation with a
 * public address cannot then be reached at a private one.
 *
 * @param httpClient Ktor HTTP client that does not follow redirects,
 *   normally from [createHttpClient]
 * @param urlValidator validator for SSRF protection
 * @param rateLimiter spaces requests per host and caps concurrent ones
 * @param maxContentBytes maximum bytes to read per fetch (default 2 MB)
 * @param userAgent User-Agent header value
 * @param maxRedirects maximum redirects followed per request
 */
internal class SafeFetcher(
  private val httpClient: HttpClient,
  private val urlValidator: UrlValidator,
  private val rateLimiter: RateLimiter = RateLimiter(),
  val maxContentBytes: Long = DEFAULT_MAX_CONTENT_BYTES,
  private val userAgent: String = DEFAULT_USER_AGENT,
  private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
) {

  init {
    require(httpClient.pluginOrNull(HttpRedirect) == null) {
      "SafeFetcher validates each redirect itself; build the client with followRedirects = false"
    }
  }

  private val log = KetchLogger("SafeFetcher")

  /**
   * Fetches the content at [url], reading at most [maxBytes] of the
   * body (capped at the fetcher's per-fetch limit).
   *
   * @param truncate keep the first bytes of a body over the limit
   *   instead of failing when its `Content-Length` declares it too large
   * @param checkHop runs on every URL about to be requested, redirect
   *   targets included, after [UrlValidator.check] and before the host is
   *   looked up; a non-null result is why that URL must not be requested,
   *   and ends the fetch
   * @return [FetchResult.Success] with the content, or
   *   [FetchResult.Failed] with a reason
   */
  suspend fun fetch(
    url: String,
    maxBytes: Long = maxContentBytes,
    truncate: Boolean = false,
    checkHop: suspend (URI) -> String? = { null },
  ): FetchResult {
    val limit = maxBytes.coerceIn(0, maxContentBytes)
    val fail: (String) -> FetchResult = { FetchResult.Failed(url, it) }
    return try {
      exchange(url, HttpMethod.Get, checkHop, fail) { finalUrl, response ->
        readBody(url, finalUrl, response, limit, truncate)
      }
    } catch (e: Exception) {
      if (e is CancellationException) currentCoroutineContext().ensureActive()
      log.w(e) { "Fetch failed for ${redactUrl(url)}" }
      FetchResult.Failed(url, e.message ?: "Unknown error")
    }
  }

  /**
   * Performs an HTTP HEAD request on [url].
   *
   * @param checkHop runs on every URL about to be requested, redirect
   *   targets included, after [UrlValidator.check] and before the host is
   *   looked up; a non-null result is why that URL must not be requested,
   *   and ends the request
   * @return [HeadResult.Success] with response metadata, or
   *   [HeadResult.Failed] with a reason
   */
  suspend fun head(
    url: String,
    checkHop: suspend (URI) -> String? = { null },
  ): HeadResult {
    val fail: (String) -> HeadResult = { HeadResult.Failed(url, it) }
    return try {
      exchange(url, HttpMethod.Head, checkHop, fail) { finalUrl, response ->
        if (!response.status.isSuccess()) {
          HeadResult.Failed(url, "HTTP ${response.status.value}")
        } else {
          HeadResult.Success(
            url = url,
            finalUrl = finalUrl,
            statusCode = response.status.value,
            contentType = response.headers[HttpHeaders.ContentType],
            contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
            lastModified = response.headers[HttpHeaders.LastModified],
            etag = response.headers[HttpHeaders.ETag],
          )
        }
      }
    } catch (e: Exception) {
      if (e is CancellationException) currentCoroutineContext().ensureActive()
      log.w(e) { "HEAD failed for ${redactUrl(url)}" }
      HeadResult.Failed(url, e.message ?: "Unknown error")
    }
  }

  /**
   * Sends [method] to [url] and hands the first response that is not a
   * redirect to [handle] along with the URL that produced it. Each hop
   * is checked, passed to [checkHop], then validated with its host looked
   * up, before it is requested; a refused hop, or more than [maxRedirects]
   * redirects, ends the exchange through [fail].
   */
  private suspend fun <T> exchange(
    url: String,
    method: HttpMethod,
    checkHop: suspend (URI) -> String?,
    fail: (reason: String) -> T,
    handle: suspend (finalUrl: String, response: HttpResponse) -> T,
  ): T {
    var current = url
    for (redirects in 0..maxRedirects) {
      val blocked = { reason: String ->
        log.w { "Blocked ${redactUrl(current)}: $reason" }
        fail(if (redirects == 0) reason else "Redirect blocked: $reason")
      }
      val uri = when (val checked = urlValidator.check(current)) {
        is ValidationResult.Blocked -> return blocked(checked.reason)
        is ValidationResult.Valid -> checked.uri
      }
      checkHop(uri)?.let { reason ->
        log.d { "Refused ${redactUrl(current)}: $reason" }
        return fail(if (redirects == 0) reason else "Redirect refused: $reason")
      }
      // Looked up only now: the lookup sends the name to its domain's DNS servers.
      val validation = urlValidator.validate(current)
      if (validation is ValidationResult.Blocked) return blocked(validation.reason)
      val hop = try {
        rateLimiter.withPermit(uri.host) {
          httpClient.prepareRequest(current) {
            this.method = method
            header(HttpHeaders.UserAgent, userAgent)
          }.execute { response ->
            val location = response.headers[HttpHeaders.Location]
            if (response.status.value in REDIRECT_CODES && location != null) {
              Hop.Redirect(resolveLocation(current, location))
            } else {
              Hop.Done(handle(current, response))
            }
          }
        }
      } catch (e: BlockedHostException) {
        // The host passed validation, then resolved to a blocked address
        // when the client connected (DNS rebinding).
        log.w { "Blocked ${redactUrl(current)} on connect: ${e.reason}" }
        return fail(if (redirects == 0) e.reason else "Redirect blocked: ${e.reason}")
      }
      when (hop) {
        is Hop.Done -> return hop.value
        is Hop.Redirect -> {
          log.d { "Redirect ${redactUrl(current)} -> ${redactUrl(hop.location)}" }
          current = hop.location
        }
      }
    }
    return fail("Too many redirects (max $maxRedirects)")
  }

  private suspend fun readBody(
    url: String,
    finalUrl: String,
    response: HttpResponse,
    limit: Long,
    truncate: Boolean,
  ): FetchResult {
    if (!response.status.isSuccess()) {
      return FetchResult.Failed(url, "HTTP ${response.status.value}")
    }
    val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (!truncate && declared != null && declared > limit) {
      return FetchResult.Failed(url, "Content too large: $declared bytes (max $limit)")
    }
    // The body is streamed, so a response without Content-Length stops
    // at the limit instead of being buffered whole.
    val channel = response.bodyAsChannel()
    val bytes = channel.readAtMost(limit)
    val truncated = bytes.size.toLong() == limit && channel.awaitContent()
    log.d { "Fetched ${bytes.size} bytes from ${redactUrl(finalUrl)}, truncated=$truncated" }
    return FetchResult.Success(
      url = url,
      finalUrl = finalUrl,
      content = bytes.decodeToString(),
      statusCode = response.status.value,
      byteCount = bytes.size.toLong(),
      truncated = truncated,
    )
  }

  /**
   * Reads until [limit] bytes or the end of the body. Ktor's
   * `readRemaining(max)` is not used: it can return more than `max`
   * when more data arrives while it copies.
   */
  private suspend fun ByteReadChannel.readAtMost(limit: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(READ_CHUNK_BYTES)
    while (out.size() < limit) {
      val wanted = minOf(chunk.size.toLong(), limit - out.size()).toInt()
      val read = readAvailable(chunk, 0, wanted)
      if (read == -1) break
      out.write(chunk, 0, read)
    }
    return out.toByteArray()
  }

  /** Resolves a possibly relative [location] the way Ktor's redirect plugin does. */
  private fun resolveLocation(current: String, location: String): String =
    URLBuilder(current).apply {
      parameters.clear()
      takeFrom(location)
    }.buildString()

  private sealed interface Hop<out T> {
    class Done<T>(val value: T) : Hop<T>
    class Redirect(val location: String) : Hop<Nothing>
  }

  companion object {
    /**
     * Builds the client a [SafeFetcher] needs: it does not follow
     * redirects, resolves hosts through [ValidatingDns] so that it only
     * connects to addresses [urlValidator] accepts, and never uses a
     * proxy, which would resolve hosts out of reach of that check.
     */
    fun createHttpClient(urlValidator: UrlValidator, requestTimeoutMs: Long): HttpClient =
      HttpClient(OkHttp) {
        followRedirects = false
        engine {
          dns = ValidatingDns(urlValidator)
          proxy = Proxy.NO_PROXY
        }
        install(HttpTimeout) {
          requestTimeoutMillis = requestTimeoutMs
        }
      }

    private const val DEFAULT_MAX_CONTENT_BYTES = 2L * 1024 * 1024
    private const val DEFAULT_USER_AGENT = "KetchBot/1.0"
    private const val DEFAULT_MAX_REDIRECTS = 10
    private const val READ_CHUNK_BYTES = 8 * 1024
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
  }
}

/** Result of a fetch attempt. */
internal sealed interface FetchResult {
  val url: String

  /**
   * Successfully fetched content.
   *
   * @property finalUrl URL the content came from after redirects
   * @property byteCount body bytes read
   * @property truncated the body went on past the bytes read
   */
  data class Success(
    override val url: String,
    val finalUrl: String,
    val content: String,
    val statusCode: Int,
    val byteCount: Long,
    val truncated: Boolean = false,
  ) : FetchResult

  /** Fetch failed or was blocked. */
  data class Failed(
    override val url: String,
    val reason: String,
  ) : FetchResult
}

/** Result of an HTTP HEAD request. */
internal sealed interface HeadResult {
  val url: String

  /**
   * Successful HEAD response with metadata.
   *
   * @property finalUrl URL that answered after redirects
   */
  data class Success(
    override val url: String,
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String?,
    val contentLength: Long?,
    val lastModified: String?,
    val etag: String?,
  ) : HeadResult

  /** HEAD request failed or was blocked. */
  data class Failed(
    override val url: String,
    val reason: String,
  ) : HeadResult
}
