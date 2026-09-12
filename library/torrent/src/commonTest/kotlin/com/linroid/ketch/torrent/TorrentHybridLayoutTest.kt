package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class TorrentHybridLayoutTest {
  private fun file(name: String, length: Long): Map<String, Any> =
    mapOf("path" to listOf(name), "length" to length)
  private fun padding(length: Long): Map<String, Any> = mapOf("attr" to "p", "length" to length)
  private val entries = listOf(file("a", 1), padding(16_383), file("empty", 0), file("z", 2))

  private fun document(
    files: List<Map<String, Any>>,
    hashes: ByteArray = ByteArray(40),
  ): ByteArray {
    fun leaf(length: Long): Map<String, Any> = mapOf("" to buildMap {
      put("length", length)
      if (length > 0) put("pieces root", sha256Digest(ByteArray(length.toInt())))
    })
    return Bencode.encode(mapOf("info" to mapOf(
      "name" to "pack", "meta version" to 2L, "piece length" to 16_384L,
      "file tree" to mapOf("a" to leaf(1), "empty" to leaf(0), "z" to leaf(2)),
      "files" to files, "pieces" to hashes
    ), "piece layers" to emptyMap<String, Any>()))
  }

  @Test
  fun matchingHybridRetainsLegacyIndicesAndVirtualPadding() {
    val parsed = TorrentV2Document.parse(document(entries))
    val hybrid = assertNotNull(parsed.hybrid)
    assertEquals(listOf(0, 2, 3), hybrid.files.map { it.v1Index })
    assertEquals(listOf(0L, 16_384L, 16_384L), hybrid.files.map { it.offset })
    assertEquals(listOf(TorrentHybridLayout.Padding(1, 16_383)), hybrid.padding)
    assertEquals(3L, parsed.info.totalBytes)
    assertEquals(16_386L, hybrid.totalV1Bytes)
    assertEquals(parsed.info.hash, hybrid.identity.v2)
    TorrentV2Document.parse(document(entries), expectedIdentity = hybrid.identity)
  }

  @Test
  fun acceptsMatchingSingleFileLayoutsIncludingEmptyFiles() {
    for (length in listOf(0, 1, 16_384)) {
      val payload = ByteArray(length)
      val properties = buildMap<String, Any> {
        put("length", length.toLong())
        if (length > 0) put("pieces root", sha256Digest(payload))
      }
      val info = mapOf("name" to "file", "meta version" to 2L, "piece length" to 16_384L,
        "length" to length.toLong(), "pieces" to
          (if (length == 0) byteArrayOf() else sha1Digest(payload)),
        "file tree" to mapOf("file" to mapOf("" to properties)))
      val parsed = TorrentV2Document.parse(Bencode.encode(mapOf("info" to info,
        "piece layers" to emptyMap<String, Any>())))
      val hybrid = assertNotNull(parsed.hybrid)
      assertEquals(length.toLong(), hybrid.totalV1Bytes)
      assertEquals(emptyList(), hybrid.padding)
      assertEquals(InfoHash.fromBytes(sha1Digest(Bencode.encode(info))), hybrid.identity.v1)
    }
  }

  @Test
  fun rejectsInconsistentNamesOrderLengthsPaddingAndHashes() {
    val invalid = listOf(entries.filterNot { it["attr"] == "p" },
      listOf(padding(16_384)) + entries,
      listOf(file("a", 1), padding(1), file("empty", 0), file("z", 2)),
      entries.dropLast(1), entries.dropLast(1) + file("other", 2),
      entries.dropLast(1) + file("z", 3),
      listOf(file("z", 2), padding(16_382), file("empty", 0), file("a", 1)))
    for (files in invalid) {
      assertFailsWith<IllegalArgumentException> { TorrentV2Document.parse(document(files)) }
    }
    assertFailsWith<IllegalArgumentException> {
      TorrentV2Document.parse(document(entries, ByteArray(20)))
    }
    val trailing = TorrentV2Document.parse(document(entries + padding(16_382)))
    assertEquals(32_768L, trailing.hybrid!!.totalV1Bytes)
  }
}
