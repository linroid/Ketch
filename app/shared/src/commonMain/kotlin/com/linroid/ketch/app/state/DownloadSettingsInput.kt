package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.SpeedLimit

/**
 * Download limits as typed on the settings page.
 *
 * The numeric fields are kept as text so a half-typed value does not
 * snap back to a default while the user is editing; [toConfig] turns
 * them into a [DownloadConfig] once they are valid. [validate] mirrors
 * the constraints `DownloadConfig` enforces in its `init` block, so the
 * page can explain the problem instead of throwing.
 */
data class DownloadSettingsInput(
  val directory: String,
  val maxConcurrentDownloads: String,
  val maxConnectionsPerDownload: String,
  val maxConnectionsPerHost: String,
  val speedLimit: SpeedLimit,
) {
  /** First problem with the entered values, or `null` when valid. */
  fun validate(): String? {
    val concurrent = maxConcurrentDownloads.toIntOrNull()
    val perDownload = maxConnectionsPerDownload.toIntOrNull()
    val perHost = maxConnectionsPerHost.toIntOrNull()
    return when {
      concurrent == null || concurrent < 0 ->
        "Simultaneous downloads must be 0 or more."
      perDownload == null || perDownload < 1 ->
        "Connections per download must be at least 1."
      perHost == null || perHost < 0 ->
        "Downloads per host must be 0 or more."
      else -> null
    }
  }

  /**
   * Applies these values on top of [base], or returns `null` when
   * [validate] would report a problem.
   */
  fun toConfig(base: DownloadConfig): DownloadConfig? {
    if (validate() != null) return null
    return base.copy(
      defaultDirectory = directory.trim().ifBlank { null },
      maxConcurrentDownloads = maxConcurrentDownloads.toInt(),
      maxConnectionsPerDownload = maxConnectionsPerDownload.toInt(),
      maxConnectionsPerHost = maxConnectionsPerHost.toInt(),
      speedLimit = speedLimit,
    )
  }

  companion object {
    /** Fills the form from a saved [config]. */
    fun from(config: DownloadConfig): DownloadSettingsInput =
      DownloadSettingsInput(
        directory = config.defaultDirectory ?: "",
        maxConcurrentDownloads =
          config.maxConcurrentDownloads.toString(),
        maxConnectionsPerDownload =
          config.maxConnectionsPerDownload.toString(),
        maxConnectionsPerHost = config.maxConnectionsPerHost.toString(),
        speedLimit = config.speedLimit,
      )
  }
}
