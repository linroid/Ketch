package com.linroid.ketch.torrent

import kotlin.io.encoding.Base64

private const val DATA_SCHEME = "data:"
private const val METAINFO_MEDIA_TYPE = "application/x-bittorrent"

/**
 * Whether [url] is an RFC 2397 data URL carrying `.torrent` metainfo, such as
 * `data:application/x-bittorrent;base64,ZDg6...`. Apps use it to hand an opened file to any
 * backend, including a remote one that cannot read the local filesystem.
 */
internal fun isMetainfoDataUrl(url: String): Boolean {
  val prefix = DATA_SCHEME + METAINFO_MEDIA_TYPE
  if (!url.startsWith(prefix, ignoreCase = true)) return false
  val next = url.getOrNull(prefix.length)
  return next == ';' || next == ','
}

/**
 * Decodes the metainfo carried by a URL accepted by [isMetainfoDataUrl]. Only base64 payloads
 * are supported, and the encoded length is checked before decoding so an oversized URL is
 * rejected without allocating its content.
 */
internal fun decodeMetainfoDataUrl(url: String, maxBytes: Int): ByteArray {
  val comma = url.indexOf(',')
  require(comma >= 0) { "Malformed torrent data URL" }
  val parameters = url.substring(DATA_SCHEME.length, comma).split(';')
  require(parameters.first().equals(METAINFO_MEDIA_TYPE, ignoreCase = true)) {
    "Not a torrent data URL"
  }
  require(parameters.size > 1 && parameters.last().equals("base64", ignoreCase = true)) {
    "Torrent data URLs must be base64 encoded"
  }
  val maxEncodedLength = (maxBytes.toLong() + 2) / 3 * 4
  require(url.length - comma - 1 <= maxEncodedLength) { "Metainfo exceeds limit" }
  val bytes = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    .decode(url, comma + 1, url.length)
  require(bytes.size <= maxBytes) { "Metainfo exceeds limit" }
  return bytes
}
