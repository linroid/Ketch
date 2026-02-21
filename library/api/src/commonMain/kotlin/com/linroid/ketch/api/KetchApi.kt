package com.linroid.ketch.api

import com.linroid.ketch.api.config.DownloadConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * Service interface for managing downloads. Both core (in-process)
 * and remote (HTTP+SSE) backends implement this interface, allowing
 * the UI to work identically regardless of backend mode.
 */
interface KetchApi {
  /** Human-readable label: "Core" or "Remote · host:port". */
  val backendLabel: String

  /** Reactive task list updated on any state change. */
  val tasks: StateFlow<List<DownloadTask>>

  /** Create a new download and return the task handle. */
  suspend fun download(request: DownloadRequest): DownloadTask

  /**
   * Resolves metadata for the given URL without downloading.
   *
   * Probes the URL (e.g., via an HTTP HEAD request) and returns
   * file size, resume support, suggested file name, and other
   * source-specific metadata. The returned [ResolvedSource] can be
   * passed in [DownloadRequest.resolvedSource] to skip the probe
   * during [download].
   *
   * @param url the URL to resolve
   * @param headers optional HTTP headers to include in the probe
   */
  suspend fun resolve(
    url: String,
    headers: Map<String, String> = emptyMap(),
  ): ResolvedSource

  /**
   * Initialize backend runtime state.
   *
   * - Core backend restores persisted tasks.
   * - Remote backend establishes connection and syncs tasks.
   */
  suspend fun start()

  /**
   * Returns a point-in-time status snapshot including
   * configuration, system information, and storage details.
   */
  suspend fun status(): KetchStatus

  /**
   * Updates the runtime download configuration.
   *
   * Changes take effect immediately on all active downloads.
   * For example, updating [DownloadConfig.speedLimit] adjusts
   * the global speed limit, and updating
   * [DownloadConfig.queueConfig] adjusts concurrency settings.
   */
  suspend fun updateConfig(config: DownloadConfig)

  /** Release resources (HTTP client, SSE connection, etc.). */
  fun close()


  companion object {
    /** Library version string (e.g., "0.0.1-dev"). */
    const val VERSION: String = KETCH_BUILD_VERSION

    /** Build revision (git short hash). */
    const val REVISION: String = KETCH_BUILD_REVISION
  }
}
