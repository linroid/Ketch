package com.linroid.ketch.torrent.engine

import com.linroid.ketch.core.engine.ConnectionReporter
import com.linroid.ketch.torrent.TorrentDiscoveryPrivacy
import com.linroid.ketch.torrent.TorrentEncryption
import com.linroid.ketch.torrent.TorrentUploadPolicy
import com.linroid.ketch.torrent.metainfo.TorrentMetadata

/**
 * Internal interface for the torrent engine backend.
 *
 * Owns the common Kotlin runtime and exposes portable types for source integration and tests.
 */
internal interface TorrentEngine {
  /** Starts the engine. Must be called before any other operations. */
  suspend fun start()

  /** Stops the engine and releases all resources. */
  suspend fun stop()

  fun close() {}

  /**
   * Opens the one window every torrent's `stopped` announces share from now on, as the host
   * begins to shut down: torrents that stop later get what is left of it. The router's port
   * mappings are removed meanwhile, for good. [stop] opens it unless it is open already.
   */
  fun openStoppedWindow() {}

  /** Whether the engine is currently running. */
  val isRunning: Boolean

  /**
   * Fetches torrent metadata from a magnet URI.
   *
   * Blocks until metadata is available or the configured
   * timeout is reached.
   *
   * @return parsed [TorrentMetadata], or null on timeout
   */
  suspend fun fetchMetadata(magnetUri: String): TorrentMetadata?

  /** Explicit privacy must be selected before starting magnet discovery. */
  suspend fun fetchMetadata(magnetUri: String, privacy: TorrentDiscoveryPrivacy): TorrentMetadata? {
    require(privacy == TorrentDiscoveryPrivacy.PUBLIC) { "Tracker-only resolution is unsupported" }
    return fetchMetadata(magnetUri)
  }

  /**
   * Adds a torrent for downloading.
   *
   * @param infoHash hex info hash
   * @param savePath directory to save downloaded files
   * @param magnetUri optional magnet URI (for magnet-based adds)
   * @param torrentData optional raw .torrent file bytes
   * @param selectedFileIndices indices of files to download
   * @param resumeData optional resume data from a previous session
   * @return a [TorrentSession] handle for this torrent
   */
  suspend fun addTorrent(
    infoHash: String,
    savePath: String,
    magnetUri: String? = null,
    torrentData: ByteArray? = null,
    selectedFileIndices: Set<Int> = emptySet(),
    resumeData: ByteArray? = null,
  ): TorrentSession

  suspend fun addTask(spec: TorrentTaskSpec): TorrentSession {
    require(spec.privacy == TorrentDiscoveryPrivacy.PUBLIC) { "Tracker-only tasks are unsupported" }
    return addTorrent(spec.metadata.infoHash.hex, spec.outputPath, spec.magnetUri,
      spec.metadata.metainfoBytes, spec.selected, spec.resumeData)
  }

  /**
   * Removes a torrent from the engine. Unknown hashes are ignored.
   *
   * @param infoHash hex info hash of the torrent to remove: a v1 hash, a v2 hash, or a hybrid's
   *   v1 hash
   * @param deleteFiles whether to also delete downloaded files
   */
  suspend fun removeTorrent(
    infoHash: String,
    deleteFiles: Boolean = false,
  )

  /**
   * Sets the global download rate limit.
   *
   * @param bytesPerSecond rate limit in bytes/sec, or 0 for unlimited
   */
  fun setDownloadRateLimit(bytesPerSecond: Long)

  /**
   * Sets the global upload rate limit.
   *
   * @param bytesPerSecond rate limit in bytes/sec, or 0 for unlimited
   */
  fun setUploadRateLimit(bytesPerSecond: Long)

  /** Replaces the upload policy for running and later torrents, as far as each supports it. */
  suspend fun setUploadPolicy(policy: TorrentUploadPolicy) {}

  /** Replaces the encryption policy for connections opened from now on. */
  suspend fun setEncryption(encryption: TorrentEncryption) {}

  /** Switches uTP for connections opened from now on; open ones keep running. */
  suspend fun setUtpEnabled(enabled: Boolean) {}

  /** Starts or stops asking the router to forward the listen port. */
  suspend fun setPortMapping(enabled: Boolean) {}

  /** Starts or stops announcing public torrents on the LAN and hearing peers there (BEP 14). */
  suspend fun setLocalDiscovery(enabled: Boolean) {}
}

internal data class TorrentTaskSpec(
  val taskId: String,
  val metadata: TorrentMetadata,
  val outputPath: String,
  val selected: Set<Int>,
  val magnetUri: String? = null,
  val resumeData: ByteArray? = null,
  val throttle: suspend (Int) -> Unit = {},
  val privacy: TorrentDiscoveryPrivacy = TorrentDiscoveryPrivacy.PUBLIC,
  /**
   * Only seed: a recheck that finds the selection incomplete stops the session with
   * [IncompleteSeedException] instead of downloading, before any discovery, unless it only
   * misses pieces it shares with deleted or changed files outside the selection, which it seeds
   * without.
   */
  val seedOnly: Boolean = false,
  /** Reports the session's peers among the task's live connections. */
  val connections: ConnectionReporter = ConnectionReporter.None,
)
