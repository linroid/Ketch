package com.linroid.ketch.core.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.file.FileAccessor
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
 *   It enforces both the per-task and the global speed limit.
 *   This replaces direct [SpeedLimiter] access to avoid cross-module
 *   visibility issues with internal types.
 * @property headers request headers from [DownloadRequest.headers].
 *   Used by HTTP sources for custom headers; other sources may ignore.
 * @property preResolved pre-resolved URL metadata, allowing the
 *   download source to skip its own probe/HEAD request
 * @property maxConnections observable override for the number of
 *   concurrent segment connections. When positive, takes precedence
 *   over [DownloadRequest.connections]. Emitting a new value triggers
 *   live resegmentation in sources that support it. Reduced
 *   automatically on HTTP 429 (Too Many Requests) responses.
 *   Sources that do not split files into byte ranges may interpret it
 *   differently; BitTorrent uses it as the peer connection limit.
 * @property pendingResegment target connection count for a pending
 *   resegmentation. Set by the connection-change watcher before
 *   canceling the download batch scope. Read by sources to
 *   distinguish resegment-cancel from external cancel.
 * @property config snapshot of the global [DownloadConfig] taken when
 *   this download started or resumed. Sources read their defaults from
 *   it instead of their own constructor settings:
 *   [DownloadConfig.maxConnectionsPerDownload] via [effectiveConnections],
 *   [DownloadConfig.progressIntervalMs] for progress throttling and
 *   [DownloadConfig.bufferSize] for socket reads. Retries
 *   ([DownloadConfig.retryCount], [DownloadConfig.retryDelayMs]) are
 *   applied by the engine, which calls [DownloadSource.download] again
 *   after a retryable [com.linroid.ketch.api.KetchError]; sources should
 *   keep the progress recorded in [segments] on such a retry. Sources
 *   that have no use for a setting may ignore it.
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
  /** Final destination resolved by Ketch, including default directory and deduplication. */
  val outputPath: String? = null,
  /** Optional payload speed for sources whose verified progress advances in whole pieces. */
  val reportedSpeed: MutableStateFlow<Long?> = MutableStateFlow(null),
  val config: DownloadConfig = DownloadConfig.Default,
) {
  /**
   * Number of connections a segmented source should use now: a positive
   * [maxConnections] override wins, then a positive
   * [DownloadRequest.connections], then [DownloadConfig.maxConnectionsPerDownload]
   * from [config]. Sources must still use a single connection when the
   * server cannot transfer from arbitrary byte offsets.
   */
  fun effectiveConnections(): Int = when {
    maxConnections.value > 0 -> maxConnections.value
    request.connections > 0 -> request.connections
    else -> config.maxConnectionsPerDownload
  }
}
