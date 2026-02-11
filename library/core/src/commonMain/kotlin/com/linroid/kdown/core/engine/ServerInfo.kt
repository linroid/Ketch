package com.linroid.kdown.core.engine

data class ServerInfo(
  val contentLength: Long?,
  val acceptRanges: Boolean,
  val etag: String?,
  val lastModified: String?,
  val contentDisposition: String? = null
) {
  val supportsResume: Boolean
    get() = acceptRanges && contentLength != null && contentLength > 0
}
