package com.linroid.ketch.torrent

/** Actor-owned rarity index; each peer key identifies one connection lifetime. */
internal class TorrentV2RarityPicker<P : Any> private constructor(
  val pieceCount: Int,
  private var counts: IntArray,
  private val maxPeers: Int,
  private val budget: TorrentBufferBudget,
  private val lease: TorrentBufferBudget.Lease,
) {
  private class Peer(val bits: ByteArray, val lease: TorrentBufferBudget.Lease)
  private val peers = mutableMapOf<P, Peer>()
  private var cursor = 0
  private var closed = false
  private val limits = PeerFrameLimits(pieceCount)

  /** Returns false before copying if this peer's retained snapshot cannot be admitted. */
  fun update(peer: P, bits: ByteArray): Boolean {
    check(!closed)
    limits.validateBitfield(bits)
    val current = admit(peer) ?: return false
    for (byte in bits.indices) {
      val old = current.bits[byte].toInt() and 255
      val next = bits[byte].toInt() and 255
      val changed = old xor next
      if (changed == 0) continue
      for (bit in 0..7) {
        val mask = 128 ushr bit
        if (changed and mask != 0) counts[byte * 8 + bit] += if (next and mask != 0) 1 else -1
      }
    }
    bits.copyInto(current.bits)
    return true
  }

  fun have(peer: P, index: Int): Boolean {
    check(!closed)
    require(index in 0 until pieceCount)
    val current = admit(peer) ?: return false
    val byte = index / 8
    val mask = 128 ushr (index % 8)
    if (current.bits[byte].toInt() and mask == 0) {
      current.bits[byte] = (current.bits[byte].toInt() or mask).toByte()
      counts[index]++
    }
    return true
  }

  fun hasAny(index: Int): Boolean {
    check(!closed)
    require(index in 0 until pieceCount)
    return counts[index] > 0
  }

  fun has(peer: P, index: Int): Boolean {
    check(!closed)
    require(index in 0 until pieceCount)
    val current = peers[peer] ?: return false
    return current.bits[index / 8].toInt() and (128 ushr (index % 8)) != 0
  }

  /** Eligibility is an actor-local lookup; no I/O or allocation should run in this callback. */
  fun pick(peer: P, eligible: (Int) -> Boolean): Int? {
    check(!closed)
    val current = peers[peer] ?: return null
    var best = -1
    var rarity = Int.MAX_VALUE
    var index = cursor
    repeat(pieceCount) {
      if (current.bits[index / 8].toInt() and (128 ushr (index % 8)) != 0 &&
        counts[index] < rarity && eligible(index)) {
        best = index
        rarity = counts[index]
        if (rarity == 1) {
          cursor = (index + 1) % pieceCount
          return index
        }
      }
      index++
      if (index == pieceCount) index = 0
    }
    if (best < 0) return null
    cursor = (best + 1) % pieceCount
    return best
  }

  fun remove(peer: P) {
    val current = peers.remove(peer) ?: return
    for (byte in current.bits.indices) {
      val bits = current.bits[byte].toInt() and 255
      if (bits == 0) continue
      for (bit in 0..7) if (bits and (128 ushr bit) != 0) counts[byte * 8 + bit]--
    }
    current.lease.close()
  }

  private fun admit(peer: P): Peer? {
    peers[peer]?.let { return it }
    if (peers.size == maxPeers) return null
    val size = (pieceCount + 7) / 8
    val reservation = budget.reserve(size + 128) ?: return null
    try {
      val entry = Peer(ByteArray(size), reservation)
      peers[peer] = entry
      return entry
    } catch (error: Throwable) {
      reservation.close()
      throw error
    }
  }

  fun close() {
    if (closed) return
    closed = true
    peers.values.forEach { it.lease.close() }
    peers.clear()
    counts = IntArray(0)
    lease.close()
  }

  companion object {
    fun <P : Any> create(
      pieceCount: Int,
      budget: TorrentBufferBudget,
      maxPeers: Int = 100,
    ): TorrentV2RarityPicker<P>? {
      require(pieceCount in 0..1_000_000 && maxPeers in 1..500)
      val lease = budget.reserve(pieceCount * 4 + maxPeers * 128 + 512) ?: return null
      try {
        return TorrentV2RarityPicker(pieceCount, IntArray(pieceCount), maxPeers, budget, lease)
      } catch (error: Throwable) {
        lease.close()
        throw error
      }
    }
  }
}
