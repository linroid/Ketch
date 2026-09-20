package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MagnetIdentityTest {
  private val v1 = InfoHash("01".repeat(20))
  private val v2 = V2InfoHash("02".repeat(32))

  @Test
  fun v2AndHybridTopicsRoundTripWithoutTruncation() {
    for (identity in listOf(TorrentIdentity(v2 = v2), TorrentIdentity(v1, v2))) {
      val magnet = MagnetUri(identity, "file ✓", listOf("https://tracker.example/announce"),
        listOf("[::1]:6881"))
      val parsed = MagnetUri.parse(magnet.toUri())
      assertEquals(identity, parsed.identity)
      assertEquals(magnet.displayName, parsed.displayName)
      assertEquals(magnet.trackers, parsed.trackers)
      assertEquals(magnet.explicitPeers, parsed.explicitPeers)
      // The old v1 engine must not fall back to one topic and leave the other unauthenticated.
      assertFailsWith<IllegalArgumentException> { parsed.infoHash }
    }
    assertNull(MagnetUri.parse("magnet:?xt=urn:btmh:${v2.multihash()}").identity.v1)
  }

  @Test
  fun duplicateEqualTopicsAreAcceptedButConflictsAreRejected() {
    val base = "magnet:?xt=urn:btih:${v1.hex}&xt=urn:btmh:${v2.multihash()}"
    val duplicate = base + "&xt=URN:BTIH:${v1.hex.uppercase()}" +
      "&xt=urn%3Abtmh%3A${v2.multihash().uppercase()}"
    assertEquals(TorrentIdentity(v1, v2), MagnetUri.parse(duplicate).identity)
    assertFailsWith<IllegalArgumentException> {
      MagnetUri.parse(base + "&xt=urn:btih:${"03".repeat(20)}")
    }
    assertFailsWith<IllegalArgumentException> {
      MagnetUri.parse(base + "&xt=urn:btmh:1220${"03".repeat(32)}")
    }
    assertFailsWith<UnsupportedTorrentHashAlgorithm> {
      MagnetUri.parse(base + "&xt=urn:btmh:1320${"03".repeat(32)}")
    }
  }
}
