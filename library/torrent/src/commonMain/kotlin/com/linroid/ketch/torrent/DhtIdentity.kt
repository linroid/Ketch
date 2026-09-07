package com.linroid.ketch.torrent

import okio.ByteString
import okio.ByteString.Companion.toByteString

/** BEP 42 node-ID prefix binding; the remaining 139 bits retain random entropy. */
internal object DhtIdentity {
  fun create(address: ByteArray, random: ByteArray = torrentRandomBytes(20)): ByteString {
    require(random.size == 20)
    val result = random.copyOf()
    val crc = prefix(address, result[19].toInt() and 7)
    result[0] = (crc ushr 24).toByte()
    result[1] = (crc ushr 16).toByte()
    result[2] = ((crc ushr 8 and 248) or (result[2].toInt() and 7)).toByte()
    return result.toByteString()
  }

  fun valid(id: ByteString, address: ByteArray): Boolean {
    if (id.size != 20 || (address.size != 4 && address.size != 16)) return false
    val crc = prefix(address, id[19].toInt() and 7)
    return id[0] == (crc ushr 24).toByte() && id[1] == (crc ushr 16).toByte() &&
      (id[2].toInt() and 248) == (crc ushr 8 and 248)
  }

  private fun prefix(address: ByteArray, random: Int): Int {
    require(address.size == 4 || address.size == 16)
    val mask = if (address.size == 4) intArrayOf(3, 15, 63, 255)
      else intArrayOf(1, 3, 7, 15, 31, 63, 127, 255)
    val bytes = ByteArray(mask.size) { (address[it].toInt() and mask[it]).toByte() }
    bytes[0] = (bytes[0].toInt() or (random shl 5)).toByte()
    return crc32c(bytes)
  }

  internal fun crc32c(bytes: ByteArray): Int {
    var value = -1
    for (byte in bytes) {
      value = value xor (byte.toInt() and 255)
      repeat(8) { value = (value ushr 1) xor (if (value and 1 != 0) 0x82f63b78.toInt() else 0) }
    }
    return value.inv()
  }
}

/** Ten-minute maximum validity; rotating secrets bind announce tokens to the requester's IP. */
internal class DhtTokens(private val nowMs: () -> Long = monotonicClock()) {
  private var epoch = nowMs() / 300_000
  private var current = torrentRandomBytes(32)
  private var previous: ByteArray? = null

  fun issue(endpoint: PeerEndpoint): ByteArray {
    rotate()
    return token(current, endpoint)
  }

  fun valid(value: ByteArray, endpoint: PeerEndpoint): Boolean {
    rotate()
    if (value.size != 20) return false
    return equal(value, token(current, endpoint)) ||
      previous?.let { equal(value, token(it, endpoint)) } == true
  }

  private fun rotate() {
    val next = nowMs() / 300_000
    if (next != epoch) {
      previous = if (next == epoch + 1) current else null
      current = torrentRandomBytes(32)
      epoch = next
    }
  }

  private fun token(secret: ByteArray, endpoint: PeerEndpoint): ByteArray =
    sha1Digest(secret + requireNotNull(numericAddress(endpoint.host)))

  private fun equal(first: ByteArray, second: ByteArray): Boolean {
    var difference = 0
    for (index in first.indices) difference = difference or
      (first[index].toInt() xor second[index].toInt())
    return difference == 0
  }
}
