package com.linroid.ketch.torrent

/** Incremental SHA-1 for BitTorrent v1 identity and piece integrity. */
internal class Sha1 {
  private val state = intArrayOf(
    0x67452301, 0xefcdab89.toInt(), 0x98badcfe.toInt(), 0x10325476, 0xc3d2e1f0.toInt()
  )
  private val block = ByteArray(64)
  private val words = IntArray(80)
  private var used = 0
  private var length = 0L
  private var finished = false

  fun update(data: ByteArray, offset: Int = 0, count: Int = data.size - offset): Sha1 {
    check(!finished) { "Digest already finished" }
    require(offset >= 0 && count >= 0 && offset <= data.size - count)
    require(length <= Long.MAX_VALUE - count)
    length += count
    var position = offset
    var remaining = count
    while (remaining > 0) {
      val size = minOf(64 - used, remaining)
      data.copyInto(block, used, position, position + size)
      used += size
      position += size
      remaining -= size
      if (used == 64) {
        compress()
        used = 0
      }
    }
    return this
  }

  fun digest(): ByteArray {
    check(!finished) { "Digest already finished" }
    val bits = length * 8
    update(byteArrayOf(0x80.toByte()))
    while (used != 56) update(byteArrayOf(0))
    update(ByteArray(8) { (bits ushr (56 - it * 8)).toByte() })
    finished = true
    return ByteArray(20) { (state[it / 4] ushr (24 - (it % 4) * 8)).toByte() }
  }

  private fun compress() {
    for (i in 0 until 16) {
      var word = 0
      for (j in 0 until 4) word = (word shl 8) or (block[i * 4 + j].toInt() and 255)
      words[i] = word
    }
    for (i in 16 until 80) {
      words[i] = (words[i - 3] xor words[i - 8] xor words[i - 14] xor words[i - 16])
        .rotateLeft(1)
    }
    var a = state[0]
    var b = state[1]
    var c = state[2]
    var d = state[3]
    var e = state[4]
    for (i in 0 until 80) {
      val f: Int
      val k: Int
      when (i) {
        in 0..19 -> { f = (b and c) or (b.inv() and d); k = 0x5a827999 }
        in 20..39 -> { f = b xor c xor d; k = 0x6ed9eba1 }
        in 40..59 -> { f = (b and c) or (b and d) or (c and d); k = 0x8f1bbcdc.toInt() }
        else -> { f = b xor c xor d; k = 0xca62c1d6.toInt() }
      }
      val next = a.rotateLeft(5) + f + e + k + words[i]
      e = d
      d = c
      c = b.rotateLeft(30)
      b = a
      a = next
    }
    state[0] += a
    state[1] += b
    state[2] += c
    state[3] += d
    state[4] += e
  }
}

/** Returns the 20-byte SHA-1 digest of [data]. */
internal fun sha1Digest(data: ByteArray): ByteArray = Sha1().update(data).digest()
