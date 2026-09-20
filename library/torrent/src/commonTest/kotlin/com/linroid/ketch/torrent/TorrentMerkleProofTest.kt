package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentMerkleProofTest {
  @Test
  fun shortSingleBlockNeedsNoSiblings() {
    val root = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".hexToByteArray()
    assertTrue(verifyTorrentMerkleBlock(3, 0, "abc".encodeToByteArray(), emptyList(), root))
    assertFalse(verifyTorrentMerkleBlock(3, 0, "abd".encodeToByteArray(), emptyList(), root))
  }

  @Test
  fun rejectsMalformedAndImpossibleProofsBeforeHashing() {
    val block = ByteArray(16_384)
    val root = ByteArray(32)
    assertFalse(verifyTorrentMerkleBlock(0, 0, block, emptyList(), root))
    assertFalse(verifyTorrentMerkleBlock(-1, 0, block, emptyList(), root))
    assertFalse(verifyTorrentMerkleBlock(Long.MAX_VALUE, Long.MAX_VALUE, block, emptyList(), root))
    assertFalse(verifyTorrentMerkleBlock(Long.MAX_VALUE, 0, block, List(64) { root }, root))
    assertFalse(verifyTorrentMerkleBlock(32_768, 0, block, listOf(ByteArray(31)), root))
  }
}
