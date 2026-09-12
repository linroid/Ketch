package com.linroid.ketch.torrent

/** Chosen before discovery; metadata learned later cannot undo public lookups. */
internal enum class TorrentDiscoveryPrivacy {
  PUBLIC,
  TRACKER_ONLY,
}
