package com.linroid.ketch.core

import com.linroid.ketch.api.config.DispatcherConfig
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatchers used by the Ketch download engine.
 *
 * Provides dedicated dispatchers for different workloads:
 * - [main]: Single-threaded dispatcher for task management (scheduling,
 *   queue operations, state transitions). Serialized execution eliminates
 *   Mutex contention for coordination logic.
 * - [network]: Thread pool for network operations (HTTP requests, segment
 *   downloads). Sized to match expected download parallelism.
 * - [io]: Thread pool for blocking file I/O (writes, reads, preallocate).
 *
 * Pass your own [CoroutineDispatcher] instances for full control, or use
 * [Default] for platform-appropriate defaults with configurable pool sizes.
 */
class KetchDispatchers(
  /** Single-threaded dispatcher for task coordination. */
  val main: CoroutineDispatcher,
  /** Thread pool for network operations. */
  val network: CoroutineDispatcher,
  /** Thread pool for blocking file I/O. */
  val io: CoroutineDispatcher,
) {
  /**
   * Releases dispatcher resources (thread pools).
   *
   * Only closes dispatchers that implement [CloseableCoroutineDispatcher]
   * (e.g., those created by [Default]). User-provided dispatchers like
   * `Dispatchers.IO` are left untouched.
   */
  fun close() {
    (main as? CloseableCoroutineDispatcher)?.close()
    (network as? CloseableCoroutineDispatcher)?.close()
    (io as? CloseableCoroutineDispatcher)?.close()
  }

  companion object {
    private const val DEFAULT_IO_POOL_SIZE = 2

    /**
     * Creates platform-default dispatchers with dedicated thread pools.
     *
     * @param networkPoolSize number of threads in the network pool;
     *   must be positive
     * @param ioPoolSize number of threads in the I/O pool; must be positive
     * @throws IllegalArgumentException if any pool size is not positive
     */
    fun Default(
      networkPoolSize: Int = 4,
      ioPoolSize: Int = DEFAULT_IO_POOL_SIZE,
    ): KetchDispatchers {
      require(networkPoolSize > 0) {
        "networkPoolSize must be positive, was $networkPoolSize"
      }
      require(ioPoolSize > 0) {
        "ioPoolSize must be positive, was $ioPoolSize"
      }
      return createDefaultDispatchers(networkPoolSize, ioPoolSize)
    }

    /**
     * Creates dispatchers from a [DispatcherConfig].
     *
     * @param config dispatcher sizing configuration; `0` values are
     *   resolved to defaults ([maxConnections] for network,
     *   [DEFAULT_IO_POOL_SIZE] for I/O)
     * @param maxConnections fallback network pool size when
     *   [DispatcherConfig.networkPoolSize] is `0`
     */
    fun fromConfig(
      config: DispatcherConfig,
      maxConnections: Int = 4,
    ): KetchDispatchers {
      val networkSize = if (config.networkPoolSize > 0) {
        config.networkPoolSize
      } else {
        maxConnections
      }
      val ioSize = if (config.ioPoolSize > 0) {
        config.ioPoolSize
      } else {
        DEFAULT_IO_POOL_SIZE
      }
      return Default(networkSize, ioSize)
    }
  }
}
