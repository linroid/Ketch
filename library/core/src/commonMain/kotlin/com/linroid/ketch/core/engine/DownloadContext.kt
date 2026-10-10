package com.linroid.ketch.core.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
 * @property maxConnections the task's requested connection count for this run, 0 for Auto.
 *   Starts at [DownloadRequest.connections];
 *   [com.linroid.ketch.api.DownloadTask.setConnections] and HTTP 429
 *   handling change it while the task runs, which triggers live resegmentation in sources that
 *   support it. BitTorrent uses it as the peer connection limit, 0 meaning its own default.
 * @property pendingResegment target connection count for a pending
 *   resegmentation. Set by the connection-change watcher before
 *   canceling the download batch scope. Read by sources to
 *   distinguish resegment-cancel from external cancel.
 * @property config snapshot of the global [DownloadConfig] taken when
 *   this download started or resumed, with [DownloadRequest.proxy] in
 *   place of [DownloadConfig.proxy] when the request names one. Sources
 *   read their defaults from it instead of their own constructor settings:
 *   [DownloadConfig.maxConnectionsPerDownload] via [effectiveConnections],
 *   [DownloadConfig.progressIntervalMs] for progress throttling,
 *   [DownloadConfig.bufferSize] for socket reads and [DownloadConfig.proxy]
 *   for HTTP requests ([HttpEngine.withProxy]). Retries
 *   ([DownloadConfig.retryCount], [DownloadConfig.retryDelayMs]) are
 *   applied by the engine, which calls [DownloadSource.download] again
 *   after a retryable [com.linroid.ketch.api.KetchError]; sources should
 *   keep the progress recorded in [segments] on such a retry. Sources
 *   that have no use for a setting may ignore it.
 * @property selection the task's file selection while it runs. Its first value is the selection
 *   the run starts with; [com.linroid.ketch.api.DownloadTask.selectFiles] sends a new revision
 *   while it runs. Sources that implement [DownloadSource.planSelection] apply each revision live
 *   and confirm it with [acknowledgeSelection].
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
  val maxConnections: MutableStateFlow<Int> =
    MutableStateFlow(request.connections.coerceAtLeast(0)),
  var pendingResegment: Int = 0,
  /** Final destination resolved by Ketch, including default directory and deduplication. */
  val outputPath: String? = null,
  /** Optional payload speed for sources whose verified progress advances in whole pieces. */
  val reportedSpeed: MutableStateFlow<Long?> = MutableStateFlow(null),
  val config: DownloadConfig = DownloadConfig.Default,
  val selection: StateFlow<SelectionUpdate> =
    MutableStateFlow(SelectionUpdate(request.selectedFileIds, 0)),
) {
  private val acknowledged = MutableStateFlow(0)

  /** The latest [selection] revision the source confirmed with [acknowledgeSelection]. */
  val acknowledgedSelection: StateFlow<Int> = acknowledged.asStateFlow()

  /**
   * Confirms that this run delivers the files of [selection] revision [revision] before
   * [DownloadSource.download] or [DownloadSource.resume] returns. Never acknowledge a revision
   * the run can no longer act on, such as one that arrives after the transfer finished: Ketch
   * then runs the download again, through [DownloadSource.resume], with the saved selection.
   */
  fun acknowledgeSelection(revision: Int) {
    acknowledged.update { maxOf(it, revision) }
  }

  /**
   * Number of connections a segmented source should use now: [maxConnections] when positive,
   * otherwise (Auto) [DownloadConfig.maxConnectionsPerDownload] from [config]. Sources must
   * still use a single connection when the server cannot transfer from arbitrary byte offsets.
   */
  fun effectiveConnections(): Int = effectiveConnections(maxConnections.value)

  /** What [effectiveConnections] would be with [requested] connections; 0 means Auto. */
  fun effectiveConnections(requested: Int): Int =
    if (requested > 0) requested else config.maxConnectionsPerDownload
}
