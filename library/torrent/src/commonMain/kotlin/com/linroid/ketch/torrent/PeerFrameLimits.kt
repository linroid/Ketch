package com.linroid.ketch.torrent

/** Trusted piece-count bounds extend only bitfields beyond the ordinary peer-frame ceiling. */
internal class PeerFrameLimits(val pieceCount: Int? = null) {
  init { require(pieceCount == null || pieceCount in 0..1_000_000) }
  val bitfieldBytes: Int? = pieceCount?.let { (it + 7) / 8 }
  val maximum: Int = maxOf(PeerWire.MAX_FRAME_SIZE, (bitfieldBytes ?: 0) + 1)

  fun validateSize(size: Int) {
    require(size in 0..maximum) { "Peer frame exceeds limit" }
  }

  /** Called after the one-byte ID, before allocating/reading the remaining body. */
  fun validateType(size: Int, id: Int) {
    validateSize(size)
    require(size <= PeerWire.MAX_FRAME_SIZE || id == 5) { "Oversized non-bitfield frame" }
    if (id == 5 && bitfieldBytes != null) {
      require(size == bitfieldBytes + 1) { "Wrong bitfield size" }
    }
  }

  fun validateBitfield(bytes: ByteArray) {
    if (pieceCount == null) {
      require(bytes.size < PeerWire.MAX_FRAME_SIZE)
      return
    }
    require(bytes.size == bitfieldBytes) { "Wrong bitfield size" }
    val spare = (8 - pieceCount % 8) % 8
    if (spare != 0) {
      require(bytes.last().toInt() and ((1 shl spare) - 1) == 0) { "Nonzero bitfield padding" }
    }
  }
}
