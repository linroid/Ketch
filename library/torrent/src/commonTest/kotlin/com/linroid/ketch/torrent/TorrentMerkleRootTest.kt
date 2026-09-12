package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class TorrentMerkleRootTest {
  @Test
  fun partialSingleBlockUsesItsActualLength() {
    val root = TorrentMerkleRoot(3).update("xabcx".encodeToByteArray(), 1, 3)
    assertContentEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
      .hexToByteArray(), root.digest())
  }

  @Test
  fun threeLeavesUseZeroHashForMissingLeaf() {
    // Independent Python hashlib fixture: two full 'a' blocks followed by one 'a' byte.
    val root = TorrentMerkleRoot(32_769)
    val block = ByteArray(16_384) { 'a'.code.toByte() }
    root.update(block).update(block).update(byteArrayOf('a'.code.toByte()))
    assertContentEquals("7e9d905ded0e5ffe35d91855cc97fb7da71cecf51850046a8f8c2076d3fbdf23"
      .hexToByteArray(), root.digest())
  }

  @Test
  fun rejectsMissingExcessAndRepeatedPayloadWithoutLosingValidState() {
    assertFailsWith<IllegalArgumentException> { TorrentMerkleRoot(0) }
    assertFailsWith<IllegalArgumentException> { TorrentMerkleRoot(-1) }
    val root = TorrentMerkleRoot(3).update("a".encodeToByteArray())
    assertFailsWith<IllegalStateException> { root.digest() }
    assertFailsWith<IllegalArgumentException> { root.update("bcd".encodeToByteArray()) }
    assertFailsWith<IllegalArgumentException> { root.update(byteArrayOf(), Int.MAX_VALUE, 1) }
    root.update("bc".encodeToByteArray())
    assertContentEquals(sha256Digest("abc".encodeToByteArray()), root.digest())
    assertFailsWith<IllegalStateException> { root.digest() }
    assertFailsWith<IllegalStateException> { root.update(byteArrayOf()) }
  }
}
