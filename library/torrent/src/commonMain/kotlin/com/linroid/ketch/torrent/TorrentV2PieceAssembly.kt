package com.linroid.ketch.torrent

/** Single actor-owned assembly; its reservation precedes payload and block-index allocation. */
internal class TorrentV2PieceAssembly private constructor(
  val index: Int,
  private val bytes: ByteArray,
  private val received: BooleanArray,
  private val lease: TorrentBufferBudget.Lease,
) {
  private var count = 0
  private var closed = false
  private var committing = false
  val blockCount: Int get() = received.size
  val complete: Boolean get() = !closed && !committing && count == received.size

  fun request(block: Int): PeerMessage.Request {
    check(!closed)
    require(block in received.indices)
    val begin = block * PeerWire.BLOCK_SIZE
    return PeerMessage.Request(index, begin, minOf(PeerWire.BLOCK_SIZE, bytes.size - begin))
  }

  fun missingBlocks(): List<Int> {
    check(!closed)
    return received.indices.filter { !received[it] }
  }

  /** Always consumes the delivered block's credit, including duplicate and invalid responses. */
  fun accept(block: PeerBlockExchange.Response.Block) {
    try {
      check(!closed && !committing)
      val request = block.ticket.request
      require(request.index == index && request.begin >= 0 &&
        request.begin % PeerWire.BLOCK_SIZE == 0) { "Block is outside this assembly" }
      val slot = request.begin / PeerWire.BLOCK_SIZE
      require(slot in received.indices && request == request(slot) &&
        block.bytes.size == request.length) { "Invalid assembly block range" }
      require(!received[slot]) { "Duplicate assembly block" }
      block.bytes.copyInto(bytes, request.begin)
      received[slot] = true
      count++
    } finally {
      block.close()
    }
  }

  /**
   * Consumes a complete assembly on success, hash rejection, cancellation or storage failure.
   * Only the store may publish verified progress, after validating and flushing this sealed buffer.
   */
  suspend fun commit(store: TorrentV2PieceStore): Boolean {
    check(complete) { "Assembly is closed or incomplete" }
    committing = true
    try {
      return store.commitOwned(index, bytes)
    } finally {
      committing = false
      close()
    }
  }

  fun close() {
    if (closed || committing) return
    closed = true
    lease.close()
  }

  companion object {
    fun create(
      layout: TorrentContentLayout,
      index: Int,
      budget: TorrentBufferBudget,
    ): TorrentV2PieceAssembly? {
      require(layout.pieceCount <= 1_000_000 && layout.pieceLength <= 16 * 1024 * 1024)
      val length = layout.v2Piece(index.toLong()).length.toInt()
      val blocks = (length + PeerWire.BLOCK_SIZE - 1) / PeerWire.BLOCK_SIZE
      // Includes payload, received flags, bounded missing-index snapshots and container allowance.
      val lease = budget.reserve(length + blocks * 32 + 512) ?: return null
      try {
        return TorrentV2PieceAssembly(index, ByteArray(length), BooleanArray(blocks), lease)
      } catch (error: Throwable) {
        lease.close()
        throw error
      }
    }
  }
}
