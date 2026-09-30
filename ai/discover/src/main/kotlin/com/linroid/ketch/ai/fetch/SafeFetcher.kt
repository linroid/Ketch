package com.linroid.ketch.ai.fetch

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRedirect
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
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readString

/**
 * SSRF-protected HTTP fetcher that validates URLs before fetching
 * and enforces content size limits.
 *
 * Redirects are followed here rather than by the client, so every hop
 * is validated before it is requested and an HTTPS URL is never
 * downgraded to HTTP.
 *
 * @param httpClient Ktor HTTP client for making requests, built with
 *   `followRedirects = false`
 * @param urlValidator validator for SSRF protection
 * @param maxContentBytes maximum bytes to read per fetch (default 2 MB)
 * @param userAgent User-Agent header value
 * @param maxRedirects maximum redirects followed per request
 */
internal class SafeFetcher(
  private val httpClient: HttpClient,
  private val urlValidator: UrlValidator,
  private val maxContentBytes: Long = DEFAULT_MAX_CONTENT_BYTES,
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
   * Fetches the content at [url] after SSRF validation.
   *
   * @return [FetchResult.Success] with the content, or
   *   [FetchResult.Failed] with a reason
   */
  suspend fun fetch(url: String): FetchResult {
    return try {
      send(url, HttpMethod.Get, failed = { FetchResult.Failed(url, it) }) { finalUrl, response ->
        readBody(url, finalUrl, response)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w(e) { "Fetch failed for ${redactUrl(url)}" }
      FetchResult.Failed(url, e.message ?: "Unknown error")
    }
  }

  /**
   * Performs an HTTP HEAD request on [url] after SSRF validation.
   *
   * @return [HeadResult.Success] with response metadata, or
   *   [HeadResult.Failed] with a reason
   */
  suspend fun head(url: String): HeadResult {
    return try {
      send(url, HttpMethod.Head, failed = { HeadResult.Failed(url, it) }) { finalUrl, response ->
        if (!response.status.isSuccess()) {
          HeadResult.Failed(url, "HTTP ${response.status.value}")
        } else {
          HeadResult.Success(
            url = url,
            finalUrl = finalUrl,
            statusCode = response.status.value,
            contentType = response.headers[HttpHeaders.ContentType],
            contentLength = response.headers[HttpHeaders.ContentLength]
              ?.toLongOrNull(),
            lastModified = response.headers[HttpHeaders.LastModified],
            etag = response.headers[HttpHeaders.ETag],
          )
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w(e) { "HEAD failed for ${redactUrl(url)}" }
      HeadResult.Failed(url, e.message ?: "Unknown error")
    }
  }

  /**
   * Requests [url] with [method], following up to [maxRedirects]
   * redirects, and hands the final response to [onResponse]. Every hop
   * is validated before it is requested; a blocked hop, an HTTPS to
   * HTTP downgrade or too many redirects end the request with [failed].
   */
  private suspend fun <T> send(
    url: String,
    method: HttpMethod,
    failed: (reason: String) -> T,
    onResponse: suspend (finalUrl: String, response: HttpResponse) -> T,
  ): T {
    var current = url
    for (hop in 0..maxRedirects) {
      val validation = urlValidator.validate(current)
      if (validation is ValidationResult.Blocked) {
        val reason = if (hop == 0) validation.reason else "Redirect blocked: ${validation.reason}"
        log.w { "URL blocked: $reason" }
        return failed(reason)
      }
      val step: Step<T> = httpClient.prepareRequest(current) {
        this.method = method
        header(HttpHeaders.UserAgent, userAgent)
      }.execute { response ->
        val location = response.headers[HttpHeaders.Location]
        if (response.status.value in REDIRECT_CODES && location != null) {
          Step.Redirect(location)
        } else {
          Step.Done(onResponse(current, response))
        }
      }
      when (step) {
        is Step.Done -> return step.result
        is Step.Redirect -> {
          val next = URLBuilder(current).takeFrom(step.location).buildString()
          if (isHttps(current) && !isHttps(next)) {
            return failed("Redirect from HTTPS to a non-HTTPS URL refused")
          }
          log.d { "Redirect ${redactUrl(current)} -> ${redactUrl(next)}" }
          current = next
        }
      }
    }
    return failed("Too many redirects (max $maxRedirects)")
  }

  private suspend fun readBody(
    url: String,
    finalUrl: String,
    response: HttpResponse,
  ): FetchResult {
    if (!response.status.isSuccess()) {
      return FetchResult.Failed(url, "HTTP ${response.status.value}")
    }

    val contentLength = response.headers[HttpHeaders.ContentLength]
      ?.toLongOrNull()
    if (contentLength != null && contentLength > maxContentBytes) {
      return FetchResult.Failed(
        url,
        "Content too large: $contentLength bytes" +
          " (max $maxContentBytes)",
      )
    }

    val body = response.bodyAsChannel().readRemaining(maxContentBytes).readString()
    log.d { "Fetched ${body.length} chars from ${redactUrl(finalUrl)}" }
    return FetchResult.Success(
      url = url,
      finalUrl = finalUrl,
      content = body,
      statusCode = response.status.value,
    )
  }

  private fun isHttps(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

  /** Outcome of one hop: a redirect to follow, or the final result. */
  private sealed interface Step<out T> {
    data class Redirect(val location: String) : Step<Nothing>
    data class Done<T>(val result: T) : Step<T>
  }

  companion object {
    private const val DEFAULT_MAX_CONTENT_BYTES = 2L * 1024 * 1024
    private const val DEFAULT_USER_AGENT = "KetchBot/1.0"
    private const val DEFAULT_MAX_REDIRECTS = 5
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
  }
}

/** Result of a fetch attempt. */
internal sealed interface FetchResult {
  val url: String

  /**
   * Successfully fetched content.
   *
   * @param finalUrl where the content was served from after redirects;
   *   equal to [url] when there were none
   */
  data class Success(
    override val url: String,
    val finalUrl: String,
    val content: String,
    val statusCode: Int,
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
   * @param finalUrl where the resource is served from after redirects;
   *   equal to [url] when there were none
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
