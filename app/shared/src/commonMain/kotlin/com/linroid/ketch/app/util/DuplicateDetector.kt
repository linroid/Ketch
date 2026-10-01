package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource

/**
 * Finds the existing download a new link would repeat: one whose URL is the same once
 * normalized, or, for torrents, one with the same info hash, so a magnet link matches a task
 * added from a `.torrent` file of the same torrent.
 *
 * @param existing entries to search, such as the target device's tasks, oldest first.
 * @param requestOf the request each entry downloads.
 */
class DuplicateDetector<T>(
  existing: List<T>,
  requestOf: (T) -> DownloadRequest,
) {
  private val byKey: Map<String, T> = buildMap {
    for (entry in existing) {
      val request = requestOf(entry)
      duplicateKeys(request.url, request.resolvedSource).forEach { put(it, entry) }
    }
  }

  /**
   * The entry that downloads the same thing as [url], or `null` when there is none. When several
   * do, the last one in `existing` wins: the most recent when entries are oldest first.
   *
   * @param resolved what [url] resolved to, if known; its info hash also matches torrents that
   *   were added from another link.
   */
  fun find(url: String, resolved: ResolvedSource? = null): T? =
    duplicateKeys(url, resolved).firstNotNullOfOrNull { byKey[it] }
}

/**
 * Keys that identify what [url] downloads; links that share a key download the same thing.
 * A magnet or `torrent:` link is identified by its info hashes (`btih:` and `btmh:` keys in
 * lowercase hex), any other link by its URL normalized as [normalizeUrl] describes. [resolved]
 * adds the keys of the URL and info hash it resolved to.
 */
fun duplicateKeys(url: String, resolved: ResolvedSource? = null): Set<String> = buildSet {
  addAll(linkKeys(url))
  if (resolved != null) {
    if (resolved.url != url) addAll(linkKeys(resolved.url))
    resolved.metadata[INFO_HASH_METADATA]?.let(::infoHashKey)?.let(::add)
  }
}

/**
 * [url] in a form that compares equal for links to the same file: the fragment and user info
 * are dropped, the scheme and host are lowercased, a default port is removed, an empty path
 * becomes `/`, percent-encoded unreserved characters are decoded, other percent-encodings are
 * uppercased, and characters that a URL cannot hold as they are (spaces, non-ASCII, `{`, a `%`
 * that starts no escape) are percent-encoded as UTF-8. Reserved characters stay as they are, so
 * `a%2Fb` and `a/b` remain different paths.
 */
internal fun normalizeUrl(url: String): String {
  val trimmed = url.trim()
  val authority = authorityOf(trimmed) ?: return normalizePercent(trimmed.substringBefore('#'))
  val scheme = trimmed.substring(0, authority.start - 3).lowercase()
  var host = trimmed.substring(authority.hostStart, authority.end).lowercase()
  DEFAULT_PORTS[scheme]?.let { port -> host = host.removeSuffix(":$port") }
  val rest = trimmed.substring(authority.end).substringBefore('#')
  val path = if (rest.isEmpty() || rest.startsWith('?')) "/$rest" else rest
  return "$scheme://$host${normalizePercent(path)}"
}

private const val INFO_HASH_METADATA = "infoHash"
private const val HEX_UPPER = "0123456789ABCDEF"
private const val HEX_LOWER = "0123456789abcdef"
private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val RESERVED = ":/?#[]@!$&'()*+,;="

// The multihash prefix of a SHA-256 digest (code 0x12, 32 bytes) in a `urn:btmh:` topic.
private const val SHA256_MULTIHASH_PREFIX = "1220"

private val DEFAULT_PORTS = mapOf("http" to "80", "https" to "443", "ftp" to "21", "ftps" to "990")

private fun linkKeys(url: String): Set<String> {
  val trimmed = url.trim()
  val hashes = when {
    trimmed.startsWith("magnet:", ignoreCase = true) -> magnetKeys(trimmed)
    trimmed.startsWith("torrent:", ignoreCase = true) ->
      setOfNotNull(infoHashKey(trimmed.substring("torrent:".length)))
    else -> emptySet()
  }
  return hashes.ifEmpty { setOf("url:" + normalizeUrl(trimmed)) }
}

