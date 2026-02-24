package com.linroid.ketch.ai.fetch

import com.linroid.ketch.api.log.KetchLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readString

/**
 * SSRF-protected HTTP fetcher that validates URLs before fetching
 * and enforces content size limits.
 *
 * @param httpClient Ktor HTTP client for making requests
 * @param urlValidator validator for SSRF protection
 * @param maxContentBytes maximum bytes to read per fetch (default 2 MB)
 * @param userAgent User-Agent header value
 */
internal class SafeFetcher(
  private val httpClient: HttpClient,
  private val urlValidator: UrlValidator,
  private val maxContentBytes: Long = DEFAULT_MAX_CONTENT_BYTES,
  private val userAgent: String = DEFAULT_USER_AGENT,
) {

  private val log = KetchLogger("SafeFetcher")

  /**
   * Fetches the content at [url] after SSRF validation.
   *
   * @return [FetchResult.Success] with the content, or
   *   [FetchResult.Failed] with a reason
   */
  suspend fun fetch(url: String): FetchResult {
    when (val validation = urlValidator.validate(url)) {
      is ValidationResult.Blocked -> {
        log.w { "URL blocked: ${validation.reason}" }
        return FetchResult.Failed(url, validation.reason)
      }
      is ValidationResult.Valid -> {
        // URL passed validation
      }
    }

    return try {
      val response = httpClient.get(url) {
        header(HttpHeaders.UserAgent, userAgent)
      }

      if (!response.status.isSuccess()) {
        return FetchResult.Failed(
          url, "HTTP ${response.status.value}"
        )
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

      val channel = response.bodyAsChannel()
      val packet = channel.readRemaining(maxContentBytes)
      val body = packet.readString()

      log.d { "Fetched ${body.length} chars from $url" }
      FetchResult.Success(url, body, response.status.value)
    } catch (e: Exception) {
      log.w(e) { "Fetch failed for $url" }
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
    when (val validation = urlValidator.validate(url)) {
      is ValidationResult.Blocked -> {
        log.w { "URL blocked: ${validation.reason}" }
        return HeadResult.Failed(url, validation.reason)
      }
      is ValidationResult.Valid -> {
        // URL passed validation
      }
    }

    return try {
      val response = httpClient.head(url) {
        header(HttpHeaders.UserAgent, userAgent)
      }

      val finalUrl = response.headers[HttpHeaders.Location] ?: url
      if (!response.status.isSuccess()) {
        return HeadResult.Failed(
          url, "HTTP ${response.status.value}"
        )
      }

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
    } catch (e: Exception) {
      log.w(e) { "HEAD failed for $url" }
      HeadResult.Failed(url, e.message ?: "Unknown error")
    }
  }

  companion object {
    private const val DEFAULT_MAX_CONTENT_BYTES = 2L * 1024 * 1024
    private const val DEFAULT_USER_AGENT = "KetchBot/1.0"
  }
}

/** Result of a fetch attempt. */
internal sealed interface FetchResult {
  val url: String

  /** Successfully fetched content. */
  data class Success(
    override val url: String,
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

  /** Successful HEAD response with metadata. */
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
