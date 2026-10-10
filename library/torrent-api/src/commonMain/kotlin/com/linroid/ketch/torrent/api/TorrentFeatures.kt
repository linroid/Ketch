package com.linroid.ketch.torrent.api

/**
 * Names of torrent behaviors a Ketch instance lists in
 * [KetchStatus.features][com.linroid.ketch.api.KetchStatus.features], beside
 * [com.linroid.ketch.api.KetchFeatures].
 */
object TorrentFeatures {
  /**
   * `KetchApi.control(TorrentController)` executes commands: REST `/api/torrents` and its event
   * stream.
   */
  const val CONTROL: String = "torrent.control"

  /** Every torrent feature this version of Ketch supports. */
  val ALL: Set<String> = setOf(CONTROL)
}
