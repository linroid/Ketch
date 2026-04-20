package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MagnetUriTest {

  private val sampleHash =
    "aabbccddee11223344556677889900aabbccddee"

  @Test
  fun parse_minimalMagnet() {
    val uri = "magnet:?xt=urn:btih:$sampleHash"
    val parsed = MagnetUri.parse(uri)
    assertEquals(sampleHash, parsed.infoHash.hex)
    assertNull(parsed.displayName)
    assertEquals(emptyList(), parsed.trackers)
  }

  @Test
  fun parse_withDisplayNameAndTrackers() {
    val uri = "magnet:?xt=urn:btih:$sampleHash" +
      "&dn=My+File" +
      "&tr=http%3A%2F%2Ftracker.example.com%2Fannounce" +
      "&tr=udp%3A%2F%2Ftracker2.example.com%3A6881"
    val parsed = MagnetUri.parse(uri)
    assertEquals(sampleHash, parsed.infoHash.hex)
    assertEquals("My File", parsed.displayName)
    assertEquals(2, parsed.trackers.size)
    assertEquals(
      "http://tracker.example.com/announce",
      parsed.trackers[0],
    )
  }

  @Test
  fun parse_base32InfoHash() {
    // 32-char base32 encodes 20 bytes
    val base32 = "VK3GMZB5GZMH2LYMIWZMZB6MJMYFEY3F"
    val uri = "magnet:?xt=urn:btih:$base32"
    val parsed = MagnetUri.parse(uri)
    assertEquals(40, parsed.infoHash.hex.length)
  }

  @Test
  fun parse_missingInfoHash_throws() {
    assertFailsWith<IllegalArgumentException> {
      MagnetUri.parse("magnet:?dn=test")
    }
  }

  @Test
  fun parse_notMagnet_throws() {
    assertFailsWith<IllegalArgumentException> {
      MagnetUri.parse("http://example.com")
    }
  }

  @Test
  fun toUri_roundtrip() {
    val original = MagnetUri(
      infoHash = InfoHash.fromHex(sampleHash),
      displayName = "Test File",
      trackers = listOf("http://tracker.example.com/announce"),
    )
    val uri = original.toUri()
    val parsed = MagnetUri.parse(uri)
    assertEquals(original.infoHash, parsed.infoHash)
    assertEquals(original.displayName, parsed.displayName)
    assertEquals(original.trackers.size, parsed.trackers.size)
  }

  @Test
  fun parse_caseInsensitive() {
    val uri = "MAGNET:?XT=URN:BTIH:$sampleHash"
    val parsed = MagnetUri.parse(uri)
    assertEquals(sampleHash, parsed.infoHash.hex)
  }

  @Test
  fun base32Decode_validInput() {
    // M=12,F=5,R=17,A=0 -> 01100 00101 10001 00000
    // -> 01100001 01100010 -> 0x61 0x62 = "ab"
    val decoded = MagnetUri.base32Decode("MFRA")
    assertEquals(
      listOf('a'.code.toByte(), 'b'.code.toByte()),
      decoded.toList(),
    )
  }
}
