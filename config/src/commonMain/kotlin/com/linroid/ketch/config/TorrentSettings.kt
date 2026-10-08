package com.linroid.ketch.config

import kotlinx.serialization.Serializable

/**
 * BitTorrent settings for the local engine.
 *
 * @property trackers extra tracker announce URLs (`http`, `https` or `udp`)
 *   that public torrents announce to alongside their own trackers. Private
 *   torrents and tracker-only discovery ignore them.
 * @property trackerList whether to subscribe to the tracker lists at
 *   [trackerListUrls], downloaded daily, whose trackers are used like
 *   [trackers], after them. On by default, so magnets without trackers still
 *   find peers where DHT cannot bootstrap.
 * @property trackerListUrls `http` or `https` URLs of plain-text tracker
 *   lists, one announce URL per line; [DEFAULT_TRACKER_LISTS] by default.
 * @property trackerListUrl the one list Ketch 0.3.0 subscribed to, read from
 *   its config files: a custom one replaces the default [trackerListUrls]
 *   until the lists are changed ([withTrackerLists]).
 */
@Serializable
data class TorrentSettings(
  val trackers: List<String> = emptyList(),
  val trackerList: Boolean = true,
  val trackerListUrls: List<String> = DEFAULT_TRACKER_LISTS,
  val trackerListUrl: String? = null,
) {
  /** The tracker lists configured, whether or not [trackerList] is on, without repeats. */
  val trackerListAddresses: List<String>
    get() {
      val legacy = trackerListUrl?.trim()?.takeIf { it.isNotEmpty() && it != NGOSANG_BEST }
      val urls = if (legacy != null && trackerListUrls == DEFAULT_TRACKER_LISTS) {
        listOf(legacy)
      } else {
        trackerListUrls
      }
      return urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

  /** The tracker lists subscribed to: [trackerListAddresses], none while [trackerList] is off. */
  val subscribedTrackerLists: List<String>
    get() = if (trackerList) trackerListAddresses else emptyList()

  /** These settings with [urls] as the tracker lists, leaving the 0.3.0 [trackerListUrl] behind. */
  fun withTrackerLists(urls: List<String>): TorrentSettings =
    copy(trackerListUrls = urls, trackerListUrl = null)

  companion object {
    private const val NGOSANG_BEST =
      "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
    private const val XIU2_BEST =
      "https://raw.githubusercontent.com/XIU2/TrackersListCollection/master/best.txt"

    /** ngosang's and XIU2's lists of the best public trackers, both refreshed daily. */
    val DEFAULT_TRACKER_LISTS: List<String> = listOf(NGOSANG_BEST, XIU2_BEST)
  }
}
