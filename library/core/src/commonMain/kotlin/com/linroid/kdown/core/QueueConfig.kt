package com.linroid.kdown.core

/**
 * Configuration for the download queue.
 *
 * @property maxConcurrentDownloads Maximum number of downloads that can
 *   run simultaneously. Additional downloads are queued and started
 *   automatically when slots become available.
 * @property maxConnectionsPerHost Maximum number of concurrent downloads
 *   targeting the same host. Prevents overloading a single server.
 * @property autoStart When `true` (default), queued tasks start
 *   automatically when slots are available. When `false`, tasks remain
 *   queued until explicitly resumed.
 */
data class QueueConfig(
  val maxConcurrentDownloads: Int = 3,
  val maxConnectionsPerHost: Int = 4,
  val autoStart: Boolean = true,
) {
  init {
    require(maxConcurrentDownloads > 0) {
      "maxConcurrentDownloads must be greater than 0"
    }
    require(maxConnectionsPerHost > 0) {
      "maxConnectionsPerHost must be greater than 0"
    }
  }

  companion object {
    val Default = QueueConfig()
  }
}
