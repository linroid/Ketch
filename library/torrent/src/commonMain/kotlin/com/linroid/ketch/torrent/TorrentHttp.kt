package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.engine.KtorHttpEngine
import kotlinx.coroutines.withTimeout
import okio.Buffer

/** Size- and time-bounded HTTP adapter; injected engines remain owned by their caller. */
internal class TorrentHttp(
  private val engine: HttpEngine,
  private val ownsEngine: Boolean = false,
) {
  suspend fun fetch(
    url: String,
    maxBytes: Int,
    timeoutMs: Long = 30_000,
    headers: Map<String, String> = emptyMap(),
  ): ByteArray = withTimeout(timeoutMs) {
    require(maxBytes > 0)
    require(url.startsWith("https://", true) || url.startsWith("http://", true))
    val output = Buffer()
    engine.download(url, null, headers) { bytes ->
      require(bytes.size.toLong() <= maxBytes - output.size) {
        "HTTP response exceeds torrent limit"
      }
      output.write(bytes)
    }
    output.readByteArray()
  }

  fun close() {
    if (ownsEngine) engine.close()
  }

  companion object {
    fun default(): TorrentHttp = TorrentHttp(KtorHttpEngine(logRequests = false), ownsEngine = true)
  }
}
