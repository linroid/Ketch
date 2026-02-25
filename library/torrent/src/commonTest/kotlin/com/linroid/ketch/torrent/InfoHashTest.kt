package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InfoHashTest {

  @Test
  fun fromHex_validHash() {
    val hash = InfoHash.fromHex(
      "AABBCCDDEE11223344556677889900AABBCCDDEE",
    )
    assertEquals(
      "aabbccddee11223344556677889900aabbccddee",
      hash.hex,
    )
  }

  @Test
  fun fromBytes_roundtrip() {
    val bytes = ByteArray(20) { it.toByte() }
    val hash = InfoHash.fromBytes(bytes)
    val roundtripped = hash.toBytes()
    assertEquals(bytes.toList(), roundtripped.toList())
  }

  @Test
  fun fromBytes_wrongSize_throws() {
    assertFailsWith<IllegalArgumentException> {
      InfoHash.fromBytes(ByteArray(10))
    }
  }

  @Test
  fun constructor_wrongLength_throws() {
    assertFailsWith<IllegalArgumentException> {
      InfoHash("abc")
    }
  }

  @Test
  fun constructor_invalidChars_throws() {
    assertFailsWith<IllegalArgumentException> {
      InfoHash("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz")
    }
  }

  @Test
  fun toString_returnsHex() {
    val hash = InfoHash.fromHex(
      "0123456789abcdef0123456789abcdef01234567",
    )
    assertEquals(
      "0123456789abcdef0123456789abcdef01234567",
      hash.toString(),
    )
  }
}
