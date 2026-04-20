package com.linroid.ketch.torrent

/**
 * Internal interface for the torrent engine backend.
 *
 * Abstracts the libtorrent4j session manager to allow future
 * platform-specific implementations (e.g., iOS via cinterop).
 * Only KMP-portable types are used in the interface.
 */
internal interface TorrentEngine {
  /** Starts the engine. Must be called before any other operations. */
  suspend fun start()

  /** Stops the engine and releases all resources. */
  suspend fun stop()

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

  /**
   * Removes a torrent from the engine.
   *
   * @param infoHash hex info hash of the torrent to remove
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
}
