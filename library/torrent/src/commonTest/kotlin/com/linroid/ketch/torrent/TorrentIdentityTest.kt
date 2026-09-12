package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TorrentIdentityTest {
  @Test
  fun wirePrefixCollisionsDoNotCollapseFullIdentities() {
    val first = V2InfoHash("01".repeat(20) + "02".repeat(12))
    val second = V2InfoHash("01".repeat(20) + "03".repeat(12))
    assertContentEquals(first.wireBytes(), second.wireBytes())
    assertNotEquals(first, second)
    assertEquals(first, V2InfoHash.fromMultihash(first.multihash().uppercase()))
    assertFailsWith<IllegalArgumentException> { V2InfoHash.fromBytes(first.wireBytes()) }
  }

  @Test
  fun everySuppliedExactTopicMustMatchRawInfo() {
    val raw = "d4:name1:xe".encodeToByteArray()
    val v1 = InfoHash.fromBytes(sha1Digest(raw))
    val v2 = V2InfoHash.fromBytes(sha256Digest(raw))
    assertTrue(TorrentIdentity(v1, v2).matchesInfo(raw))
    assertTrue(TorrentIdentity(v2 = v2).matchesInfo(raw))
    assertFalse(TorrentIdentity(v1, V2InfoHash("00".repeat(32))).matchesInfo(raw))
    assertFalse(TorrentIdentity(InfoHash("00".repeat(20)), v2).matchesInfo(raw))
    assertFalse(TorrentIdentity(v1, v2).matchesInfo(raw + byteArrayOf(0)))
    assertFailsWith<IllegalArgumentException> { TorrentIdentity() }
  }

  @Test
  fun multihashDecodesAlgorithmAndLengthAndRejectsMalformedVarints() {
    val digest = "01".repeat(32)
    val unsupported = assertFailsWith<UnsupportedTorrentHashAlgorithm> {
      V2InfoHash.fromMultihash("1320$digest")
    }
    assertEquals(0x13L, unsupported.algorithm)
    for (value in listOf("", "12", "122", "1220gg", "1220${digest.dropLast(2)}",
      "1220${digest}00", "121f${digest.dropLast(2)}", "920020$digest",
      "12a000$digest", "80808080808080808000", "12ff")) {
      assertFailsWith<IllegalArgumentException>(value) { V2InfoHash.fromMultihash(value) }
    }
  }
}
