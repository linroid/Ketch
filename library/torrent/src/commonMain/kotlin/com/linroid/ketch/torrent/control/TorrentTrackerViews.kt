package com.linroid.ketch.torrent.control

import com.linroid.ketch.torrent.api.TorrentTracker
import com.linroid.ketch.torrent.api.TorrentTrackerState
import com.linroid.ketch.torrent.magnet.MagnetUri
import com.linroid.ketch.torrent.sourceTrackerTiers
import com.linroid.ketch.torrent.tracker.TrackerConfiguration
import com.linroid.ketch.torrent.tracker.TrackerStatus
import com.linroid.ketch.torrent.trackerLabel

/** What [com.linroid.ketch.torrent.api.TorrentController.trackers] lists for live [statuses]. */
internal fun trackerViews(statuses: List<TrackerStatus>): List<TorrentTracker> =
  statuses.sortedWith(compareBy({ it.tier }, { it.id })).map { status ->
    TorrentTracker(
      endpoint = status.label,
      tier = status.tier,
      state = when (status.outcome) {
        TrackerStatus.Outcome.NOT_CONTACTED, TrackerStatus.Outcome.CANCELED ->
          TorrentTrackerState.IDLE
        TrackerStatus.Outcome.ANNOUNCING -> TorrentTrackerState.ANNOUNCING
        TrackerStatus.Outcome.SUCCEEDED -> TorrentTrackerState.WORKING
        TrackerStatus.Outcome.FAILED -> TorrentTrackerState.FAILED
        TrackerStatus.Outcome.TIMED_OUT -> TorrentTrackerState.TIMED_OUT
      },
      peers = status.lastPeerCount,
      seeders = status.scrape?.complete,
      leechers = status.scrape?.incomplete,
      failures = status.consecutiveFailures.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
      message = status.failureReason ?: status.warning,
    )
  }

/**
 * The trackers a torrent that is not running would announce to, all idle: those of its
 * [metainfo] that Ketch can announce to, else those a magnet [url] names; `null` when neither
 * is known.
 */
internal fun idleTrackers(url: String, metainfo: ByteArray?, maxBytes: Int): List<TorrentTracker>? {
  val tiers = if (metainfo != null) {
    sourceTrackerTiers(metainfo, maxBytes)
  } else {
    val magnet = try {
      MagnetUri.parse(url)
    } catch (_: IllegalArgumentException) {
      return null
    }
    TrackerConfiguration.usable(magnet.trackers.map { listOf(it) }).tiers
  }
  val seen = HashSet<String>()
  // A tracker listed again in a later tier counts once, and a tier of repeats is none.
  return tiers.map { tier -> tier.filter(seen::add) }.filter { it.isNotEmpty() }
    .flatMapIndexed { index, tier ->
      tier.map { TorrentTracker(endpoint = trackerLabel(it), tier = index) }
    }
}
