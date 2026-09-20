package com.linroid.ketch.torrent

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Single actor-owned assembly; its reservation precedes payload and block-index allocation. */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentV2PieceAssembly private constructor(
  val index: Int,
  private var bytes: ByteArray,
  private val received: BooleanArray,
  private val lease: TorrentBufferBudget.Lease,
) {
  private var count = 0
  private enum class Owner { ACTOR, COMMITTING, CLOSED }
  // A queued Claim is itself the ownership token; stale claims cannot affect a later handoff.
  private val owner = AtomicReference<Any>(Owner.ACTOR)
  val blockCount: Int get() = received.size
  val complete: Boolean get() = owner.load() === Owner.ACTOR && count == received.size

  fun request(block: Int): PeerMessage.Request {
    check(owner.load() === Owner.ACTOR)
    require(block in received.indices)
    val begin = block * PeerWire.BLOCK_SIZE
    return PeerMessage.Request(index, begin, minOf(PeerWire.BLOCK_SIZE, bytes.size - begin))
  }

  fun missingBlocks(): List<Int> {
    check(owner.load() === Owner.ACTOR)
    return received.indices.filter { !received[it] }
  }

  /** Always consumes the delivered block's credit, including duplicate and invalid responses. */
  fun accept(block: PeerBlockExchange.Response.Block) {
    try {
      check(owner.load() === Owner.ACTOR)
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
    check(complete) { "Assembly is unavailable or incomplete" }
    return commitFrom(Owner.ACTOR, store)
  }

  /** Queue ownership prevents mutation or early release through the original assembly handle. */
  class Claim internal constructor(private val assembly: TorrentV2PieceAssembly) {
    val index: Int get() = assembly.index
    suspend fun commit(store: TorrentV2PieceStore): Boolean = assembly.commitFrom(this, store)
    fun close() {
      if (assembly.owner.compareAndSet(this, Owner.CLOSED)) assembly.release()
    }
    fun restore() {
      check(assembly.owner.compareAndSet(this, Owner.ACTOR)) { "Stale assembly handoff" }
    }
  }

  fun transfer(): Claim {
    check(complete) { "Assembly is unavailable or incomplete" }
    val claim = Claim(this)
    check(owner.compareAndSet(Owner.ACTOR, claim)) { "Assembly ownership changed" }
    return claim
  }

  private suspend fun commitFrom(expected: Any, store: TorrentV2PieceStore): Boolean {
    check(owner.compareAndSet(expected, Owner.COMMITTING)) { "Stale assembly owner" }
    try {
      return store.commitOwned(index, bytes)
    } finally {
      owner.store(Owner.CLOSED)
      release()
    }
  }

  fun close() {
    if (owner.compareAndSet(Owner.ACTOR, Owner.CLOSED)) release()
  }

  private fun release() {
    // Stale handles or coroutine locals must not retain a full piece after its credit returns.
    bytes = ByteArray(0)
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
