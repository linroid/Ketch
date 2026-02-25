package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Additional edge case tests for [MagnetUri] beyond the basics
 * covered in [MagnetUriTest].
 */
class MagnetUriEdgeCaseTest {

  private val validHash =
    "0123456789abcdef0123456789abcdef01234567"

  // -- Multiple trackers --

  @Test
  fun parse_multipleTrackers_preservesOrder() {
    val uri = "magnet:?xt=urn:btih:$validHash" +
      "&tr=http%3A%2F%2Ftracker1.com" +
      "&tr=http%3A%2F%2Ftracker2.com" +
      "&tr=http%3A%2F%2Ftracker3.com"
    val parsed = MagnetUri.parse(uri)
    assertEquals(3, parsed.trackers.size)
    assertEquals("http://tracker1.com", parsed.trackers[0])
    assertEquals("http://tracker2.com", parsed.trackers[1])
    assertEquals("http://tracker3.com", parsed.trackers[2])
  }

  @Test
  fun parse_noTrackers() {
    val uri = "magnet:?xt=urn:btih:$validHash&dn=NoTrackers"
    val parsed = MagnetUri.parse(uri)
    assertEquals(0, parsed.trackers.size)
    assertEquals("NoTrackers", parsed.displayName)
  }

  // -- Display name encoding --

  @Test
  fun parse_displayNameWithSpaces_urlDecoded() {
    val uri = "magnet:?xt=urn:btih:$validHash&dn=My+Cool+File"
    val parsed = MagnetUri.parse(uri)
    assertEquals("My Cool File", parsed.displayName)
  }

  @Test
  fun parse_displayNameWithPercentEncoding() {
    val uri = "magnet:?xt=urn:btih:$validHash&dn=File%20%26%20Data"
    val parsed = MagnetUri.parse(uri)
    assertEquals("File & Data", parsed.displayName)
  }

  @Test
  fun parse_noDisplayName() {
    val uri = "magnet:?xt=urn:btih:$validHash"
    val parsed = MagnetUri.parse(uri)
    assertNull(parsed.displayName)
  }

  // -- Missing/invalid required fields --

  @Test
  fun parse_missingXtParameter_throws() {
    assertFailsWith<IllegalArgumentException> {
      MagnetUri.parse("magnet:?dn=test&tr=http://tracker.com")
    }
  }

  @Test
  fun parse_nonBtihXtParameter_throws() {
    // Has xt but not btih (e.g., ed2k)
    assertFailsWith<IllegalArgumentException> {
      MagnetUri.parse("magnet:?xt=urn:ed2k:abc123")
    }
  }

  @Test
  fun parse_emptyQueryString_throws() {
    assertFailsWith<IllegalArgumentException> {
      MagnetUri.parse("magnet:?")
    }
  }

  @Test
  fun parse_invalidInfoHashLength_throws() {
    // 20 chars is neither 40 (hex) nor 32 (base32)
    assertFailsWith<IllegalArgumentException> {
      MagnetUri.parse("magnet:?xt=urn:btih:01234567890123456789")
    }
  }

  // -- Unknown parameters are ignored --

  @Test
  fun parse_unknownParameters_ignored() {
    val uri = "magnet:?xt=urn:btih:$validHash" +
      "&xl=1024&as=http%3A%2F%2Falt.com&xs=foo"
    val parsed = MagnetUri.parse(uri)
    assertEquals(validHash, parsed.infoHash.hex)
    assertNull(parsed.displayName)
    assertEquals(0, parsed.trackers.size)
  }

  // -- Parameter without value --

  @Test
  fun parse_parameterWithoutValue_skipped() {
    val uri = "magnet:?xt=urn:btih:$validHash&broken&dn=test"
    val parsed = MagnetUri.parse(uri)
    assertEquals(validHash, parsed.infoHash.hex)
    assertEquals("test", parsed.displayName)
  }

