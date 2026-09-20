package com.linroid.ketch.torrent

import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentMerkleProofReferenceTest {
  @Test
  fun verifiesEveryBlockAgainstIndependentTreeAndRejectsTampering() {
    val random = Random(52)
    for (count in listOf(1, 2, 3, 5, 7, 8, 9, 17)) {
      val data = ByteArray((count - 1) * 16_384 + 37).also(random::nextBytes)
      val digest = MessageDigest.getInstance("SHA-256")
      val blocks = data.asList().chunked(16_384).map { it.toByteArray() }
      var width = 1
      while (width < count) width *= 2
      val layers = mutableListOf(List(width) { index ->
        if (index < count) digest.digest(blocks[index]) else ByteArray(32)
      })
      while (layers.last().size > 1) {
        layers += layers.last().chunked(2).map { digest.digest(it[0] + it[1]) }
      }
      val root = layers.last().single()
      for (index in blocks.indices) {
        val proof = layers.dropLast(1).mapIndexed { level, hashes ->
          hashes[(index shr level) xor 1].copyOf()
        }
        fun verifies(payload: ByteArray = blocks[index], siblings: List<ByteArray> = proof,
                     expected: ByteArray = root, blockIndex: Long = index.toLong()): Boolean =
          verifyTorrentMerkleBlock(data.size.toLong(), blockIndex, payload, siblings, expected)
        assertTrue(verifies(), "File blocks $count, block $index")
        val corrupt = blocks[index].copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(verifies(payload = corrupt))
        assertFalse(verifies(payload = blocks[index].copyOf(blocks[index].size - 1)))
        assertFalse(verifies(blockIndex = count.toLong()))
        assertFalse(verifies(blockIndex = -1))
        assertFalse(verifies(expected = root.copyOf(31)))
        assertFalse(verifies(siblings = proof + ByteArray(32)))
        if (count == 3 && index == 2) {
          val badPadding = ByteArray(32).also { it[0] = 1 }
          val right = digest.digest(digest.digest(blocks[index]) + badPadding)
          val invalidRoot = digest.digest(proof[1] + right)
          assertFalse(verifies(siblings = listOf(badPadding, proof[1]), expected = invalidRoot))
        }
        if (proof.isNotEmpty()) {
          assertFalse(verifies(siblings = proof.dropLast(1)))
          for (level in proof.indices) {
            val altered = proof.map { it.copyOf() }
            altered[level][0] = (altered[level][0].toInt() xor 1).toByte()
            assertFalse(verifies(siblings = altered), "Altered proof level $level")
          }
        }
      }
    }
  }

}
