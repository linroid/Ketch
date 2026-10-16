package com.linroid.ketch.torrent.magnet

import com.linroid.ketch.torrent.net.PeerEndpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The peers that sent some of a torrent's metadata when a lookup found it, by the swarm's wire
 * tag, so the download that follows dials them first rather than waiting for trackers and the
 * DHT to name them again, which in a small swarm can take minutes. Each is taken once. At most
 * [MAX_TORRENTS] torrents of [MAX_PEERS] peers each are kept, the oldest forgotten first.
 */
internal class MetadataLookupPeers {
  private val mutex = Mutex()
  private val peers = LinkedHashMap<String, List<PeerEndpoint>>()

  /** Keeps [found] for the torrent with wire tag [tag], replacing what an earlier lookup kept. */
  suspend fun keep(tag: String, found: Collection<PeerEndpoint>) {
    if (found.isEmpty()) return
    mutex.withLock {
      peers.remove(tag)
      peers[tag] = found.take(MAX_PEERS)
      while (peers.size > MAX_TORRENTS) peers.remove(peers.keys.first())
    }
  }

  /** The peers kept for the torrent with wire tag [tag], which are forgotten as they are taken. */
  suspend fun take(tag: String): List<PeerEndpoint> = mutex.withLock { peers.remove(tag) }.orEmpty()

  companion object {
    const val MAX_TORRENTS = 32
    const val MAX_PEERS = 64
  }
}

/** The peers of one lookup that sent some of the metadata, recorded from any thread. */
@OptIn(ExperimentalAtomicApi::class)
internal class AnsweringPeers {
  private val peers = AtomicReference(emptySet<PeerEndpoint>())

  val all: Set<PeerEndpoint> get() = peers.load()

  fun add(peer: PeerEndpoint) {
    while (true) {
      val current = peers.load()
      if (peer in current || current.size >= MetadataLookupPeers.MAX_PEERS) return
      if (peers.compareAndSet(current, current + peer)) return
    }
  }
}
