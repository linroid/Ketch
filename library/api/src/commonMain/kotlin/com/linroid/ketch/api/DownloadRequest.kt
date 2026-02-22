package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * Describes a file to download.
 *
 * @property url the HTTP(S) URL to download from. Must not be blank.
 * @property destination where the file should be saved. Semantics depend
 *   on the value:
 *   - `null` — use the default directory, resolve file name from server
 *   - `Destination("/downloads/")` — directory (trailing `/`), resolve
 *     file name from server
 *   - `Destination("custom.zip")` — bare name ([Destination.isName]),
 *     use default directory
 *   - `Destination("/downloads/custom.zip")` — full file path, use as-is
 *   - `Destination("content://...")` — content URI, use as-is
 * @property connections number of concurrent connections (segments) to
 *   use. Must be non-negative. When `0` (the default), the engine uses
 *   [DownloadConfig.maxConnectionsPerDownload].
 *   Falls back to a single connection if the server does not support
 *   HTTP Range requests.
 * @property headers custom HTTP headers to include in every request
 *   (HEAD and GET) for this download.
 * @property properties arbitrary key-value pairs for use by custom
 *   extensions. Ketch itself does not read these values.
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
 * @property resolvedSource pre-resolved metadata from
 *   [KetchApi.resolve]. When present, the download engine skips
 *   its own probe and uses this information directly. Not persisted
 *   across restarts.
 */
@Serializable
data class DownloadRequest(
  val url: String,
  val destination: Destination? = null,
  val connections: Int = 0,
  val headers: Map<String, String> = emptyMap(),
  val properties: Map<String, String> = emptyMap(),
  val speedLimit: SpeedLimit = SpeedLimit.Unlimited,
  val priority: DownloadPriority = DownloadPriority.NORMAL,
  val schedule: DownloadSchedule = DownloadSchedule.Immediate,
  val selectedFileIds: Set<String> = emptySet(),
  val conditions: List<DownloadCondition> = emptyList(),
  val resolvedSource: ResolvedSource? = null,
) {
  init {
    require(url.isNotBlank()) { "URL must not be blank" }
    require(connections >= 0) { "Connections must be non-negative" }
  }
}
