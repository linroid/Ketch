package com.linroid.ketch.torrent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** First peer-to-storage transfer path. Swarm scheduling composes this protocol in layer 08. */
internal class TorrentPeerDownloader(
  private val store: TorrentPieceStore,
  private val network: TorrentNetwork,
  private val consumePayload: suspend (Int) -> Unit = {},
  private val onProgress: suspend (Long) -> Unit = {},
) {
  suspend fun download(endpoint: PeerEndpoint) {
    store.initialize()
    if (store.completed()) {
      store.finish()
      return
    }
    val metadata = store.metadata
    val verified = store.verifiedPieces()
    val connection = network.connect(endpoint)
    try {
      val wire = PeerWire(connection, metadata, idleTimeoutMs = 30_000)
      val peerId = torrentRandomBytes(20)
      val handshake = wire.handshake(PeerHandshake(metadata.infoHash, peerId, false, false))
      require(!handshake.peerId.contentEquals(peerId)) { "Connected to ourselves" }
      val state = PeerProtocolState(store.pieceCount, maxPending = 1)
      wire.send(PeerMessage.Bitfield(pieceBitfield(verified)))
      wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
      var piece = -1
      var assembled = ByteArray(0)
      var offset = 0
      while (!store.completed()) {
        currentCoroutineContext().ensureActive()
        val message = wire.read()
        if (message is PeerMessage.Piece) consumePayload(message.bytes.size)
        val accepted = state.received(message)
        if (message is PeerMessage.Piece && accepted) {
          check(message.index == piece && message.begin == offset)
          message.bytes.copyInto(assembled, offset)
          offset += message.bytes.size
          if (offset == assembled.size) {
            require(store.commit(piece, assembled)) { "Peer sent a corrupt piece" }
            verified[piece] = true
            if (!store.completed()) wire.send(PeerMessage.Have(piece))
            onProgress(store.progress().sum())
            piece = -1
            assembled = ByteArray(0)
            offset = 0
          }
        }
        if (!state.choking && state.requests.isEmpty()) {
          if (piece == -1) {
            piece = verified.indices.firstOrNull {
              store.needed(it) && !verified[it] && state.available[it]
            } ?: -1
            if (piece != -1) assembled = ByteArray(store.pieceSize(piece))
          }
          if (piece != -1) {
            val request = PeerMessage.Request(
              index = piece,
              begin = offset,
              length = minOf(PeerWire.BLOCK_SIZE, assembled.size - offset),
            )
            state.requested(request)
            wire.send(request)
          }
        }
      }
      store.finish()
    } finally {
      connection.close()
    }
  }
}

internal fun pieceBitfield(pieces: BooleanArray): ByteArray {
  val bits = ByteArray((pieces.size + 7) / 8)
  for (index in pieces.indices) {
    if (pieces[index]) bits[index / 8] =
      (bits[index / 8].toInt() or (128 ushr (index % 8))).toByte()
  }
  return bits
}
