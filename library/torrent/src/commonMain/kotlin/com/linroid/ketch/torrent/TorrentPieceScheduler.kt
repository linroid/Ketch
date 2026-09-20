package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialized rarity and ownership state. No disk or network operation runs under its lock. */
internal class TorrentPieceScheduler(
  private val wanted: BooleanArray,
  verified: BooleanArray,
  private val pieceSize: (Int) -> Int,
  private val budget: TorrentBufferBudget,
) {
  private val mutex = Mutex()
  private val verified = verified.copyOf()
  private val availability = mutableMapOf<Int, BooleanArray>()
  private var version = 0L
  private val rarity = IntArray(wanted.size)
  private val owners = mutableMapOf<Int, MutableSet<Int>>()
  private val claims = mutableMapOf<Int, Claim>()

  class Claim(val index: Int, val lease: TorrentBufferBudget.Lease) {
    val bytes: ByteArray = ByteArray(lease.bytes)
  }

  init { require(wanted.size == verified.size) }

  suspend fun availability(peer: Int, pieces: BooleanArray) = mutex.withLock {
    require(pieces.size == wanted.size)
    val previous = availability.put(peer, pieces.copyOf())
    for (index in pieces.indices) {
      if (previous?.get(index) == true) rarity[index]--
      if (pieces[index]) rarity[index]++
    }
  }

  /**
   * Applies a single HAVE without rebuilding this peer's availability. A peer may announce
   * before any bitfield, and may repeat announcements, so absent state starts empty and a
   * duplicate index is ignored rather than counted twice.
   */
  suspend fun announce(peer: Int, index: Int) = mutex.withLock {
    require(index in wanted.indices)
    val pieces = availability.getOrPut(peer) { BooleanArray(wanted.size) }
    if (!pieces[index]) {
      pieces[index] = true
      rarity[index]++
    }
  }

  suspend fun claim(peer: Int): Claim? = mutex.withLock {
    check(peer !in claims)
    val available = availability[peer] ?: return@withLock null
    val candidates = wanted.indices.filter { wanted[it] && !verified[it] && available[it] }
    val unclaimed = candidates.filter { owners[it].isNullOrEmpty() }
    // Duplicate at most twice, only when remaining work is already assigned (endgame).
    val choices = unclaimed.ifEmpty {
      if (wanted.indices.count { wanted[it] && !verified[it] } > availability.size) emptyList()
      else candidates.filter { (owners[it]?.size ?: 0) < 2 }
    }
    val index = choices.minWithOrNull(compareBy<Int> { rarity[it] }.thenBy { it })
      ?: return@withLock null
    val lease = budget.reserve(pieceSize(index)) ?: return@withLock null
    val claim = try { Claim(index, lease) } catch (e: Throwable) { lease.close(); throw e }
    owners.getOrPut(index) { mutableSetOf() }.add(peer)
    claims[peer] = claim
    claim
  }

  suspend fun isVerified(index: Int): Boolean = mutex.withLock { verified[index] }

  suspend fun verified(index: Int) = mutex.withLock {
    if (!verified[index]) {
      verified[index] = true
      version++
    }
  }

  suspend fun snapshot(knownVersion: Long): Pair<Long, BooleanArray>? = mutex.withLock {
    if (knownVersion == version) null else version to verified.copyOf()
  }

  suspend fun release(peer: Int) = mutex.withLock { releaseLocked(peer) }

  suspend fun remove(peer: Int) = mutex.withLock {
    releaseLocked(peer)
    availability.remove(peer)?.forEachIndexed { index, present -> if (present) rarity[index]-- }
  }

  private fun releaseLocked(peer: Int) {
    claims.remove(peer)?.let { claim ->
      owners[claim.index]?.let { peers ->
        peers.remove(peer)
        if (peers.isEmpty()) owners.remove(claim.index)
      }
      claim.lease.close()
    }
  }
}
