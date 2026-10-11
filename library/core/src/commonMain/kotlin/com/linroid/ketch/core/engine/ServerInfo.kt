package com.linroid.ketch.core.engine

import com.linroid.ketch.api.Checksum
import com.linroid.ketch.api.ChecksumAlgorithm

/**
 * Metadata returned by an HTTP HEAD request.
 *
 * Used to determine download strategy (segmented vs. single) and
 * to validate resume integrity.
 *
 * @property contentLength total content size in bytes, or `null` if unknown
 * @property acceptRanges `true` if the server advertises `Accept-Ranges: bytes`
 * @property etag the `ETag` header value, used for resume validation
 * @property lastModified the `Last-Modified` header value, used for resume validation
 * @property contentDisposition the `Content-Disposition` header, used for file name resolution
 * @property rateLimitRemaining number of requests remaining in the current rate limit window
 *   (`RateLimit-Remaining` header), or `null` if absent
 * @property rateLimitReset seconds until the rate limit window resets
 *   (`RateLimit-Reset` header), or `null` if absent
 * @property contentType the `Content-Type` header, which category folders match by media type
 * @property checksum the digest the server publishes for the whole file, read with
 *   [publishedChecksum]; downloads without a [com.linroid.ketch.api.DownloadRequest.checksum]
 *   are verified against it
 */
data class ServerInfo(
  val contentLength: Long?,
  val acceptRanges: Boolean,
  val etag: String?,
  val lastModified: String?,
  val contentDisposition: String? = null,
  val rateLimitRemaining: Long? = null,
  val rateLimitReset: Long? = null,
  val contentType: String? = null,
  val checksum: Checksum? = null,
) {
  /** `true` when the server supports byte-range requests and reports a content length. */
  val supportsResume: Boolean
    get() = acceptRanges && contentLength != null && contentLength > 0

  companion object {
    /**
     * The digest a response publishes for the whole, unencoded file, or `null`: the strongest
     * supported entry of [reprDigest] (RFC 9530 `Repr-Digest`, such as `sha-256=:<base64>:`),
     * else of [digest] (RFC 3230 `Digest`, such as `SHA-256=<base64>`). Pass the legacy
     * [digest] only for a response that carries the whole file, such as a `HEAD` or a `200`
     * answer, never a `206`: some servers computed it over the part they sent.
     *
     * Nothing is published for a response with a [contentEncoding] other than `identity`, whose
     * digest covers the encoded bytes rather than the file Ketch saves. `Content-Digest` is
     * never used for the same reason. Entries Ketch cannot read are skipped.
     */
    fun publishedChecksum(
      reprDigest: String?,
      digest: String?,
      contentEncoding: String?,
    ): Checksum? {
      val encoding = contentEncoding?.trim().orEmpty()
      if (encoding.isNotEmpty() && !encoding.equals("identity", ignoreCase = true)) return null
      return strongest(reprDigest) ?: strongest(digest)
    }

    private fun strongest(header: String?): Checksum? {
      if (header.isNullOrBlank()) return null
      return header.split(',').mapNotNull { entry ->
        // Structured field parameters (`;...`) follow the value.
        val item = entry.substringBefore(';').trim()
        val separator = item.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val name = item.substring(0, separator).trim().lowercase()
        // RFC 3230 calls SHA-1 `SHA`.
        val algorithm = when (name) {
          "sha" -> ChecksumAlgorithm.SHA_1
          else -> ChecksumAlgorithm.named(name) ?: return@mapNotNull null
        }
        try {
          Checksum.parse(algorithm, item.substring(separator + 1).trim().removeSurrounding("\""))
        } catch (_: IllegalArgumentException) {
          null
        }
      }.maxByOrNull { STRENGTH.indexOf(it.algorithm) }
    }

    private val STRENGTH = listOf(
      ChecksumAlgorithm.MD5,
      ChecksumAlgorithm.SHA_1,
      ChecksumAlgorithm.SHA_256,
      ChecksumAlgorithm.SHA_512,
    )
  }
}
