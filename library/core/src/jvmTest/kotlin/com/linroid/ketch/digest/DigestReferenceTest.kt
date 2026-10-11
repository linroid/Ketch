package com.linroid.ketch.digest

import com.linroid.ketch.api.ChecksumAlgorithm
import com.linroid.ketch.core.digest.newDigest
import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class DigestReferenceTest {
  @Test
  fun matchesJdkAcrossPaddingAndTorrentBlockBoundaries() {
    val random = Random(162)
    val sizes = (0..300).toList() + listOf(16_383, 16_384, 16_385, 262_143, 262_144, 262_145)
    for (algorithm in ChecksumAlgorithm.entries) {
      val jdk = when (algorithm) {
        ChecksumAlgorithm.MD5 -> "MD5"
        ChecksumAlgorithm.SHA_1 -> "SHA-1"
        ChecksumAlgorithm.SHA_256 -> "SHA-256"
        ChecksumAlgorithm.SHA_512 -> "SHA-512"
      }
      for (size in sizes) {
        val bytes = ByteArray(size).also(random::nextBytes)
        val expected = MessageDigest.getInstance(jdk).digest(bytes)
        assertContentEquals(
          expected,
          algorithm.newDigest().update(bytes).digest(),
          "${algorithm.label}: one-shot bytes $size",
        )
        val digest = algorithm.newDigest()
        var offset = 0
        while (offset < size) {
          val count = minOf(1 + random.nextInt(257), size - offset)
          digest.update(bytes, offset, count)
          offset += count
        }
        assertContentEquals(expected, digest.digest(), "${algorithm.label}: streamed bytes $size")
      }
    }
  }
}
