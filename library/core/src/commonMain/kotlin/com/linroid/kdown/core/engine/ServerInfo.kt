package com.linroid.kdown.core.engine

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
 */
data class ServerInfo(
  val contentLength: Long?,
  val acceptRanges: Boolean,
  val etag: String?,
  val lastModified: String?,
  val contentDisposition: String? = null,
  val rateLimitRemaining: Long? = null,
  val rateLimitReset: Long? = null,
) {
  /** `true` when the server supports byte-range requests and reports a content length. */
  val supportsResume: Boolean
    get() = acceptRanges && contentLength != null && contentLength > 0
}
