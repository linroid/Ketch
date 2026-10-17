package com.linroid.ketch.torrent.net

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.torrent.monotonicClock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.IOException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Size- and time-bounded HTTP adapter; injected engines remain owned by their caller. */
internal class TorrentHttp(
  private val engine: HttpEngine,
  private val ownsEngine: Boolean = false,
  /** The clock [fetchRange]'s idle timeout follows; tests pass virtual time. */
  private val nowMs: () -> Long = monotonicClock(),
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
   * [HttpIdleException] once the server sends nothing for [idleTimeoutMs], checked every tenth of
   * it (10 ms to 1 s); time spent in [onData], such as waiting on a rate limit, does not count.
   * The engine checks the response is that range.
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
    // When the server last sent anything: the request, or the last chunk as it arrived and again
    // once handed over. While a chunk is being handed over it is not idle, however long that takes.
    val idleSince = AtomicLong(nowMs())
    val handing = AtomicBoolean(false)
    val transfer = async {
      engine.download(url, range) { bytes ->
        idleSince.store(nowMs())
        handing.store(true)
        try {
          onData(bytes)
        } finally {
          idleSince.store(nowMs())
          handing.store(false)
        }
      }
    }
    val tick = (idleTimeoutMs / 10).coerceIn(10, 1_000)
    val watchdog = launch {
      while (true) {
        delay(tick)
        if (!handing.load() && nowMs() - idleSince.load() >= idleTimeoutMs) {
          throw HttpIdleException()
        }
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
