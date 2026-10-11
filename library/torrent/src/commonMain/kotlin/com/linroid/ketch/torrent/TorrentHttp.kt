package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.engine.KtorHttpEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.IOException
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

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

  /**
   * Streams [range] of [url] to [onData], which must copy what it keeps. The request fails with
   * [HttpIdleException] once the server sends nothing for [idleTimeoutMs]; time spent in [onData],
   * such as waiting on a rate limit, does not count. The engine checks the response is that range.
   */
  @OptIn(ExperimentalAtomicApi::class)
  suspend fun fetchRange(
    url: String,
    range: LongRange,
    idleTimeoutMs: Long,
    onData: suspend (ByteArray) -> Unit,
  ) = coroutineScope {
    require(url.startsWith("https://", true) || url.startsWith("http://", true))
    require(!range.isEmpty() && range.first >= 0 && idleTimeoutMs > 0)
    // Odd while a chunk is being handed over, so a long callback never looks idle.
    val progress = AtomicLong(0)
    val transfer = async {
      engine.download(url, range) { bytes ->
        progress.incrementAndFetch()
        try { onData(bytes) } finally { progress.incrementAndFetch() }
      }
    }
    val watchdog = launch {
      var seen = 0L
      while (true) {
        delay(idleTimeoutMs)
        val current = progress.load()
        if (current == seen && current % 2 == 0L) throw HttpIdleException()
        seen = current
      }
    }
    try { transfer.await() } finally { watchdog.cancel() }
  }

  fun close() {
    if (ownsEngine) engine.close()
  }

  companion object {
    fun default(): TorrentHttp = TorrentHttp(KtorHttpEngine(logRequests = false), ownsEngine = true)
  }
}

/** A server sent nothing for longer than the request allows. */
internal class HttpIdleException : IOException("HTTP response stalled")
