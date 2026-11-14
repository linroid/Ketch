package com.linroid.ketch.torrent.api

/**
 * Names of torrent behaviors a Ketch instance lists in
 * [KetchStatus.features][com.linroid.ketch.api.KetchStatus.features], beside
 * [com.linroid.ketch.api.KetchFeatures].
 */
object TorrentFeatures {
  /**
   * `KetchApi.controller(TorrentController)` executes commands: REST `/api/torrents` and its event
   * stream.
   */
  const val CONTROL: String = "torrent.control"

  /**
   * A torrent request that asks to overwrite ([com.linroid.ketch.api.FileConflict.OVERWRITE])
   * uses the files of its name already in the folder: it checks them and downloads only what is
   * missing or different. Without it, such a request saves beside them.
   */
  const val EXISTING_FILES: String = "torrent.existingFiles"

  /** Every torrent feature this version of Ketch supports. */
  val ALL: Set<String> = setOf(CONTROL, EXISTING_FILES)
}
