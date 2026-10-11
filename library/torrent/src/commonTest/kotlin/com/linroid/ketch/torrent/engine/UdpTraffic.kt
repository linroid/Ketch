package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentDatagramSocket
import com.linroid.ketch.torrent.utp.UtpPacket
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Counts in [counter] the datagrams this socket sends that are not uTP: DHT messages and UDP
 * tracker requests, the traffic private and tracker-only torrents must never cause. uTP shares
 * the port and may run for any torrent.
 */
@OptIn(ExperimentalAtomicApi::class)
internal fun TorrentDatagramSocket.countingPublicDiscovery(
  counter: AtomicInt,
): TorrentDatagramSocket {
  val socket = this
  return object : TorrentDatagramSocket by socket {
    override suspend fun send(remote: PeerEndpoint, bytes: ByteArray) {
      if (!UtpPacket.looksLikeUtp(bytes)) counter.fetchAndAdd(1)
      socket.send(remote, bytes)
    }
  }
}
