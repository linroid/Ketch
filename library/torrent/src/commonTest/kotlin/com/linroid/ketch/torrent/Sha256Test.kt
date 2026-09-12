package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Sha256Test {
  @Test
  fun knownDigestsCoverEmptySingleAndMultipleBlocks() {
    val vectors = listOf(
      "" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "abc" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" to
        "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
      ("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmn" +
        "hijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu") to
        "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1"
    )
    for ((input, expected) in vectors) {
      val bytes = input.encodeToByteArray()
      assertEquals(expected, sha256Digest(bytes).toHexString())
      for (split in 0..bytes.size) {
        val hash = Sha256().update(bytes, 0, split).update(byteArrayOf())
          .update(bytes, split, bytes.size - split)
        assertEquals(expected, hash.digest().toHexString(), "Input split at $split")
      }
    }
  }

  @Test
  fun millionByteVectorStreamsFromReusableBuffer() {
    val block = ByteArray(1000) { 'a'.code.toByte() }
    val hash = Sha256()
    repeat(1000) { hash.update(block) }
    block.fill(0)
    assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
      hash.digest().toHexString())
  }

  @Test
  fun slicesAreCopiedAndInvalidRangesDoNotChangeState() {
    val bytes = "xabcx".encodeToByteArray()
    val hash = Sha256().update(bytes, 1, 3)
    assertFailsWith<IllegalArgumentException> { hash.update(bytes, -1, 1) }
    assertFailsWith<IllegalArgumentException> { hash.update(bytes, 1, -1) }
    assertFailsWith<IllegalArgumentException> { hash.update(bytes, 4, 2) }
    assertFailsWith<IllegalArgumentException> { hash.update(bytes, Int.MAX_VALUE, Int.MAX_VALUE) }
    bytes.fill(0)
    assertContentEquals(sha256Digest("abc".encodeToByteArray()), hash.digest())
    assertFailsWith<IllegalStateException> { hash.digest() }
    assertFailsWith<IllegalStateException> { hash.update(byteArrayOf()) }
  }
}
