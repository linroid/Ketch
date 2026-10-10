package com.linroid.ketch.torrent

import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.api.ConnectionRoute
import com.linroid.ketch.api.PeerDetails
import com.linroid.ketch.core.engine.ConnectionHandle
import com.linroid.ketch.core.engine.ConnectionReporter
import com.linroid.ketch.core.engine.ConnectionSpec

/**
 * One connected peer among the task's live connections: its payload in both directions and
 * its choke and interest state. Only the peer's own worker (v1) or the session loop (v2) calls
 * it, so it needs no locking. Counting is one atomic add and allocates nothing; [state] and
 * [listenPort] allocate only when something changed. The peer's address goes to the reporter
 * only, never to the logs.
 */
internal class PeerTraffic private constructor(
  private val handle: ConnectionHandle,
  private var spec: ConnectionSpec,
) {
  /** Counts payload [bytes] the peer sent us. */
  fun received(bytes: Int) = handle.received(bytes)

  /** Counts payload [bytes] we sent the peer. */
  fun sent(bytes: Int) = handle.sent(bytes)

  /** Reports whether the peer chokes us, whether we unchoked it and whether it is interested. */
  fun state(peerChoking: Boolean, uploadSlot: Boolean, peerInterested: Boolean) {
    val peer = spec.peer ?: return
    if (peer.peerChoking == peerChoking && peer.uploadSlot == uploadSlot &&
      peer.peerInterested == peerInterested) return
    describe(spec.copy(peer = peer.copy(peerChoking = peerChoking, uploadSlot = uploadSlot,
      peerInterested = peerInterested)))
  }

  /** An incoming peer told us where it listens (BEP 10 `p`); shown instead of its source port. */
  fun listenPort(port: Int) {
    if (port == spec.port || port !in 1..65535) return
    describe(spec.copy(port = port))
  }

  /** The connection ended; later calls do nothing. */
  fun close() = handle.close()

  private fun describe(next: ConnectionSpec) {
    spec = next
    handle.describe(next)
  }

  companion object {
    /** The protocol label of every peer connection; [PeerDetails.wire] tells v1 from v2. */
    const val PROTOCOL = "BitTorrent"

    /** The source type the connections are reported under. */
    private const val SOURCE = "torrent"

    /**
     * Opens the connection of a peer at [remote] on [reporter], once its handshake succeeded;
     * null when [reporter] reports nothing.
     *
     * @param incoming the peer dialed us, so [remote] is its source port
     * @param v1 the peer speaks the v1 wire protocol, as every peer of a v1 torrent and a
     *   hybrid's peers from its v1 swarm do
     */
    fun open(
      reporter: ConnectionReporter,
      remote: PeerEndpoint,
      incoming: Boolean,
      v1: Boolean,
    ): PeerTraffic? {
      if (reporter === ConnectionReporter.None) return null
      val spec = ConnectionSpec(
        source = SOURCE,
        host = remote.host.removePrefix("[").removeSuffix("]"),
        port = remote.port.takeIf { it in 1..65535 },
        protocol = PROTOCOL,
        secure = false,
        direction = if (incoming) ConnectionDirection.INCOMING else ConnectionDirection.OUTGOING,
        // Peers are always dialed directly: proxies only carry HTTP(S), HLS and DASH.
        route = ConnectionRoute.DIRECT,
        peer = PeerDetails(wire = if (v1) "v1" else "v2", peerChoking = true,
          uploadSlot = false, peerInterested = false),
      )
      return PeerTraffic(reporter.open(spec), spec)
    }
  }
}
