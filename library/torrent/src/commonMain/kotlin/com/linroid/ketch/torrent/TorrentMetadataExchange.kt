package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** BEP 9 bounded metadata exchange, with four outstanding blocks and full hash verification. */
private val log = KetchLogger("TorrentEngine")

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
    privacy: TorrentDiscoveryPrivacy = TorrentDiscoveryPrivacy.PUBLIC,
  ): TorrentMetadata = fetchContent(TorrentIdentity(v1 = hash), endpoint, trackerTiers, privacy) {
      _, info, _ ->
    // A btih magnet may name a hybrid; its v1 view tells resolution the v2 identity to fetch.
    val metadata = TorrentMetadata.fromBencode(metainfoFromInfo(info, trackerTiers),
      allowHybrid = true)
    if (metadata.isPrivate && privacy != TorrentDiscoveryPrivacy.TRACKER_ONLY) {
      throw PrivateTorrentMagnetException()
    }
    metadata
  }

  suspend fun <T> fetchContent(
    identity: TorrentIdentity,
    endpoint: PeerEndpoint,
    trackerTiers: List<List<String>> = emptyList(),
    privacy: TorrentDiscoveryPrivacy = TorrentDiscoveryPrivacy.PUBLIC,
    decode: suspend (TorrentConnection, ByteArray, TorrentBufferBudget) -> T,
  ): T = withTimeout(timeoutMs) {
    require(privacy != TorrentDiscoveryPrivacy.TRACKER_ONLY ||
      trackerTiers.any { it.isNotEmpty() }) {
      "Tracker-only metadata requires supplied trackers"
    }
    var lease: TorrentBufferBudget.Lease? = null
    var connection: TorrentConnection? = null
    try {
      log.v { "Metadata exchange with $endpoint: connecting" }
      connection = network.connect(endpoint)
      val workspace = TorrentBufferBudget(256 * 1024)
      val handshake = PeerIdentityHandshake(identity, extensions = true).initiate(connection,
        if (identity.v2 != null) PeerIdentityHandshake.Mode.V2 else PeerIdentityHandshake.Mode.V1,
        torrentRandomBytes(20).toByteString(), workspace)
      val wire = PeerWire(connection)
      require(handshake.extensions) {
        "Peer does not support metadata exchange"
      }
      log.v { "Metadata exchange with $endpoint: handshake done, waiting for a metadata buffer" }
      // Reserved only now, so peers that never answer do not hold the budget, which several
      // lookups share, while others wait to connect.
      while (lease == null) {
        lease = budget.reserve(maxBytes * 4 + 256 * 1024)
        if (lease == null) delay(10)
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
            log.v { "Metadata exchange with $endpoint: $size bytes to fetch" }
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
            require(identity.matchesInfo(output)) { "Metadata hash mismatch" }
            return@withTimeout decode(connection, output, workspace)
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
      lease?.close()
    }
  }

  companion object {
    const val BLOCK_SIZE = 16_384

    fun metadataMessage(id: Int, type: Int, piece: Int): PeerMessage.Extended =
      PeerMessage.Extended(id, Bencode.encode(mapOf("msg_type" to type.toLong(),
        "piece" to piece.toLong())))

    fun response(id: Int, piece: Int, metadata: TorrentMetadata): PeerMessage.Extended =
      response(id, piece, metadata.infoBytes.size) { from, to ->
        metadata.infoBytes.copyOfRange(from, to)
      }

    /** Block [piece] of the raw info dictionary [info], or a reject past its end. */
    fun response(id: Int, piece: Int, info: ByteString): PeerMessage.Extended =
      response(id, piece, info.size) { from, to -> info.substring(from, to).toByteArray() }

    private inline fun response(
      id: Int,
      piece: Int,
      size: Int,
      slice: (from: Int, to: Int) -> ByteArray,
    ): PeerMessage.Extended {
      val offset = piece.toLong() * BLOCK_SIZE
      if (piece < 0 || offset >= size) return metadataMessage(id, 2, piece)
      val header = Bencode.encode(mapOf("msg_type" to 1L, "piece" to piece.toLong(),
        "total_size" to size.toLong()))
      return PeerMessage.Extended(id, header + slice(offset.toInt(),
        minOf(offset + BLOCK_SIZE, size.toLong()).toInt()))
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
  IllegalArgumentException("Private torrents require tracker-only discovery or a metainfo input")
