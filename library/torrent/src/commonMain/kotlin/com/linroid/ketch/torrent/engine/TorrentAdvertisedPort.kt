package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.localTorrentAddress
import com.linroid.ketch.torrent.net.numericAddress
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The port peers are told to reach this engine on. Trackers, DHT and the BEP 10 `p` key read it
 * each time they use it, never once at start, so a later change reaches the next announce.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentAdvertisedPort {
  private val listen = AtomicInt(0)
  /** The external port the router forwards to us; 0 without a mapping. */
  private val mapped = AtomicInt(0)

  /** The bound TCP listener port; 0 before the engine starts. */
  val listenPort: Int get() = listen.load()

  /** The port to announce: the external port a router maps for us, else the listen port. */
  fun current(): Int = mapped.load().takeIf { it != 0 } ?: listen.load()

  /**
   * The port a peer at [peer] reaches us on (BEP 10 `p`): the listen port for one on this
   * machine or a network it is attached to ([localTorrentAddress]), which dials our own address
   * as local discovery announces it, else [current]. Both ends of a duplicate connection compare
   * this with the port they dialed, so they keep the same one.
   */
  fun portFor(peer: PeerEndpoint): Int {
    val local = numericAddress(peer.host)?.let(::localTorrentAddress) == true
    return if (local) listen.load() else current()
  }

  /** The external port of a port mapping, or null once there is none. */
  fun setMapped(port: Int?) {
    require(port == null || port in 1..65535) { "Invalid mapped port" }
    mapped.store(port ?: 0)
  }

  fun setListen(port: Int) {
    require(port in 1..65535) { "Invalid listen port" }
    listen.store(port)
  }
}
