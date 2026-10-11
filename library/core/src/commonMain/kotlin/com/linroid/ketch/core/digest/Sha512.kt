package com.linroid.ketch.core.digest

/**
 * Incremental SHA-512 for [com.linroid.ketch.api.ChecksumAlgorithm.SHA_512] checksums.
 * Algorithm: https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.180-4.pdf (sections 4–6).
 * Instances are confined to one owner and cannot be updated after [digest].
 */
class Sha512 : MessageDigest {
  private val state = INITIAL_STATE.copyOf()
  private val block = ByteArray(128)
  private val words = LongArray(80)
  private var used = 0
  private var length = 0L
  private var finished = false

  override fun update(data: ByteArray, offset: Int, count: Int): Sha512 {
    check(!finished) { "Digest already finished" }
    checkRange(data, offset, count)
    // The bit length must fit the low 64 bits of the 128-bit length field.
    require(length <= (Long.MAX_VALUE ushr 2) - count) { "SHA-512 message is too long" }
    length += count
    var position = offset
    var remaining = count
    while (remaining > 0) {
      val size = minOf(block.size - used, remaining)
      data.copyInto(block, used, position, position + size)
      used += size
      position += size
      remaining -= size
      if (used == block.size) {
        compress()
        used = 0
      }
    }
    return this
  }

  override fun digest(): ByteArray {
    check(!finished) { "Digest already finished" }
    finished = true
    val bits = length shl 3
    block[used++] = 0x80.toByte()
    if (used > 112) {
      block.fill(0, used)
      compress()
      used = 0
    }
    // The high 64 bits of the length are zero.
    block.fill(0, used, 120)
    for (i in 0 until 8) block[120 + i] = (bits ushr (56 - i * 8)).toByte()
    compress()
    return ByteArray(64) { (state[it / 8] ushr (56 - (it % 8) * 8)).toByte() }
  }

  private fun compress() {
    for (i in 0 until 16) {
      var word = 0L
      for (j in 0 until 8) word = (word shl 8) or (block[i * 8 + j].toLong() and 255)
      words[i] = word
    }
    for (i in 16 until 80) {
      val x = words[i - 15]
      val y = words[i - 2]
      val small0 = x.rotateRight(1) xor x.rotateRight(8) xor (x ushr 7)
      val small1 = y.rotateRight(19) xor y.rotateRight(61) xor (y ushr 6)
      words[i] = words[i - 16] + small0 + words[i - 7] + small1
    }
    var a = state[0]
    var b = state[1]
    var c = state[2]
    var d = state[3]
    var e = state[4]
    var f = state[5]
    var g = state[6]
    var h = state[7]
    for (i in 0 until 80) {
      val sum1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
      val choose = (e and f) xor (e.inv() and g)
      val t1 = h + sum1 + choose + ROUND_CONSTANTS[i] + words[i]
      val sum0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
      val majority = (a and b) xor (a and c) xor (b and c)
      val t2 = sum0 + majority
      h = g
      g = f
      f = e
      e = d + t1
      d = c
      c = b
      b = a
      a = t1 + t2
    }
    state[0] += a
    state[1] += b
    state[2] += c
    state[3] += d
    state[4] += e
    state[5] += f
    state[6] += g
    state[7] += h
  }

  private companion object {
    val INITIAL_STATE = longs(
      "6a09e667f3bcc908", "bb67ae8584caa73b", "3c6ef372fe94f82b", "a54ff53a5f1d36f1",
      "510e527fade682d1", "9b05688c2b3e6c1f", "1f83d9abfb41bd6b", "5be0cd19137e2179"
    )

    val ROUND_CONSTANTS = longs(
      "428a2f98d728ae22", "7137449123ef65cd", "b5c0fbcfec4d3b2f", "e9b5dba58189dbbc",
      "3956c25bf348b538", "59f111f1b605d019", "923f82a4af194f9b", "ab1c5ed5da6d8118",
      "d807aa98a3030242", "12835b0145706fbe", "243185be4ee4b28c", "550c7dc3d5ffb4e2",
      "72be5d74f27b896f", "80deb1fe3b1696b1", "9bdc06a725c71235", "c19bf174cf692694",
      "e49b69c19ef14ad2", "efbe4786384f25e3", "0fc19dc68b8cd5b5", "240ca1cc77ac9c65",
      "2de92c6f592b0275", "4a7484aa6ea6e483", "5cb0a9dcbd41fbd4", "76f988da831153b5",
      "983e5152ee66dfab", "a831c66d2db43210", "b00327c898fb213f", "bf597fc7beef0ee4",
      "c6e00bf33da88fc2", "d5a79147930aa725", "06ca6351e003826f", "142929670a0e6e70",
      "27b70a8546d22ffc", "2e1b21385c26c926", "4d2c6dfc5ac42aed", "53380d139d95b3df",
      "650a73548baf63de", "766a0abb3c77b2a8", "81c2c92e47edaee6", "92722c851482353b",
      "a2bfe8a14cf10364", "a81a664bbc423001", "c24b8b70d0f89791", "c76c51a30654be30",
      "d192e819d6ef5218", "d69906245565a910", "f40e35855771202a", "106aa07032bbd1b8",
      "19a4c116b8d2d0c8", "1e376c085141ab53", "2748774cdf8eeb99", "34b0bcb5e19b48a8",
      "391c0cb3c5c95a63", "4ed8aa4ae3418acb", "5b9cca4f7763e373", "682e6ff3d6b2b8a3",
      "748f82ee5defb2fc", "78a5636f43172f60", "84c87814a1f0ab72", "8cc702081a6439ec",
      "90befffa23631e28", "a4506cebde82bde9", "bef9a3f7b2c67915", "c67178f2e372532b",
      "ca273eceea26619c", "d186b8c721c0c207", "eada7dd6cde0eb1e", "f57d4f7fee6ed178",
      "06f067aa72176fba", "0a637dc5a2c898a6", "113f9804bef90dae", "1b710b35131c471b",
      "28db77f523047d84", "32caab7b40c72493", "3c9ebe0a15c9bebc", "431d67c49c100d4c",
      "4cc5d4becb3e42b6", "597f299cfc657e2a", "5fcb6fab3ad6faec", "6c44198c4a475817"
    )

    fun longs(vararg hex: String): LongArray = LongArray(hex.size) { hex[it].toULong(16).toLong() }
  }
}
