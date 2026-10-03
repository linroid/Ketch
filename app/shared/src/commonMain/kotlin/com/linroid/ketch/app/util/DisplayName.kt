package com.linroid.ketch.app.util

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.isDirectory

/**
 * Name to show for a task in rows, search, sorting, dialogs, toasts and notifications.
 *
 * Takes the first non-blank of: the file name of the completed output, the file name of a
 * [Destination] that is not a directory, the source's suggested file name, the magnet `dn`
 * parameter, the percent-decoded last segment of the URL path, "Magnet {first 8 hex digits of
 * the info hash}" and the URL host.
 */
fun displayName(request: DownloadRequest, state: DownloadState? = null): String {
  val outputPath = (state as? DownloadState.Completed)?.outputPath
  return outputPath?.let(::pathFileName)
    ?: request.destination?.let(::destinationFileName)
    ?: request.resolvedSource?.suggestedFileName?.trim()?.ifEmpty { null }
    ?: magnetName(request.url)
    ?: urlFileName(request.url)
    ?: infoHashName(request.url)
    ?: urlHost(request.url)
    ?: request.url.trim()
}

/**
 * Host of [url] in lower case, without user info, port or IPv6 brackets, like the key of the
 * engine's per-host limit. Returns `null` for URIs without a network host: magnet links,
 * `torrent:` identifiers, `file:` URLs and local paths.
 */
fun urlHost(url: String): String? {
  val match = AUTHORITY.find(url.trim()) ?: return null
  if (match.groupValues[1].equals("file", ignoreCase = true)) return null
  val hostPort = match.groupValues[2].substringAfterLast('@')
  val host = if (hostPort.startsWith('[')) {
    hostPort.substring(1).substringBefore(']')
  } else {
    hostPort.substringBefore(':')
  }
  return host.lowercase().ifEmpty { null }
}

/**
 * Decodes `%XX` escapes in [value] as UTF-8, leaving malformed escapes as they are. With
 * [plusAsSpace], `+` decodes to a space, as in form-encoded values such as a magnet's `dn`.
 */
internal fun percentDecode(value: String, plusAsSpace: Boolean = false): String {
  if ('%' !in value && !(plusAsSpace && '+' in value)) return value
  val input = value.encodeToByteArray()
  val output = ByteArray(input.size)
  var size = 0
  var i = 0
  while (i < input.size) {
    val byte = input[i]
    val high = if (byte == PERCENT && i + 2 < input.size) hexValue(input[i + 1]) else -1
    val low = if (high >= 0) hexValue(input[i + 2]) else -1
    when {
      low >= 0 -> {
        output[size++] = (high * 16 + low).toByte()
        i += 3
      }
      plusAsSpace && byte == PLUS -> {
        output[size++] = SPACE
        i++
      }
      else -> {
        output[size++] = byte
        i++
      }
    }
  }
  return output.decodeToString(0, size)
}

/** Whether [request] downloads a torrent: a magnet, `torrent:` or `.torrent` link. */
internal fun isTorrent(request: DownloadRequest): Boolean {
  val url = request.url.trim().lowercase()
  return url.startsWith(MAGNET) || url.startsWith(TORRENT) ||
    url.substringBefore('?').substringBefore('#').endsWith(".torrent") ||
    request.resolvedSource?.sourceType == "torrent"
}

private fun destinationFileName(destination: Destination): String? {
  if (destination.isDirectory()) return null
  return pathFileName(destination.value)
}

/**
 * Last component of a file path or Android `content://` URI. Both separators count, because a
 * remote device may run another operating system. Directories yield `null`.
 */
private fun pathFileName(path: String): String? {
  val value = path.trim()
  val name = if (value.startsWith(CONTENT, ignoreCase = true)) {
    contentUriFileName(value)
  } else {
    value.substringAfterLast('/').substringAfterLast('\\')
  }
  return name?.trim()?.takeUnless { it.isEmpty() || it == "." || it == ".." }
}

/**
 * File name in a document URI such as `content://…/document/primary%3ADownload%2Fa.iso`, whose
 * last segment is a document id that ends in the path or name of the file. A tree URI
 * (`content://…/tree/{id}`) is a folder, and an opaque numeric id (`msf:1234`, or the
 * `content://media/…/1234` row of a media store) names no file.
 */
private fun contentUriFileName(uri: String): String? {
  val segments = uri.substringAfter("://").substringBefore('?').substringBefore('#')
    .split('/').drop(1).filter { it.isNotEmpty() }
  if (segments.firstOrNull() == "tree" && segments.size <= 2) return null
  val id = percentDecode(segments.lastOrNull() ?: return null)
  val name = if ('/' in id) id.substringAfterLast('/') else id.substringAfterLast(':')
  return name.takeUnless { it.all(Char::isDigit) }
}

