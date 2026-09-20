package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TorrentContentLayoutTest {
  private fun info(lengths: List<Long>, pieceLength: Long = 16_384): TorrentV2Info {
    val tree = lengths.mapIndexed { index, length ->
      index.toString() to mapOf("" to buildMap<String, Any> {
        put("length", length)
        if (length > 0) put("pieces root", ByteArray(32))
      })
    }.toMap()
    return TorrentV2Info.parse(Bencode.encode(mapOf("meta version" to 2L,
      "piece length" to pieceLength, "file tree" to tree)))
  }

  @Test
  fun mapsFileTailsPaddingAndEmptyFilesWithoutCountingZerosAsPayload() {
    val layout = TorrentContentLayout.from(info(listOf(3, 0, 5)))
    assertEquals(8L, layout.payloadBytes)
    assertEquals(16_389L, layout.protocolBytes)
    assertEquals(2L, layout.pieceCount)
    assertEquals(listOf(TorrentContentLayout.Extent("0", 0, 3),
      TorrentContentLayout.Extent(null, 0, 16_381)), layout.pieceExtents(0))
    assertEquals(TorrentContentLayout.Extent("0", 0, 3), layout.v2Piece(0))
    assertEquals(TorrentContentLayout.Extent("2", 0, 5), layout.v2Piece(1))
    assertEquals(listOf(TorrentContentLayout.Extent(null, 0, 2),
      TorrentContentLayout.Extent("2", 0, 2)), layout.map(16_382, 4))
    assertEquals(listOf(TorrentContentLayout.Extent("2", 2, 3)), layout.map(16_386, 3))
  }

  @Test
  fun hybridUsesOriginalFileIdsAndSeparatesTrailingZerosFromV2Payload() {
    fun leaf(length: Long) = mapOf("" to mapOf("length" to length, "pieces root" to ByteArray(32)))
    val raw = mapOf("meta version" to 2L, "piece length" to 16_384L, "name" to "pack",
      "file tree" to mapOf("a" to leaf(1), "z" to leaf(2)),
      "files" to listOf(mapOf("path" to listOf("a"), "length" to 1L),
        mapOf("attr" to "p", "length" to 16_383L),
        mapOf("path" to listOf("z"), "length" to 2L),
        mapOf("attr" to "p", "length" to 16_382L)), "pieces" to ByteArray(40))
    val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to raw,
      "piece layers" to emptyMap<String, Any>())))
    val layout = TorrentContentLayout.from(document.info, document.hybrid)
    assertEquals(listOf("0", "2"), layout.files.map { it.id })
    assertEquals(3L, layout.payloadBytes)
    assertEquals(32_768L, layout.protocolBytes)
    assertEquals(listOf(TorrentContentLayout.Extent("2", 0, 2),
      TorrentContentLayout.Extent(null, 0, 16_382)), layout.pieceExtents(1))
    assertEquals(TorrentContentLayout.Extent("2", 0, 2), layout.v2Piece(1))
  }

  @Test
  fun supportsTenTiBArithmeticWithoutPieceSizedIndexArrays() {
    val length = 10L * 1024 * 1024 * 1024 * 1024
    val layout = TorrentContentLayout.from(info(listOf(length)))
    assertEquals(671_088_640L, layout.pieceCount)
    assertEquals(TorrentContentLayout.Extent("0", length - 16_384, 16_384),
      layout.v2Piece(layout.pieceCount - 1))
    assertEquals(listOf(TorrentContentLayout.Extent("0", length - 1, 1)), layout.map(length - 1, 1))
    assertFailsWith<IllegalArgumentException> { layout.map(Long.MAX_VALUE, 1) }
    assertFailsWith<IllegalArgumentException> { layout.map(1, Long.MAX_VALUE) }
    assertFailsWith<IllegalArgumentException> { layout.v2Piece(layout.pieceCount) }
  }

  @Test
  fun emptyLayoutsHaveNoPiecesAndAlignmentOverflowIsRejected() {
    val empty = TorrentContentLayout.from(info(listOf(0, 0)))
    assertEquals(0L, empty.pieceCount)
    assertEquals(emptyList(), empty.map(0, 0))
    assertFailsWith<IllegalArgumentException> { empty.v2Piece(0) }
    assertFailsWith<IllegalArgumentException> {
      TorrentContentLayout.from(info(listOf(Long.MAX_VALUE - 1, 1)))
    }
  }
}
