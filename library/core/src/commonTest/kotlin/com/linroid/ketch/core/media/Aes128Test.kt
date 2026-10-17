package com.linroid.ketch.core.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Aes128Test {
  @Test
  fun decrypt_nistCbcVector_returnsPlaintextAndHoldsLastBlock() {
    // NIST SP 800-38A, F.2.2 CBC-AES128.Decrypt.
    val decryptor = Aes128CbcDecryptor(hex("2b7e151628aed2a6abf7158809cf4f3c"),
      hex("000102030405060708090a0b0c0d0e0f"))
    val plain = decryptor.update(hex(
      "7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b2" +
        "73bed6b8e3c1743b7116e69e222295163ff1caa1681fac09120eca307586e1a7"
    ))
    assertContentEquals(hex(
      "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
        "30c81c46a35ce411e5fbc1191a0a52ef"
    ), plain)
    // The vector has no PKCS#7 padding, so the held last block is refused.
    assertFailsWith<IllegalArgumentException> { decryptor.finish() }
  }

  @Test
  fun decrypt_paddedInAnyChunks_stripsPadding() {
    val cipher = hex("b55739e57a2df67b7fbf13796ac52505cee24163257f6a44d12746d587f58a68" +
      "0f4cf2d949a74700193f255245ffbb21")
    for (chunk in listOf(1, 5, 16, 17, cipher.size)) {
      val decryptor = Aes128CbcDecryptor(KEY, hex("0f0e0d0c0b0a09080706050403020100"))
      val out = mutableListOf<Byte>()
      cipher.toList().chunked(chunk).forEach { out += decryptor.update(it.toByteArray()).toList() }
      out += decryptor.finish().toList()
      assertEquals("exactly sixteen.and a bit more than two blocks",
        out.toByteArray().decodeToString())
    }
  }

  @Test
  fun decrypt_shortPlaintexts_matchOpenssl() {
    assertEquals("first", decrypt("31986406a37445c5762cb21866bada77", 7))
    assertEquals("second segment!!",
      decrypt("26ecb304c12aeecb621951aeb11b498596802b21dd75f4e849518787d666e4ae", 8))
  }

  @Test
  fun decrypt_truncatedOrWrongKey_isRefused() {
    val truncated = Aes128CbcDecryptor(KEY, ByteArray(16))
    truncated.update(hex("31986406a37445c5762cb21866bada"))
    assertFailsWith<IllegalArgumentException> { truncated.finish() }
    val wrong = Aes128CbcDecryptor(ByteArray(16), ByteArray(16))
    wrong.update(hex("26ecb304c12aeecb621951aeb11b498596802b21dd75f4e849518787d666e4ae"))
    assertFailsWith<IllegalArgumentException> { wrong.finish() }
  }

  private fun decrypt(cipher: String, sequence: Int): String {
    val iv = ByteArray(16).also { it[15] = sequence.toByte() }
    val decryptor = Aes128CbcDecryptor(KEY, iv)
    return (decryptor.update(hex(cipher)) + decryptor.finish()).decodeToString()
  }

  private companion object {
    val KEY = hex("000102030405060708090a0b0c0d0e0f")
  }
}

internal fun hex(value: String): ByteArray =
  ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
