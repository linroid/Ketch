package com.linroid.kdown

import com.linroid.kdown.file.FileNameResolver
import com.linroid.kdown.file.PathSerializer
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

/**
 * Describes a file to download.
 *
 * @property url the HTTP(S) URL to download from. Must not be blank.
 * @property directory the local directory where the file will be saved.
 * @property fileName explicit file name to save as. When `null`, the
 *   [FileNameResolver] determines the name from the server response
 *   (Content-Disposition header, URL path, or a fallback).
 * @property connections number of concurrent connections (segments) to
 *   use. Must be greater than 0. Falls back to a single connection if
 *   the server does not support HTTP Range requests.
 * @property headers custom HTTP headers to include in every request
 *   (HEAD and GET) for this download.
 * @property properties arbitrary key-value pairs for use by custom
 *   [FileNameResolver] implementations or other extensions. KDown
 *   itself does not read these values.
 * @property speedLimit per-task speed limit. Overrides the global
 *   [DownloadConfig.speedLimit] for this download. Defaults to
 *   [SpeedLimit.Unlimited] (use global limit).
 */
@Serializable
data class DownloadRequest(
  val url: String,
  @Serializable(with = PathSerializer::class)
  val directory: Path,
  val fileName: String? = null,
  val connections: Int = 1,
  val headers: Map<String, String> = emptyMap(),
  val properties: Map<String, String> = emptyMap(),
  val speedLimit: SpeedLimit = SpeedLimit.Unlimited
) {
  init {
    require(url.isNotBlank()) { "URL must not be blank" }
    require(connections > 0) { "Connections must be greater than 0" }
  }
}
