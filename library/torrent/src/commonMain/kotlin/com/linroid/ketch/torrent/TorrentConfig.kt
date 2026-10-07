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
    "router.bittorrent.com:6881", "router.utorrent.com:6881", "dht.transmissionbt.com:6881",
    "dht.libtorrent.org:25401"
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
  /**
   * Extra `http`, `https` or `udp` announce URLs for public torrents, announced alongside each
   * torrent's own trackers rather than after them. Private torrents and tracker-only discovery
   * never contact them. Invalid URLs are ignored, and at most 64 are used.
   */
  val additionalTrackers: List<String> = emptyList(),
  /**
   * `http` or `https` URL of a tracker list to subscribe to, such as [BEST_TRACKERS_URL]: plain
   * text with one announce URL per line. It is downloaded when the source is created, unless
   * [stateDirectory] holds a copy less than a day old, and again daily; its trackers are used like
   * [additionalTrackers], after them. `null`, or a URL that is not `http` or `https`, subscribes
   * to none.
   */
  val trackerListUrl: String? = null,
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

  companion object {
    /** ngosang's list of the most reliable public trackers, refreshed daily. */
    const val BEST_TRACKERS_URL: String =
      "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
    /**
     * [BEST_TRACKERS_URL] as of 2026-10-07, used for that list until its first download succeeds,
     * so a subscription works offline and where GitHub is unreachable.
     */
    val BEST_TRACKERS: List<String> = listOf(
      "udp://tracker.opentrackr.org:1337/announce",
      "udp://open.stealth.si:80/announce",
      "udp://tracker.torrent.eu.org:451/announce",
      "udp://open.demonii.com:1337/announce",
      "udp://exodus.desync.com:6969/announce",
      "udp://tracker.skynetcloud.site:6969/announce",
      "udp://tracker.qu.ax:6969/announce",
      "udp://tracker.bittor.pw:1337/announce",
      "udp://tracker.tryhackx.org:6969/announce",
      "udp://tracker.nyaa.vc:6969/announce",
      "udp://tracker.corpscorp.online:80/announce",
      "udp://explodie.org:6969/announce",
      "udp://tracker.theoks.net:6969/announce",
      "udp://tracker-udp.gbitt.info:80/announce",
      "udp://tracker.ducks.party:1984/announce",
      "udp://tracker.gmi.gd:6969/announce",
      "udp://tracker.dler.org:6969/announce",
      "udp://tracker2.dler.org:80/announce",
      "http://tracker.dler.com:6969/announce",
      "udp://retracker01-msk-virt.corbina.net:80/announce",
    )
  }
}
