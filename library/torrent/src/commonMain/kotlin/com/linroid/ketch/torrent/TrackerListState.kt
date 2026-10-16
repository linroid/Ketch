package com.linroid.ketch.torrent

import kotlin.time.Instant

/**
 * Trackers of one of the lists [TorrentConfig.trackerListUrls] names.
 *
 * @property url the subscribed list, or `null` when there is none.
 * @property trackers the usable announce URLs of the newest copy of the list, in its order.
 * @property updatedAt when that copy was downloaded; `null` before the first download.
 * @property failed whether the last download failed; [trackers] then come from an older copy.
 * @property updating whether the list is being downloaded.
 */
data class TrackerListState(
  val url: String? = null,
  val trackers: List<String> = emptyList(),
  val updatedAt: Instant? = null,
  val failed: Boolean = false,
  val updating: Boolean = false,
)
