package com.linroid.ketch.torrent

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import okio.Buffer

/** BEP 9 bounded metadata exchange, with four outstanding blocks and full hash verification. */
internal class TorrentMetadataExchange(
  private val network: TorrentNetwork,
  private val maxBytes: Int = 4 * 1024 * 1024,
  private val timeoutMs: Long = 30_000,
  private val budget: TorrentBufferBudget = TorrentBufferBudget(32 * 1024 * 1024),
) {
  init {
    require(maxBytes in 1..4 * 1024 * 1024 && timeoutMs > 0)
    require(budget.capacity >= maxBytes * 4 + 256 * 1024)
  }

  suspend fun fetch(
    hash: InfoHash,
    endpoint: PeerEndpoint,
    trackerTiers: List<List<String>> = emptyList(),
  ): TorrentMetadata = withTimeout(timeoutMs) {
    var lease: TorrentBufferBudget.Lease? = null
    while (lease == null) {
      lease = budget.reserve(maxBytes * 4 + 256 * 1024)
      if (lease == null) delay(10)
    }
    var connection: TorrentConnection? = null
    try {
      connection = network.connect(endpoint)
      val wire = PeerWire(connection)
      val peerId = torrentRandomBytes(20)
      val handshake = wire.handshake(PeerHandshake(hash, peerId, true, false))
      require(handshake.extensions && !handshake.peerId.contentEquals(peerId)) {
        "Peer does not support metadata exchange"
      }
      wire.send(PeerExtensions.handshake())
      val extensions = PeerExtensions()
      var data: ByteArray? = null
      var received = BooleanArray(0)
      val pending = mutableSetOf<Int>()
      var next = 0
      var receivedCount = 0
      while (true) {
        val message = wire.read()
        require(message !is PeerMessage.Piece) { "Unsolicited data during metadata exchange" }
        if (message !is PeerMessage.Extended) continue
        if (message.id == 0) {
          extensions.receive(message.payload, maxBytes)
          val size = extensions.metadataSize
          if (data == null && size != null && extensions.id("ut_metadata") != 0) {
            data = ByteArray(size)
            received = BooleanArray((size + BLOCK_SIZE - 1) / BLOCK_SIZE)
          }
        } else if (message.id == PeerExtensions.METADATA) {
          val header = Bencode.parsePrefix(message.payload, PeerWire.MAX_FRAME_SIZE)
          val type = requireNotNull(header["msg_type"]?.integer)
          if (type !in 0..2) continue
          val piece = requireNotNull(header["piece"]?.integer)
          require(piece in 0..Int.MAX_VALUE.toLong())
          if (type == 0L) {
            val remoteId = extensions.id("ut_metadata")
            if (remoteId != 0) wire.send(metadataMessage(remoteId, 2, piece.toInt()))
            continue
          }
          val output = requireNotNull(data) { "Metadata arrived before negotiation" }
          require(piece.toInt() in pending) { "Unrequested metadata block" }
          require(type != 2L) { "Peer rejected metadata request" }
          require(header["total_size"]?.integer == output.size.toLong()) {
            "Metadata size changed"
          }
          val offset = piece.toInt() * BLOCK_SIZE
          val count = minOf(BLOCK_SIZE, output.size - offset)
          require(message.payload.size - header.end == count) { "Invalid metadata block length" }
          message.payload.copyInto(output, offset, header.end)
          pending.remove(piece.toInt())
          received[piece.toInt()] = true
          receivedCount++
          if (receivedCount == received.size) {
            require(InfoHash.fromBytes(sha1Digest(output)) == hash) { "Metadata hash mismatch" }
            val metainfo = metainfoFromInfo(output, trackerTiers)
            val metadata = TorrentMetadata.fromBencode(metainfo)
            if (metadata.isPrivate) throw PrivateTorrentMagnetException()
            return@withTimeout metadata
          }
        }
        val id = extensions.id("ut_metadata")
        if (id != 0 && data != null) {
          while (pending.size < 4 && next < received.size) {
            pending += next
            wire.send(metadataMessage(id, 0, next++))
          }
        }
      }
      @Suppress("UNREACHABLE_CODE")
      error("Metadata exchange ended")
    } finally {
      connection?.close()
      lease.close()
    }
  }

  companion object {
    const val BLOCK_SIZE = 16_384

    fun metadataMessage(id: Int, type: Int, piece: Int): PeerMessage.Extended =
      PeerMessage.Extended(id, Bencode.encode(mapOf("msg_type" to type.toLong(),
        "piece" to piece.toLong())))

    fun response(id: Int, piece: Int, metadata: TorrentMetadata): PeerMessage.Extended {
      val offset = piece.toLong() * BLOCK_SIZE
      if (piece < 0 || offset >= metadata.infoBytes.size) return metadataMessage(id, 2, piece)
      val header = Bencode.encode(mapOf("msg_type" to 1L, "piece" to piece.toLong(),
        "total_size" to metadata.infoBytes.size.toLong()))
      return PeerMessage.Extended(id, header + metadata.infoBytes.copyOfRange(offset.toInt(),
        minOf(offset + BLOCK_SIZE, metadata.infoBytes.size.toLong()).toInt()))
    }
  }
}

/** Wraps the original info bytes without re-encoding their identity-bearing dictionary. */
internal fun metainfoFromInfo(info: ByteArray, trackerTiers: List<List<String>>): ByteArray {
  val prefix = Bencode.encode(mapOf("announce-list" to trackerTiers))
  return Buffer().write(prefix, 0, prefix.size - 1).writeUtf8("4:info").write(info)
    .writeByte('e'.code).readByteArray()
}

internal class PrivateTorrentMagnetException :
  IllegalArgumentException("Private torrents require a metainfo input")
