package com.linroid.ketch.torrent

import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Authenticates a correlated BEP 52 response all the way to a metadata-trusted file root.
 * Partial proofs terminating at a cached intermediate anchor are not accepted by this verifier.
 * Callers reserve incoming/retained hash bytes and enforce their supported base-layer policy.
 */
internal fun verifyPeerHashes(
  response: PeerHashMessage.Hashes,
  requested: PeerHashSelector,
  fileLength: Long,
  trustedRoot: ByteString,
): Boolean {
  if (fileLength <= 0 || trustedRoot.size != 32 || response.selector != requested ||
    requested.root != trustedRoot || response.hashes.size != requested.hashCount * 32) return false
  peerHashProofHeight(requested, fileLength) ?: return false
  val blocks = (fileLength - 1) / TorrentMerkleRoot.BLOCK_BYTES + 1
  val base = requested.baseLayer
  val groupHeight = requested.length.countTrailingZeroBits()
  val uncles = requested.hashCount - requested.length

  fun parent(left: ByteArray, right: ByteArray): ByteArray =
    Sha256().update(left).update(right).digest()
  fun hash(index: Int): ByteArray = response.hashes.substring(index * 32, (index + 1) * 32)
    .toByteArray()

  var zero = ByteArray(32)
  repeat(base) { zero = parent(zero, zero) }
  val frontier = TorrentMerkleFrontier(base)
  for (index in 0 until requested.length) {
    val value = hash(index)
    if (((requested.index + index) shl base) >= blocks && !value.contentEquals(zero)) return false
    frontier.append(value)
  }
  var result = frontier.digest()
  var level = base
  repeat(groupHeight) { zero = parent(zero, zero); level++ }
  var node = requested.index shr groupHeight
  for (index in 0 until uncles) {
    val sibling = hash(requested.length + index)
    if (((node xor 1L) shl level) >= blocks && !sibling.contentEquals(zero)) return false
    result = if (node and 1L == 0L) parent(result, sibling) else parent(sibling, result)
    node = node shr 1
    zero = parent(zero, zero)
    level++
  }
  return result.toByteString() == trustedRoot
}

/** Returns the root height only when this bounded request can carry a complete file-root proof. */
internal fun peerHashProofHeight(request: PeerHashSelector, fileLength: Long): Int? {
  if (fileLength <= 0) return null
  val blocks = (fileLength - 1) / TorrentMerkleRoot.BLOCK_BYTES + 1
  var height = 0
  var width = 1L
  while (width < blocks) { width *= 2; height++ }
  val base = request.baseLayer
  if (base >= height || request.proofLayers > height - base - 1) return null
  if (request.index + request.length > (width shr base)) return null
  val groupHeight = request.length.countTrailingZeroBits()
  val uncles = request.hashCount - request.length
  return height.takeIf { base + groupHeight + uncles == height }
}
