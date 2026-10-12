package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * Describes a download.
 *
 * @property url what to download; must not be blank
 * @property destination where to save: `null` for the default directory and the server's file
 *   name, a folder (ending in `/`), a bare file name in the default directory
 *   ([Destination.isName]), a full file path, or a content URI
 * @property connections connections (segments) to use, or `0` for
 *   [DownloadConfig.maxConnectionsPerDownload]. Servers without ranges use one; BitTorrent treats
 *   it as its peer limit.
 * @property headers HTTP headers for every request. Names must be tokens and values free of
 *   control characters, or [KetchApi.download] rejects them; `Host`, `Range`, `Content-Length`
 *   and hop-by-hop headers are dropped. A redirect to another origin keeps only `User-Agent`,
 *   the `Accept*` headers and `Referer`'s origin, so credentials stay with their site.
 * @property properties free-form values for extensions, which the engine ignores. `ketch.origin`
 *   names where the download was added (`browser`, `discover`, `agent`, `app` or `cli`).
 * @property speedLimit a cap on top of [DownloadConfig.speedLimit]; the lower one wins
 * @property priority queue priority
 * @property schedule when to start
 * @property conditions what must all hold before starting; not saved
 * @property selectedFileIds which of [ResolvedSource.files] to download; empty for the default
 * @property resolvedSource the result of [KetchApi.resolve], which skips the probe. Saved
 *   without its bulky metadata ([ResolvedSource.withoutBulkMetadata]) once the download starts.
 * @property proxy replaces [DownloadConfig.proxy] for this download; saved with its password
 * @property requestId a caller's UUID for this submission. With [KetchFeatures.REQUEST_ID], an
 *   identical resubmission returns the existing task, unchanged; reusing it with another source,
 *   destination, headers, properties, proxy or checksum is rejected.
 * @property awaitFileSelection with no [selectedFileIds], pause for
 *   [PauseReason.AwaitingFileSelection] once several files or variants are known
 *   ([KetchFeatures.AWAIT_FILE_SELECTION])
 * @property checksum the digest the file must have, checked once it is saved, failing with
 *   [KetchError.ChecksumMismatch]. Without one, HTTP downloads are checked against a digest the
 *   server publishes. Only for sources Ketch writes as one file (not torrents); needs
 *   [KetchFeatures.CHECKSUM].
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
  val requestId: String? = null,
  val proxy: ProxyConfig? = null,
  val awaitFileSelection: Boolean = false,
  val checksum: Checksum? = null,
) {
  init {
    require(url.isNotBlank()) { "URL must not be blank" }
    require(connections >= 0) { "Connections must be non-negative" }
    require(requestId == null || REQUEST_ID.matches(requestId)) { "Request ID must be a UUID" }
  }

}

private val REQUEST_ID = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
