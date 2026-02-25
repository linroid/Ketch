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
) {
  /** Metadata fetch timeout as a [Duration]. */
  val metadataTimeout: Duration
    get() = metadataTimeoutSeconds.seconds
}
