package com.linroid.kdown.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Describes a file to download.
 *
 * @property url the HTTP(S) URL to download from. Must not be blank.
 * @property directory the local directory where the file will be saved.
 *   When `null`, the implementation chooses a default location.
 * @property fileName explicit file name to save as. When `null`, the
 *   file name is determined from the server response
 *   (Content-Disposition header, URL path, or a fallback).
 * @property connections number of concurrent connections (segments) to
 *   use. Must be greater than 0. Falls back to a single connection if
 *   the server does not support HTTP Range requests.
 * @property headers custom HTTP headers to include in every request
 *   (HEAD and GET) for this download.
 * @property properties arbitrary key-value pairs for use by custom
 *   extensions. KDown itself does not read these values.
 * @property speedLimit per-task speed limit. Overrides the global
 *   speed limit for this download. Defaults to
 *   [SpeedLimit.Unlimited] (use global limit).
 * @property priority queue priority for this download. Higher-priority
 *   tasks are started before lower-priority ones when download slots
 *   become available. Defaults to [DownloadPriority.NORMAL].
 * @property schedule when the download should start. Defaults to
 *   [DownloadSchedule.Immediate].
 * @property conditions list of [DownloadCondition]s that must all be
 *   met before the download starts. Not persisted across restarts.
 * @property selectedFileIds IDs of files selected from
 *   [ResolvedSource.files]. Empty means download all/default.
 *   Sources read this via the download context to determine
 *   which files to download.
 * @property resolvedUrl pre-resolved URL metadata from
 *   [KDownApi.resolve]. When present, the download engine skips
 *   its own probe and uses this information directly. Not persisted
 *   across restarts.
 */
@Serializable
data class DownloadRequest(
  val url: String,
  val directory: String? = null,
  val fileName: String? = null,
  val connections: Int = 1,
  val headers: Map<String, String> = emptyMap(),
  val properties: Map<String, String> = emptyMap(),
  val speedLimit: SpeedLimit = SpeedLimit.Unlimited,
  val priority: DownloadPriority = DownloadPriority.NORMAL,
  val schedule: DownloadSchedule = DownloadSchedule.Immediate,
  val selectedFileIds: Set<String> = emptySet(),
  @Transient
  val conditions: List<DownloadCondition> = emptyList(),
  @Transient
  val resolvedUrl: ResolvedSource? = null,
) {
  init {
    require(url.isNotBlank()) { "URL must not be blank" }
    require(connections > 0) { "Connections must be greater than 0" }
  }
}
