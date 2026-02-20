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

      if (!response.status.isSuccess()) {
        KDownLogger.e("KtorHttpEngine") {
          "HTTP error ${response.status.value}: ${response.status.description}"
        }
        val is429 = response.status.value == 429
        val retryAfter = if (is429) {
          parseRetryAfter(response.headers["Retry-After"])
            ?: parseRateLimitLong(response.headers["RateLimit-Reset"])
        } else null
        val remaining = if (is429) {
          parseRateLimitLong(response.headers["RateLimit-Remaining"])
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
      val rateLimitRemaining = parseRateLimitLong(
        response.headers["RateLimit-Remaining"]
      )
      val rateLimitReset = parseRateLimitLong(
        response.headers["RateLimit-Reset"]
      )

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
        if (!status.isSuccess()) {
          KDownLogger.e("KtorHttpEngine") {
            "HTTP error ${status.value}: ${status.description}"
          }
          val is429 = status.value == 429
          val retryAfter = if (is429) {
            parseRetryAfter(response.headers["Retry-After"])
              ?: parseRateLimitLong(response.headers["RateLimit-Reset"])
          } else null
          val remaining = if (is429) {
            parseRateLimitLong(response.headers["RateLimit-Remaining"])
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
  }
}
