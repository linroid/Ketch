package com.linroid.kdown.engine

import com.linroid.kdown.core.engine.HttpEngine
import com.linroid.kdown.core.engine.ServerInfo
import com.linroid.kdown.api.KDownError
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

class KtorHttpEngine(
  private val client: HttpClient = defaultClient()
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
        throw KDownError.Http(response.status.value, response.status.description)
      }

      val acceptRanges = response.headers[HttpHeaders.AcceptRanges]
      val etag = response.headers[HttpHeaders.ETag]
      val lastModified = response.headers[HttpHeaders.LastModified]
      val contentLength = response.contentLength()
      val contentDisposition =
        response.headers[HttpHeaders.ContentDisposition]

      return ServerInfo(
        contentLength = contentLength,
        acceptRanges = acceptRanges?.contains("bytes", ignoreCase = true) == true,
        etag = etag,
        lastModified = lastModified,
        contentDisposition = contentDisposition
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
    onData: suspend (ByteArray) -> Unit
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
          throw KDownError.Http(status.value, status.description)
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
  }
}
