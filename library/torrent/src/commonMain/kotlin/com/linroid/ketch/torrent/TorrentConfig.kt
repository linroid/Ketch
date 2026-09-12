package com.linroid.ketch.torrent

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for the torrent engine.
 *
 * @property dhtEnabled whether to enable DHT for peer discovery
 * @property maxActiveTorrents maximum number of active torrents
 * @property metadataTimeoutSeconds timeout for magnet metadata
 *   resolution in seconds
 * @property connectionsPerTorrent default connections per torrent
 * @property enableUpload whether to seed after download completes
 * @property listenPort port for incoming peer connections; 0 for
 *   random port
 */
data class TorrentConfig(
  val dhtEnabled: Boolean = true,
  val maxActiveTorrents: Int = 5,
  val metadataTimeoutSeconds: Int = 120,
  val connectionsPerTorrent: Int = 100,
  val enableUpload: Boolean = false,
  val listenPort: Int = 0,
  /** Total TCP connections, including metadata requests and incoming peers. */
  val maxConnections: Int = 200,
  /** Optional directory for DHT routing snapshots. Contains no task payload. */
  val stateDirectory: String? = null,
  /** Bootstrap endpoints in host:port or [IPv6]:port form. */
  val dhtBootstrap: List<String> = listOf(
    "router.bittorrent.com:6881", "router.utorrent.com:6881", "dht.transmissionbt.com:6881"
  ),
  /** Explicit policy; null preserves the legacy [enableUpload] setting. */
  val uploadPolicy: TorrentUploadPolicy? = null,
  /** Maximum metainfo bytes accepted from HTTP or peers. */
  val maxMetadataBytes: Int = 4 * 1024 * 1024,
  /** Maximum simultaneously buffered piece data across the engine. */
  val maxBufferedBytes: Int = 32 * 1024 * 1024,
  /**
   * Combined admission ceiling for buffers, metadata exchange, cache entries, and session state.
   * This is not a process RSS limit; platform allocations and caller-owned state are separate.
   */
  val maxExchangeBytes: Int = 64 * 1024 * 1024,
  /** Cache retention ceiling, including conservative file/index and string allowances. */
  val maxCachedMetadataBytes: Int = 4 * 1024 * 1024,
  /** Aggregate admission allowance for session metadata, indexes, and checking scratch space. */
  val maxSessionStateBytes: Int = 8 * 1024 * 1024,
  /** File count ceiling applied before constructing a session's storage indexes. */
  val maxFilesPerTorrent: Int = 10_000,
  /** Logical piece ceiling applied before constructing session and scheduler arrays. */
  val maxPiecesPerTorrent: Int = 250_000,

) {
  init {
    require(maxActiveTorrents in 1..64) { "maxActiveTorrents must be in 1..64" }
    require(connectionsPerTorrent in 1..512)
    require(maxConnections in 1..4096)
    require(dhtBootstrap.size <= 64)
    require(metadataTimeoutSeconds >= 0) { "metadata timeout must be non-negative" }
    require(listenPort in 0..65535) { "listenPort must be in 0..65535" }
    require(maxMetadataBytes in 1..4 * 1024 * 1024)
    require(maxBufferedBytes >= 16384) { "maxBufferedBytes must hold a protocol block" }
    require(maxCachedMetadataBytes > 0 && maxSessionStateBytes > 0)
    require(maxFilesPerTorrent in 1..100_000)
    require(maxPiecesPerTorrent in 1..1_000_000)
    require(maxBufferedBytes.toLong() + metadataExchangeBytes + maxCachedMetadataBytes +
      maxSessionStateBytes <=
      maxExchangeBytes.toLong()) {
      "maxExchangeBytes must cover transfers, metadata exchange, cache, and session state"
    }
  }

  /** One metadata exchange, including parse copies and bounded wire overhead. */
  internal val metadataExchangeBytes: Int
    get() = maxMetadataBytes * 4 + 256 * 1024

  /** Effective policy, including compatibility with the legacy boolean. */
  val effectiveUploadPolicy: TorrentUploadPolicy
    get() = uploadPolicy ?: if (enableUpload) {
      TorrentUploadPolicy.SEED_AFTER_COMPLETION
    } else {
      TorrentUploadPolicy.DISABLED
    }

  /** Metadata fetch timeout as a [Duration]. */
  val metadataTimeout: Duration
    get() = metadataTimeoutSeconds.seconds
}
