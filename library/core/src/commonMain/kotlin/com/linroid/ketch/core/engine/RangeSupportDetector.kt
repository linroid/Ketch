package com.linroid.ketch.core.engine

import com.linroid.ketch.core.log.KetchLogger

internal class RangeSupportDetector(
  private val httpEngine: HttpEngine,
) {
  suspend fun detect(url: String, headers: Map<String, String> = emptyMap()): ServerInfo {
    KetchLogger.d("RangeDetector") { "Sending HEAD request to $url" }
    val serverInfo = httpEngine.head(url, headers)
    KetchLogger.i("RangeDetector") {
      "Server info: contentLength=${serverInfo.contentLength}, " +
        "acceptRanges=${serverInfo.acceptRanges}, " +
        "supportsResume=${serverInfo.supportsResume}, " +
        "etag=${serverInfo.etag}, " +
        "lastModified=${serverInfo.lastModified}"
    }
    return serverInfo
  }
}
