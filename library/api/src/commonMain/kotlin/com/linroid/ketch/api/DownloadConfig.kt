package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * Global download configuration, replaced at runtime with [KetchApi.updateConfig].
 * [speedLimit], [maxConcurrentDownloads] and [maxConnectionsPerHost] apply at once; other fields
 * when a download starts or resumes.
 *
 * @property defaultDirectory where downloads go unless their destination names a folder or
 *   path; `null` for the platform's downloads folder
 * @property maxConnectionsPerDownload connections (segments) per HTTP(S) or FTP(S) download that
 *   asks for none; servers without ranges use one, and BitTorrent ignores it
 * @property retryCount retries after a retryable failure ([KetchError.isRetryable]), counted
 *   again from zero once an attempt gets further than every one before
 * @property unlimitedRetries retry until paused or canceled, ignoring [retryCount]
 * @property retryDelayMs delay before the first retry, doubled for each further one up to
 *   [maxRetryDelayMs], then randomized between half and all of it. A 429's `Retry-After` wins.
 * @property maxRetryDelayMs the longest retry delay, before randomizing
 * @property idleTimeoutMs how long a transfer may receive nothing, outside speed limit waits,
 *   before it is dropped and retried; `0` turns it off
 * @property minSpeed bytes per second each connection must average over [MIN_SPEED_WINDOW_MS],
 *   or it is treated like [idleTimeoutMs]; `0` turns it off
 * @property progressIntervalMs the least time between progress updates of segmented downloads
 * @property saveIntervalMs how often segment progress is saved
 * @property bufferSize read buffer size for FTP transfers, in bytes
 * @property speedLimit the limit shared by all downloads
 * @property maxConcurrentDownloads downloads running at once, `0` for unlimited; more wait in
 *   the queue. Lowering it never stops running downloads.
 * @property maxConnectionsPerHost downloads running at once per URL host, `0` for unlimited.
 *   Magnets, `torrent:` IDs and local files have no host.
 * @property proxy how HTTP(S) downloads and [KetchApi.resolve] reach servers;
 *   [DownloadRequest.proxy] replaces it per download
 * @property categories folders under [defaultDirectory] for downloads that choose none; the
 *   first match wins ([DownloadCategory])
 */
@Serializable
data class DownloadConfig(
  val defaultDirectory: String? = null,
  val retryCount: Int = 3,
  val retryDelayMs: Long = 1_000,
  val unlimitedRetries: Boolean = false,
  val maxRetryDelayMs: Long = 60_000,
  val idleTimeoutMs: Long = 60_000,
  val minSpeed: Long = 0,
  val progressIntervalMs: Long = 200,
  val saveIntervalMs: Long = 5_000,
  val bufferSize: Int = 8 * 1024,
  val speedLimit: SpeedLimit = SpeedLimit.Unlimited,
  val maxConcurrentDownloads: Int = 4,
  val maxConnectionsPerDownload: Int = 4,
  val maxConnectionsPerHost: Int = 16,
  val proxy: ProxyConfig = ProxyConfig.System,
  val categories: List<DownloadCategory> = emptyList(),
) {
  init {
    require(retryCount >= 0) { "retryCount must be non-negative" }
    require(retryDelayMs >= 0) { "retryDelayMs must be non-negative" }
    require(maxRetryDelayMs >= 0) { "maxRetryDelayMs must be non-negative" }
    require(idleTimeoutMs >= 0) { "idleTimeoutMs must be non-negative" }
    require(minSpeed >= 0) { "minSpeed must be non-negative" }
    require(progressIntervalMs > 0) { "progressIntervalMs must be greater than 0" }
    require(saveIntervalMs > 0) { "saveIntervalMs must be greater than 0" }
    require(bufferSize > 0) { "bufferSize must be greater than 0" }
    require(maxConcurrentDownloads >= 0) { "maxConcurrentDownloads must be non-negative" }
    require(maxConnectionsPerHost >= 0) { "maxConnectionsPerHost must be non-negative" }
    require(maxConnectionsPerDownload > 0) { "maxConnectionsPerDownload must be greater than 0" }
  }

  companion object {
    /** The span [minSpeed] is averaged over. */
    const val MIN_SPEED_WINDOW_MS: Long = 30_000

    /** The default configuration. */
    val Default: DownloadConfig = DownloadConfig()
  }
}
