package com.linroid.kdown.internal

import com.linroid.kdown.HttpEngine
import com.linroid.kdown.KDownLogger
import com.linroid.kdown.model.ServerInfo

internal class RangeSupportDetector(
  private val httpEngine: HttpEngine
) {
  suspend fun detect(url: String, headers: Map<String, String> = emptyMap()): ServerInfo {
    KDownLogger.d("RangeDetector") { "Sending HEAD request to $url" }
    val serverInfo = httpEngine.head(url, headers)
    KDownLogger.i("RangeDetector") {
      "Server info: contentLength=${serverInfo.contentLength}, " +
        "acceptRanges=${serverInfo.acceptRanges}, " +
        "supportsResume=${serverInfo.supportsResume}, " +
        "etag=${serverInfo.etag}, " +
        "lastModified=${serverInfo.lastModified}"
    }
    return serverInfo
  }
}
