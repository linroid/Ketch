package com.linroid.kdown.core.engine

import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.core.file.FileAccessor
import com.linroid.kdown.api.Segment
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
 */
class DownloadContext(
  val taskId: String,
  val url: String,
  val request: DownloadRequest,
  val fileAccessor: FileAccessor,
  val segments: MutableStateFlow<List<Segment>>,
  val onProgress: suspend (downloaded: Long, total: Long) -> Unit,
  val throttle: suspend (bytes: Int) -> Unit,
  val headers: Map<String, String>
)
