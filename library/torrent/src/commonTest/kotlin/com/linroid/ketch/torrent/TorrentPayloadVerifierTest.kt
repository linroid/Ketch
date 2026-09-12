package com.linroid.ketch.torrent

import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TorrentPayloadVerifierTest {
  private fun parent(left: ByteArray, right: ByteArray) = sha256Digest(left + right)

  private fun document(
    payload: ByteArray,
    pieceLength: Long,
    root: ByteArray,
    layers: ByteArray? = null,
    v1Hash: ByteArray? = null,
  ): TorrentV2Document {
    val info = mutableMapOf<String, Any>("meta version" to 2L, "piece length" to pieceLength,
      "file tree" to mapOf("file" to mapOf("" to mapOf("length" to payload.size.toLong(),
        "pieces root" to root))))
    if (v1Hash != null) {
      info["name"] = "file"
      info["length"] = payload.size.toLong()
      info["pieces"] = v1Hash
    }
    return TorrentV2Document.parse(Bencode.encode(mapOf("info" to info,
      "piece layers" to if (layers == null) emptyMap() else mapOf(root.toByteString() to layers))))
  }

  @Test
  fun verifiesShortFinalPieceAtThePieceLayerHeight() {
    val payload = ByteArray(65_537) { it.toByte() }
    val leaves = (0..3).map { sha256Digest(payload.copyOfRange(it * 16_384, (it + 1) * 16_384)) }
    val first = parent(parent(leaves[0], leaves[1]), parent(leaves[2], leaves[3]))
    val zero = ByteArray(32)
    val last = parent(parent(sha256Digest(payload.copyOfRange(65_536, 65_537)), zero),
      parent(zero, zero))
    val verifier = TorrentPayloadVerifier(
      document(payload, 65_536, parent(first, last), first + last))
    val full = verifier.piece(0)
    full.update(payload, 0, 17)
    full.update(payload, 17, 65_536 - 17)
    assertTrue(full.verify())
    val tail = verifier.piece(1)
    tail.update(payload, 65_536, 1)
    assertTrue(tail.verify())
    val bad = verifier.piece(1)
    bad.update(byteArrayOf(42))
    assertFalse(bad.verify())
  }

  @Test
  fun smallFileUsesItsOwnTreeHeightAndRejectsBadInputWithoutConsumingIt() {
    val payload = ByteArray(16_385) { 3 }
    val root = parent(sha256Digest(payload.copyOfRange(0, 16_384)), sha256Digest(byteArrayOf(3)))
    val piece = TorrentPayloadVerifier(document(payload, 65_536, root)).piece(0)
    assertFailsWith<IllegalArgumentException> { piece.update(ByteArray(payload.size + 1)) }
    assertFailsWith<IllegalArgumentException> { piece.update(payload, -1, 1) }
    piece.update(payload, 0, 10)
    assertFailsWith<IllegalStateException> { piece.verify() }
    piece.update(payload, 10, payload.size - 10)
    assertTrue(piece.verify())
    assertFailsWith<IllegalStateException> { piece.verify() }
    assertFailsWith<IllegalStateException> { piece.update(byteArrayOf()) }
  }

  @Test
  fun hybridHashesVirtualPaddingWithoutRequiringItFromTheCaller() {
    val payload = byteArrayOf(9)
    val paddedHash = sha1Digest(payload + ByteArray(16_383))
    val info = mapOf("meta version" to 2L, "piece length" to 16_384L, "name" to "pack",
      "file tree" to mapOf("a" to mapOf("" to mapOf("length" to 1L,
        "pieces root" to sha256Digest(payload)))),
      "files" to listOf(mapOf("path" to listOf("a"), "length" to 1L),
        mapOf("attr" to "p", "length" to 16_383L)), "pieces" to paddedHash)
    val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to info,
      "piece layers" to emptyMap<String, Any>())))
    val piece = TorrentPayloadVerifier(document).piece(0)
    piece.update(payload)
    assertTrue(piece.verify())
  }

  @Test
  fun hybridRequiresBothHashesToMatch() {
    val payload = byteArrayOf(7)
    val good = document(payload, 16_384, sha256Digest(payload), v1Hash = sha1Digest(payload))
    val piece = TorrentPayloadVerifier(good).piece(0)
    piece.update(payload)
    assertTrue(piece.verify())
    val conflict = document(payload, 16_384, sha256Digest(payload), v1Hash = ByteArray(20))
    val bad = TorrentPayloadVerifier(conflict).piece(0)
    bad.update(payload)
    assertFalse(bad.verify())
  }
}
