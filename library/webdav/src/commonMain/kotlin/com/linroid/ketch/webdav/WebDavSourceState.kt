package com.linroid.ketch.webdav

import kotlinx.serialization.Serializable

/**
 * What [WebDavDownloadSource] saves to resume a download: the file's identity as the download
 * started, which a resume compares with the server's before it continues.
 *
 * @property etag the file's entity tag (`getetag`, else the `ETag` header), or `null`
 * @property lastModified its modification time (`getlastmodified`, else `Last-Modified`), or
 *   `null`
 * @property totalBytes its size, `-1` when the server did not report it
 */
@Serializable
data class WebDavSourceState(
  val etag: String? = null,
  val lastModified: String? = null,
  val totalBytes: Long = -1,
)
