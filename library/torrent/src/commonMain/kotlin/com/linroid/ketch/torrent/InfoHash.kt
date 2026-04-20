package com.linroid.ketch.torrent

import kotlinx.serialization.Serializable

/**
 * 20-byte SHA-1 info hash identifying a torrent.
 *
 * @property hex lowercase hex-encoded hash string (40 characters)
 */
@Serializable
@kotlin.jvm.JvmInline
internal value class InfoHash(val hex: String) {
  init {
    require(hex.length == 40 && hex.all { it in HEX_CHARS }) {
      "InfoHash must be 40 hex characters, got: $hex"
    }
  }

  /** Returns the raw 20-byte hash. */
  fun toBytes(): ByteArray {
    return ByteArray(20) { i ->
      hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
  }

  override fun toString(): String = hex

  companion object {
    private const val HEX_CHARS = "0123456789abcdef"

    /** Creates an [InfoHash] from raw 20-byte SHA-1 data. */
    fun fromBytes(bytes: ByteArray): InfoHash {
      require(bytes.size == 20) {
        "SHA-1 hash must be 20 bytes, got ${bytes.size}"
      }
      val hex = bytes.joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
      }
      return InfoHash(hex)
    }

    /** Parses a 40-character hex string (case-insensitive). */
    fun fromHex(hex: String): InfoHash = InfoHash(hex.lowercase())
  }
}
