package com.linroid.ketch.torrent

import kotlinx.coroutines.withTimeout
import okio.ByteString

/** Negotiates an authorized endpoint outside the session actor, admitting availability first. */
internal object PeerV2Connector {
  /** One caller serializes close/attach; successful attach transfers all cleanup to the pool. */
  class Connected internal constructor(
    val route: PeerIdentityHandshake.Result,
    /** Who the peer is and how we met it; travels with the peer into the pool. */
    val info: PeerInfo,
    transport: PeerHashTransport,
    blocks: PeerBlockExchange,
    admission: TorrentBufferBudget.Lease,
    /** The session generation this connection was dialed or accepted in. */
    val generation: Long = 0,
  ) {
    private class Ownership(
      val transport: PeerHashTransport,
      val blocks: PeerBlockExchange,
      val admission: TorrentBufferBudget.Lease,
    )
    private var ownership: Ownership? = Ownership(transport, blocks, admission)

    /** Null leaves this handle owned and retryable when pool capacity returns. */
    fun attach(pool: PeerV2Pool): PeerV2Pool.Peer? {
      val owned = checkNotNull(ownership) { "Connection ownership already released" }
      val peer = pool.attach(owned.transport, owned.blocks, owned.admission, info)
      if (peer != null) ownership = null
      return peer
    }

    /** After successful attach this does nothing; the pool must first join its actor and reader. */
    fun close() {
      val owned = ownership ?: return
      ownership = null
      try { owned.blocks.close() } finally { owned.admission.close() }
    }
  }

  /**
   * Metadata/layout and global socket policy/admission belong to the caller. This method admits
   * per-peer packed availability before opening a socket, validates the layout's full v2 hash,
   * and closes every unsuccessful connection. The caller closes or attaches the returned handle.
   * A hybrid may dial in v1 [mode], offering the upgrade; a peer that keeps v1 is a v1 peer.
   */
  suspend fun connect(
    network: TorrentNetwork,
    remote: PeerEndpoint,
    document: TorrentV2Document,
    layout: TorrentContentLayout,
    peerId: ByteString,
    buffers: TorrentBufferBudget,
    state: TorrentBufferBudget,
    handshakes: TorrentBufferBudget = buffers,
    mode: PeerIdentityHandshake.Mode = PeerIdentityHandshake.Mode.V2,
    allowUpgrade: Boolean = false,
    expectedPeerId: ByteString? = null,
    maxPending: Int = 32,
    connectTimeoutMs: Long = 10_000,
    extensions: Boolean = false,
    generation: Long = 0,
  ): Connected? {
    require(layout.infoHash == document.info.hash) { "Layout belongs to another torrent" }
    require(layout.pieceCount in 0..1_000_000 && layout.pieceLength <= 16 * 1024 * 1024)
    require(peerId.size == 20 && (expectedPeerId == null || expectedPeerId.size == 20))
    require(maxPending in 1..256 && connectTimeoutMs in 1..180_000 && remote.port > 0)
    val admission = state.reserve(availabilityBytes(layout)) ?: return null
    var connection: TorrentConnection? = null
    try {
      // Capture inside the timeout: cancellation at its return boundary must not lose a socket.
      withTimeout(connectTimeoutMs) { connection = network.connect(remote) }
      val socket = checkNotNull(connection)
      val route = PeerIdentityHandshake(document.identity, extensions).initiate(
        socket, mode, peerId, handshakes,
        allowUpgrade = allowUpgrade, expectedPeerId = expectedPeerId)
      return negotiated(socket, route, PeerV2Origin.Outgoing(remote), document, layout, buffers,
        admission, extensions, maxPending, generation)
    } catch (error: Throwable) {
      try { connection?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
      admission.close()
      throw error
    }
  }

  /**
   * Answers a peer that dialed us on [connection], whose handshake is still unread. Admits
   * per-peer availability first; without it the connection is closed and null returned. A
   * hybrid answers a v1 tag that offers the upgrade bit in v2 mode, and any other v1 tag as a
   * v1 peer. Every failure closes the connection; the caller closes or attaches the handle.
   */
  suspend fun respond(
    connection: TorrentConnection,
    document: TorrentV2Document,
    layout: TorrentContentLayout,
    peerId: ByteString,
    buffers: TorrentBufferBudget,
    state: TorrentBufferBudget,
    handshakes: TorrentBufferBudget = buffers,
    extensions: Boolean = false,
    maxPending: Int = 32,
    generation: Long = 0,
  ): Connected? {
    var admission: TorrentBufferBudget.Lease? = null
    try {
      require(layout.infoHash == document.info.hash) { "Layout belongs to another torrent" }
      require(layout.pieceCount in 0..1_000_000 && layout.pieceLength <= 16 * 1024 * 1024)
      require(peerId.size == 20 && maxPending in 1..256)
      admission = state.reserve(availabilityBytes(layout))
      if (admission == null) {
        connection.close()
        return null
      }
      val route = PeerIdentityHandshake(document.identity, extensions).respond(connection, peerId,
        handshakes, allowUpgrade = document.hybrid != null)
      return negotiated(connection, route, PeerV2Origin.Incoming(connection.remote), document,
        layout, buffers, admission, extensions, maxPending, generation)
    } catch (error: Throwable) {
      try { connection.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
      admission?.close()
      throw error
    }
  }

  private fun availabilityBytes(layout: TorrentContentLayout): Int =
    ((layout.pieceCount + 7) / 8).toInt() + 1024

  private fun negotiated(
    socket: TorrentConnection,
    route: PeerIdentityHandshake.Result,
    origin: PeerV2Origin,
    document: TorrentV2Document,
    layout: TorrentContentLayout,
    buffers: TorrentBufferBudget,
    admission: TorrentBufferBudget.Lease,
    extensions: Boolean,
    maxPending: Int,
    generation: Long,
  ): Connected {
    // A hybrid also joins the v1 swarm: those peers exchange canonical v1 blocks and no hashes.
    val v2 = route.mode == PeerIdentityHandshake.Mode.V2
    require(v2 || document.hybrid != null) { "Peer did not negotiate v2" }
    val hashes = PeerHashExchange(buffers, { root ->
      if (!v2) null else document.info.files.firstOrNull { it.piecesRoot == root }?.length
    })
    val transport = PeerHashTransport(socket, hashes, buffers,
      pieceCount = layout.pieceCount.toInt(), hashMessages = v2)
    val blocks = PeerBlockExchange(layout, transport, buffers, maxPending = maxPending,
      mode = route.mode)
    val info = PeerInfo(route.peerId, route.mode, origin, extensions && route.extensions, socket)
    return Connected(route, info, transport, blocks, admission, generation)
  }
}
