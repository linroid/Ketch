package com.linroid.ketch.api

import com.linroid.ketch.api.torrent.TorrentController
import kotlinx.coroutines.flow.StateFlow

/**
 * Service interface for managing downloads. Both core (in-process)
 * and remote (HTTP+SSE) backends implement this interface, allowing
 * the UI to work identically regardless of backend mode.
 */
interface KetchApi {
  /** Human-readable label: "Core" or "Remote · host:port". */
  val backendLabel: String

  /**
   * Optional typed torrent controls exposed by this backend. Null preserves compatibility with
   * backends that support legacy torrent downloads but have not implemented the control protocol.
   */
  val torrents: TorrentController? get() = null

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
   * @param properties source-specific key-value pairs. For HTTP
   *   sources this contains HTTP headers; other sources may
   *   interpret them differently or ignore them.
   */
  suspend fun resolve(
    url: String,
    properties: Map<String, String> = emptyMap(),
  ): ResolvedSource

  /**
   * Resolves metadata from file content the caller already holds, such as
   * a `.torrent` file picked or dropped by the user, without fetching it.
   *
   * The content is not stored anywhere else, so start the download by
   * passing the returned [ResolvedSource] in [DownloadRequest.resolvedSource]
   * with its [ResolvedSource.url] as [DownloadRequest.url].
   *
   * @param content the raw file content
   * @param fileName the original file name, if known. Sources may use its
   *   extension to recognize the content.
   * @throws KetchError.Unsupported if no source recognizes the content
   * @throws UnsupportedOperationException if this backend cannot resolve content
   */
  suspend fun resolveContent(
    content: ByteArray,
    fileName: String? = null,
  ): ResolvedSource {
    throw UnsupportedOperationException("Resolving file content is unavailable")
  }

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
   * Replaces the runtime download configuration.
   *
   * Applied immediately:
   * - [DownloadConfig.speedLimit] throttles all active downloads.
   * - [DownloadConfig.maxConcurrentDownloads] and [DownloadConfig.maxConnectionsPerHost]:
   *   raising a limit starts queued downloads right away; lowering one never interrupts
   *   running downloads, queued ones wait until enough of them finish.
   *
   * Applied to downloads that start or resume after the update (running downloads keep the
   * values they started with until paused and resumed):
   * [DownloadConfig.defaultDirectory], [DownloadConfig.maxConnectionsPerDownload],
   * [DownloadConfig.retryCount], [DownloadConfig.retryDelayMs],
   * [DownloadConfig.progressIntervalMs], [DownloadConfig.saveIntervalMs] and
   * [DownloadConfig.bufferSize].
   */
  suspend fun updateConfig(config: DownloadConfig)

  /** Lists HTTP network interfaces and their runtime selection on this instance. */
  suspend fun networkInterfaces(): NetworkInterfaces = NetworkInterfaces()

  /**
   * Selects interfaces for subsequent HTTP requests, including retries and new segments.
   * In-flight requests finish on their existing network. An empty selection restores default
   * routing. Selection is runtime-only and is not persisted across instance restarts.
   *
   * @throws IllegalArgumentException if an ID is unknown or unavailable
   * @throws UnsupportedOperationException if this backend cannot configure interfaces
   */
  suspend fun updateNetworkInterfaces(config: NetworkInterfaceConfig): NetworkInterfaces {
    throw UnsupportedOperationException("HTTP network interface configuration is unavailable")
  }

  /** Release resources (HTTP client, SSE connection, etc.). */
  fun close()


  companion object {
    /** Library version string (e.g., "0.0.1-dev"). */
    const val VERSION: String = KETCH_BUILD_VERSION

    /** Build revision (git short hash). */
    const val REVISION: String = KETCH_BUILD_REVISION
  }
}