private fun magnetName(url: String): String? {
  val dn = magnetParams(url)?.firstOrNull { it.first == "dn" }?.second ?: return null
  return percentDecode(dn, plusAsSpace = true).trim().ifEmpty { null }
}

private fun urlFileName(url: String): String? {
  val value = url.trim()
  val lower = value.lowercase()
  if (lower.startsWith(MAGNET) || lower.startsWith(TORRENT)) return null
  val withoutQuery = value.substringBefore('#').substringBefore('?')
  val schemeEnd = withoutQuery.indexOf("://")
  val path = if (schemeEnd < 0) {
    withoutQuery
  } else {
    withoutQuery.substring(schemeEnd + 3).substringAfter('/', "")
  }
  return path.split('/')
    .map { percentDecode(it).substringAfterLast('/').trim() }
    .lastOrNull { it.isNotEmpty() && it != "." && it != ".." }
}

private fun infoHashName(url: String): String? {
  val value = url.trim()
  if (value.lowercase().startsWith(TORRENT)) {
    val hash = value.substring(TORRENT.length).lowercase()
    return if (hash.length >= 8 && hash.all(::isHexDigit)) "Torrent ${hash.take(8)}" else null
  }
  val topics = magnetParams(value)
    ?.filter { it.first == "xt" }
    ?.map { percentDecode(it.second).lowercase() }
    ?: return null
  val hex = topics.firstNotNullOfOrNull { topic ->
    if (topic.startsWith(BTIH)) btihHex(topic.substring(BTIH.length)) else null
  } ?: topics.firstNotNullOfOrNull { topic ->
    // A v2 topic is a SHA-256 multihash: "1220" followed by the digest.
    topic.takeIf { it.startsWith(BTMH) }?.substring(BTMH.length)?.removePrefix("1220")
      ?.takeIf { it.length >= 8 && it.all(::isHexDigit) }
  } ?: return null
  return "Magnet ${hex.take(8)}"
}

/** Hex form of a btih info hash, given as 40 hex digits or 32 base32 characters. */
private fun btihHex(hash: String): String? = when {
  hash.length == 40 && hash.all(::isHexDigit) -> hash
  hash.length == 32 -> base32ToHex(hash)
  else -> null
}

/** Hex form of a base32 info hash, or `null` when [value] is not base32. */
internal fun base32ToHex(value: String): String? {
  val hex = StringBuilder()
  var buffer = 0
  var bits = 0
  for (char in value.uppercase()) {
    val digit = BASE32.indexOf(char)
    if (digit < 0) return null
    buffer = (buffer shl 5) or digit
    bits += 5
    if (bits >= 8) {
      bits -= 8
      val byte = (buffer shr bits) and 0xFF
      hex.append(HEX[byte shr 4]).append(HEX[byte and 0x0F])
    }
  }
  return hex.toString()
}

private fun magnetParams(url: String): List<Pair<String, String>>? {
  val value = url.trim()
  if (!value.lowercase().startsWith(MAGNET)) return null
  return value.substringAfter('?', "").split('&').mapNotNull { param ->
    if ('=' !in param) null else param.substringBefore('=').lowercase() to param.substringAfter('=')
  }
}

/** Value of the hex digit [char], or -1. */
internal fun hexValue(char: Char): Int = when (char) {
  in '0'..'9' -> char - '0'
  in 'a'..'f' -> char - 'a' + 10
  in 'A'..'F' -> char - 'A' + 10
  else -> -1
}

private fun hexValue(byte: Byte): Int = hexValue(byte.toInt().toChar())

/** [value] as UTF-8, every byte but the unreserved characters of RFC 3986 escaped as `%XX`. */
internal fun percentEncode(value: String): String = buildString {
  for (byte in value.encodeToByteArray()) {
    val code = byte.toInt() and 0xFF
    if (code < 0x80 && isUnreserved(code.toChar())) append(code.toChar()) else appendEscaped(code)
  }
}

/** Whether [char] is an unreserved character of RFC 3986, which never needs an escape. */
internal fun isUnreserved(char: Char): Boolean = char in 'A'..'Z' || char in 'a'..'z' ||
  char in '0'..'9' || char == '-' || char == '.' || char == '_' || char == '~'

/** Appends [byte] as a `%XX` escape with upper-case digits. */
internal fun StringBuilder.appendEscaped(byte: Int) {
  append('%').append(HEX_UPPER[byte shr 4]).append(HEX_UPPER[byte and 0x0F])
}

private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f'

private val AUTHORITY = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)""")
private const val CONTENT = "content://"
private const val MAGNET = "magnet:"
private const val TORRENT = "torrent:"
private const val BTIH = "urn:btih:"
private const val BTMH = "urn:btmh:"
private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val HEX = "0123456789abcdef"
private const val HEX_UPPER = "0123456789ABCDEF"
private const val PERCENT = '%'.code.toByte()
private const val PLUS = '+'.code.toByte()
private const val SPACE = ' '.code.toByte()
