package com.linroid.ketch.torrent

/**
 * Verifies one 16 KiB block (or its short file tail) against a trusted BEP 52 pieces root.
 * Siblings are ordered from the leaf upwards. This is an integrity primitive, not a wire decoder;
 * callers must bound incoming messages before constructing their sibling list.
 */
internal fun verifyTorrentMerkleBlock(
  fileLength: Long,
  blockIndex: Long,
  payload: ByteArray,
  siblings: List<ByteArray>,
  expectedRoot: ByteArray,
): Boolean {
  if (fileLength <= 0 || blockIndex < 0 || expectedRoot.size != 32) return false
  val blockCount = (fileLength - 1) / TorrentMerkleRoot.BLOCK_BYTES + 1
  if (blockIndex >= blockCount) return false
  val expectedBytes = minOf(TorrentMerkleRoot.BLOCK_BYTES.toLong(),
    fileLength - blockIndex * TorrentMerkleRoot.BLOCK_BYTES).toInt()
  if (payload.size != expectedBytes) return false
  var height = 0
  var width = 1L
  while (width < blockCount) { width *= 2; height++ }
  if (siblings.size != height || siblings.any { it.size != 32 }) return false

  var hash = sha256Digest(payload)
  var zero = ByteArray(32)
  for ((level, sibling) in siblings.withIndex()) {
    val siblingStart = ((blockIndex shr level) xor 1L) shl level
    if (siblingStart >= blockCount && !sibling.contentEquals(zero)) return false
    hash = if (((blockIndex shr level) and 1L) == 0L) {
      Sha256().update(hash).update(sibling).digest()
    } else {
      Sha256().update(sibling).update(hash).digest()
    }
    zero = Sha256().update(zero).update(zero).digest()
  }
  return hash.contentEquals(expectedRoot)
}
