package com.linroid.ketch.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChecksumTest {
  private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  private val abcSha256Base64 = "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0="

  @Test
  fun parse_prefixedHex_normalizesCase() {
    val checksum = Checksum.parse("SHA-256:${abcSha256.uppercase()}")
    assertEquals(Checksum(ChecksumAlgorithm.SHA_256, abcSha256), checksum)
    assertEquals("sha256:$abcSha256", checksum.toString())
  }

  @Test
  fun parse_bareHex_namesAlgorithmByLength() {
    assertEquals(
      ChecksumAlgorithm.MD5,
      Checksum.parse("d41d8cd98f00b204e9800998ecf8427e").algorithm,
    )
    assertEquals(
      ChecksumAlgorithm.SHA_1,
      Checksum.parse("da39a3ee5e6b4b0d3255bfef95601890afd80709").algorithm,
    )
    assertEquals(ChecksumAlgorithm.SHA_256, Checksum.parse(" $abcSha256 ").algorithm)
    assertEquals(ChecksumAlgorithm.SHA_512, Checksum.parse("ab".repeat(64)).algorithm)
  }

  @Test
  fun parse_base64Forms_decodeToHex() {
    val expected = Checksum(ChecksumAlgorithm.SHA_256, abcSha256)
    assertEquals(expected, Checksum.parse("sha-256=:$abcSha256Base64:"))
    assertEquals(expected, Checksum.parse("SHA-256=$abcSha256Base64"))
    assertEquals(expected, Checksum.parse("sha256:${abcSha256Base64.trimEnd('=')}"))
    val urlSafe = abcSha256Base64.replace('+', '-').replace('/', '_')
    assertEquals(expected, Checksum.parse(ChecksumAlgorithm.SHA_256, urlSafe))
  }

  @Test
  fun parse_invalid_throws() {
    assertFailsWith<IllegalArgumentException> { Checksum.parse("crc32:abcd1234") }
    assertFailsWith<IllegalArgumentException> { Checksum.parse("sha256:abcd") }
    assertFailsWith<IllegalArgumentException> { Checksum.parse("md5:$abcSha256") }
    assertFailsWith<IllegalArgumentException> { Checksum.parse("abc") }
    assertFailsWith<IllegalArgumentException> { Checksum.parse("sha256:" + "g".repeat(64)) }
    assertNull(Checksum.parseOrNull(""))
  }

  @Test
  fun constructor_requiresLowercaseHexOfTheAlgorithmsLength() {
    assertFailsWith<IllegalArgumentException> {
      Checksum(ChecksumAlgorithm.SHA_256, abcSha256.uppercase())
    }
    assertFailsWith<IllegalArgumentException> { Checksum(ChecksumAlgorithm.SHA_1, abcSha256) }
  }

  @Test
  fun matches_comparesDigestBytes() {
    val checksum = Checksum.parse("sha256:$abcSha256")
    val bytes = abcSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    assertTrue(checksum.matches(bytes))
    bytes[0] = 0
    assertFalse(checksum.matches(bytes))
  }

  @Test
  fun serializer_writesAndReadsText() {
    val checksum = Checksum(ChecksumAlgorithm.SHA_256, abcSha256)
    val json = Json.encodeToString(Checksum.serializer(), checksum)
    assertEquals("\"sha256:$abcSha256\"", json)
    assertEquals(checksum, Json.decodeFromString(Checksum.serializer(), "\"SHA256:$abcSha256\""))
    assertFailsWith<SerializationException> {
      Json.decodeFromString(Checksum.serializer(), "\"sha256:abcd\"")
    }
  }

  @Test
  fun downloadRequest_withoutChecksum_decodesFromOlderJson() {
    val request = Json.decodeFromString(DownloadRequest.serializer(), """{"url":"https://a/b"}""")
    assertNull(request.checksum)
    val withChecksum = Json.decodeFromString(
      DownloadRequest.serializer(),
      """{"url":"https://a/b","checksum":"sha256:$abcSha256"}""",
    )
    assertEquals(ChecksumAlgorithm.SHA_256, withChecksum.checksum?.algorithm)
  }

  @Test
  fun mismatch_isNotRetryableAndNamesBothDigests() {
    val error = KetchError.ChecksumMismatch(ChecksumAlgorithm.MD5, "aa", "bb", published = true)
    assertFalse(error.isRetryable)
    assertEquals(
      "MD5 checksum mismatch: expected aa, got bb (digest published by the server)",
      error.message,
    )
  }
}
