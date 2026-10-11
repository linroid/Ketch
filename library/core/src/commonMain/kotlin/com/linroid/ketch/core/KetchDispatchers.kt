package com.linroid.ketch.core

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatchers used by the Ketch download engine:
 * - [main]: task coordination (scheduling, queue operations, state transitions). Each [Ketch]
 *   runs its coordination on a serial view of it, so it needs no thread of its own.
 * - [network]: network operations (HTTP requests, segment downloads).
 * - [io]: blocking file I/O (writes, reads, preallocation).
 *
 * Every [Ketch] uses [Default] unless given others. The caller owns the dispatchers it passes:
 * [Ketch.close] never closes them.
 */
class KetchDispatchers(
  /** Dispatcher for task coordination; each [Ketch] runs one coroutine on it at a time. */
  val main: CoroutineDispatcher,
  /** Dispatcher for network operations. */
  val network: CoroutineDispatcher,
  /** Dispatcher for blocking file I/O. */
  val io: CoroutineDispatcher,
) {
  /**
   * Closes the dispatchers that implement [CloseableCoroutineDispatcher], such as thread pools
   * the caller created; others, like `Dispatchers.IO`, are left untouched. Does nothing for
   * [Default], which every [Ketch] in the process shares.
   */
  fun close() {
    if (isDefault(this)) return
    (main as? CloseableCoroutineDispatcher)?.close()
    (network as? CloseableCoroutineDispatcher)?.close()
    (io as? CloseableCoroutineDispatcher)?.close()
  }

  companion object {
    private val default = lazy { createDefaultDispatchers() }

    /**
     * The dispatchers every [Ketch] in the process shares unless given others, created on first
     * use. On the JVM and Android they run on Ketch's own daemon threads, apart from the
     * application's `Dispatchers.IO` and `Dispatchers.Default`: at most 8 for [network]
     * (`ketch-network-N`) and 8 for [io] (`ketch-io-N`), started as work arrives and ended once
     * idle for 30 seconds. iOS keeps two pools of 8 threads; JS and WasmWasi use
     * `Dispatchers.Default`. [main] is a serial view of [network].
     */
    val Default: KetchDispatchers
      get() = default.value

    private fun isDefault(dispatchers: KetchDispatchers): Boolean =
      default.isInitialized() && default.value === dispatchers
  }
}
