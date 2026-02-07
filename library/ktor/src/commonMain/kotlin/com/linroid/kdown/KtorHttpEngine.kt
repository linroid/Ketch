package com.linroid.kdown

import com.linroid.kdown.error.KDownError
import com.linroid.kdown.model.ServerInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable

class KtorHttpEngine(
  private val client: HttpClient = HttpClient()
) : HttpEngine {

  override suspend fun head(url: String): ServerInfo {
    try {
      val response = client.head(url)

      if (!response.status.isSuccess()) {
        throw KDownError.Http(response.status.value, response.status.description)
      }

      val acceptRanges = response.headers[HttpHeaders.AcceptRanges]
      val etag = response.headers[HttpHeaders.ETag]
      val lastModified = response.headers[HttpHeaders.LastModified]
      val contentLength = response.contentLength()

      return ServerInfo(
        contentLength = contentLength,
        acceptRanges = acceptRanges?.contains("bytes", ignoreCase = true) == true,
        etag = etag,
        lastModified = lastModified
      )
    } catch (e: KDownError) {
      throw e
    } catch (e: Exception) {
      throw KDownError.Network(e)
    }
  }

  override suspend fun download(
    url: String,
    range: LongRange?,
    onData: suspend (ByteArray) -> Unit
  ) {
    try {
      client.prepareGet(url) {
        if (range != null) {
          header(HttpHeaders.Range, "bytes=${range.first}-${range.last}")
        }
      }.execute { response ->
        val status = response.status
        if (!status.isSuccess()) {
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
    } catch (e: KDownError) {
      throw e
    } catch (e: Exception) {
      throw KDownError.Network(e)
    }
  }

  override fun close() {
    client.close()
  }

  companion object {
    private const val DEFAULT_BUFFER_SIZE = 8192
  }
}
