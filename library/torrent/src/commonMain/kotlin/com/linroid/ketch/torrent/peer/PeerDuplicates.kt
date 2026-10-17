package com.linroid.ketch.torrent.peer

import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.localTorrentAddress
import com.linroid.ketch.torrent.net.numericAddress
import okio.ByteString

/**
 * One peer as far as duplicate connections go: the ID it claims, at the [host] it came from or
 * was dialed at. A peer ID is only a claim, so another public host claiming it is another peer
 * and never displaces it. Hosts on this machine or a network it is attached to share one null
 * [host]: one client there is often reached at several of its addresses, such as loopback from a
 * magnet's `x.pe` and its LAN address from local service discovery.
 */
internal data class PeerIdentity(val host: String?, val peerId: ByteString) {
  companion object {
    fun of(host: String, peerId: ByteString): PeerIdentity {
      val local = numericAddress(host)?.let(::localTorrentAddress) == true
      return PeerIdentity(if (local) null else host, peerId)
    }
  }
}

/**
 * Of two connections to one peer, exactly one of which we dialed ([outgoing]), whether we keep
 * that one; the peer, deciding the same way, keeps the same socket. libtorrent resolves such
 * duplicates by listen port: the side whose listen port is lower keeps the connection it dialed,
 * so we compare the port we advertise ([ourListenPort], 0 when unknown) with the one we dialed.
 * With the ports equal or ours unknown, the connection the higher peer ID dialed stays, as
 * libtorrent decides duplicates it does not match by address.
 */
internal fun keepsOutgoing(
  ourListenPort: Int,
  outgoing: PeerEndpoint,
  ourPeerId: ByteString,
  remotePeerId: ByteString,
): Boolean {
  if (ourListenPort != 0 && ourListenPort != outgoing.port) return ourListenPort < outgoing.port
  return higherPeerId(ourPeerId, remotePeerId)
}

/** True when [local] sorts after [remote] as unsigned bytes. */
private fun higherPeerId(local: ByteString, remote: ByteString): Boolean {
  for (index in 0 until minOf(local.size, remote.size)) {
    val left = local[index].toInt() and 0xff
    val right = remote[index].toInt() and 0xff
    if (left != right) return left > right
  }
  return local.size > remote.size
}
