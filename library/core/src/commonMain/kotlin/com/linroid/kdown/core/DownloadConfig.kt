package com.linroid.kdown.core

import com.linroid.kdown.api.SpeedLimit

/**
 * Download configuration.
 *
 * @property maxConnections Maximum number of concurrent segment downloads
 * @property retryCount Maximum number of retry attempts for failed requests
 * @property retryDelayMs Base delay in milliseconds between retry attempts (uses exponential backoff)
 * @property progressUpdateIntervalMs Interval for throttling progress updates to prevent UI spam
 * @property segmentSaveIntervalMs Interval for persisting segment progress during downloads
 * @property bufferSize Size of the download buffer in bytes
 * @property speedLimit Global speed limit applied across all downloads
 * @property queueConfig Configuration for the download queue
 */
data class DownloadConfig(
  val maxConnections: Int = 4,
  val retryCount: Int = 3,
  val retryDelayMs: Long = 1000,
  val progressUpdateIntervalMs: Long = 200,
  val segmentSaveIntervalMs: Long = 5000,
  val bufferSize: Int = 8192,
  val speedLimit: SpeedLimit = SpeedLimit.Unlimited,
  val queueConfig: QueueConfig = QueueConfig(),
) {
  init {
    require(maxConnections > 0) { "maxConnections must be greater than 0" }
    require(retryCount >= 0) { "retryCount must be non-negative" }
    require(retryDelayMs >= 0) { "retryDelayMs must be non-negative" }
    require(progressUpdateIntervalMs > 0) { "progressUpdateIntervalMs must be greater than 0" }
    require(segmentSaveIntervalMs > 0) { "segmentSaveIntervalMs must be greater than 0" }
    require(bufferSize > 0) { "bufferSize must be greater than 0" }
  }

  companion object {
    val Default = DownloadConfig()
  }
}
