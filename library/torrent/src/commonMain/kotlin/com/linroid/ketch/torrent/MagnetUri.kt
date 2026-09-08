package com.linroid.ketch.torrent

import okio.Buffer

/**
 * Parsed magnet URI containing torrent identification and metadata.
 *
 * Format: `magnet:?xt=urn:btih:<hash>&dn=<name>&tr=<tracker>`
 *
 * @property infoHash 20-byte SHA-1 info hash
 * @property displayName optional human-readable name (`dn` parameter)
 * @property trackers list of tracker announce URLs (`tr` parameters)
 */
internal data class MagnetUri(
  val infoHash: InfoHash,
  val displayName: String? = null,
  val trackers: List<String> = emptyList(),
  val explicitPeers: List<String> = emptyList(),
) {

  /** Reconstructs the magnet URI string. */
  fun toUri(): String = buildString {
    append("magnet:?xt=urn:btih:")
    append(infoHash.hex)
    if (displayName != null) {
      append("&dn=")
      append(urlEncode(displayName))
    }
    for (peer in explicitPeers) append("&x.pe=${urlEncode(peer)}")
    for (tracker in trackers) {
      append("&tr=")
      append(urlEncode(tracker))
    }
  }

  companion object {
    /**
     * Parses a magnet URI string.
     *
     * @throws IllegalArgumentException if the URI is malformed or
     *   missing the `xt=urn:btih:` parameter
     */
    fun parse(uri: String): MagnetUri {
      require(uri.lowercase().startsWith("magnet:?")) {
        "Not a magnet URI: $uri"
      }
      require(uri.length <= 65536) { "Magnet URI exceeds limit" }
      val query = uri.substringAfter('?')
      val params = query.split('&')

      var infoHash: InfoHash? = null
      var displayName: String? = null
      val trackers = mutableListOf<String>()
      val peers = mutableListOf<String>()

      for (param in params) {
        val (key, value) = param.split('=', limit = 2)
          .let { if (it.size == 2) it[0] to it[1] else continue }

        when (key.lowercase()) {
          "xt" -> {
            val decoded = urlDecode(value)
            val lower = decoded.lowercase()
            require(!lower.startsWith("urn:btmh:")) { "BitTorrent v2/hybrid unsupported" }
            if (lower.startsWith("urn:btih:")) {
              val candidate = parseInfoHash(decoded.substring(9))
              require(infoHash == null || infoHash == candidate) { "Conflicting info hashes" }
              infoHash = candidate
            }
          }
          "x.pe" -> peers.add(urlDecode(value))
          "dn" -> displayName = urlDecode(value)
          "tr" -> trackers.add(urlDecode(value))
        }
      }

      require(trackers.size <= 128 && peers.size <= 128) { "Too many magnet endpoints" }
      requireNotNull(infoHash) {
        "Magnet URI missing xt=urn:btih: parameter"
      }

      return MagnetUri(
        infoHash = infoHash,
        displayName = displayName,
        trackers = trackers.distinct(),
        explicitPeers = peers.distinct(),
      )
    }

    private fun parseInfoHash(hash: String): InfoHash {
      return when (hash.length) {
        40 -> InfoHash.fromHex(hash)
        32 -> InfoHash.fromBytes(base32Decode(hash))
        else -> throw IllegalArgumentException(
          "Invalid info hash length: ${hash.length}"
        )
      }
    }

    /**
     * Decodes a base32-encoded string (RFC 4648) to bytes.
     * Used for magnet URIs with base32-encoded info hashes.
     */
    internal fun base32Decode(input: String): ByteArray {
      val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
      val upper = input.uppercase()
      val output = mutableListOf<Byte>()
      var buffer = 0
      var bitsLeft = 0

      for (ch in upper) {
        if (ch == '=') break
        val value = alphabet.indexOf(ch)
        require(value >= 0) { "Invalid base32 character: $ch" }
        buffer = (buffer shl 5) or value
        bitsLeft += 5
        if (bitsLeft >= 8) {
          bitsLeft -= 8
          output.add((buffer shr bitsLeft and 0xFF).toByte())
        }
      }
      return output.toByteArray()
    }

    private fun urlDecode(value: String): String {
      val input = value.encodeToByteArray()
      val output = Buffer()
      var index = 0
      while (index < input.size) {
        val byte = input[index].toInt() and 255
        val hex = if (byte == '%'.code && index + 2 < input.size) {
          input.decodeToString(index + 1, index + 3).toIntOrNull(16)
        } else null
        when {
          hex != null -> { output.writeByte(hex); index += 3 }
          byte == '+'.code -> { output.writeByte(' '.code); index++ }
          else -> { output.writeByte(byte); index++ }
        }
      }
      return output.readByteArray().decodeToString(throwOnInvalidSequence = true)
    }

    private fun urlEncode(value: String): String = buildString {
      for (byte in value.encodeToByteArray()) {
        val number = byte.toInt() and 255
        val char = number.toChar()
        if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~") {
          append(char)
        } else {
          append('%')
          append(number.toString(16).uppercase().padStart(2, '0'))
        }
      }
    }
  }
}
