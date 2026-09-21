package com.linroid.ketch.core.engine

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Dispatches HTTP requests round-robin across independently network-bound [engines].
 *
 * Supply one engine per network interface. Binding sockets and DNS to that network is the
 * responsibility of each engine; supplying ordinary engines does not select different networks.
 * Concurrent download segments can use different networks, but each request stays on one engine.
 * HEAD requests also participate in dispatch. Failed requests are not replayed here, since their
 * callbacks may already have written data; Ketch's existing retry/resume logic handles retries.
 * All networks must reach the same resource with the same content and credentials.
 *
 * This engine owns its delegates and closes each distinct instance once. Closing prevents new
 * requests and closes any requests in flight according to the delegate's own close semantics.
 * This affects HTTP downloads only, not FTP or BitTorrent sources.
 *
 * @param engines non-empty list of network-bound engines, copied during construction
 */
@OptIn(ExperimentalAtomicApi::class)
class MultiNetworkHttpEngine(engines: List<HttpEngine>) : HttpEngine {
  private val engines = engines.toList()
  // -1 marks the engine closed; the index otherwise stays bounded by the delegate count.
  private val nextIndex = AtomicInt(0)

  init {
    require(this.engines.isNotEmpty()) { "At least one network engine is required" }
  }

  override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
    nextEngine().head(url, headers)

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    nextEngine().download(url, range, headers, onData)
  }

  private fun nextEngine(): HttpEngine {
    while (true) {
      val index = nextIndex.load()
      check(index >= 0) { "Network engines are closed" }
      val next = if (index == engines.lastIndex) 0 else index + 1
      if (nextIndex.compareAndSet(index, next)) return engines[index]
    }
  }

  override fun close() {
    if (nextIndex.exchange(-1) == -1) return
    val closed = mutableListOf<HttpEngine>()
    var failure: Exception? = null
    for (engine in engines) {
      if (closed.any { it === engine }) continue
      closed.add(engine)
      try {
        engine.close()
      } catch (e: Exception) {
        if (failure == null) failure = e else if (failure !== e) failure.addSuppressed(e)
      }
    }
    failure?.let { throw it }
  }
}
