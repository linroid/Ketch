package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PeerWireTest {
  private val hash = InfoHash.fromBytes(ByteArray(20))
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "file", "piece length" to 16384L, "length" to 16387L, "pieces" to ByteArray(40)
  ))))

  @Test
  fun handshake_validatesProtocolAndSwarm() {
    val bytes = PeerWire.encodeHandshake(PeerHandshake(hash, ByteArray(20) { 7 }, true, true))
    assertEquals(68, bytes.size)
    val result = PeerWire.decodeHandshake(bytes, hash)
    assertContentEquals(ByteArray(20) { 7 }, result.peerId)
    assertTrue(result.extensions && result.dht)
    assertFailsWith<IllegalArgumentException> {
      PeerWire.decodeHandshake(bytes, InfoHash.fromBytes(ByteArray(20) { 1 }))
    }
    bytes[1] = 0
    assertFailsWith<IllegalArgumentException> { PeerWire.decodeHandshake(bytes, hash) }
  }

  @Test
  fun piece_shortFinalBlockAcceptedAndOutOfBoundsRejected() {
    val encoded = PeerWire.encode(PeerMessage.Piece(1, 0, byteArrayOf(1, 2, 3)), metadata)
    val piece = assertIs<PeerMessage.Piece>(
      PeerWire.decode(encoded.copyOfRange(4, encoded.size), metadata)
    )
    assertEquals(1, piece.index)
    assertContentEquals(byteArrayOf(1, 2, 3), piece.bytes)
    assertFailsWith<IllegalArgumentException> {
      PeerWire.encode(PeerMessage.Request(1, 0, 4), metadata)
    }
    assertFailsWith<IllegalArgumentException> {
      PeerWire.encode(PeerMessage.Request(2, 0, 1), metadata)
    }
    assertFailsWith<IllegalArgumentException> {
      PeerWire.encode(PeerMessage.Request(0, -1, 1), metadata)
    }
    assertFailsWith<IllegalArgumentException> {
      PeerWire.encode(PeerMessage.Request(0, 0, 16385), metadata)
    }
  }

  @Test
  fun decode_malformedLengthsAndBitfieldsRejected() {
    for (payload in listOf(byteArrayOf(0, 1), byteArrayOf(4), byteArrayOf(6, 0),
      byteArrayOf(7, 0), byteArrayOf(9, 0), byteArrayOf(20))) {
      assertFailsWith<IllegalArgumentException> { PeerWire.decode(payload, metadata) }
    }
    assertFailsWith<IllegalArgumentException> {
      PeerWire.decode(byteArrayOf(5, 1), metadata)
    }
    assertFailsWith<IllegalArgumentException> {
      PeerWire.decode(byteArrayOf(5, -64, 0), metadata)
    }
    assertIs<PeerMessage.Bitfield>(PeerWire.decode(byteArrayOf(5, -64), metadata))
  }

  @Test
  fun read_rejectsHugeFrameBeforeReadingPayload() = runTest {
    val requested = mutableListOf<Int>()
    val connection = object : TorrentConnection {
      override val remote = PeerEndpoint("127.0.0.1", 1)
      override suspend fun readExactly(size: Int): ByteArray {
        requested.add(size)
        return Buffer().writeInt(Int.MAX_VALUE).readByteArray()
      }
      override suspend fun write(bytes: ByteArray) = Unit
      override fun close() = Unit
    }
    assertFailsWith<IllegalArgumentException> { PeerWire(connection).read() }
    assertEquals(listOf(4), requested)
  }

  @Test
  fun messages_knownWireEncodingAndUnknownExtensions() {
    assertContentEquals(
      byteArrayOf(0, 0, 0, 1, 2),
      PeerWire.encode(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
    )
    assertContentEquals(byteArrayOf(0, 0, 0, 0), PeerWire.encode(PeerMessage.KeepAlive))
    val unknown = assertIs<PeerMessage.Unknown>(PeerWire.decode(byteArrayOf(99, 1, 2)))
    assertContentEquals(byteArrayOf(1, 2), unknown.payload)
  }
}
