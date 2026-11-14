package com.linroid.ketch.torrent.control

import com.linroid.ketch.torrent.api.TorrentTracker
import com.linroid.ketch.torrent.api.TorrentTrackerState
import com.linroid.ketch.torrent.magnet.MagnetUri
import com.linroid.ketch.torrent.metainfo.MAX_TRACKERS
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
internal fun idleTrackers(
  url: String,
  metainfo: ByteArray?,
  maxBytes: Int,
): List<TorrentTracker>? = torrentTrackerTiers(url, metainfo, maxBytes)?.let(::idleTrackers)

/**
 * The tiers of the trackers a torrent names that Ketch can announce to: those of its
 * [metainfo], else those a magnet [url] names; `null` when neither is known.
 */
internal fun torrentTrackerTiers(
  url: String,
  metainfo: ByteArray?,
  maxBytes: Int,
): List<List<String>>? {
  if (metainfo != null) return sourceTrackerTiers(metainfo, maxBytes)
  val magnet = try {
    MagnetUri.parse(url)
  } catch (_: IllegalArgumentException) {
    return null
  }
  return TrackerConfiguration.usable(magnet.trackers.map { listOf(it) }).tiers
}

/** The trackers of [tiers], all idle. */
internal fun idleTrackers(tiers: List<List<String>>): List<TorrentTracker> {
  val seen = HashSet<String>()
  // A tracker listed again in a later tier counts once, and a tier of repeats is none.
  return tiers.map { tier -> tier.filter(seen::add) }.filter { it.isNotEmpty() }
    .flatMapIndexed { index, tier ->
      tier.map { TorrentTracker(endpoint = trackerLabel(it), tier = index) }
    }
}

/**
 * [tiers] without the trackers at the endpoints [remove] names and with the announce URLs
 * [add] after them, each a tier of its own unless [tiers] has it already.
 *
 * @throws IllegalArgumentException for a URL Ketch cannot announce to, or more than
 *   [MAX_TRACKERS] trackers
 */
internal fun editedTrackerTiers(
  tiers: List<List<String>>,
  add: List<String>,
  remove: List<String>,
): List<List<String>> {
  val gone = remove.toSet()
  val kept = tiers.map { tier -> tier.filter { trackerLabel(it) !in gone } }
    .filter { it.isNotEmpty() }
  val known = kept.flatten().toHashSet()
  val added = add.map { it.trim() }.filter { it.isNotEmpty() && known.add(it) }
  val edited = TrackerConfiguration.prepare(kept + added.map { listOf(it) }).tiers
  require(edited.sumOf { it.size } <= MAX_TRACKERS) { "Too many trackers" }
  return edited
}
