package com.linroid.ketch.core.media

/**
 * Decrypts AES-128 in CBC mode with PKCS#7 padding as the bytes arrive (FIPS 197, NIST SP
 * 800-38A), as HLS encrypts whole segments. Pure Kotlin, so it runs on every engine target.
 *
 * Feed the ciphertext to [update] in pieces of any size, then call [finish], which strips the
 * padding. The last block is held back until then, as only it carries the padding.
 */
class Aes128CbcDecryptor(key: ByteArray, iv: ByteArray) {
  private val roundKeys: IntArray
  private var previous: IntArray
  private val pending = ByteArray(BLOCK)
  private var pendingSize = 0
  private var held: ByteArray? = null
  private var finished = false

  init {
    require(key.size == BLOCK) { "AES-128 needs a 16-byte key" }
    require(iv.size == BLOCK) { "AES-128 needs a 16-byte IV" }
    roundKeys = decryptionKeys(key)
    previous = words(iv, 0)
  }

  /** Decrypts [data] and returns the plaintext it completes; may be empty. */
  fun update(data: ByteArray): ByteArray {
    check(!finished) { "Decryption already finished" }
    if (data.isEmpty()) return EMPTY
    // At most every block completed now plus the one held back before.
    val out = ByteArray((pendingSize + data.size) / BLOCK * BLOCK + BLOCK)
    var written = 0
    var offset = 0
    while (offset < data.size) {
      val take = minOf(BLOCK - pendingSize, data.size - offset)
      data.copyInto(pending, pendingSize, offset, offset + take)
      pendingSize += take
      offset += take
      if (pendingSize == BLOCK) {
        held?.let {
          it.copyInto(out, written)
          written += BLOCK
        }
        held = decryptBlock(pending)
        pendingSize = 0
      }
    }
    return if (written == out.size) out else out.copyOf(written)
  }

  /**
   * Returns the last plaintext, without its padding.
   *
   * @throws IllegalArgumentException when the ciphertext is not whole blocks or the padding is
   *   wrong, as with the wrong key
   */
  fun finish(): ByteArray {
    check(!finished) { "Decryption already finished" }
    finished = true
    val last = held
    require(pendingSize == 0 && last != null) { "Encrypted media is not whole AES blocks" }
    val padding = last[BLOCK - 1].toInt() and 0xff
    require(padding in 1..BLOCK && (BLOCK - padding until BLOCK).all {
      last[it].toInt() and 0xff == padding
    }) { "Encrypted media has invalid padding; the key may be wrong" }
    return last.copyOf(BLOCK - padding)
  }

  private fun decryptBlock(block: ByteArray): ByteArray {
    val cipher = words(block, 0)
    val rk = roundKeys
    var s0 = cipher[0] xor rk[0]
    var s1 = cipher[1] xor rk[1]
    var s2 = cipher[2] xor rk[2]
    var s3 = cipher[3] xor rk[3]
    var k = 4
    repeat(ROUNDS - 1) {
      val t0 = TD0[s0 ushr 24] xor TD1[(s3 ushr 16) and 255] xor TD2[(s2 ushr 8) and 255] xor
        TD3[s1 and 255] xor rk[k]
      val t1 = TD0[s1 ushr 24] xor TD1[(s0 ushr 16) and 255] xor TD2[(s3 ushr 8) and 255] xor
        TD3[s2 and 255] xor rk[k + 1]
      val t2 = TD0[s2 ushr 24] xor TD1[(s1 ushr 16) and 255] xor TD2[(s0 ushr 8) and 255] xor
        TD3[s3 and 255] xor rk[k + 2]
      val t3 = TD0[s3 ushr 24] xor TD1[(s2 ushr 16) and 255] xor TD2[(s1 ushr 8) and 255] xor
        TD3[s0 and 255] xor rk[k + 3]
      s0 = t0
      s1 = t1
      s2 = t2
      s3 = t3
      k += 4
    }
    val plain = intArrayOf(
      lastRound(s0, s3, s2, s1) xor rk[k],
      lastRound(s1, s0, s3, s2) xor rk[k + 1],
      lastRound(s2, s1, s0, s3) xor rk[k + 2],
      lastRound(s3, s2, s1, s0) xor rk[k + 3],
    )
    val out = ByteArray(BLOCK)
    for (i in 0 until 4) {
      val word = plain[i] xor previous[i]
      out[i * 4] = (word ushr 24).toByte()
      out[i * 4 + 1] = (word ushr 16).toByte()
      out[i * 4 + 2] = (word ushr 8).toByte()
      out[i * 4 + 3] = word.toByte()
    }
    previous = cipher
    return out
  }

