package com.linroid.ketch.torrent

import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerHashProofTest {
  private val leaves = (1..5).map { sha256Digest(byteArrayOf(it.toByte())) } +
    List(3) { ByteArray(32) }
  private fun parent(left: ByteArray, right: ByteArray) = sha256Digest(left + right)
  private val pairs = (0..3).map { parent(leaves[it * 2], leaves[it * 2 + 1]) }
  private val halves = listOf(parent(pairs[0], pairs[1]), parent(pairs[2], pairs[3]))
  private val root = parent(halves[0], halves[1]).toByteString()
  private val length = 4 * 16_384L + 1

  private fun response(selector: PeerHashSelector, hashes: List<ByteArray>) =
    PeerHashMessage.Hashes(selector, hashes.fold(ByteArray(0)) { a, b -> a + b }.toByteString())

  @Test
  fun authenticatesLeafRangesOnBothSidesOfTheTreeAndPieceLayerRanges() {
    val first = PeerHashSelector(root, 0, 0, 2, 2)
    val last = first.copy(index = 4)
    assertTrue(verifyPeerHashes(response(first, leaves.take(2) + listOf(pairs[1], halves[1])),
      first, length, root))
    assertTrue(verifyPeerHashes(response(last, leaves.subList(4, 6) + listOf(pairs[3], halves[0])),
      last, length, root))
    val pieces = PeerHashSelector(root, 1, 2, 2, 1)
    assertTrue(verifyPeerHashes(response(pieces, pairs.drop(2) + listOf(halves[0])),
      pieces, length, root))
    val whole = PeerHashSelector(root, 0, 0, 8, 0)
    assertTrue(verifyPeerHashes(response(whole, leaves), whole, length, root))
  }

  @Test
  fun rejectsUncorrelatedCorruptTruncatedAndOutOfTreeResponses() {
    val selector = PeerHashSelector(root, 0, 0, 2, 2)
    val valid = response(selector, leaves.take(2) + listOf(pairs[1], halves[1]))
    assertFalse(verifyPeerHashes(valid, selector.copy(index = 2), length, root))
    assertFalse(verifyPeerHashes(valid, selector, length, ByteArray(32).toByteString()))
    for (index in 0 until valid.hashes.size) {
      val changed = valid.hashes.toByteArray()
      changed[index] = (changed[index].toInt() xor 1).toByte()
      assertFalse(verifyPeerHashes(valid.copy(hashes = changed.toByteString()),
        selector, length, root))
    }
    assertFalse(verifyPeerHashes(valid.copy(hashes = valid.hashes.substring(0, 64)),
      selector, length, root))
    for (size in listOf(0L, 1L, 16_384L, 16_385L)) {
      assertFalse(verifyPeerHashes(valid, selector, size, root))
    }
    val outside = selector.copy(index = 8)
    assertFalse(verifyPeerHashes(valid.copy(selector = outside), outside, length, root))
    val partial = selector.copy(proofLayers = 0)
    assertFalse(verifyPeerHashes(response(partial, leaves.take(2)), partial, length, root))
  }

  @Test
  fun maximumFileLengthUsesBoundedTreeArithmetic() {
    val trusted = parent(pairs[0], pairs[1]).toByteString()
    val selector = PeerHashSelector(trusted, 48, 0, 2, 0)
    assertTrue(verifyPeerHashes(response(selector, pairs.take(2)),
      selector, Long.MAX_VALUE, trusted))
    val aboveRoot = selector.copy(baseLayer = 49)
    assertFalse(verifyPeerHashes(response(aboveRoot, pairs.take(2)),
      aboveRoot, Long.MAX_VALUE, trusted))
    assertFalse(verifyPeerHashes(response(selector, pairs.take(2)),
      selector, -1, trusted))
  }

  @Test
  fun rejectsNonzeroPaddingEvenWhenItMatchesTheSuppliedRoot() {
    val badLeaves = leaves.toMutableList().also { it[7] = ByteArray(32) { 1 } }
    val badPair = parent(badLeaves[6], badLeaves[7])
    val badHalf = parent(pairs[2], badPair)
    val badRoot = parent(halves[0], badHalf).toByteString()
    val whole = PeerHashSelector(badRoot, 0, 0, 8, 0)
    assertFalse(verifyPeerHashes(response(whole, badLeaves), whole, length, badRoot))
    val first = PeerHashSelector(badRoot, 0, 4, 2, 2)
    assertFalse(verifyPeerHashes(response(first, leaves.subList(4, 6) + listOf(badPair, halves[0])),
      first, length, badRoot))
    val piece = PeerHashSelector(badRoot, 1, 2, 2, 1)
    assertFalse(verifyPeerHashes(response(piece, listOf(pairs[2], badPair, halves[0])),
      piece, length, badRoot))
  }
}
