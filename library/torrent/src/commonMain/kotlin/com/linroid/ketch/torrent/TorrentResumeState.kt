package com.linroid.ketch.torrent

import kotlinx.serialization.Serializable

/**
 * Persisted resume state for torrent downloads.
 *
 * @property infoHash hex-encoded info hash
 * @property totalBytes total selected bytes
 * @property resumeData base64-encoded libtorrent resume data
 * @property selectedFileIds set of selected file index strings
 * @property savePath directory where files are saved
 */
@Serializable
internal data class TorrentResumeState(
  val infoHash: String,
  val totalBytes: Long,
  val resumeData: String,
  val selectedFileIds: Set<String>,
  val savePath: String,
)
