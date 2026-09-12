package com.linroid.ketch.torrent

/**
 * Streams a non-empty BEP 52 file into its pieces root using O(log(block count)) retained hashes.
 * Empty files have no pieces root. Partial final blocks are hashed at their actual length;
 * missing leaves are zero hashes, not hashes of zero-filled payload blocks.
 */
internal class TorrentMerkleRoot(private val fileLength: Long) {
  private val frontier = arrayOfNulls<ByteArray>(64)
  private var blockHash = Sha256()
  private var blockBytes = 0
  private var received = 0L
  private var finished = false

  init { require(fileLength > 0) { "Empty files do not have a pieces root" } }

  fun update(
    bytes: ByteArray,
    offset: Int = 0,
    count: Int = bytes.size - offset,
  ): TorrentMerkleRoot {
    check(!finished) { "Merkle root already finished" }
    require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
    require(count.toLong() <= fileLength - received) { "Payload exceeds file length" }
    var position = offset
    var remaining = count
    while (remaining > 0) {
      val size = minOf(BLOCK_BYTES - blockBytes, remaining)
      blockHash.update(bytes, position, size)
      blockBytes += size
      position += size
      remaining -= size
      if (blockBytes == BLOCK_BYTES) {
        append(blockHash.digest())
        blockHash = Sha256()
        blockBytes = 0
      }
    }
    received += count
    return this
  }

  fun digest(): ByteArray {
    check(!finished) { "Merkle root already finished" }
    check(received == fileLength) { "Incomplete file payload" }
    finished = true
    if (blockBytes > 0) append(blockHash.digest())
    var right: ByteArray? = null
    var rightLevel = 0
    for (level in frontier.indices) {
      val left = frontier[level] ?: continue
      if (right == null) {
        right = left
        rightLevel = level
      } else {
        while (rightLevel < level) {
          right = parent(checkNotNull(right), zeroHash(rightLevel))
          rightLevel++
        }
        right = parent(left, checkNotNull(right))
        rightLevel++
      }
    }
    return checkNotNull(right).copyOf()
  }

  private fun append(leaf: ByteArray) {
    var hash = leaf
    for (level in frontier.indices) {
      val left = frontier[level]
      if (left == null) {
        frontier[level] = hash
        return
      }
      frontier[level] = null
      hash = parent(left, hash)
    }
    error("Merkle tree exceeds supported file length")
  }

  private fun zeroHash(level: Int): ByteArray {
    var hash = ByteArray(32)
    repeat(level) { hash = parent(hash, hash) }
    return hash
  }

  private fun parent(left: ByteArray, right: ByteArray): ByteArray =
    Sha256().update(left).update(right).digest()

  companion object {
    const val BLOCK_BYTES = 16_384
  }
}
