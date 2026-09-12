package com.linroid.ketch.torrent

import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TorrentV2InfoTest {
  private val root = ByteArray(32) { it.toByte() }

  private fun encoded(tree: Any, pieceLength: Long = 16_384, version: Long = 2): ByteArray =
    Bencode.encode(mapOf("meta version" to version, "piece length" to pieceLength,
      "file tree" to tree))

  private fun file(length: Long, hash: ByteArray? = root): Map<String, Any> = mapOf("" to buildMap {
    put("length", length)
    hash?.let { put("pieces root", it) }
  })

  @Test
  fun preservesRawPathsAndInfoBytesWithoutTreatingThemAsOutputPaths() {
    val component = byteArrayOf(0xff.toByte(), 0x80.toByte()).toByteString()
    val raw = encoded(mapOf("dir" to mapOf(component to file(7)), "empty" to file(0, null)))
    val info = TorrentV2Info.parse(raw)
    assertContentEquals(raw, info.rawInfo.toByteArray())
    assertEquals(V2InfoHash.fromBytes(sha256Digest(raw)), info.hash)
    assertEquals(component, info.files[0].path[1])
    assertEquals(7L, info.totalBytes)
    assertEquals(root.toByteString(), info.files[0].piecesRoot)
    assertNull(info.files[1].piecesRoot)
    raw.fill(0)
    root.fill(0)
    assertEquals(1, info.files[0].piecesRoot!![1].toInt())
  }

  @Test
  fun authenticatesEverySuppliedTopicBeforeDecodingTheTree() {
    val raw = encoded(mapOf("a" to file(1)))
    val expected = TorrentIdentity(InfoHash.fromBytes(sha1Digest(raw)),
      V2InfoHash.fromBytes(sha256Digest(raw)))
    assertEquals(expected.v2, TorrentV2Info.parse(raw, expectedIdentity = expected).hash)
    assertFailsWith<IllegalArgumentException> {
      TorrentV2Info.parse(raw, expectedIdentity = expected.copy(v1 = InfoHash("00".repeat(20))))
    }
    assertFailsWith<IllegalArgumentException> {
      TorrentV2Info.parse(raw, expectedIdentity = expected.copy(v2 = V2InfoHash("00".repeat(32))))
    }
  }

  @Test
  fun versionIsReportedBeforeVersionSpecificStructureErrors() {
    val error = assertFailsWith<UnsupportedTorrentMetaVersion> {
      TorrentV2Info.parse(Bencode.encode(mapOf("meta version" to 3L)))
    }
    assertEquals(3L, error.version)
  }

  @Test
  fun rejectsInvalidTreesRootsLengthsAndPieceGeometry() {
    val malformed = listOf(
      emptyMap<String, Any>(), file(1),
      mapOf("a" to mapOf("" to mapOf("length" to 0L), "child" to file(0, null))),
      mapOf("a" to file(0)), mapOf("a" to file(1, null)),
      mapOf("a" to file(1, ByteArray(31))), mapOf("a" to file(-1)),
      mapOf("a" to file(Long.MAX_VALUE), "b" to file(1)),
      mapOf("a" to mapOf("" to mapOf("attr" to "l", "length" to 0L))),
      mapOf("a" to 1L)
    )
    for (tree in malformed) {
      assertFailsWith<IllegalArgumentException> { TorrentV2Info.parse(encoded(tree)) }
    }
    for (piece in listOf(0L, 16_383L, 16_385L, Long.MAX_VALUE)) {
      assertFailsWith<IllegalArgumentException> {
        TorrentV2Info.parse(encoded(mapOf("a" to file(1)), piece))
      }
    }
  }

  @Test
  fun enforcesByteNodeAndFileLimitsBeforeReturningMetadata() {
    val raw = encoded(mapOf("a" to file(1), "b" to file(1)))
    assertFailsWith<IllegalArgumentException> { TorrentV2Info.parse(raw, maxBytes = raw.size - 1) }
    assertFailsWith<IllegalArgumentException> { TorrentV2Info.parse(raw, maxNodes = 2) }
    assertFailsWith<IllegalArgumentException> { TorrentV2Info.parse(raw, maxFiles = 1) }
    assertEquals(2, TorrentV2Info.parse(raw, maxBytes = raw.size, maxFiles = 2).files.size)
  }
}
