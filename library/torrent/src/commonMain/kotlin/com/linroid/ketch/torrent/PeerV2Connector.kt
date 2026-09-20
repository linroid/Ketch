package com.linroid.ketch.torrent

import kotlinx.coroutines.withTimeout
import okio.ByteString

/** Negotiates an authorized endpoint outside the session actor, admitting availability first. */
internal object PeerV2Connector {
  /** One caller serializes close/attach; successful attach transfers all cleanup to the pool. */
  class Connected internal constructor(
    val route: PeerIdentityHandshake.Result,
    transport: PeerHashTransport,
    blocks: PeerBlockExchange,
    admission: TorrentBufferBudget.Lease,
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
      val peer = pool.attach(owned.transport, owned.blocks, owned.admission)
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
  ): Connected? {
    require(layout.infoHash == document.info.hash) { "Layout belongs to another torrent" }
    require(layout.pieceCount in 0..1_000_000 && layout.pieceLength <= 16 * 1024 * 1024)
    require(peerId.size == 20 && (expectedPeerId == null || expectedPeerId.size == 20))
    require(maxPending in 1..256 && connectTimeoutMs in 1..180_000 && remote.port > 0)
    val admission = state.reserve(((layout.pieceCount + 7) / 8).toInt() + 1024) ?: return null
    var connection: TorrentConnection? = null
    try {
      // Capture inside the timeout: cancellation at its return boundary must not lose a socket.
      withTimeout(connectTimeoutMs) { connection = network.connect(remote) }
      val socket = checkNotNull(connection)
      val route = PeerIdentityHandshake(document.identity).initiate(
        socket, mode, peerId, handshakes,
        allowUpgrade = allowUpgrade, expectedPeerId = expectedPeerId)
      require(route.mode == PeerIdentityHandshake.Mode.V2) { "Peer did not negotiate v2" }
      val hashes = PeerHashExchange(buffers, { root ->
        document.info.files.firstOrNull { it.piecesRoot == root }?.length
      })
      val transport = PeerHashTransport(socket, hashes, buffers,
        pieceCount = layout.pieceCount.toInt())
      val blocks = PeerBlockExchange(layout, transport, buffers, maxPending = maxPending)
      return Connected(route, transport, blocks, admission)
    } catch (error: Throwable) {
      try { connection?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
      admission.close()
      throw error
    }
  }
}
