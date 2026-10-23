package com.linroid.ketch.core.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl

/**
 * @param watchdog what `HEAD` and the probe each run under, as transfers do: engines wait for an
 *   answer as long as it takes, so a server that takes the connection and never answers would
 *   otherwise hold the download forever
 */
internal class RangeSupportDetector(
  private val httpEngine: HttpEngine,
  private val watchdog: TransferWatchdog = TransferWatchdog.Off,
) {
  private val log = KetchLogger("RangeDetector")

  /**
   * Reads the server's metadata with `HEAD`, or with [HttpEngine.probe] when the server refuses
   * `HEAD` with a status a `GET` may still pass, as URLs presigned for `GET` only do, or the
   * `HEAD` fails on the network, as servers and middleboxes that drop it without a reply make it.
   */
  suspend fun detect(url: String, headers: Map<String, String> = emptyMap()): ServerInfo {
    log.d { "Sending HEAD request to ${redactUrl(url)}" }
    val serverInfo = try {
      watchdog.watch { httpEngine.head(url, headers) }
    } catch (e: KetchError.Http) {
      if (e.code !in HEAD_REFUSED) throw e
      log.d { "HEAD refused with ${e.code}, probing ${redactUrl(url)} with a ranged GET" }
      probe(url, headers, e)
    } catch (e: KetchError.Network) {
      // As Hetzner's servers answer it, with an empty reply, while a GET gets the file.
      log.d { "HEAD failed (${e.describeCauses()}), probing ${redactUrl(url)} with a ranged GET" }
      probe(url, headers, e)
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

  /**
   * [HttpEngine.probe] in place of a `HEAD` that failed with [headError], which an engine that
   * cannot probe throws again.
   */
  private suspend fun probe(
    url: String,
    headers: Map<String, String>,
    headError: KetchError,
  ): ServerInfo = try {
    watchdog.watch { httpEngine.probe(url, headers) }
  } catch (_: UnsupportedOperationException) {
    throw headError
  }

  private companion object {
    /**
     * `HEAD` statuses after which a `GET` may still succeed: signatures valid for `GET` only
     * (403), servers or routes without `HEAD` (404, 405, 501), servers that reject it (400) and
     * handlers that fail on it (500).
     */
    val HEAD_REFUSED = setOf(400, 403, 404, 405, 500, 501)
  }
}
