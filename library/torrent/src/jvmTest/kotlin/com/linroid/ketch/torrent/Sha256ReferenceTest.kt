package com.linroid.ketch.torrent

import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class Sha256ReferenceTest {
  @Test
  fun matchesJdkAcrossPaddingAndTorrentBlockBoundaries() {
    val random = Random(162)
    val sizes = (0..256).toList() + listOf(16_383, 16_384, 16_385, 262_143, 262_144, 262_145)
    for (size in sizes) {
      val bytes = ByteArray(size).also(random::nextBytes)
      val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
      assertContentEquals(expected, sha256Digest(bytes), "One-shot bytes $size")
      val hash = Sha256()
      var offset = 0
      while (offset < size) {
        val count = minOf(1 + random.nextInt(257), size - offset)
        hash.update(bytes, offset, count)
        offset += count
      }
      assertContentEquals(expected, hash.digest(), "Streamed bytes $size")
    }
  }
}
