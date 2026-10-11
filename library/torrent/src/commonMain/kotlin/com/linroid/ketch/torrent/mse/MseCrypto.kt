package com.linroid.ketch.torrent.mse

import com.linroid.ketch.torrent.crypto.torrentRandomBytes

/** RC4, as MSE/PE uses it: one keystream per direction, the first 1024 bytes discarded. */
internal class Rc4(key: ByteArray, discard: Int = 0) {
  private val s = IntArray(256) { it }
  private var i = 0
  private var j = 0

  init {
    require(key.isNotEmpty() && key.size <= 256)
    var k = 0
    for (n in 0 until 256) {
      k = (k + s[n] + (key[n % key.size].toInt() and 255)) and 255
      val swap = s[n]
      s[n] = s[k]
      s[k] = swap
    }
    if (discard > 0) process(ByteArray(discard))
  }

  /** XORs [data] in place with the next keystream bytes and returns it. */
  fun process(data: ByteArray, offset: Int = 0, count: Int = data.size - offset): ByteArray {
    require(offset >= 0 && count >= 0 && offset <= data.size - count)
    for (index in offset until offset + count) {
      i = (i + 1) and 255
      j = (j + s[i]) and 255
      val swap = s[i]
      s[i] = s[j]
      s[j] = swap
      data[index] = (data[index].toInt() xor s[(s[i] + s[j]) and 255]).toByte()
    }
    return data
  }
}

/**
 * The 768-bit Diffie-Hellman exchange of MSE/PE: generator 2 over its fixed prime, with 160-bit
 * private keys. Public keys and the shared secret are 96 big-endian bytes.
 */
internal class MseKeyPair(private val secret: ByteArray = torrentRandomBytes(20)) {
  init { require(secret.size == 20) }

  val publicKey: ByteArray = Montgomery.prime.toBytes(Montgomery.prime.pow(TWO, secret))

  /** The shared secret with a peer's [publicKey], which must be in 2..P-2. */
  fun agree(publicKey: ByteArray): ByteArray {
    require(publicKey.size == KEY_BYTES) { "MSE public key must be $KEY_BYTES bytes" }
    val value = Montgomery.prime.fromBytes(publicKey)
    require(Montgomery.prime.isValidPublic(value)) { "Invalid MSE public key" }
    return Montgomery.prime.toBytes(Montgomery.prime.pow(value, secret))
  }

  companion object {
    const val KEY_BYTES = 96
    private val TWO = IntArray(24).also { it[0] = 2 }
  }
}

/**
 * Modular exponentiation over one odd modulus with Montgomery multiplication, on little-endian
 * 32-bit limbs. Pure Kotlin, so every target computes MSE keys alike.
 */
internal class Montgomery(modulus: ByteArray) {
  private val n = limbs(modulus, (modulus.size + 3) / 4)
  private val size = n.size
  private val bytes = modulus.size
  private val inverse: Int
  /** R² mod n, R being 2^(32·size). */
  private val r2: IntArray

  init {
    require(n[0] and 1 == 1) { "Montgomery needs an odd modulus" }
    // Newton's iteration doubles the correct low bits of n[0]⁻¹ mod 2³² each round.
    var inv = 1
    repeat(5) { inv *= 2 - n[0] * inv }
    inverse = -inv
    var x = IntArray(size).also { it[0] = 1 }
    repeat(2 * 32 * size) { x = doubleMod(x) }
    r2 = x
  }

  fun fromBytes(value: ByteArray): IntArray {
    require(value.size <= size * 4)
    return limbs(value, size)
  }

  fun toBytes(value: IntArray): ByteArray {
    val out = ByteArray(bytes)
    for (index in 0 until bytes) {
      val limb = value[index / 4]
      out[bytes - 1 - index] = (limb ushr (8 * (index % 4))).toByte()
    }
    return out
  }

