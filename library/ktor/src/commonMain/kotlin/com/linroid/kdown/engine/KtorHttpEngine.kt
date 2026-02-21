package com.linroid.kdown.engine

import com.linroid.kdown.api.KDownError
import com.linroid.kdown.core.engine.HttpEngine
import com.linroid.kdown.core.engine.ServerInfo
import com.linroid.kdown.core.log.KDownLogger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlin.coroutines.cancellation.CancellationException

/**
 * [HttpEngine] implementation backed by a Ktor [HttpClient].
 *
 * Uses platform-specific Ktor engines: OkHttp (Android), Darwin (iOS),
 * CIO (JVM), and Js (WasmJs/JS).
 *
 * @param client the Ktor HTTP client to use, or a default client
 *   with infinite timeouts (suitable for large downloads)
 */
class KtorHttpEngine(
  private val client: HttpClient = defaultClient(),
) : HttpEngine {

  override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
    try {
      KDownLogger.d("KtorHttpEngine") { "HEAD request: $url" }
      val customHeaders = headers
      val response = client.head(url) {
        customHeaders.forEach { (name, value) -> header(name, value) }
      }

      KDownLogger.d("KtorHttpEngine") {
        "HEAD ${response.status.value} headers: " +
          response.headers.entries().joinToString { (k, v) ->
            "$k=${v.joinToString(",")}"
          }
      }

      if (!response.status.isSuccess()) {
        KDownLogger.e("KtorHttpEngine") {
          "HTTP error ${response.status.value}: ${response.status.description}"
        }
        val is429 = response.status.value == 429
        val retryAfter = if (is429) {
          parseRetryAfter(response.headers["Retry-After"])
            ?: findRateLimitReset(response.headers)
        } else null
        val remaining = if (is429) {
          findRateLimitRemaining(response.headers)
        } else null
        throw KDownError.Http(
          response.status.value,
          response.status.description,
          retryAfter,
          remaining,
        )
      }

      val acceptRanges = response.headers[HttpHeaders.AcceptRanges]
      val etag = response.headers[HttpHeaders.ETag]
      val lastModified = response.headers[HttpHeaders.LastModified]
      val contentLength = response.contentLength()
      val contentDisposition =
        response.headers[HttpHeaders.ContentDisposition]
      val rateLimitRemaining =
        findRateLimitRemaining(response.headers)
      val rateLimitReset =
        findRateLimitReset(response.headers)

      return ServerInfo(
        contentLength = contentLength,
        acceptRanges = acceptRanges?.contains("bytes", ignoreCase = true) == true,
        etag = etag,
        lastModified = lastModified,
        contentDisposition = contentDisposition,
        rateLimitRemaining = rateLimitRemaining,
        rateLimitReset = rateLimitReset,
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: KDownError) {
      throw e
    } catch (e: Exception) {
      KDownLogger.e("KtorHttpEngine") { "Network error: ${e.message}" }
      throw KDownError.Network(e)
    }
  }

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    try {
      if (range != null) {
        KDownLogger.d("KtorHttpEngine") { "GET request: $url, range=${range.first}-${range.last}" }
      } else {
        KDownLogger.d("KtorHttpEngine") { "GET request: $url (no range)" }
      }
      val customHeaders = headers
      client.prepareGet(url) {
        customHeaders.forEach { (name, value) -> header(name, value) }
        if (range != null) {
          header(HttpHeaders.Range, "bytes=${range.first}-${range.last}")
        }
      }.execute { response ->
        val status = response.status

        KDownLogger.d("KtorHttpEngine") {
          "GET ${status.value} headers: " +
            response.headers.entries().joinToString { (k, v) ->
              "$k=${v.joinToString(",")}"
            }
        }

        if (!status.isSuccess()) {
          KDownLogger.e("KtorHttpEngine") {
            "HTTP error ${status.value}: ${status.description}"
          }
          val is429 = status.value == 429
          val retryAfter = if (is429) {
            parseRetryAfter(response.headers["Retry-After"])
              ?: findRateLimitReset(response.headers)
          } else null
          val remaining = if (is429) {
            findRateLimitRemaining(response.headers)
          } else null
          throw KDownError.Http(
            status.value, status.description, retryAfter, remaining,
          )
        }

        val channel = response.bodyAsChannel()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        while (!channel.isClosedForRead) {
          val bytesRead = channel.readAvailable(buffer)
          if (bytesRead > 0) {
            val data = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
            onData(data)
          }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: KDownError) {
      throw e
    } catch (e: Exception) {
      KDownLogger.e("KtorHttpEngine") { "Network error: ${e.message}" }
      throw KDownError.Network(e)
    }
  }

  override fun close() {
    client.close()
  }

  companion object {
    private const val DEFAULT_BUFFER_SIZE = 8192

    private fun defaultClient(): HttpClient = HttpClient {
      install(HttpTimeout) {
        socketTimeoutMillis = Long.MAX_VALUE
        requestTimeoutMillis = Long.MAX_VALUE
      }
    }

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
     * Extracts a numeric parameter from a
     * [structured field](https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-ratelimit-headers)
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
