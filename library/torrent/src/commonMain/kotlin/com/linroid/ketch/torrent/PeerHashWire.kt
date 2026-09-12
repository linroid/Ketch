package com.linroid.ketch.torrent

import okio.Buffer
import okio.ByteString

/** BEP 52 hash exchange. These messages are interpreted only on a negotiated v2 connection. */
internal sealed interface PeerHashMessage {
  data class Request(val selector: PeerHashSelector) : PeerHashMessage
  data class Hashes(val selector: PeerHashSelector, val hashes: ByteString) : PeerHashMessage
  data class Reject(val selector: PeerHashSelector) : PeerHashMessage
}

/** File-root identity and coordinates; file-specific tree bounds require authenticated metadata. */
internal data class PeerHashSelector(
  val root: ByteString,
  val baseLayer: Int,
  val index: Long,
  val length: Int,
  val proofLayers: Int,
) {
  init {
    require(root.size == 32)
    require(baseLayer in 0..63 && proofLayers in 0..63 && baseLayer + proofLayers <= 63)
    require(length in 2..512 && length and (length - 1) == 0)
    require(index in 0..0xffff_ffffL && index % length == 0L)
    require(index + length <= 0x1_0000_0000L)
  }

  // The first log2(length)-1 proof layers are counted but omitted from the response.
  val hashCount: Int get() = length + maxOf(0, proofLayers - length.countTrailingZeroBits() + 1)
}

/** Adapts bounded PeerWire unknown frames without enabling v2 semantics in the v1 runtime. */
internal object PeerHashWire {
  fun decode(message: PeerMessage.Unknown): PeerHashMessage? {
    if (message.id !in 21..23) return null
    require(message.payload.size in 48 until PeerWire.MAX_FRAME_SIZE)
    val input = Buffer().write(message.payload)
    val root = input.readByteString(32)
    fun layer(): Int {
      val value = input.readInt()
      require(value in 0..63)
      return value
    }
    val base = layer()
    val index = input.readInt().toLong() and 0xffff_ffffL
    val length = input.readInt()
    val proof = layer()
    val selector = PeerHashSelector(root, base, index, length, proof)
    return when (message.id) {
      21, 23 -> {
        require(input.exhausted()) { "Unexpected hash request/reject payload" }
        if (message.id == 21) PeerHashMessage.Request(selector) else
          PeerHashMessage.Reject(selector)
      }
      else -> {
        require(input.size == selector.hashCount * 32L) { "Wrong hash response size" }
        PeerHashMessage.Hashes(selector, input.readByteString())
      }
    }
  }

  fun encode(message: PeerHashMessage): PeerMessage.Unknown {
    val selector = when (message) {
      is PeerHashMessage.Request -> message.selector
      is PeerHashMessage.Hashes -> message.selector
      is PeerHashMessage.Reject -> message.selector
    }
    val id = when (message) {
      is PeerHashMessage.Request -> 21
      is PeerHashMessage.Hashes -> 22
      is PeerHashMessage.Reject -> 23
    }
    if (message is PeerHashMessage.Hashes) {
      require(message.hashes.size == selector.hashCount * 32) { "Wrong hash response size" }
    }
    val out = Buffer().write(selector.root).writeInt(selector.baseLayer)
      .writeInt(selector.index.toInt()).writeInt(selector.length).writeInt(selector.proofLayers)
    if (message is PeerHashMessage.Hashes) out.write(message.hashes)
    return PeerMessage.Unknown(id, out.readByteArray())
  }
}
