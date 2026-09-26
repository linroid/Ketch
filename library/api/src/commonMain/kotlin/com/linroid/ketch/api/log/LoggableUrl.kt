package com.linroid.ketch.api.log

private const val MAX_DATA_URL_HEADER = 64

/**
 * Returns [url] in a form safe to log. Inline `data:` URLs, such as an opened `.torrent` file,
 * can be megabytes long, so they keep only their header and payload length.
 *
 * @suppress This is internal API and should not be used directly by library users.
 */
fun loggableUrl(url: String): String {
  if (!url.startsWith("data:", ignoreCase = true)) return url
  val comma = url.indexOf(',')
  val headerEnd = if (comma < 0) url.length else comma
  val header = url.substring(0, minOf(headerEnd, MAX_DATA_URL_HEADER))
  val payload = if (comma < 0) 0 else url.length - comma - 1
  return "$header,<$payload chars>"
}
