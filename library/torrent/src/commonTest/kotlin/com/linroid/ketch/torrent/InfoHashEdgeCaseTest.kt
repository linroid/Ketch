package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Additional edge case tests for [InfoHash] beyond the basics
 * covered in [InfoHashTest].
 */
class InfoHashEdgeCaseTest {

  @Test
  fun fromHex_lowercasesUppercase() {
    val hash = InfoHash.fromHex(
      "AABBCCDDEE11223344556677889900AABBCCDDEE",
    )
    assertEquals(
      "aabbccddee11223344556677889900aabbccddee",
      hash.hex,
    )
  }

  @Test
  fun fromHex_mixedCase() {
    val hash = InfoHash.fromHex(
      "aAbBcCdDeE11223344556677889900AaBbCcDdEe",
    )
    assertEquals(
      "aabbccddee11223344556677889900aabbccddee",
      hash.hex,
    )
  }

  @Test
  fun constructor_rejectsUppercase() {
    // Constructor requires lowercase; fromHex normalizes
    assertFailsWith<IllegalArgumentException> {
      InfoHash("AABBCCDDEE11223344556677889900AABBCCDDEE")
    }
  }

  @Test
  fun constructor_rejects39Characters() {
    assertFailsWith<IllegalArgumentException> {
      InfoHash("aabbccddee11223344556677889900aabbccdde")
    }
  }

  @Test
  fun constructor_rejects41Characters() {
    assertFailsWith<IllegalArgumentException> {
      InfoHash("aabbccddee11223344556677889900aabbccddeef")
    }
  }

  @Test
  fun constructor_rejectsEmptyString() {
    assertFailsWith<IllegalArgumentException> {
      InfoHash("")
    }
  }

  @Test
  fun constructor_rejectsNonHexAtEnd() {
    // 39 valid hex + 1 invalid character 'g'
    assertFailsWith<IllegalArgumentException> {
      InfoHash("0123456789abcdef0123456789abcdef0123456g")
    }
  }

  @Test
  fun fromBytes_allZeros() {
    val bytes = ByteArray(20)
    val hash = InfoHash.fromBytes(bytes)
    assertEquals(
      "0000000000000000000000000000000000000000",
      hash.hex,
    )
  }

  @Test
  fun fromBytes_allOnes() {
    val bytes = ByteArray(20) { 0xFF.toByte() }
    val hash = InfoHash.fromBytes(bytes)
    assertEquals(
      "ffffffffffffffffffffffffffffffffffffffff",
      hash.hex,
    )
  }

  @Test
  fun fromBytes_rejects19Bytes() {
    assertFailsWith<IllegalArgumentException> {
      InfoHash.fromBytes(ByteArray(19))
    }
  }

  @Test
  fun fromBytes_rejects21Bytes() {
    assertFailsWith<IllegalArgumentException> {
      InfoHash.fromBytes(ByteArray(21))
    }
  }

  @Test
  fun fromBytes_rejectsEmptyArray() {
    assertFailsWith<IllegalArgumentException> {
      InfoHash.fromBytes(ByteArray(0))
    }
  }

  @Test
  fun toBytes_allZerosHash() {
    val hash = InfoHash(
      "0000000000000000000000000000000000000000",
    )
    val bytes = hash.toBytes()
    assertEquals(20, bytes.size)
    for (b in bytes) {
      assertEquals(0, b.toInt())
    }
  }

  @Test
  fun toBytes_preservesHighBits() {
    // 0xff = -1 as signed byte
    val hash = InfoHash(
      "ffffffffffffffffffffffffffffffffffffffff",
    )
    val bytes = hash.toBytes()
    for (b in bytes) {
      assertEquals(0xFF.toByte(), b)
    }
  }

  @Test
  fun equality_sameHex_areEqual() {
    val a = InfoHash.fromHex(
      "0123456789abcdef0123456789abcdef01234567",
    )
    val b = InfoHash.fromHex(
      "0123456789ABCDEF0123456789ABCDEF01234567",
    )
    assertEquals(a, b)
  }

  @Test
  fun equality_fromBytesAndFromHex_areEqual() {
    val bytes = ByteArray(20) { (it * 13).toByte() }
    val fromBytes = InfoHash.fromBytes(bytes)
    val fromHex = InfoHash.fromHex(fromBytes.hex)
    assertEquals(fromBytes, fromHex)
  }
}
