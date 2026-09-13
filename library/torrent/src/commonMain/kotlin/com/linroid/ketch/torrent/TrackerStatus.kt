package com.linroid.ketch.torrent

/** Credential-free snapshot; id indexes unique URLs in the original tier traversal order. */
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
) {
  enum class ScrapeOutcome { NOT_REQUESTED, REQUESTING, SUCCEEDED, MISSING, FAILED, CANCELED }

  enum class Outcome { NOT_CONTACTED, ANNOUNCING, SUCCEEDED, FAILED, TIMED_OUT, CANCELED }
}
