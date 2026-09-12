package com.linroid.ketch.torrent

import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TorrentV2DocumentTest {
  private val hashes = sha256Digest(ByteArray(16_384)) + sha256Digest(byteArrayOf(1))
  private val root = sha256Digest(hashes).toByteString()
  private val info = mapOf("meta version" to 2L, "piece length" to 16_384L,
    "file tree" to mapOf("file" to mapOf("" to mapOf(
      "length" to 16_385L, "pieces root" to root
    ))))

  private fun document(layers: Any = mapOf(root to hashes)): ByteArray =
    Bencode.encode(mapOf("info" to info, "piece layers" to layers, "comment" to "caller context"))

  @Test
  fun importsOnlyAfterLayerAuthenticationAndHashesExactInfoRange() {
    val bytes = document()
    val raw = Bencode.encode(info)
    val expected = TorrentIdentity(v2 = V2InfoHash.fromBytes(sha256Digest(raw)))
    val originalLayer = hashes.toByteString()
    val parsed = TorrentV2Document.parse(bytes, expectedIdentity = expected)
    assertContentEquals(raw, parsed.info.rawInfo.toByteArray())
    assertEquals(expected.v2, parsed.info.hash)
    assertEquals(hashes.toByteString(), parsed.pieceLayers[root])
    bytes.fill(0)
    hashes.fill(0)
    assertContentEquals(raw, parsed.info.rawInfo.toByteArray())
    assertEquals(originalLayer, parsed.pieceLayers.getValue(root))
  }

  @Test
  fun importedDocumentsCannotUseInfoOnlyOrUnauthenticatedLayers() {
    assertFailsWith<IllegalArgumentException> {
      TorrentV2Document.parse(Bencode.encode(mapOf("info" to info)))
    }
    assertFailsWith<IllegalArgumentException> {
      TorrentV2Document.parse(document(emptyMap<String, Any>()))
    }
    assertFailsWith<IllegalArgumentException> { TorrentV2Document.parse(document(1L)) }
    assertFailsWith<IllegalArgumentException> {
      TorrentV2Document.parse(document(mapOf(root to hashes.copyOf(32))))
    }
    assertFailsWith<IllegalArgumentException> {
      TorrentV2Document.parse(document(mapOf(root to ByteArray(64))))
    }
  }

  @Test
  fun emptyAndSmallFilesNeedAnEmptyLayerDictionary() {
    for (length in listOf(0L, 1L, 16_384L)) {
      val properties = buildMap<String, Any> {
        put("length", length)
        if (length > 0) put("pieces root", sha256Digest(ByteArray(length.toInt())))
      }
      val rawInfo = mapOf("meta version" to 2L, "piece length" to 16_384L,
        "file tree" to mapOf("file" to mapOf("" to properties)))
      val bytes = Bencode.encode(mapOf("info" to rawInfo,
        "piece layers" to emptyMap<String, Any>()))
      val parsed = TorrentV2Document.parse(bytes, maxLayerBytes = 0)
      assertEquals(length, parsed.info.totalBytes)
      assertEquals(emptyMap(), parsed.pieceLayers)
    }
  }

  @Test
  fun enforcesIndependentEnvelopeInfoAndLayerLimits() {
    val bytes = document()
    val raw = Bencode.encode(info)
    assertFailsWith<IllegalArgumentException> {
      TorrentV2Document.parse(bytes, maxDocumentBytes = bytes.size - 1)
    }
    assertFailsWith<IllegalArgumentException> {
      TorrentV2Document.parse(bytes, maxInfoBytes = raw.size - 1)
    }
    assertFailsWith<IllegalArgumentException> { TorrentV2Document.parse(bytes, maxLayerBytes = 63) }
    assertEquals(1, TorrentV2Document.parse(bytes, maxDocumentBytes = bytes.size,
      maxInfoBytes = raw.size, maxLayerBytes = 64).info.files.size)
  }

  @Test
  fun doesNotAcceptUnvalidatedHybridFieldsOrV1Topics() {
    assertFailsWith<IllegalArgumentException> {
      TorrentV2Document.parse(Bencode.encode(mapOf("info" to (info + ("pieces" to byteArrayOf())),
        "piece layers" to mapOf(root to hashes))))
    }
    val raw = Bencode.encode(info)
    assertFailsWith<IllegalArgumentException> {
      TorrentV2Document.parse(document(), expectedIdentity = TorrentIdentity(
        InfoHash.fromBytes(sha1Digest(raw)), V2InfoHash.fromBytes(sha256Digest(raw))))
    }
  }
}
