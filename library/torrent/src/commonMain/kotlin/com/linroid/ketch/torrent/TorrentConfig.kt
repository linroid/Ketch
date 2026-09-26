package com.linroid.ketch.torrent

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for the torrent engine.
 *
 * @property dhtEnabled whether to enable DHT for peer discovery
 * @property maxActiveTorrents maximum number of torrents the engine runs at once, including
 *   seeding sessions. Further downloads wait for a slot (higher priority first, then arrival
 *   order) instead of failing, and a seeding session yields its slot to a waiting download.
 * @property metadataTimeoutSeconds timeout for magnet metadata
 *   resolution in seconds
 * @property connectionsPerTorrent peer cap for a task that sets no connections. A task's
 *   `DownloadRequest.connections` or `DownloadTask.setConnections` value replaces it, clamped to
 *   1..512 for v1 and 1..500 for v2. `DownloadConfig.maxConnectionsPerDownload` counts HTTP
 *   segments and does not apply to torrents; [maxConnections] bounds all torrents together.
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
  val maxExchangeBytes: Int = 128 * 1024 * 1024,
  /** Cache retention ceiling, including conservative file/index and string allowances. */
  val maxCachedMetadataBytes: Int = 4 * 1024 * 1024,
  /**
   * Aggregate allowance for session metadata, indexes, checkpoint decoding, and checking.
   * Admission charges 1.7-14x the measured retained JVM heap (see docs/development/
   * torrent-verification.md), so a full default pool retains at most ~37 MiB. The default admits
   * five 30k-piece, 1,000-file torrents of either format, or one 100k-piece, 10k-file torrent.
   */
  val maxSessionStateBytes: Int = 64 * 1024 * 1024,
  /** Aggregate open payload-file ceiling. Storage waits before opening another payload handle. */
  val maxOpenPayloadFiles: Int = 32,
  /** File count ceiling applied before constructing a session's storage indexes. */
  val maxFilesPerTorrent: Int = 10_000,
  /** Logical piece ceiling applied before constructing session and scheduler arrays. */
  val maxPiecesPerTorrent: Int = 250_000,
  /** Default for newly resolved inputs; persisted tasks retain their saved privacy choice. */
  val discoveryPrivacy: TorrentDiscoveryPrivacy = TorrentDiscoveryPrivacy.PUBLIC,

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
    require(maxOpenPayloadFiles in 1..128)
    require(maxFilesPerTorrent in 1..100_000)
    require(maxPiecesPerTorrent in 1..1_000_000)
    require(maxBufferedBytes.toLong() + metadataExchangeBytes + maxCachedMetadataBytes +
      maxSessionStateBytes <=
      maxExchangeBytes.toLong()) {
      "maxExchangeBytes must cover transfers, metadata/scrape exchange, cache, and session state"
    }
  }

  /** One metadata or tracker scrape exchange, including parse copies and bounded wire overhead. */
  internal val metadataExchangeBytes: Int
    get() = maxOf(maxMetadataBytes * 4 + 256 * 1024, TRACKER_SCRAPE_WORKSPACE_BYTES)

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