  private fun lastRound(a: Int, b: Int, c: Int, d: Int): Int =
    (INV_SBOX[a ushr 24] shl 24) or (INV_SBOX[(b ushr 16) and 255] shl 16) or
      (INV_SBOX[(c ushr 8) and 255] shl 8) or INV_SBOX[d and 255]

  private companion object {
    const val BLOCK = 16
    const val ROUNDS = 10
    val EMPTY = ByteArray(0)
    val SBOX = IntArray(256)
    val INV_SBOX = IntArray(256)
    val TD0 = IntArray(256)
    val TD1 = IntArray(256)
    val TD2 = IntArray(256)
    val TD3 = IntArray(256)

    init {
      // The S-box from the multiplicative inverse in GF(2^8): p walks the field multiplying by
      // 3, q tracks its inverse dividing by 3.
      var p = 1
      var q = 1
      do {
        p = (p xor (p shl 1) xor (if (p and 0x80 != 0) 0x1b else 0)) and 0xff
        q = q xor (q shl 1)
        q = q xor (q shl 2)
        q = q xor (q shl 4)
        q = q and 0xff
        if (q and 0x80 != 0) q = q xor 0x09
        val x = q xor rotl8(q, 1) xor rotl8(q, 2) xor rotl8(q, 3) xor rotl8(q, 4)
        SBOX[p] = (x xor 0x63) and 0xff
      } while (p != 1)
      SBOX[0] = 0x63
      for (i in 0 until 256) INV_SBOX[SBOX[i]] = i
      for (i in 0 until 256) {
        val s = INV_SBOX[i]
        val word = (mul(s, 0x0e) shl 24) or (mul(s, 0x09) shl 16) or (mul(s, 0x0d) shl 8) or
          mul(s, 0x0b)
        TD0[i] = word
        TD1[i] = word.rotateRight(8)
        TD2[i] = word.rotateRight(16)
        TD3[i] = word.rotateRight(24)
      }
    }

    fun rotl8(value: Int, shift: Int): Int =
      ((value shl shift) or (value ushr (8 - shift))) and 0xff

    fun mul(a: Int, b: Int): Int {
      var x = a
      var y = b
      var result = 0
      while (y != 0) {
        if (y and 1 != 0) result = result xor x
        x = (x shl 1) xor (if (x and 0x80 != 0) 0x11b else 0)
        y = y ushr 1
      }
      return result and 0xff
    }

    fun words(bytes: ByteArray, offset: Int): IntArray = IntArray(4) { i ->
      val at = offset + i * 4
      ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
        ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
    }

    fun subWord(word: Int): Int =
      (SBOX[word ushr 24] shl 24) or (SBOX[(word ushr 16) and 255] shl 16) or
        (SBOX[(word ushr 8) and 255] shl 8) or SBOX[word and 255]

    /** The round keys of the equivalent inverse cipher, last encryption round first. */
    fun decryptionKeys(key: ByteArray): IntArray {
      val w = IntArray(4 * (ROUNDS + 1))
      words(key, 0).copyInto(w)
      var rcon = 1
      for (i in 4 until w.size) {
        var temp = w[i - 1]
        if (i % 4 == 0) {
          temp = subWord(temp.rotateLeft(8)) xor (rcon shl 24)
          rcon = mul(rcon, 2)
        }
        w[i] = w[i - 4] xor temp
      }
      val d = IntArray(w.size)
      for (round in 0..ROUNDS) {
        for (j in 0 until 4) {
          val word = w[(ROUNDS - round) * 4 + j]
          d[round * 4 + j] = if (round == 0 || round == ROUNDS) word else {
            TD0[SBOX[word ushr 24]] xor TD1[SBOX[(word ushr 16) and 255]] xor
              TD2[SBOX[(word ushr 8) and 255]] xor TD3[SBOX[word and 255]]
          }
        }
      }
      return d
    }
  }
}
