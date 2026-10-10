package com.linroid.ketch.torrent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persisted resume state for torrent downloads. Ketch's task record, not this state, decides
 * what a task downloads: the request's selection wins, and this one mirrors what the engine last
 * ran with.
 *
 * @property infoHash hex-encoded info hash
 * @property totalBytes total selected bytes
 * @property resumeData base64-encoded engine checkpoint
 * @property selectedFileIds the files the engine last ran with: metainfo indices (v1) or output
 *   mapping IDs (v2 and hybrid). Read only when the task's request has no selection, as in
 *   records older than file selection changes, and as a creation log binding older builds used
 * @property savePath directory where files are saved
 */
@Serializable
internal data class TorrentResumeState(
  val infoHash: String,
  val totalBytes: Long,
  val resumeData: String,
  val selectedFileIds: Set<String>,
  val savePath: String,
  val metainfo: String = "",
  val version: Int = 1,
  val privacy: TorrentDiscoveryPrivacy? = null,
) {
  init {
    require(version in 1..3) { "Unsupported torrent resume version" }
    require(version != 3 || infoHash.length == 64) { "Invalid v2 identity" }
    require((version == 1) == (privacy == null)) { "Invalid torrent resume privacy" }
  }
}

// Lenient, so states written by newer builds still decode; encoding stays the default Json.
private val ResumeJson = Json { ignoreUnknownKeys = true }

/** Decodes a [TorrentResumeState]; every decode site goes through it. */
internal fun decodeResumeState(data: String): TorrentResumeState =
  ResumeJson.decodeFromString(data)
