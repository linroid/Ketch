package com.linroid.kdown.core.engine

import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.ResolvedSource
import com.linroid.kdown.api.Segment
import com.linroid.kdown.core.file.FileAccessor
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bundles everything a [DownloadSource] needs to execute a download.
 *
 * @property taskId unique identifier for the download task
 * @property url the URL to download from
 * @property request the original download request
 * @property fileAccessor random-access file writer for the destination
 * @property segments mutable flow of segment progress; sources update
 *   this as segments are created and progress
 * @property onProgress callback to report download progress
 *   (downloadedBytes, totalBytes)
 * @property throttle callback to apply speed limiting; sources must
 *   call this with the number of bytes before writing each chunk.
 *   This replaces direct [SpeedLimiter] access to avoid cross-module
 *   visibility issues with internal types.
 * @property headers HTTP headers or source-specific metadata headers
 * @property preResolved pre-resolved URL metadata, allowing the
 *   download source to skip its own probe/HEAD request
 * @property maxConnections observable override for the number of
 *   concurrent segment connections. When positive, takes precedence
 *   over [DownloadRequest.connections]. Emitting a new value triggers
 *   live resegmentation in [HttpDownloadSource]. Reduced automatically
 *   on HTTP 429 (Too Many Requests) responses.
 * @property pendingResegment target connection count for a pending
 *   resegmentation. Set by the connection-change watcher before
 *   canceling the download batch scope. Read by [HttpDownloadSource]
 *   to distinguish resegment-cancel from external cancel.
 */
class DownloadContext(
  val taskId: String,
  val url: String,
  val request: DownloadRequest,
  val fileAccessor: FileAccessor,
  val segments: MutableStateFlow<List<Segment>>,
  val onProgress: suspend (downloaded: Long, total: Long) -> Unit,
  val throttle: suspend (bytes: Int) -> Unit,
  val headers: Map<String, String>,
  val preResolved: ResolvedSource? = null,
  val maxConnections: MutableStateFlow<Int> = MutableStateFlow(0),
  var pendingResegment: Int = 0,
)
