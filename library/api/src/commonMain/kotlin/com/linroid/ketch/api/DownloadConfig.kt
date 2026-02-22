package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * Download configuration.
 *
 * @property defaultDirectory Default directory for saving downloaded files when
 *   [DownloadRequest.directory][com.linroid.ketch.api.DownloadRequest.destination] is `null`.
 *   `null` means use the platform default (e.g. `~/Downloads` on desktop,
 *   external storage on Android). Set explicitly to override.
 * @property maxConnectionsPerDownload Maximum number of concurrent segment downloads per task.
 *   Each segment is a separate HTTP Range request. Servers that do not
 *   support Range requests always use a single segment regardless of
 *   this value.
 * @property retryCount Maximum number of retry attempts for failed requests
 * @property retryDelayMs Base delay in milliseconds between retry attempts (uses exponential backoff)
 * @property progressIntervalMs Interval for throttling progress updates to prevent UI spam
 * @property saveIntervalMs Interval for persisting segment progress during downloads
 * @property bufferSize Size of the download buffer in bytes
 * @property speedLimit Global speed limit applied across all downloads
 * @property maxConcurrentDownloads Maximum number of downloads that can
 *   run simultaneously. Additional downloads are queued and started
 *   automatically when slots become available. `0` means unlimited.
 * @property maxConnectionsPerHost Maximum number of concurrent downloads
 *   targeting the same host. Prevents overloading a single server.
 *   `0` means unlimited.
 */
@Serializable
data class DownloadConfig(
  val defaultDirectory: String? = null,
  val retryCount: Int = 3,
  val retryDelayMs: Long = 1000,
  val progressIntervalMs: Long = 200,
  val saveIntervalMs: Long = 5000,
  val bufferSize: Int = 8192,
  val speedLimit: SpeedLimit = SpeedLimit.Unlimited,
  val maxConcurrentDownloads: Int = 2,
  val maxConnectionsPerDownload: Int = 4,
  val maxConnectionsPerHost: Int = 8,
) {
  init {
    require(retryCount >= 0) { "retryCount must be non-negative" }
    require(retryDelayMs >= 0) { "retryDelayMs must be non-negative" }
    require(progressIntervalMs > 0) { "progressIntervalMs must be greater than 0" }
    require(saveIntervalMs > 0) { "saveIntervalMs must be greater than 0" }
    require(bufferSize > 0) { "bufferSize must be greater than 0" }
    require(maxConcurrentDownloads >= 0) { "maxConcurrentDownloads must be non-negative" }
    require(maxConnectionsPerHost >= 0) { "maxConnectionsPerHost must be non-negative" }
    require(maxConnectionsPerDownload > 0) { "maxConnectionsPerDownload must be greater than 0" }
  }

  companion object {
    val Default = DownloadConfig()
  }
}
