package com.linroid.ketch.torrent

/** A bounded frontier for a layer of hashes; [baseLayer] is measured above 16 KiB block hashes. */
internal class TorrentMerkleFrontier(private val baseLayer: Int = 0) {
  private val frontier = arrayOfNulls<ByteArray>(64)
  private var finished = false
  private var count = 0L

  init { require(baseLayer in 0..48) }

  fun append(leaf: ByteArray) {
    check(!finished)
    require(leaf.size == 32)
    check(count < Long.MAX_VALUE)
    count++
    var hash = leaf.copyOf()
    for (level in frontier.indices) {
      val left = frontier[level]
      if (left == null) {
        frontier[level] = hash
        return
      }
      frontier[level] = null
      hash = parent(left, hash)
    }
    error("Merkle tree exceeds supported length")
  }

  fun digest(): ByteArray {
    check(!finished && count > 0)
    finished = true
    var right: ByteArray? = null
    var rightLevel = 0
    for (level in frontier.indices) {
      val left = frontier[level] ?: continue
      if (right == null) {
        right = left
        rightLevel = level
      } else {
        while (rightLevel < level) {
          right = parent(checkNotNull(right), zeroHash(baseLayer + rightLevel))
          rightLevel++
        }
        right = parent(left, checkNotNull(right))
        rightLevel++
      }
    }
    return checkNotNull(right).copyOf()
  }

  private fun zeroHash(level: Int): ByteArray {
    var hash = ByteArray(32)
    repeat(level) { hash = parent(hash, hash) }
    return hash
  }

  private fun parent(left: ByteArray, right: ByteArray): ByteArray =
    Sha256().update(left).update(right).digest()
}
