package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * Global download configuration.
 *
 * Can be replaced at runtime with [KetchApi.updateConfig]. [speedLimit],
 * [maxConcurrentDownloads] and [maxConnectionsPerHost] apply immediately. Every other field
 * is read when a download starts or resumes, so a running download keeps the values it
 * started with until it is paused and resumed.
 *
 * @property defaultDirectory Default directory for saving downloaded files when
 *   [DownloadRequest.destination] does not name a directory or full path.
 *   `null` means use the platform default (e.g. `~/Downloads` on desktop,
 *   external storage on Android). Set explicitly to override.
 * @property maxConnectionsPerDownload Default number of concurrent connections per task, used
 *   when [DownloadRequest.connections] is `0`. HTTP(S) opens one Range request per segment and
 *   FTP(S) one connection per segment using REST offsets. Servers without Range (HTTP) or REST
 *   (FTP) support always use a single connection. Sources that do not split files into byte
 *   ranges, such as BitTorrent, ignore this default.
 * @property retryCount Maximum number of automatic retries after a retryable failure
 *   ([KetchError.isRetryable]). Segment progress is kept between attempts.
 * @property retryDelayMs Base delay in milliseconds between retry attempts (uses exponential backoff)
 * @property progressIntervalMs Minimum interval between progress updates of segmented
 *   (HTTP/FTP) downloads, to prevent UI spam
 * @property saveIntervalMs Interval for persisting segment progress during downloads
 * @property bufferSize Read buffer size in bytes for FTP data transfers. HTTP transfers use
 *   the buffering of the configured `HttpEngine`.
 * @property speedLimit Global speed limit shared by all downloads. Per-task limits
 *   ([DownloadRequest.speedLimit]) apply in addition to it.
 * @property maxConcurrentDownloads Maximum number of downloads that can
 *   run simultaneously. Additional downloads are queued and started
 *   automatically when slots become available. `0` means unlimited.
 *   Raising the limit at runtime starts queued downloads right away. Lowering it never
 *   interrupts running downloads; queued ones wait until enough of them finish.
 * @property maxConnectionsPerHost Maximum number of concurrent downloads per server, keyed
 *   by the URL host (case-insensitive, ignoring user info and port). Applies to URLs with a
 *   network host, such as HTTP(S) and FTP(S). Magnet links, `torrent:` identifiers and local
 *   files are not counted; a torrent added from an HTTP(S) `.torrent` URL counts against that
 *   URL's host. `0` means unlimited. Runtime changes behave like [maxConcurrentDownloads].
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
