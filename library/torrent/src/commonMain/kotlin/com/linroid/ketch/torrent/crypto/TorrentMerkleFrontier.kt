package com.linroid.ketch.torrent.crypto

import okio.ByteString
import okio.ByteString.Companion.toByteString

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

  private fun zeroHash(level: Int): ByteArray = merkleZeroHash(level)

  private fun parent(left: ByteArray, right: ByteArray): ByteArray =
    Sha256().update(left).update(right).digest()
}

private val zeroHashes: List<ByteString> = buildList {
  var hash = ByteArray(32)
  repeat(64) {
    add(hash.toByteString())
    hash = Sha256().update(hash).update(hash).digest()
  }
}

/**
 * The root of a BEP 52 subtree [level] levels above 16 KiB leaves that holds only padding: its
 * leaves are zero hashes, not hashes of zero bytes. Memoized for levels 0..63.
 */
internal fun merkleZeroHash(level: Int): ByteArray {
  require(level >= 0)
  if (level < zeroHashes.size) return zeroHashes[level].toByteArray()
  var hash = zeroHashes.last().toByteArray()
  repeat(level - zeroHashes.lastIndex) { hash = Sha256().update(hash).update(hash).digest() }
  return hash
}
