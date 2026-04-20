package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BencodeTest {

  @Test
  fun decode_integer() {
    val result = Bencode.decode("i42e".encodeToByteArray())
    assertEquals(42L, result)
  }

  @Test
  fun decode_negativeInteger() {
    val result = Bencode.decode("i-7e".encodeToByteArray())
    assertEquals(-7L, result)
  }

  @Test
  fun decode_zero() {
    val result = Bencode.decode("i0e".encodeToByteArray())
    assertEquals(0L, result)
  }

  @Test
  fun decode_string() {
    val result = Bencode.decode("4:spam".encodeToByteArray())
    assertIs<ByteArray>(result)
    assertEquals("spam", result.decodeToString())
  }

  @Test
  fun decode_emptyString() {
    val result = Bencode.decode("0:".encodeToByteArray())
    assertIs<ByteArray>(result)
    assertEquals("", result.decodeToString())
  }

  @Test
  fun decode_list() {
    val result = Bencode.decode("l4:spam4:eggse".encodeToByteArray())
    assertIs<List<*>>(result)
    assertEquals(2, result.size)
    assertEquals("spam", (result[0] as ByteArray).decodeToString())
    assertEquals("eggs", (result[1] as ByteArray).decodeToString())
  }

  @Test
  fun decode_dictionary() {
    val data = "d3:cow3:moo4:spam4:eggse".encodeToByteArray()
    val result = Bencode.decode(data)
    assertIs<Map<*, *>>(result)
    assertEquals(
      "moo",
      (result["cow"] as ByteArray).decodeToString(),
    )
    assertEquals(
      "eggs",
      (result["spam"] as ByteArray).decodeToString(),
    )
  }

  @Test
  fun decode_nestedStructure() {
    val data = "d4:listli1ei2ei3ee5:valuei42ee".encodeToByteArray()
    val result = Bencode.decode(data)
    assertIs<Map<*, *>>(result)
    val list = result["list"]
    assertIs<List<*>>(list)
    assertEquals(listOf(1L, 2L, 3L), list)
    assertEquals(42L, result["value"])
  }

  @Test
  fun encode_integer() {
    val encoded = Bencode.encode(42L)
    assertEquals("i42e", encoded.decodeToString())
  }

  @Test
  fun encode_string() {
    val encoded = Bencode.encode("spam")
    assertEquals("4:spam", encoded.decodeToString())
  }

  @Test
  fun encode_list() {
    val encoded = Bencode.encode(listOf("spam", "eggs"))
    assertEquals("l4:spam4:eggse", encoded.decodeToString())
  }

  @Test
  fun encode_dictionary() {
    val encoded = Bencode.encode(
      mapOf("cow" to "moo", "spam" to "eggs"),
    )
    // Keys must be sorted
    assertEquals(
      "d3:cow3:moo4:spam4:eggse",
      encoded.decodeToString(),
    )
  }

  @Test
  fun roundtrip_complexStructure() {
    val original = mapOf(
      "info" to mapOf(
        "name" to "test.txt",
        "piece length" to 262144L,
        "length" to 1024L,
      ),
      "announce" to "http://tracker.example.com/announce",
    )
    val encoded = Bencode.encode(original)
    @Suppress("UNCHECKED_CAST")
    val decoded = Bencode.decode(encoded) as Map<String, Any>
    @Suppress("UNCHECKED_CAST")
    val info = decoded["info"] as Map<String, Any>
    assertEquals(
      "test.txt",
      (info["name"] as ByteArray).decodeToString(),
    )
    assertEquals(262144L, info["piece length"])
    assertEquals(1024L, info["length"])
  }

  @Test
  fun decode_malformedInput_throws() {
    assertFailsWith<IllegalArgumentException> {
      Bencode.decode("x".encodeToByteArray())
    }
  }

  @Test
  fun decode_unterminatedList_throws() {
    assertFailsWith<IllegalArgumentException> {
      Bencode.decode("li42e".encodeToByteArray())
    }
  }
}
