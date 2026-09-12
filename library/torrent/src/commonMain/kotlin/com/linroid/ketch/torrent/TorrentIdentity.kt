package com.linroid.ketch.torrent

/** Full SHA-256 identity; its 20-byte wire prefix must never become a content-cache key. */
@kotlin.jvm.JvmInline
internal value class V2InfoHash(val hex: String) {
  init {
    require(hex.length == 64 && hex.all { it in "0123456789abcdef" }) {
      "V2 info hash must contain 64 lowercase hex characters"
    }
  }

  fun toBytes(): ByteArray = hex.hexToByteArray()
  fun wireBytes(): ByteArray = toBytes().copyOf(20)
  fun multihash(): String = "1220$hex"

  companion object {
    fun fromBytes(bytes: ByteArray): V2InfoHash {
      require(bytes.size == 32)
      return V2InfoHash(bytes.toHexString())
    }

    /** Decode canonical multihash algorithm and length varints before accepting SHA-256. */
    fun fromMultihash(value: String): V2InfoHash {
      require(value.length in 4..1024 && value.length % 2 == 0) { "Invalid multihash length" }
      val bytes = value.hexToByteArray()
      var position = 0
      fun integer(): Long {
        var result = 0L
        for (index in 0 until 9) {
          require(position < bytes.size) { "Truncated multihash varint" }
          val next = bytes[position++].toInt() and 255
          result = result or ((next and 127).toLong() shl (index * 7))
          if (next and 128 == 0) {
            require(index == 0 || next != 0) { "Noncanonical multihash varint" }
            return result
          }
        }
        throw IllegalArgumentException("Multihash varint exceeds 63 bits")
      }
      val algorithm = integer()
      val length = integer()
      require(length == (bytes.size - position).toLong()) { "Multihash digest length mismatch" }
      if (algorithm != 0x12L) throw UnsupportedTorrentHashAlgorithm(algorithm)
      require(length == 32L) { "BitTorrent v2 requires a full SHA-256 digest" }
      return fromBytes(bytes.copyOfRange(position, bytes.size))
    }
  }
}

/** Exact topics supplied by the caller. Every present hash must authenticate the same raw info. */
internal data class TorrentIdentity(val v1: InfoHash? = null, val v2: V2InfoHash? = null) {
  init { require(v1 != null || v2 != null) { "Torrent identity needs an exact topic" } }

  /** Authenticates identity only; metainfo structure and external layers need validation too. */
  fun matchesInfo(rawInfo: ByteArray): Boolean =
    (v1 == null || v1.toBytes().contentEquals(sha1Digest(rawInfo))) &&
      (v2 == null || v2.toBytes().contentEquals(sha256Digest(rawInfo)))
}

internal class UnsupportedTorrentHashAlgorithm(val algorithm: Long) :
  IllegalArgumentException("Unsupported torrent multihash algorithm: $algorithm")