  /** True for 2 ≤ [value] ≤ n − 2, the public keys a peer may send. */
  fun isValidPublic(value: IntArray): Boolean {
    if (compare(value, n) >= 0) return false
    val two = IntArray(size).also { it[0] = 2 }
    if (compare(value, two) < 0) return false
    val limit = n.copyOf().also { subtractInPlace(it, two) }
    return compare(value, limit) <= 0
  }

  /** [base]^[exponent] mod n, [exponent] big-endian; [base] must be below n. */
  fun pow(base: IntArray, exponent: ByteArray): IntArray {
    require(base.size == size && compare(base, n) < 0)
    val one = IntArray(size).also { it[0] = 1 }
    val montBase = multiply(base, r2)
    var result = multiply(one, r2)
    for (byte in exponent) {
      for (bit in 7 downTo 0) {
        result = multiply(result, result)
        if ((byte.toInt() ushr bit) and 1 == 1) result = multiply(result, montBase)
      }
    }
    return multiply(result, one)
  }

  /** Montgomery product a·b·R⁻¹ mod n (CIOS). */
  private fun multiply(a: IntArray, b: IntArray): IntArray {
    val t = LongArray(size + 2)
    for (i in 0 until size) {
      val bi = b[i].toLong() and MASK
      var carry = 0L
      for (j in 0 until size) {
        // Each term stays below 2⁶⁴: unsigned arithmetic on the bits.
        val sum = t[j].toULong() + (a[j].toLong() and MASK).toULong() * bi.toULong() +
          carry.toULong()
        t[j] = (sum and MASK.toULong()).toLong()
        carry = (sum shr 32).toLong()
      }
      var sum = t[size] + carry
      t[size] = sum and MASK
      t[size + 1] = sum ushr 32
      val m = ((t[0] * inverse.toLong()) and MASK)
      var c = ((t[0].toULong() + m.toULong() * (n[0].toLong() and MASK).toULong()) shr 32)
      for (j in 1 until size) {
        val s = t[j].toULong() + m.toULong() * (n[j].toLong() and MASK).toULong() + c
        t[j - 1] = (s and MASK.toULong()).toLong()
        c = s shr 32
      }
      sum = t[size] + c.toLong()
      t[size - 1] = sum and MASK
      t[size] = t[size + 1] + (sum ushr 32)
      t[size + 1] = 0
    }
    val result = IntArray(size) { t[it].toInt() }
    if (t[size] != 0L || compare(result, n) >= 0) subtractInPlace(result, n)
    return result
  }

  /** 2·[x] mod n for [x] below n. */
  private fun doubleMod(x: IntArray): IntArray {
    val out = IntArray(size)
    var carry = 0
    for (index in 0 until size) {
      out[index] = (x[index] shl 1) or carry
      carry = x[index] ushr 31
    }
    if (carry != 0 || compare(out, n) >= 0) subtractInPlace(out, n)
    return out
  }

  companion object {
    private const val MASK = 0xffffffffL

    /** The MSE/PE prime. */
    val prime = Montgomery(
      ("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A0879" +
        "8E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A63A3621" +
        "0000000000090563").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    )

    private fun limbs(value: ByteArray, size: Int): IntArray {
      val out = IntArray(size)
      for (index in value.indices) {
        val position = value.size - 1 - index
        out[position / 4] = out[position / 4] or ((value[index].toInt() and 255) shl
          (8 * (position % 4)))
      }
      return out
    }

    /** Unsigned comparison of equally sized limb arrays. */
    private fun compare(a: IntArray, b: IntArray): Int {
      for (index in a.indices.reversed()) {
        if (a[index] != b[index]) return a[index].toUInt().compareTo(b[index].toUInt())
      }
      return 0
    }

    /** [a] −= [b], modulo 2^(32·size): callers subtract only when the true result fits. */
    private fun subtractInPlace(a: IntArray, b: IntArray) {
      var borrow = 0L
      for (index in a.indices) {
        val difference = (a[index].toLong() and MASK) - (b[index].toLong() and MASK) - borrow
        a[index] = difference.toInt()
        borrow = if (difference < 0) 1 else 0
      }
    }
  }
}
