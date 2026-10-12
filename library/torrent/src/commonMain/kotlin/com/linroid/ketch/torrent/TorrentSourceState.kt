package com.linroid.ketch.torrent

import kotlinx.serialization.Serializable

/**
 * What [TorrentDownloadSource] saves to resume a download; opaque outside this module. Ketch's
 * task record, not this state, decides what a task downloads: the request's selection wins, and
 * this one mirrors what the engine last ran with.
 *
 * @property infoHash hex-encoded info hash
 * @property totalBytes total selected bytes
 * @property resumeData base64-encoded engine checkpoint
 * @property selectedFileIds the files the engine last ran with: metainfo indices (v1) or output
 *   mapping IDs (v2 and hybrid). Read only when the task's request has no selection, as in
 *   records older than file selection changes, and as a creation log binding older builds used
 * @property savePath directory where files are saved
 * @property metainfo base64 metainfo, which builds before Ketch kept it in
 *   [com.linroid.ketch.core.task.TaskRecord.resolvedSource] saved here; read only as their
 *   fallback, and carried over for their tasks
 */
@Serializable
@ConsistentCopyVisibility
data class TorrentSourceState internal constructor(
  internal val infoHash: String,
  internal val totalBytes: Long,
  internal val resumeData: String,
  internal val selectedFileIds: Set<String>,
  internal val savePath: String,
  internal val metainfo: String = "",
  internal val version: Int = 1,
  internal val privacy: TorrentDiscoveryPrivacy? = null,
) {
  init {
    require(version in 1..3) { "Unsupported torrent resume version" }
    require(version != 3 || infoHash.length == 64) { "Invalid v2 identity" }
    require((version == 1) == (privacy == null)) { "Invalid torrent resume privacy" }
  }
}
