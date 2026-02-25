package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Additional edge case tests for [Bencode] beyond the basics
 * covered in [BencodeTest].
 */
class BencodeEdgeCaseTest {

  // -- Integer edge cases --

  @Test
  fun decode_largePositiveInteger() {
    val result = Bencode.decode(
      "i9223372036854775807e".encodeToByteArray(),
    )
    assertEquals(Long.MAX_VALUE, result)
  }

  @Test
  fun decode_largeNegativeInteger() {
    val result = Bencode.decode(
      "i-9223372036854775808e".encodeToByteArray(),
    )
    assertEquals(Long.MIN_VALUE, result)
  }

  @Test
  fun decode_emptyInteger_throws() {
    assertFailsWith<IllegalArgumentException> {
      Bencode.decode("ie".encodeToByteArray())
    }
  }

  @Test
  fun decode_nonNumericInteger_throws() {
    assertFailsWith<IllegalArgumentException> {
      Bencode.decode("iabce".encodeToByteArray())
    }
  }

  @Test
  fun decode_integerMissingEndMarker_throws() {
    assertFailsWith<IllegalArgumentException> {
      Bencode.decode("i42".encodeToByteArray())
    }
  }

  // -- String edge cases --

  @Test
  fun decode_binaryDataInString() {
    // String with bytes 0x00, 0xFF, 0x80
    val binary = byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte())
    val encoded = "3:".encodeToByteArray() + binary
    val result = Bencode.decode(encoded)
    assertIs<ByteArray>(result)
    assertContentEquals(binary, result)
  }

  @Test
  fun decode_longString() {
    val str = "a".repeat(1000)
    val encoded = "1000:$str".encodeToByteArray()
    val result = Bencode.decode(encoded)
    assertIs<ByteArray>(result)
    assertEquals(str, result.decodeToString())
  }

  @Test
  fun decode_stringOverflowsData_throws() {
    // Claims 10 bytes but only 3 available
    assertFailsWith<IllegalArgumentException> {
      Bencode.decode("10:abc".encodeToByteArray())
    }
  }

  @Test
  fun decode_invalidStringLength_throws() {
    assertFailsWith<IllegalArgumentException> {
      Bencode.decode("abc:data".encodeToByteArray())
    }
  }

  // -- List edge cases --

  @Test
  fun decode_emptyList() {
    val result = Bencode.decode("le".encodeToByteArray())
    assertIs<List<*>>(result)
    assertEquals(0, result.size)
  }

  @Test
  fun decode_nestedEmptyLists() {
    val result = Bencode.decode("llleee".encodeToByteArray())
    assertIs<List<*>>(result)
    val inner = result[0]
    assertIs<List<*>>(inner)
    val innermost = inner[0]
    assertIs<List<*>>(innermost)
    assertEquals(0, innermost.size)
  }

  @Test
  fun decode_listWithMixedTypes() {
    // list containing an integer, string, and another list
    val data = "li42e3:fooli1eee".encodeToByteArray()
    val result = Bencode.decode(data)
    assertIs<List<*>>(result)
    assertEquals(3, result.size)
    assertEquals(42L, result[0])
    assertEquals("foo", (result[1] as ByteArray).decodeToString())
    val innerList = result[2]
    assertIs<List<*>>(innerList)
    assertEquals(listOf(1L), innerList)
  }

  // -- Dictionary edge cases --

  @Test
  fun decode_emptyDictionary() {
    val result = Bencode.decode("de".encodeToByteArray())
    assertIs<Map<*, *>>(result)
    assertEquals(0, result.size)
  }

  @Test
  fun decode_unterminatedDictionary_throws() {
    assertFailsWith<IllegalArgumentException> {
      Bencode.decode("d3:key5:value".encodeToByteArray())
    }
  }

  @Test
  fun decode_deeplyNestedStructure() {
    // d -> d -> d -> d -> i42e
    val data = "d1:ad1:bd1:cd1:di42eeeee".encodeToByteArray()
    val result = Bencode.decode(data)
    @Suppress("UNCHECKED_CAST")
    val a = (result as Map<String, Any>)["a"] as Map<String, Any>
    @Suppress("UNCHECKED_CAST")
    val b = a["b"] as Map<String, Any>
    @Suppress("UNCHECKED_CAST")
    val c = b["c"] as Map<String, Any>
    assertEquals(42L, c["d"])
  }

  // -- Encoding edge cases --

  @Test
  fun encode_intValue() {
    // Verify Int (not Long) encoding works
    val encoded = Bencode.encode(42)
    assertEquals("i42e", encoded.decodeToString())
  }

  @Test
  fun encode_negativeInteger() {
    val encoded = Bencode.encode(-1L)
    assertEquals("i-1e", encoded.decodeToString())
  }

  @Test
  fun encode_zeroLengthByteArray() {
    val encoded = Bencode.encode(ByteArray(0))
    assertEquals("0:", encoded.decodeToString())
  }

  @Test
  fun encode_emptyStringValue() {
    val encoded = Bencode.encode("")
    assertEquals("0:", encoded.decodeToString())
  }

  @Test
  fun encode_emptyList() {
    val encoded = Bencode.encode(emptyList<Any>())
    assertEquals("le", encoded.decodeToString())
  }

  @Test
  fun encode_emptyDictionary() {
    val encoded = Bencode.encode(emptyMap<String, Any>())
    assertEquals("de", encoded.decodeToString())
  }

  @Test
  fun encode_dictionaryKeysSorted() {
    val encoded = Bencode.encode(
      mapOf("z" to 1L, "a" to 2L, "m" to 3L),
    )
    // Keys must be sorted: a=2, m=3, z=1
    assertEquals("d1:ai2e1:mi3e1:zi1ee", encoded.decodeToString())
  }

  @Test
  fun encode_listWithNulls_skipsNulls() {
    val list = listOf("a", null, "b")
    val encoded = Bencode.encode(list)
    assertEquals("l1:a1:be", encoded.decodeToString())
  }

  @Test
  fun encode_unsupportedType_throws() {
    assertFailsWith<IllegalArgumentException> {
      Bencode.encode(3.14)
    }
  }

  @Test
  fun encode_byteArrayKey_inDictionary() {
    val dict = mapOf(
      "key".encodeToByteArray() to 1L,
    )
    val encoded = Bencode.encode(dict)
    assertEquals("d3:keyi1ee", encoded.decodeToString())
  }

  // -- Round-trip edge cases --

  @Test
  fun roundtrip_emptyString() {
    val encoded = Bencode.encode("")
    val decoded = Bencode.decode(encoded)
    assertIs<ByteArray>(decoded)
    assertEquals("", decoded.decodeToString())
  }

  @Test
  fun roundtrip_nestedDictWithList() {
    val original = mapOf(
      "data" to listOf(1L, 2L, 3L),
      "meta" to mapOf("key" to "val"),
    )
    val encoded = Bencode.encode(original)
    @Suppress("UNCHECKED_CAST")
    val decoded = Bencode.decode(encoded) as Map<String, Any>
    assertEquals(listOf(1L, 2L, 3L), decoded["data"])
    @Suppress("UNCHECKED_CAST")
    val meta = decoded["meta"] as Map<String, Any>
    assertEquals(
      "val",
      (meta["key"] as ByteArray).decodeToString(),
    )
  }

  // -- Empty/malformed data --

  @Test
  fun decode_emptyByteArray_throws() {
    assertFailsWith<IllegalArgumentException> {
      Bencode.decode(ByteArray(0))
    }
  }

  @Test
  fun decode_unexpectedByte_throws() {
    assertFailsWith<IllegalArgumentException> {
      // '!' is not a valid bencode start character
      Bencode.decode("!".encodeToByteArray())
    }
  }
}
