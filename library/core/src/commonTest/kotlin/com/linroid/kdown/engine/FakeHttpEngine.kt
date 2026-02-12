package com.linroid.kdown.engine

import com.linroid.kdown.core.engine.HttpEngine
import com.linroid.kdown.core.engine.ServerInfo
import com.linroid.kdown.api.KDownError

/**
 * A fake HttpEngine for unit testing. Simulates a server with configurable behavior
 * including range support, ETags, and content delivery.
 */
class FakeHttpEngine(
  var serverInfo: ServerInfo = ServerInfo(
    contentLength = 1000,
    acceptRanges = true,
    etag = "\"test-etag\"",
    lastModified = "Wed, 01 Jan 2025 00:00:00 GMT",
  ),
  var content: ByteArray = ByteArray(1000) { (it % 256).toByte() },
  var chunkSize: Int = 100,
  var failAfterBytes: Long = -1,
  var failOnHead: Boolean = false,
  var httpErrorCode: Int = 0,
) : HttpEngine {

  var headCallCount = 0
    private set
  var downloadCallCount = 0
    private set
  var lastHeadHeaders: Map<String, String> = emptyMap()
    private set
  var lastDownloadHeaders: Map<String, String> = emptyMap()
    private set
  var closed = false
    private set

  override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
    headCallCount++
    lastHeadHeaders = headers
    if (failOnHead) {
      throw KDownError.Network(RuntimeException("Simulated network failure"))
    }
    if (httpErrorCode > 0) {
      throw KDownError.Http(httpErrorCode, "Simulated HTTP error")
    }
    return serverInfo
  }

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    downloadCallCount++
    lastDownloadHeaders = headers

    if (httpErrorCode > 0) {
      throw KDownError.Http(httpErrorCode, "Simulated HTTP error")
    }

    val start = range?.first?.toInt() ?: 0
    val end = range?.last?.toInt() ?: (content.size - 1)
    val rangeContent = content.sliceArray(start..minOf(end, content.size - 1))

    var offset = 0
    var totalSent = 0L
    while (offset < rangeContent.size) {
      if (failAfterBytes in 0..totalSent) {
        throw KDownError.Network(RuntimeException("Simulated failure after $failAfterBytes bytes"))
      }
      val chunkEnd = minOf(offset + chunkSize, rangeContent.size)
      val chunk = rangeContent.sliceArray(offset until chunkEnd)
      onData(chunk)
      offset = chunkEnd
      totalSent += chunk.size
    }
  }

  override fun close() {
    closed = true
  }
}
