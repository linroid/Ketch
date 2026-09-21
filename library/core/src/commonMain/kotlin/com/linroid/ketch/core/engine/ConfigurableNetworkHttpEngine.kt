package com.linroid.ketch.core.engine

import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * HTTP engine with discoverable, runtime-selectable network interfaces.
 *
 * Pass this engine to Ketch to expose interface controls through KetchApi. Updates validate and
 * construct the entire replacement before switching. Old transports are closed only after their
 * in-flight requests finish. Closing the engine prevents new requests and likewise drains existing
 * requests; Ketch cancels its downloads before closing the engine.
 *
 * @param provider platform discovery and factories; every factory call must return a new engine
 */
@OptIn(ExperimentalAtomicApi::class)
class ConfigurableNetworkHttpEngine(private val provider: NetworkInterfaceProvider) : HttpEngine {
  private val updates = Mutex()
  private val current = AtomicReference<Generation?>(
    Generation(provider.createDefaultEngine(), NetworkInterfaceConfig())
  )

  /** Discovers interfaces without changing the selection, including unavailable selected IDs. */
  suspend fun networkInterfaces(): NetworkInterfaces = updates.withLock {
    val generation = checkNotNull(current.load()) { "Network engine is closed" }
    NetworkInterfaces(
      supported = true,
      available = provider.availableInterfaces(),
      config = generation.config.copy(interfaceIds = generation.config.interfaceIds.toList()),
    )
  }

  /** Replaces the selection for new requests atomically; failure preserves the old selection. */
  suspend fun updateNetworkInterfaces(config: NetworkInterfaceConfig): NetworkInterfaces =
    updates.withLock {
      val previous = checkNotNull(current.load()) { "Network engine is closed" }
      // Copy and validate again so the caller cannot later mutate the selected IDs.
      val selected = NetworkInterfaceConfig(config.interfaceIds.toList())
      val available = provider.availableInterfaces()
      val interfaces = selected.interfaceIds.map { id ->
        requireNotNull(available.find { it.id == id }) { "Unknown or unavailable interface: $id" }
      }
      val created = mutableListOf<HttpEngine>()
      val replacement = try {
        if (interfaces.isEmpty()) {
          created.add(provider.createDefaultEngine())
        } else {
          interfaces.forEach { created.add(provider.createEngine(it)) }
        }
        Generation(MultiNetworkHttpEngine(created), selected)
      } catch (e: Throwable) {
        created.forEach { closeEngine(it) }
        throw e
      }
      if (!current.compareAndSet(previous, replacement)) {
        replacement.release()
        error("Network engine is closed")
      }
      previous.release()
      NetworkInterfaces(
        supported = true,
        available = available,
        config = selected.copy(interfaceIds = selected.interfaceIds.toList()),
      )
    }

  override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
    withEngine { it.head(url, headers) }

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    withEngine { it.download(url, range, headers, onData) }
  }

  private suspend fun <T> withEngine(block: suspend (HttpEngine) -> T): T {
    while (true) {
      val generation = checkNotNull(current.load()) { "Network engine is closed" }
      if (!generation.acquire()) continue
      try {
        if (current.load() !== generation) continue
        return block(generation.engine)
      } finally {
        generation.release()
      }
    }
  }

  override fun close() {
    current.exchange(null)?.release()
  }

  private class Generation(val engine: HttpEngine, val config: NetworkInterfaceConfig) {
    // One owner reference plus one per active request. Zero is terminal.
    private val references = AtomicInt(1)

    fun acquire(): Boolean {
      while (true) {
        val count = references.load()
        if (count == 0) return false
        if (references.compareAndSet(count, count + 1)) return true
      }
    }

    fun release() {
      if (references.fetchAndAdd(-1) == 1) closeEngine(engine)
    }
  }

  private companion object {
    val log = KetchLogger("NetworkHttpEngine")

    fun closeEngine(engine: HttpEngine) {
      try {
        engine.close()
      } catch (e: Exception) {
        log.w(e) { "Failed to close HTTP network engine" }
      }
    }
  }
}
