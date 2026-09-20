package com.linroid.ketch.torrent

import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentPieceLayerReferenceTest {
  @Test
  fun matchesIndependentFullTreesAcrossPieceSizesAndIncompleteSubtrees() {
    val random = Random(52)
    val digest = MessageDigest.getInstance("SHA-256")
    for (blockCount in listOf(3, 5, 7, 9, 17, 33)) {
      val payload = ByteArray((blockCount - 1) * 16_384 + 37).also(random::nextBytes)
      var width = 1
      while (width < blockCount) width *= 2
      val tree = mutableListOf(List(width) { index ->
        if (index >= blockCount) ByteArray(32) else digest.digest(payload.copyOfRange(
          index * 16_384, minOf(payload.size, (index + 1) * 16_384)))
      })
      while (tree.last().size > 1) {
        tree += tree.last().chunked(2).map { digest.digest(it[0] + it[1]) }
      }
      for (layer in 0 until tree.lastIndex) {
        val pieceLength = 16_384L shl layer
        if (payload.size <= pieceLength) continue
        val count = ((payload.size - 1) / pieceLength + 1).toInt()
        val hashes = tree[layer].take(count)
        val verifier = TorrentPieceLayerVerifier(payload.size.toLong(), pieceLength,
          tree.last().single())
        hashes.forEach(verifier::append)
        assertTrue(verifier.verify(), "Blocks $blockCount, layer $layer")
        val corrupt = TorrentPieceLayerVerifier(payload.size.toLong(), pieceLength,
          tree.last().single())
        hashes.forEachIndexed { index, hash ->
          val copy = hash.copyOf()
          if (index == hashes.lastIndex) copy[0] = (copy[0].toInt() xor 1).toByte()
          corrupt.append(copy)
        }
        assertFalse(corrupt.verify())
      }
    }
  }
}
