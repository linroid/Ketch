package com.linroid.ketch.config

import kotlinx.serialization.Serializable

/**
 * BitTorrent settings for the local engine.
 *
 * @property trackers extra tracker announce URLs (`http`, `https` or `udp`)
 *   that public torrents announce to alongside their own trackers. Private
 *   torrents and tracker-only discovery ignore them.
 * @property trackerList whether to subscribe to the tracker list at
 *   [trackerListUrl], downloaded daily, whose trackers are used like
 *   [trackers], after them. On by default, so magnets without trackers still
 *   find peers where DHT cannot bootstrap.
 * @property trackerListUrl `http` or `https` URL of a plain-text tracker
 *   list, one announce URL per line; ngosang's `trackers_best.txt` by default.
 */
@Serializable
data class TorrentSettings(
  val trackers: List<String> = emptyList(),
  val trackerList: Boolean = true,
  val trackerListUrl: String = DEFAULT_TRACKER_LIST_URL,
) {
  /** The tracker list subscribed to, or `null` when [trackerList] is off. */
  val subscribedTrackerList: String?
    get() = trackerListUrl.trim().takeIf { trackerList && it.isNotEmpty() }

  companion object {
    /** ngosang's list of the most reliable public trackers. */
    const val DEFAULT_TRACKER_LIST_URL: String =
      "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
  }
}
