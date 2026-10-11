package com.linroid.ketch.torrent

import com.linroid.ketch.core.KetchDispatchers
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where the torrent engine runs: on [KetchDispatchers.Default], Ketch's own bounded threads,
 * which every engine and Ketch instance in the process shares, never on the application's
 * `Dispatchers.Default` or `Dispatchers.IO`. Blocking work is capped per kind, so a slow
 * resolver or the discovery socket never takes every file I/O thread.
 */
internal object TorrentDispatchers {
  /** The engine, its peers and hashing: work that computes or suspends, never blocks. */
  val engine: CoroutineDispatcher get() = KetchDispatchers.Default.network

  /** Blocking file I/O. */
  val disk: CoroutineDispatcher get() = KetchDispatchers.Default.disk

  /** Blocking lookups (DNS, network interfaces), at most [LOOKUPS] at once. */
  val lookup: CoroutineDispatcher by lazy { disk.limitedParallelism(LOOKUPS) }

  /**
   * Local service discovery's blocking receive, which keeps its thread while the LAN is quiet,
   * waking every half second: one thread at most. Its sends, which never wait, run on [disk], so
   * they never queue behind it.
   */
  val multicast: CoroutineDispatcher by lazy { disk.limitedParallelism(1) }

  private const val LOOKUPS = 2
}
