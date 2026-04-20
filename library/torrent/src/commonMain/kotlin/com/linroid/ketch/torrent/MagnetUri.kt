package com.linroid.ketch.torrent

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
) {

  /** Reconstructs the magnet URI string. */
  fun toUri(): String = buildString {
    append("magnet:?xt=urn:btih:")
    append(infoHash.hex)
    if (displayName != null) {
      append("&dn=")
      append(urlEncode(displayName))
    }
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
      val query = uri.substringAfter('?')
      val params = query.split('&')

      var infoHash: InfoHash? = null
      var displayName: String? = null
      val trackers = mutableListOf<String>()

      for (param in params) {
        val (key, value) = param.split('=', limit = 2)
          .let { if (it.size == 2) it[0] to it[1] else continue }

        when (key.lowercase()) {
          "xt" -> {
            val lower = value.lowercase()
            if (lower.startsWith("urn:btih:")) {
              val hash = value.substring(9)
              infoHash = parseInfoHash(hash)
            }
          }
          "dn" -> displayName = urlDecode(value)
          "tr" -> trackers.add(urlDecode(value))
        }
      }

      requireNotNull(infoHash) {
        "Magnet URI missing xt=urn:btih: parameter"
      }

      return MagnetUri(
        infoHash = infoHash,
        displayName = displayName,
        trackers = trackers,
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
      return buildString {
        var i = 0
        while (i < value.length) {
          when {
            value[i] == '%' && i + 2 < value.length -> {
              val hex = value.substring(i + 1, i + 3)
              val byte = hex.toIntOrNull(16)
              if (byte != null) {
                append(byte.toChar())
                i += 3
              } else {
                append(value[i])
                i++
              }
            }
            value[i] == '+' -> {
              append(' ')
              i++
            }
            else -> {
              append(value[i])
              i++
            }
          }
        }
      }
    }

    private fun urlEncode(value: String): String {
      return buildString {
        for (ch in value) {
          when {
            ch.isLetterOrDigit() || ch in "-._~" -> append(ch)
            else -> {
              val bytes = ch.toString().encodeToByteArray()
              for (b in bytes) {
                append('%')
                append(
                  (b.toInt() and 0xFF).toString(16)
                    .uppercase().padStart(2, '0')
                )
              }
            }
          }
        }
      }
    }
  }
}