  // -- toUri round-trip --

  @Test
  fun toUri_noDisplayNameNoTrackers() {
    val magnet = MagnetUri(
      infoHash = InfoHash.fromHex(validHash),
    )
    val uri = magnet.toUri()
    assertTrue(uri.startsWith("magnet:?xt=urn:btih:"))
    assertTrue(uri.contains(validHash))
    // No &dn= or &tr= in the URI
    assertTrue("&dn=" !in uri)
    assertTrue("&tr=" !in uri)
  }

  @Test
  fun toUri_specialCharsInDisplayName_encoded() {
    val magnet = MagnetUri(
      infoHash = InfoHash.fromHex(validHash),
      displayName = "File [2024] (HD).mkv",
    )
    val uri = magnet.toUri()
    // Round-trip should preserve the display name
    val parsed = MagnetUri.parse(uri)
    assertEquals("File [2024] (HD).mkv", parsed.displayName)
  }

  @Test
  fun toUri_multipleTrackers_allPresent() {
    val trackers = listOf(
      "http://tracker1.com/announce",
      "udp://tracker2.com:6881",
      "http://tracker3.com/announce?passkey=abc",
    )
    val magnet = MagnetUri(
      infoHash = InfoHash.fromHex(validHash),
      trackers = trackers,
    )
    val uri = magnet.toUri()
    val parsed = MagnetUri.parse(uri)
    assertEquals(3, parsed.trackers.size)
    assertEquals(trackers, parsed.trackers)
  }

  // -- Base32 edge cases --

  @Test
  fun base32Decode_withPadding() {
    // "MFRA====" pads to full block; should still decode "ab"
    val decoded = MagnetUri.base32Decode("MFRA====")
    assertEquals(
      listOf('a'.code.toByte(), 'b'.code.toByte()),
      decoded.toList(),
    )
  }

  @Test
  fun base32Decode_invalidCharacter_throws() {
    assertFailsWith<IllegalArgumentException> {
      MagnetUri.base32Decode("MFRA1!!!") // '1' is valid, '!' is not
    }
  }

  @Test
  fun base32Decode_lowercase_works() {
    // Should be case-insensitive
    val decoded = MagnetUri.base32Decode("mfra")
    assertEquals(
      listOf('a'.code.toByte(), 'b'.code.toByte()),
      decoded.toList(),
    )
  }

  @Test
  fun base32Decode_emptyString() {
    val decoded = MagnetUri.base32Decode("")
    assertEquals(0, decoded.size)
  }

  // -- URL encoding/decoding edge cases --

  @Test
  fun parse_percentEncodedTrackerUrls() {
    val tracker =
      "http://tracker.example.com:2710/announce?passkey=abc123"
    val encoded = tracker
      .replace(":", "%3A")
      .replace("/", "%2F")
      .replace("?", "%3F")
      .replace("=", "%3D")
    val uri = "magnet:?xt=urn:btih:$validHash&tr=$encoded"
    val parsed = MagnetUri.parse(uri)
    assertEquals(tracker, parsed.trackers[0])
  }

  @Test
  fun parse_invalidPercentEncoding_preservesLiteral() {
    // %ZZ is not valid hex, should be kept as literal
    val uri = "magnet:?xt=urn:btih:$validHash&dn=test%ZZdata"
    val parsed = MagnetUri.parse(uri)
    // The '%' is kept as literal, then 'Z', 'Z', 'data'
    val dn = assertNotNull(parsed.displayName)
    assertTrue(dn.contains("%"))
  }

  @Test
  fun parse_truncatedPercentEncoding_preservesLiteral() {
    // %2 at end of string (missing second hex digit)
    val uri = "magnet:?xt=urn:btih:$validHash&dn=test%2"
    val parsed = MagnetUri.parse(uri)
    val dn = assertNotNull(parsed.displayName)
    assertTrue(dn.endsWith("%2"))
  }
}
