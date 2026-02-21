package com.linroid.ketch.core.engine

import com.linroid.ketch.api.log.KetchLogger

internal class RangeSupportDetector(
  private val httpEngine: HttpEngine,
) {
  private val log = KetchLogger("RangeDetector")

  suspend fun detect(url: String, headers: Map<String, String> = emptyMap()): ServerInfo {
    log.d { "Sending HEAD request to $url" }
    val serverInfo = httpEngine.head(url, headers)
    log.i {
      "Server info: contentLength=${serverInfo.contentLength}, " +
        "acceptRanges=${serverInfo.acceptRanges}, " +
        "supportsResume=${serverInfo.supportsResume}, " +
        "etag=${serverInfo.etag}, " +
        "lastModified=${serverInfo.lastModified}"
    }
    return serverInfo
  }
}
