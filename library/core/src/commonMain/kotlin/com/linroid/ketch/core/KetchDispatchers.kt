package com.linroid.ketch.core

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
 * `KetchDispatchers(networkThreads, ioThreads)` for platform-appropriate
 * defaults with configurable pool sizes.
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
   * Creates platform-default dispatchers with dedicated thread pools.
   *
   * @param networkThreads number of threads in the network pool;
   *   must be positive
   * @param ioThreads number of threads in the I/O pool; must be positive
   */
  constructor(
    networkThreads: Int = 8,
    ioThreads: Int = 4,
  ) : this(
    main = createMainDispatcher(),
    network = createNetworkDispatcher(networkThreads),
    io = createIoDispatcher(ioThreads),
  )

  /**
   * Releases dispatcher resources (thread pools).
   *
   * Only closes dispatchers that implement [CloseableCoroutineDispatcher]
   * (e.g., those created via pool-size constructor). User-provided
   * dispatchers like `Dispatchers.IO` are left untouched.
   */
  fun close() {
    (main as? CloseableCoroutineDispatcher)?.close()
    (network as? CloseableCoroutineDispatcher)?.close()
    (io as? CloseableCoroutineDispatcher)?.close()
  }
}
