package com.linroid.ketch.torrent

import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class TorrentMerkleRootReferenceTest {
  @Test
  fun streamedRootMatchesIndependentFullTreeAcrossUnevenShapes() {
    val random = Random(52)
    for (blocks in 1..33) {
      for (tail in listOf(1, 16_383, 16_384)) {
        val bytes = ByteArray((blocks - 1) * 16_384 + tail).also(random::nextBytes)
        val expected = referenceRoot(bytes)
        val root = TorrentMerkleRoot(bytes.size.toLong())
        var position = 0
        while (position < bytes.size) {
          val count = minOf(1 + random.nextInt(8192), bytes.size - position)
          root.update(bytes, position, count)
          position += count
        }
        assertContentEquals(expected, root.digest(), "Blocks $blocks, final bytes $tail")
      }
    }
  }

  // Independent JDK hashes and a complete padded tree, rather than the production frontier fold.
  private fun referenceRoot(bytes: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val blocks = (bytes.size + 16_383) / 16_384
    var leaves = 1
    while (leaves < blocks) leaves *= 2
    var layer = List(leaves) { index ->
      if (index >= blocks) ByteArray(32) else digest.digest(bytes.copyOfRange(index * 16_384,
        minOf((index + 1) * 16_384, bytes.size)))
    }
    while (layer.size > 1) {
      layer = layer.chunked(2).map { digest.digest(it[0] + it[1]) }
    }
    return layer.single()
  }
}
