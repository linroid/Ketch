package com.linroid.ketch.digest

import com.linroid.ketch.api.ChecksumAlgorithm
import com.linroid.ketch.core.digest.Md5
import com.linroid.ketch.core.digest.Sha512
import com.linroid.ketch.core.digest.newDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Known answers from RFC 1321 (MD5) and FIPS 180 (SHA-1, SHA-512), for whole and split input. */
class DigestVectorsTest {
  @Test
  fun md5_rfc1321Vectors() {
    check(
      ChecksumAlgorithm.MD5,
      "" to "d41d8cd98f00b204e9800998ecf8427e",
      "a" to "0cc175b9c0f1b6a831c399e269772661",
      "abc" to "900150983cd24fb0d6963f7d28e17f72",
      "message digest" to "f96b697d7cb7938d525a2f31aaf161d0",
      "abcdefghijklmnopqrstuvwxyz" to "c3fcd3d76192e4007dfb496cca67e13b",
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" to
        "d174ab98d277d9f5a5611c2c9f419d9f",
      "1234567890".repeat(8) to "57edf4a22be3c955ac49da2e2107b67a",
    )
  }

  @Test
  fun sha1_fips180Vectors() {
    check(
      ChecksumAlgorithm.SHA_1,
      "" to "da39a3ee5e6b4b0d3255bfef95601890afd80709",
      "abc" to "a9993e364706816aba3e25717850c26c9cd0d89d",
      "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" to
        "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
    )
  }

  @Test
  fun sha512_fips180Vectors() {
    check(
      ChecksumAlgorithm.SHA_512,
      "" to "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
        "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
      "abc" to "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
        "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
      ("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmn" +
        "hijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu") to
        "8e959b75dae313da8cf4f72814fc143f8f7779c6eb9f7fa17299aeadb6889018" +
        "501d289e4900f7e4331b99dec4b5433ac7d329eeb6dd26545e96e55b874be909",
    )
  }

  @Test
  fun millionLetterA_streamedInBlocks() {
    val expected = mapOf(
      ChecksumAlgorithm.MD5 to "7707d6ae4e027c70eea2a935c2296f21",
      ChecksumAlgorithm.SHA_1 to "34aa973cd4c4daa4f61eeb2bdbad27316534016f",
      ChecksumAlgorithm.SHA_256 to
        "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
      ChecksumAlgorithm.SHA_512 to
        "e718483d0ce769644e2e42c7bc15b4638e1f98b13b2044285632a803afa973eb" +
        "de0ff244877ea60a4cb0432ce577c31beb009c5c2c49aa2e4eadb217ad8cc09b",
    )
    val block = ByteArray(1000) { 'a'.code.toByte() }
    for ((algorithm, hex) in expected) {
      val digest = algorithm.newDigest()
      repeat(1000) { digest.update(block) }
      assertEquals(hex, digest.digest().hex(), algorithm.label)
    }
  }

  @Test
  fun finishedOrInvalidRange_throws() {
    for (digest in listOf(Md5(), Sha512())) {
      assertFailsWith<IllegalArgumentException> { digest.update(ByteArray(4), 3, 2) }
      digest.digest()
      assertFailsWith<IllegalStateException> { digest.digest() }
      assertFailsWith<IllegalStateException> { digest.update(byteArrayOf()) }
    }
  }

  /** Checks every vector whole and split at every byte, across the padding boundaries. */
  private fun check(algorithm: ChecksumAlgorithm, vararg vectors: Pair<String, String>) {
    for ((input, expected) in vectors) {
      val bytes = input.encodeToByteArray()
      assertEquals(expected, algorithm.newDigest().update(bytes).digest().hex(), input)
      for (split in 0..bytes.size) {
        val digest = algorithm.newDigest()
          .update(bytes, 0, split)
          .update(bytes, split, bytes.size - split)
        assertEquals(expected, digest.digest().hex(), "$input split at $split")
      }
    }
  }

  private fun ByteArray.hex(): String =
    joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
