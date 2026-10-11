package com.linroid.ketch.core.digest

/**
 * Incremental MD5 (RFC 1321) for [com.linroid.ketch.api.ChecksumAlgorithm.MD5] checksums, which
 * many download pages still publish. MD5 detects corruption, not tampering. Instances are confined
 * to one owner and cannot be updated after [digest].
 */
class Md5 : MessageDigest {
  private val state = intArrayOf(0x67452301, 0xefcdab89.toInt(), 0x98badcfe.toInt(), 0x10325476)
  private val block = ByteArray(64)
  private val words = IntArray(16)
  private var used = 0
  private var length = 0L
  private var finished = false

  override fun update(data: ByteArray, offset: Int, count: Int): Md5 {
    check(!finished) { "Digest already finished" }
    checkRange(data, offset, count)
    require(length <= (Long.MAX_VALUE ushr 2) - count) { "MD5 message is too long" }
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
    if (used > 56) {
      block.fill(0, used)
      compress()
      used = 0
    }
    block.fill(0, used, 56)
    // MD5 stores the bit length and the result little-endian.
    for (i in 0 until 8) block[56 + i] = (bits ushr (i * 8)).toByte()
    compress()
    return ByteArray(16) { (state[it / 4] ushr ((it % 4) * 8)).toByte() }
  }

  private fun compress() {
    for (i in 0 until 16) {
      var word = 0
      for (j in 3 downTo 0) word = (word shl 8) or (block[i * 4 + j].toInt() and 255)
      words[i] = word
    }
    var a = state[0]
    var b = state[1]
    var c = state[2]
    var d = state[3]
    for (i in 0 until 64) {
      val f: Int
      val g: Int
      when (i / 16) {
        0 -> { f = (b and c) or (b.inv() and d); g = i }
        1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
        2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
        else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
      }
      val next = d
      d = c
      c = b
      b += (a + f + SINES[i] + words[g]).rotateLeft(SHIFTS[i])
      a = next
    }
    state[0] += a
    state[1] += b
    state[2] += c
    state[3] += d
  }

  private companion object {
    val SHIFTS = IntArray(64) { i ->
      val round = when (i / 16) {
        0 -> intArrayOf(7, 12, 17, 22)
        1 -> intArrayOf(5, 9, 14, 20)
        2 -> intArrayOf(4, 11, 16, 23)
        else -> intArrayOf(6, 10, 15, 21)
      }
      round[i % 4]
    }

    /** `floor(abs(sin(i + 1)) * 2^32)`, the table RFC 1321 lists in section 3.4. */
    val SINES = intArrayOf(
      0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
      0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
      0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
      0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
      0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
      0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
      0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
      0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
      0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
      0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
      0x289b7ec6, 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
      0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(),
      0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
      0x655b59c3, 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
      0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
      0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb, 0xeb86d391.toInt()
    )
  }
}
