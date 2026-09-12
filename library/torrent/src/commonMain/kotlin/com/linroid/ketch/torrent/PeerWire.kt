package com.linroid.ketch.torrent

import kotlinx.coroutines.withTimeout
import okio.Buffer

internal sealed interface PeerMessage {
  data object KeepAlive : PeerMessage
  enum class Signal { CHOKE, UNCHOKE, INTERESTED, NOT_INTERESTED }
  data class Control(val signal: Signal) : PeerMessage
  data class Have(val index: Int) : PeerMessage
  data class Bitfield(val bytes: ByteArray) : PeerMessage
  data class Request(val index: Int, val begin: Int, val length: Int) : PeerMessage
  data class Piece(val index: Int, val begin: Int, val bytes: ByteArray) : PeerMessage
  data class Cancel(val index: Int, val begin: Int, val length: Int) : PeerMessage
  data class Port(val port: Int) : PeerMessage
  data class Extended(val id: Int, val payload: ByteArray) : PeerMessage
  data class Unknown(val id: Int, val payload: ByteArray) : PeerMessage
}

internal data class PeerHandshake(
  val infoHash: InfoHash,
  val peerId: ByteArray,
  val extensions: Boolean,
  val dht: Boolean,
)

/** Wire codec with bounded allocations; sessions enforce message ordering and request ownership. */
internal class PeerWire(
  private val connection: TorrentConnection,
  private val metadata: TorrentMetadata? = null,
  private val idleTimeoutMs: Long = 180_000,
  private val pieceCount: Int? = metadata?.let { it.pieceHashes.size / 20 },
) {
  suspend fun handshake(local: PeerHandshake): PeerHandshake = withTimeout(10_000) {
    connection.write(encodeHandshake(local))
    decodeHandshake(connection.readExactly(68), local.infoHash)
  }

  suspend fun read(): PeerMessage = withTimeout(idleTimeoutMs) {
    val size = Buffer().write(connection.readExactly(4)).readInt()
    val limits = PeerFrameLimits(pieceCount)
    limits.validateSize(size)
    if (size == 0) return@withTimeout PeerMessage.KeepAlive
    val id = connection.readExactly(1).single().toInt() and 255
    limits.validateType(size, id)
    val payload = Buffer().writeByte(id).write(connection.readExactly(size - 1)).readByteArray()
    decode(payload, metadata, pieceCount)
  }

  suspend fun send(message: PeerMessage) = withTimeout(idleTimeoutMs) {
    connection.write(encode(message, metadata, pieceCount))
  }

  companion object {
    const val BLOCK_SIZE: Int = 16 * 1024
    const val MAX_FRAME_SIZE: Int = 64 * 1024
    private val protocol = "BitTorrent protocol".encodeToByteArray()

    fun encodeHandshake(handshake: PeerHandshake): ByteArray {
      require(handshake.peerId.size == 20)
      val reserved = ByteArray(8)
      if (handshake.extensions) reserved[5] = 0x10
      if (handshake.dht) reserved[7] = 1
      return Buffer().writeByte(19).write(protocol).write(reserved)
        .write(handshake.infoHash.toBytes()).write(handshake.peerId).readByteArray()
    }

    fun decodeHandshake(bytes: ByteArray, expected: InfoHash): PeerHandshake {
      require(bytes.size == 68 && bytes[0] == 19.toByte()) { "Invalid peer handshake length" }
      require(bytes.copyOfRange(1, 20).contentEquals(protocol)) { "Unknown peer protocol" }
      val hash = InfoHash.fromBytes(bytes.copyOfRange(28, 48))
      require(hash == expected) { "Peer belongs to another swarm" }
      return PeerHandshake(
        infoHash = hash,
        peerId = bytes.copyOfRange(48, 68),
        extensions = bytes[25].toInt() and 0x10 != 0,
        dht = bytes[27].toInt() and 1 != 0,
      )
    }

    fun decode(
      payload: ByteArray,
      metadata: TorrentMetadata? = null,
      pieceCount: Int? = metadata?.let { it.pieceHashes.size / 20 },
    ): PeerMessage {
      val limits = PeerFrameLimits(pieceCount)
      limits.validateSize(payload.size)
      if (payload.isEmpty()) return PeerMessage.KeepAlive
      val input = Buffer().write(payload)
      val id = input.readByte().toInt() and 255
      limits.validateType(payload.size, id)
      fun exact(size: Int) = require(input.size == size.toLong()) { "Invalid peer message length" }
      val message = when (id) {
        in 0..3 -> {
          exact(0)
          PeerMessage.Control(PeerMessage.Signal.entries[id])
        }
        4 -> {
          exact(4)
          PeerMessage.Have(input.readInt())
        }
        5 -> PeerMessage.Bitfield(input.readByteArray())
        6, 8 -> {
          exact(12)
          val index = input.readInt()
          val begin = input.readInt()
          val length = input.readInt()
          if (id == 6) PeerMessage.Request(index, begin, length)
          else PeerMessage.Cancel(index, begin, length)
        }
        7 -> {
          require(input.size >= 9) { "Empty or truncated peer block" }
          PeerMessage.Piece(input.readInt(), input.readInt(), input.readByteArray())
        }
        9 -> {
          exact(2)
          PeerMessage.Port(input.readShort().toInt() and 65535)
        }
        20 -> {
          require(input.size >= 1)
          PeerMessage.Extended(input.readByte().toInt() and 255, input.readByteArray())
        }
        else -> PeerMessage.Unknown(id, input.readByteArray())
      }
      validate(message, metadata, pieceCount)
      return message
    }

    fun encode(
      message: PeerMessage,
      metadata: TorrentMetadata? = null,
      pieceCount: Int? = metadata?.let { it.pieceHashes.size / 20 },
    ): ByteArray {
      validate(message, metadata, pieceCount)
      val out = Buffer()
      when (message) {
        PeerMessage.KeepAlive -> Unit
        is PeerMessage.Control -> out.writeByte(message.signal.ordinal)
        is PeerMessage.Have -> out.writeByte(4).writeInt(message.index)
        is PeerMessage.Bitfield -> out.writeByte(5).write(message.bytes)
        is PeerMessage.Request -> out.writeByte(6).writeInt(message.index)
          .writeInt(message.begin).writeInt(message.length)
        is PeerMessage.Piece -> out.writeByte(7).writeInt(message.index)
          .writeInt(message.begin).write(message.bytes)
        is PeerMessage.Cancel -> out.writeByte(8).writeInt(message.index)
          .writeInt(message.begin).writeInt(message.length)
        is PeerMessage.Port -> out.writeByte(9).writeShort(message.port)
        is PeerMessage.Extended -> out.writeByte(20).writeByte(message.id).write(message.payload)
        is PeerMessage.Unknown -> out.writeByte(message.id).write(message.payload)
      }
      PeerFrameLimits(pieceCount).validateSize(out.size.toInt())
      return Buffer().writeInt(out.size.toInt()).write(out.readByteArray()).readByteArray()
    }

    private fun validate(message: PeerMessage, metadata: TorrentMetadata?, pieceCount: Int?) {
      val limits = PeerFrameLimits(pieceCount)
      require(metadata == null || pieceCount == metadata.pieceHashes.size / 20)
      val index = when (message) {
        is PeerMessage.Have -> message.index
        is PeerMessage.Request -> message.index
        is PeerMessage.Cancel -> message.index
        is PeerMessage.Piece -> message.index
        else -> null
      }
      if (index != null && pieceCount != null) require(index in 0 until pieceCount)
      when (message) {
        is PeerMessage.Have -> validateIndex(message.index, metadata)
        is PeerMessage.Request ->
          validateBlock(message.index, message.begin, message.length, metadata)
        is PeerMessage.Cancel ->
          validateBlock(message.index, message.begin, message.length, metadata)
        is PeerMessage.Piece ->
          validateBlock(message.index, message.begin, message.bytes.size, metadata)
        is PeerMessage.Bitfield -> limits.validateBitfield(message.bytes)
        is PeerMessage.Port -> require(message.port in 1..65535)
        is PeerMessage.Extended -> require(message.id in 0..255 &&
          message.payload.size <= MAX_FRAME_SIZE - 2)
        is PeerMessage.Unknown -> require(message.id in 0..255 &&
          message.payload.size <= MAX_FRAME_SIZE - 1)
        else -> Unit
      }
    }

    private fun validateIndex(index: Int, metadata: TorrentMetadata?) {
      require(index >= 0) { "Negative piece index" }
      if (metadata != null) {
        require(index < metadata.pieceHashes.size / 20) { "Invalid piece index" }
      }
    }

    private fun validateBlock(index: Int, begin: Int, length: Int, metadata: TorrentMetadata?) {
      validateIndex(index, metadata)
      require(begin >= 0 && length in 1..BLOCK_SIZE) { "Invalid block bounds" }
      if (metadata != null) {
        val pieceSize = minOf(
          metadata.pieceLength, metadata.totalBytes - index * metadata.pieceLength
        )
        require(begin.toLong() <= pieceSize - length) { "Block exceeds piece" }
      }
    }
  }
}