private fun magnetKeys(magnet: String): Set<String> = buildSet {
  for (param in magnet.substringAfter('?', "").split('&')) {
    if (!param.startsWith("xt=", ignoreCase = true)) continue
    val topic = decodePercentEscapes(param.substring(3))
    when {
      topic.startsWith("urn:btih:", ignoreCase = true) ->
        infoHashKey(topic.substring("urn:btih:".length))?.let(::add)
      topic.startsWith("urn:btmh:", ignoreCase = true) -> {
        val multihash = topic.substring("urn:btmh:".length)
        if (multihash.startsWith(SHA256_MULTIHASH_PREFIX)) {
          infoHashKey(multihash.substring(SHA256_MULTIHASH_PREFIX.length))?.let(::add)
        }
      }
    }
  }
}

/** `btih:` for a 40-hex or 32-base32 v1 hash, `btmh:` for a 64-hex v2 hash, else `null`. */
private fun infoHashKey(hash: String): String? = when {
  hash.length == 40 && hash.all { hexValue(it) >= 0 } -> "btih:" + hash.lowercase()
  hash.length == 64 && hash.all { hexValue(it) >= 0 } -> "btmh:" + hash.lowercase()
  hash.length == 32 -> base32ToHex(hash)?.let { "btih:$it" }
  else -> null
}

private fun base32ToHex(value: String): String? {
  val hex = StringBuilder()
  var buffer = 0
  var bits = 0
  for (char in value.uppercase()) {
    val digit = BASE32_ALPHABET.indexOf(char)
    if (digit < 0) return null
    buffer = ((buffer shl 5) or digit) and 0xFFF
    bits += 5
    if (bits >= 8) {
      bits -= 8
      val byte = (buffer shr bits) and 0xFF
      hex.append(HEX_LOWER[byte shr 4]).append(HEX_LOWER[byte and 0x0F])
    }
  }
  return hex.toString()
}

private fun hexValue(char: Char): Int = when (char) {
  in '0'..'9' -> char - '0'
  in 'a'..'f' -> char - 'a' + 10
  in 'A'..'F' -> char - 'A' + 10
  else -> -1
}

private fun isUnreserved(char: Char): Boolean = char in 'A'..'Z' || char in 'a'..'z' ||
  char in '0'..'9' || char == '-' || char == '.' || char == '_' || char == '~'

/** The escaped byte at [index] when it starts a `%XX` escape, else -1. */
private fun escapedByte(value: String, index: Int): Int {
  if (value[index] != '%' || index + 2 >= value.length) return -1
  val high = hexValue(value[index + 1])
  val low = hexValue(value[index + 2])
  return if (high < 0 || low < 0) -1 else (high shl 4) or low
}

private fun StringBuilder.appendEscaped(byte: Int) {
  append('%').append(HEX_UPPER[byte shr 4]).append(HEX_UPPER[byte and 0x0F])
}

/** Length of the character at [index], two for a surrogate pair. */
private fun charLength(value: String, index: Int): Int =
  if (value[index].isHighSurrogate() && index + 1 < value.length) 2 else 1

private fun normalizePercent(value: String): String = buildString {
  var i = 0
  while (i < value.length) {
    val char = value[i]
    val byte = escapedByte(value, i)
    when {
      byte >= 0 -> {
        val unreserved = byte < 0x80 && isUnreserved(byte.toChar())
        if (unreserved) append(byte.toChar()) else appendEscaped(byte)
        i += 3
      }
      char == '%' -> {
        append("%25")
        i++
      }
      char.code < 0x80 && (isUnreserved(char) || char in RESERVED) -> {
        append(char)
        i++
      }
      else -> {
        val length = charLength(value, i)
        for (utf8 in value.substring(i, i + length).encodeToByteArray()) {
          appendEscaped(utf8.toInt() and 0xFF)
        }
        i += length
      }
    }
  }
}

/** Decodes every `%XX` escape as UTF-8; a `%` that starts no escape stays as it is. */
private fun decodePercentEscapes(value: String): String {
  if ('%' !in value) return value
  val bytes = ArrayList<Byte>(value.length)
  var i = 0
  while (i < value.length) {
    val byte = escapedByte(value, i)
    if (byte >= 0) {
      bytes.add(byte.toByte())
      i += 3
    } else {
      val length = charLength(value, i)
      value.substring(i, i + length).encodeToByteArray().forEach(bytes::add)
      i += length
    }
  }
  return bytes.toByteArray().decodeToString()
}
