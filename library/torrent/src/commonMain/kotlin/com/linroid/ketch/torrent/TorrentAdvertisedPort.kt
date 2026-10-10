package com.linroid.ketch.torrent

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The port peers are told to reach this engine on. Trackers, DHT and the BEP 10 `p` key read it
 * each time they use it, never once at start, so a later change reaches the next announce.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentAdvertisedPort {
  private val listen = AtomicInt(0)

  /** The bound TCP listener port; 0 before the engine starts. */
  val listenPort: Int get() = listen.load()

  /** The port to announce. Port mapping will return its external port here when one exists. */
  fun current(): Int = listen.load()

  fun setListen(port: Int) {
    require(port in 1..65535) { "Invalid listen port" }
    listen.store(port)
  }
}
