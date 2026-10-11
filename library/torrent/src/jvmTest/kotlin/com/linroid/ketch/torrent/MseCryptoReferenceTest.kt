package com.linroid.ketch.torrent

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pure Kotlin MSE arithmetic against java.math.BigInteger. */
class MseCryptoReferenceTest {
  private val prime = BigInteger(
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A0879" +
      "8E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A63A3621" +
      "0000000000090563", 16)

  @Test
  fun publicKeysAndSecrets_matchBigInteger() {
    val random = Random(42)
    repeat(20) {
      val secret = random.nextBytes(20)
      val keys = MseKeyPair(secret)
      val expected = BigInteger.TWO.modPow(BigInteger(1, secret), prime)
      assertEquals(expected, BigInteger(1, keys.publicKey))
      val peer = BigInteger(1, random.nextBytes(96)).mod(prime.subtract(BigInteger.valueOf(3)))
        .add(BigInteger.TWO)
      // Big-endian, padded to 96 bytes; toByteArray may add a sign byte or drop leading zeros.
      val magnitude = peer.toByteArray().takeLast(96).toByteArray()
      val peerBytes = ByteArray(96).also { magnitude.copyInto(it, 96 - magnitude.size) }
      assertEquals(peer.modPow(BigInteger(1, secret), prime), BigInteger(1, keys.agree(peerBytes)))
    }
  }
}
