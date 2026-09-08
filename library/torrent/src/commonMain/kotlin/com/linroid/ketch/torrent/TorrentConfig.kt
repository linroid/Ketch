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

) {
  init {
    require(maxActiveTorrents > 0) { "maxActiveTorrents must be positive" }
    require(connectionsPerTorrent in 1..512)
    require(maxConnections in 1..4096)
    require(dhtBootstrap.size <= 64)
    require(metadataTimeoutSeconds >= 0) { "metadata timeout must be non-negative" }
    require(listenPort in 0..65535) { "listenPort must be in 0..65535" }
    require(maxMetadataBytes in 1..4 * 1024 * 1024)
    require(maxBufferedBytes >= 16384) { "maxBufferedBytes must hold a protocol block" }
  }

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
