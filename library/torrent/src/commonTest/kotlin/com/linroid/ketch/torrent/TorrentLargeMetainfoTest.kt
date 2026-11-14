package com.linroid.ketch.torrent

import com.linroid.ketch.torrent.metainfo.Bencode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Torrents within the file limit whose metainfo holds more than 100,000 bencode values, as deep
 * folders and hybrids do: every reader of a task's metainfo takes them, not only the v1 parser.
 */
class TorrentLargeMetainfoTest {
  private val config = TorrentConfig()
  private val tracker = "http://tracker.example/announce"

  /** A v1 torrent of [count] files eleven folders deep: thirteen values a file. */
  private fun deepV1(count: Int): ByteArray {
    val folders = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
    val files = (0 until count).map { index ->
      mapOf("length" to 1L, "path" to folders + "f$index")
    }
    return Bencode.encode(mapOf("announce" to tracker,
      "info" to mapOf("name" to "pack", "piece length" to 16_384L,
        "pieces" to ByteArray(20), "files" to files)))
  }

  @Test
  fun `a v1 torrent of many deep files lists its files and trackers`() {
    val metainfo = deepV1(9_000)

    val table = TorrentFileTable.parse(metainfo, config)

    assertEquals(9_000, table.files.size)
    assertTrue(!table.v2)
    assertEquals(listOf(listOf(tracker)), sourceTrackerTiers(metainfo, config.maxMetadataBytes))
  }

  @Test
  fun `a v2 torrent of many deep files lists its files`() {
    // Each file in eight folders of its own: twelve values a file.
    val tree = (0 until 9_000).associate { index ->
      val file: Map<String, Any> =
        mapOf("" to mapOf("length" to 1L, "pieces root" to ByteArray(32) { index.toByte() }))
      val nested = listOf("h", "g", "f", "e", "d", "c", "b").fold(mapOf("i" to file)) { inner, name ->
        mapOf(name to inner)
      }
      "d" + index.toString().padStart(5, '0') to nested
    }
    val metainfo = Bencode.encode(mapOf("announce" to tracker,
      "info" to mapOf("file tree" to tree, "meta version" to 2L, "name" to "pack",
        "piece length" to 16_384L)))

    val table = TorrentFileTable.parse(metainfo, config)

    assertEquals(9_000, table.files.size)
    assertTrue(table.v2)
  }
}
