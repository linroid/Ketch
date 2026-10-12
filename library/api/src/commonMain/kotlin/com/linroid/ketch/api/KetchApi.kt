package com.linroid.ketch.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * Manages downloads. Implemented in-process by `Ketch` and over HTTP and SSE by `RemoteKetch`,
 * so callers work the same with either.
 */
@SubclassOptInRequired(KetchImplementationApi::class)
interface KetchApi {
  /** Human-readable label: "Core" or "Remote · host:port". */
  val backendLabel: String

  // Lifecycle

  /** Restores saved tasks (in-process) or connects and loads them (remote). */
  suspend fun start()

  /** Releases the instance's resources. */
  fun close()

  // Tasks

  /** Every task of this instance. */
  val tasks: StateFlow<List<DownloadTask>>

  /**
   * Adds a download.
   *
   * @throws UnsupportedOperationException if [DownloadRequest.proxy] sets a proxy and this
   *   backend does not list [KetchFeatures.PROXY]
   */
  suspend fun download(request: DownloadRequest): DownloadTask

  // Probing

  /**
   * Probes [url] without downloading it. Pass the result in [DownloadRequest.resolvedSource] to
   * skip the probe when downloading.
   *
   * @param headers request headers for HTTP sources; other sources may ignore them
   */
  suspend fun resolve(
    url: String,
    headers: Map<String, String> = emptyMap(),
  ): ResolvedSource

  /**
   * Resolves file content the caller holds, such as a `.torrent` file. The content is not kept,
   * so download it by passing the result in [DownloadRequest.resolvedSource], with its
   * [ResolvedSource.url] as [DownloadRequest.url].
   *
   * @param fileName the original file name, whose extension may identify the content
   * @throws KetchError.Unsupported if no source recognizes the content
   * @throws UnsupportedOperationException if this backend cannot resolve content
   */
  suspend fun resolveContent(
    content: ByteArray,
    fileName: String? = null,
  ): ResolvedSource {
    throw UnsupportedOperationException("Resolving file content is unavailable")
  }

  // Status and settings

  /** The instance's configuration, system, storage and [features][KetchStatus.features]. */
  suspend fun status(): KetchStatus

  /**
   * Replaces the download configuration. Speed and queue limits apply at once; other fields
   * apply to downloads that start or resume afterwards (see [DownloadConfig]).
   *
   * @throws IllegalArgumentException if [DownloadConfig.defaultDirectory]
   *   changed to a folder that does not exist on this instance
   * @throws UnsupportedOperationException if [DownloadConfig.proxy] sets a proxy and this
   *   backend does not list [KetchFeatures.PROXY]
   */
  suspend fun updateConfig(config: DownloadConfig)

  /** Lists HTTP network interfaces and their runtime selection on this instance. */
  suspend fun networkInterfaces(): NetworkInterfaces = NetworkInterfaces()

  /**
   * Selects the interfaces later HTTP requests use, round-robin; requests in flight keep theirs.
   * Empty restores default routing. Not saved across restarts.
   *
   * @throws IllegalArgumentException if an ID is unknown or unavailable
   * @throws UnsupportedOperationException if this backend cannot configure interfaces
   */
  suspend fun selectNetworkInterfaces(config: NetworkInterfaceConfig): NetworkInterfaces {
    throw UnsupportedOperationException("HTTP network interface configuration is unavailable")
  }

  // Extensions

  /**
   * Follows [interest] while collected. A remote instance asks its server for updates every
   * [interval] (the shortest its callers ask for); an in-process one reports changes as they
   * happen, and stats every [interval]. Unknown interests fail with
   * [UnsupportedOperationException] when collected.
   *
   * @throws IllegalArgumentException if [interval] is not positive
   */
  fun <T> subscribe(interest: Interest<T>, interval: Duration = Interest.DEFAULT_INTERVAL): Flow<T>

  /**
   * The controller this backend offers for [key], defined by another module, or `null`. Check the
   * feature its module names in [KetchStatus.features] first: a remote controller fails against
   * servers without it.
   */
  fun <T : Any> controller(key: ControlKey<T>): T? = null

  // Not `const`, which callers would inline, reporting the version they were compiled against.
  @Suppress("MayBeConstant")
  companion object {
    /** Library version string (e.g., "0.0.1-dev"). */
    val VERSION: String = KETCH_BUILD_VERSION

    /** Build revision (git short hash). */
    val REVISION: String = KETCH_BUILD_REVISION
  }
}
