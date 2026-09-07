package com.linroid.ketch.torrent

import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MetainfoValidationTest {
  @Test
  fun sha1_knownAnswersAndChunkBoundaries() {
    assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", hex(sha1Digest(ByteArray(0))))
    assertEquals(
      "a9993e364706816aba3e25717850c26c9cd0d89d", hex(sha1Digest("abc".encodeToByteArray()))
    )
    val input = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()
    for (split in 0..input.size) {
      assertEquals(
        "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
        hex(Sha1().update(input, 0, split).update(input, split).digest())
      )
    }
    val hash = Sha1()
    repeat(1000) { hash.update(ByteArray(1000) { 'a'.code.toByte() }) }
    assertEquals("34aa973cd4c4daa4f61eeb2bdbad27316534016f", hex(hash.digest()))
  }

  @Test
  fun bencode_malformedInputsRejected() {
    for (text in listOf("i01e", "i-0e", "i+1e", "i1ee", "01:a", "9999999999:a",
      "d1:bi1e1:ai2ee", "d1:ai1e1:ai2ee", "l".repeat(65) + "e".repeat(65))) {
      assertFailsWith<IllegalArgumentException>(text) { Bencode.parse(text.encodeToByteArray()) }
    }
    assertFailsWith<IllegalArgumentException> { Bencode.parse("4:spam".encodeToByteArray(), 5) }
  }

  @Test
  fun bencode_binaryKeysAndPrefixPreserved() {
    val key = byteArrayOf(255.toByte())
    val data = Bencode.encode(mapOf(key to 7L))
    val root = Bencode.parse(data)
    assertEquals(7L, root.dictionary!![key.toByteString()]?.integer)
    assertContentEquals(data, Bencode.encode(root))
    assertEquals(data.size, Bencode.parsePrefix(data + byteArrayOf(0, 1)).end)
  }

  @Test
  fun metainfo_exactInfoBytesAndPrivateTrackerTiersPreserved() {
    val info = info() + mapOf("private" to 1L)
    val raw = Bencode.encode(info)
    val encoded = Bencode.encode(mapOf("info" to info, "announce-list" to listOf(
      listOf("https://a", "https://b"), listOf("udp://c:80")
    )))
    val metadata = TorrentMetadata.fromBencode(encoded)
    assertContentEquals(raw, metadata.infoBytes)
    assertEquals(InfoHash.fromBytes(sha1Digest(raw)), metadata.infoHash)
    assertEquals(listOf(2, 1), metadata.trackerTiers.map { it.size })
    assertTrue(metadata.isPrivate)
  }

  @Test
  fun metainfo_invalidLayoutAndPathsRejected() {
    for (changes in listOf(
      mapOf("length" to -1L), mapOf("pieces" to ByteArray(0)),
      mapOf("piece length" to 0L), mapOf("meta version" to 2L),
      mapOf("name" to "../escape"), mapOf("name" to "CON"), mapOf("name" to "file.")
    )) {
      assertFailsWith<IllegalArgumentException> {
        TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to (info() + changes))))
      }
    }
  }

  @Test
  fun magnet_utf8AndSupplementaryCharactersRoundTrip() {
    val magnet = MagnetUri(InfoHash.fromBytes(ByteArray(20)), "文件 🎥 café")
    assertEquals(magnet.displayName, MagnetUri.parse(magnet.toUri()).displayName)
    assertTrue(magnet.toUri().contains("%F0%9F"))
  }

  private fun info(): Map<String, Any> = mapOf(
    "name" to "test", "length" to 3L, "piece length" to 16L,
    "pieces" to sha1Digest("abc".encodeToByteArray())
  )

  private fun hex(bytes: ByteArray): String = bytes.toByteString().hex()
}
