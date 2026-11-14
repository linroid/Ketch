package com.linroid.ketch.torrent.tracker

/**
 * Credential-free snapshot; id indexes unique URLs in the original tier traversal order. What the
 * tracker said is kept only as [trackerMessage] cleans it, and of its URL only [label], the
 * scheme, host and port ([com.linroid.ketch.torrent.trackerLabel]), never a path or passkey.
 *
 * @property tier the index of the tracker's tier, 0 for the first
 */
internal data class TrackerStatus(
  val id: Int,
  val configurationRevision: Long = 0,
  val outcome: Outcome = Outcome.NOT_CONTACTED,
  val attempts: Long = 0,
  val failures: Long = 0,
  val consecutiveFailures: Long = 0,
  val scrapeOutcome: ScrapeOutcome = ScrapeOutcome.NOT_REQUESTED,
  val scrape: TrackerScrape? = null,
  val lastPeerCount: Int? = null,
  val lastIntervalSeconds: Long? = null,
  val lastMinimumIntervalSeconds: Long? = null,
  /** Why the tracker refused the last announce, if it did and said so; private trackers say. */
  val failureReason: String? = null,
  /** The `warning message` of the tracker's last answer. */
  val warning: String? = null,
  val label: String = "",
  val tier: Int = 0,
) {
  enum class ScrapeOutcome { NOT_REQUESTED, REQUESTING, SUCCEEDED, MISSING, FAILED, CANCELED }

  enum class Outcome { NOT_CONTACTED, ANNOUNCING, SUCCEEDED, FAILED, TIMED_OUT, CANCELED }
}

/**
 * One status per tracker from the [statuses] of several announcers of one torrent, as a hybrid's
 * two swarms announce to the same trackers: by tier, then in the order first seen. A tracker
 * keeps the status that says most, one that answered over one that is asked, over one that
 * failed, over one not asked yet, and of two alike the one that named more peers.
 */
internal fun mergeTrackerStatuses(statuses: Collection<List<TrackerStatus>>): List<TrackerStatus> {
  val merged = linkedMapOf<Pair<Int, String>, TrackerStatus>()
  for (status in statuses.flatten()) {
    val key = status.tier to status.label
    val kept = merged[key]
    if (kept == null || status.weight() > kept.weight()) merged[key] = status
  }
  return merged.values.sortedBy { it.tier }.mapIndexed { index, status -> status.copy(id = index) }
}

private fun TrackerStatus.weight(): Long = when (outcome) {
  TrackerStatus.Outcome.SUCCEEDED -> 3
  TrackerStatus.Outcome.ANNOUNCING -> 2
  TrackerStatus.Outcome.FAILED, TrackerStatus.Outcome.TIMED_OUT -> 1
  TrackerStatus.Outcome.NOT_CONTACTED, TrackerStatus.Outcome.CANCELED -> 0
} * 1_000_000L + (lastPeerCount ?: 0).coerceIn(0, 999_999)
