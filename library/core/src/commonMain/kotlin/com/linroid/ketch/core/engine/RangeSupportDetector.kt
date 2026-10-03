package com.linroid.ketch.core.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl

internal class RangeSupportDetector(
  private val httpEngine: HttpEngine,
) {
  private val log = KetchLogger("RangeDetector")

  /**
   * Reads the server's metadata with `HEAD`, or with [HttpEngine.probe] when the server refuses
   * `HEAD` with a status a `GET` may still pass, as URLs presigned for `GET` only do.
   */
  suspend fun detect(url: String, headers: Map<String, String> = emptyMap()): ServerInfo {
    log.d { "Sending HEAD request to ${redactUrl(url)}" }
    val serverInfo = try {
      httpEngine.head(url, headers)
    } catch (e: KetchError.Http) {
      if (e.code !in HEAD_REFUSED) throw e
      log.d { "HEAD refused with ${e.code}, probing ${redactUrl(url)} with a ranged GET" }
      try {
        httpEngine.probe(url, headers)
      } catch (_: UnsupportedOperationException) {
        throw e
      }
    }
    log.i {
      "Server info: contentLength=${serverInfo.contentLength}, " +
        "acceptRanges=${serverInfo.acceptRanges}, " +
        "supportsResume=${serverInfo.supportsResume}, " +
        "etag=${serverInfo.etag}, " +
        "lastModified=${serverInfo.lastModified}"
    }
    return serverInfo
  }

  private companion object {
    /**
     * `HEAD` statuses after which a `GET` may still succeed: signatures valid for `GET` only
     * (403), servers or routes without `HEAD` (404, 405, 501) and servers that reject it (400).
     */
    val HEAD_REFUSED = setOf(400, 403, 404, 405, 501)
  }
}
