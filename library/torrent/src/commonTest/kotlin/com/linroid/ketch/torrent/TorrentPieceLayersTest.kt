package com.linroid.ketch.torrent

import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TorrentPieceLayersTest {
  private val hashes = listOf(sha256Digest(ByteArray(16_384)), sha256Digest(byteArrayOf(1)))
  private val root = Sha256().update(hashes[0]).update(hashes[1]).digest().toByteString()
  private val layer = (hashes[0] + hashes[1]).toByteString()
  private val info = TorrentV2Info.parse(Bencode.encode(mapOf(
    "meta version" to 2L, "piece length" to 16_384L,
    "file tree" to mapOf("a" to file(), "b" to file())
  )))

  private fun file(): Map<String, Any> = mapOf("" to mapOf(
    "length" to 16_385L, "pieces root" to root
  ))

  @Test
  fun sharedRootsReuseOneValidatedLayerAndRespectByteLimit() {
    assertEquals(mapOf(root to layer),
      info.validatePieceLayers(mapOf(root to layer), maxBytes = 64))
    assertFailsWith<IllegalArgumentException> {
      info.validatePieceLayers(mapOf(root to layer), maxBytes = 63)
    }
  }

  @Test
  fun rejectsMissingExtraTruncatedAndCorruptLayers() {
    assertFailsWith<IllegalArgumentException> { info.validatePieceLayers(emptyMap()) }
    assertFailsWith<IllegalArgumentException> {
      info.validatePieceLayers(mapOf(root to layer, ByteArray(32).toByteString() to layer))
    }
    assertFailsWith<IllegalArgumentException> {
      info.validatePieceLayers(mapOf(root to layer.substring(0, 32)))
    }
    val corrupt = layer.toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }.toByteString()
    assertFailsWith<IllegalArgumentException> { info.validatePieceLayers(mapOf(root to corrupt)) }
  }

  @Test
  fun streamRejectsIncompleteExtraAndMalformedHashesWithoutLosingProgress() {
    val verifier = TorrentPieceLayerVerifier(16_385, 16_384, root.toByteArray())
    verifier.append(hashes[0])
    assertFailsWith<IllegalStateException> { verifier.verify() }
    assertFailsWith<IllegalArgumentException> { verifier.append(ByteArray(31)) }
    verifier.append(hashes[1])
    assertFailsWith<IllegalArgumentException> { verifier.append(hashes[0]) }
    assertTrue(verifier.verify())
    assertFailsWith<IllegalStateException> { verifier.verify() }
    assertFailsWith<IllegalStateException> { verifier.append(hashes[0]) }
    assertFailsWith<IllegalArgumentException> {
      TorrentPieceLayerVerifier(16_384, 16_384, root.toByteArray())
    }
  }
}
