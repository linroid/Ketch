package com.linroid.ketch.torrent

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TorrentOutputMappingTest {
  private fun document(paths: List<List<ByteString>>): TorrentV2Document {
    val tree = linkedMapOf<ByteString, Any>()
    for (path in paths) {
      var node = tree
      for (part in path) {
        @Suppress("UNCHECKED_CAST")
        val next = node.getOrPut(part) { linkedMapOf<ByteString, Any>() } as
          LinkedHashMap<ByteString, Any>
        node = next
      }
      node[ByteString.EMPTY] = mapOf("length" to 0L)
    }
    return TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf("meta version" to 2L,
      "piece length" to 16_384L, "file tree" to tree), "piece layers" to emptyMap<String, Any>())))
  }

  @Test
  fun preservesNormalNamesAndMapsUnsafeBinaryComponentsWithoutTraversal() {
    val names = listOf("readme.txt", "音楽.flac", "..", "a/b", "a\\b", "C:drive", "NUL.txt",
      "COM¹", "CON .txt", "trailing.", "trailing ", "%2e", "~hash", "control\u0000")
      .map { it.encodeUtf8() } + byteArrayOf(0xc0.toByte(), 0xaf.toByte()).toByteString()
    val doc = document(names.map { listOf(it) })
    val mapped = TorrentOutputMapping.from(doc)
    val byRaw = mapped.files.associate { doc.info.files[it.v2Index].path.single() to
      it.components.single() }
    assertEquals("readme.txt", byRaw["readme.txt".encodeUtf8()])
    assertEquals("音楽.flac", byRaw["音楽.flac".encodeUtf8()])
    assertEquals("%2e%2e", byRaw["..".encodeUtf8()])
    assertEquals("%c0%af", byRaw[names.last()])
    for (file in mapped.files) {
      assertEquals(1, file.components.size)
      TorrentMetadata.validatePathComponent(file.components.single())
    }
    assertEquals(names.size, mapped.files.map { it.components }.toSet().size)
  }

  @Test
  fun separatesCaseAndUnicodeAliasesForFilesAndDirectories() {
    val paths = listOf(listOf("A", "one"), listOf("a", "two"), listOf("é"), listOf("é"),
      listOf("Readme"), listOf("readme", "child"), listOf("Σ"), listOf("ς"))
      .map { path -> path.map { it.encodeUtf8() } }
    val doc = document(paths)
    val mapped = TorrentOutputMapping.from(doc)
    val keys = mapped.files.map { file -> file.components.map {
      canonicalTorrentName(it).uppercase().lowercase()
    } }
    assertEquals(paths.size, keys.toSet().size)
    val top = keys.map { it.first() }
    assertEquals(paths.size, top.toSet().size)
    assertTrue(mapped.files.all { it.components.first().contains('~') })
    assertEquals(mapped.files, TorrentOutputMapping.from(document(paths.reversed())).files)
  }

  @Test
  fun hybridMappingPreservesOriginalFileIdsAndOmitsPadding() {
    val properties = mapOf("" to mapOf("length" to 1L, "pieces root" to ByteArray(32)))
    val info = mapOf("meta version" to 2L, "piece length" to 16_384L, "name" to "pack",
      "file tree" to mapOf("a" to properties, "z" to properties),
      "files" to listOf(mapOf("path" to listOf("a"), "length" to 1L),
        mapOf("attr" to "p", "length" to 16_383L),
        mapOf("path" to listOf("z"), "length" to 1L)), "pieces" to ByteArray(40))
    val doc = TorrentV2Document.parse(Bencode.encode(mapOf("info" to info,
      "piece layers" to emptyMap<String, Any>())))
    val mapped = TorrentOutputMapping.from(doc)
    assertEquals(listOf("0", "2"), mapped.files.map { it.id })
    assertEquals(listOf(listOf("a"), listOf("z")), mapped.files.map { it.components })
  }

  @Test
  fun boundsLongNamesAndKeepsDifferentRawNamesDistinct() {
    val first = "x".repeat(1000).encodeUtf8()
    val second = ("x".repeat(999) + "y").encodeUtf8()
    val mapped = TorrentOutputMapping.from(document(listOf(listOf(first), listOf(second))))
    assertNotEquals(mapped.files[0].components, mapped.files[1].components)
    assertTrue(mapped.files.all { it.components.single().length == 65 })
    assertEquals(listOf("0", "1"), mapped.files.map { it.id })
  }
}
