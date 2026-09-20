package com.linroid.ketch.torrent

/**
 * Incremental SHA-256 for BitTorrent v2 identity and Merkle integrity.
 * Algorithm: https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.180-4.pdf (sections 4–6).
 * Instances are confined to one owner and cannot be updated after finalization.
 */
internal class Sha256 {
  private val state = intArrayOf(
    0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
    0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19
  )
  private val block = ByteArray(64)
  private val words = IntArray(64)
  private var used = 0
  private var length = 0L
  private var finished = false

  fun update(data: ByteArray, offset: Int = 0, count: Int = data.size - offset): Sha256 {
    check(!finished) { "Digest already finished" }
    require(offset >= 0 && count >= 0 && offset <= data.size - count)
    // SHA-256 encodes an unsigned 64-bit bit length. Byte inputs must stay below 2^61 bytes.
    require(length <= (Long.MAX_VALUE ushr 2) - count) { "SHA-256 message is too long" }
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

  fun digest(): ByteArray {
    check(!finished) { "Digest already finished" }
    finished = true
    val bits = length shl 3
    block[used++] = 0x80.toByte()
    if (used > 56) {
      block.fill(0, used)
      compress()
      used = 0
    }
    block.fill(0, used, 56)
    for (i in 0 until 8) block[56 + i] = (bits ushr (56 - i * 8)).toByte()
    compress()
    return ByteArray(32) { (state[it / 4] ushr (24 - (it % 4) * 8)).toByte() }
  }

  private fun compress() {
    for (i in 0 until 16) {
      var word = 0
      for (j in 0 until 4) word = (word shl 8) or (block[i * 4 + j].toInt() and 255)
      words[i] = word
    }
    for (i in 16 until 64) {
      val x = words[i - 15]
      val y = words[i - 2]
      val small0 = x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)
      val small1 = y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10)
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
    for (i in 0 until 64) {
      val sum1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
      val choose = (e and f) xor (e.inv() and g)
      val t1 = h + sum1 + choose + ROUND_CONSTANTS[i] + words[i]
      val sum0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
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

  companion object {
    private val ROUND_CONSTANTS = intArrayOf(
      0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
      0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
      0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
      0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
      0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
      0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
      0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
      0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
      0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
      0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
      0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
      0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
      0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
      0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
      0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
      0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
    )
  }
}

/** Returns the 32-byte SHA-256 digest of [data] without retaining the input. */
internal fun sha256Digest(data: ByteArray): ByteArray = Sha256().update(data).digest()
