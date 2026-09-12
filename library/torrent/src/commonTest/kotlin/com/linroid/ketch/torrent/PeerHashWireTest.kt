package com.linroid.ketch.torrent

import okio.Buffer
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class PeerHashWireTest {
  private val root = ByteArray(32) { it.toByte() }.toByteString()

  @Test
  fun requestAndRejectUseExactBigEndianWireFieldsIncludingUnsignedIndex() {
    val selector = PeerHashSelector(root, 1, 0xffff_fe00L, 512, 12)
    val fields = root.toByteArray() + byteArrayOf(0, 0, 0, 1,
      -1, -1, -2, 0, 0, 0, 2, 0, 0, 0, 0, 12)
    for ((id, message) in listOf(21 to PeerHashMessage.Request(selector),
      23 to PeerHashMessage.Reject(selector))) {
      val encoded = PeerWire.encode(PeerHashWire.encode(message))
      assertContentEquals(byteArrayOf(0, 0, 0, 49, id.toByte()) + fields, encoded)
      val outer = assertIs<PeerMessage.Unknown>(
        PeerWire.decode(encoded.copyOfRange(4, encoded.size)))
      assertEquals(message, PeerHashWire.decode(outer))
    }
  }

  @Test
  fun responseOmitsCoveredProofLayersButPreservesTheirCountInTheSelector() {
    // Eight base hashes cover the first two requested proof layers; only three uncles follow.
    val selector = PeerHashSelector(root, 0, 8, 8, 5)
    val hashes = ByteArray(11 * 32) { (it * 17).toByte() }.toByteString()
    val message = PeerHashMessage.Hashes(selector, hashes)
    val encoded = PeerHashWire.encode(message)
    assertEquals(22, encoded.id)
    assertEquals(48 + 11 * 32, encoded.payload.size)
    assertEquals(message, PeerHashWire.decode(encoded))
    for (proof in 0..2) {
      val covered = selector.copy(proofLayers = proof)
      assertEquals(8, covered.hashCount)
      assertEquals(PeerHashMessage.Hashes(covered, ByteArray(256).toByteString()),
        PeerHashWire.decode(PeerHashWire.encode(
          PeerHashMessage.Hashes(covered, ByteArray(256).toByteString()))))
    }
  }

  @Test
  fun malformedCoordinatesAndResponseSizesAreRejectedBeforeHashAllocation() {
    fun request(base: Int = 0, index: Int = 0, length: Int = 2, proof: Int = 0) =
      PeerMessage.Unknown(21, Buffer().write(root).writeInt(base).writeInt(index)
        .writeInt(length).writeInt(proof).readByteArray())
    for (invalid in listOf(request(base = -1), request(base = 64), request(proof = 64),
      request(base = 63, proof = 1), request(index = 1), request(length = 1),
      request(length = 3), request(length = 1024), request(length = Int.MIN_VALUE))) {
      assertFailsWith<IllegalArgumentException> { PeerHashWire.decode(invalid) }
    }
    val valid = request()
    for (size in listOf(0, 47, 49)) {
      assertFailsWith<IllegalArgumentException> {
        PeerHashWire.decode(PeerMessage.Unknown(21, valid.payload.copyOf(size)))
      }
    }
    for (size in listOf(0, 32, 63, 65, 96)) {
      assertFailsWith<IllegalArgumentException> {
        PeerHashWire.decode(PeerMessage.Unknown(22, valid.payload + ByteArray(size)))
      }
    }
    assertFailsWith<IllegalArgumentException> {
      PeerHashWire.encode(PeerHashMessage.Hashes(PeerHashSelector(root, 0, 0, 2, 0),
        ByteArray(32).toByteString()))
    }
    assertNull(PeerHashWire.decode(PeerMessage.Unknown(24, ByteArray(0))))
  }
}
