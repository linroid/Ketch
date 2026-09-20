package com.linroid.ketch.torrent

import kotlinx.serialization.Serializable

/** Chosen before discovery; metadata learned later cannot undo public lookups. */
@Serializable
enum class TorrentDiscoveryPrivacy {
  /** Permit configured public discovery. Private magnet metadata requires an explicit retry. */
  PUBLIC,
  /** Use supplied trackers only; disable explicit peers, DHT, and peer exchange for the task. */
  TRACKER_ONLY,
}
