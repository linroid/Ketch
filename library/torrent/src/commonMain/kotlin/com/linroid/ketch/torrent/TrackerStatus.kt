package com.linroid.ketch.torrent

/** Credential-free snapshot; id indexes unique URLs in the original tier traversal order. */
internal data class TrackerStatus(
  val id: Int,
  val outcome: Outcome = Outcome.NOT_CONTACTED,
  val attempts: Long = 0,
  val failures: Long = 0,
  val consecutiveFailures: Long = 0,
  val lastPeerCount: Int? = null,
  val lastIntervalSeconds: Long? = null,
  val lastMinimumIntervalSeconds: Long? = null,
) {
  enum class Outcome { NOT_CONTACTED, ANNOUNCING, SUCCEEDED, FAILED, TIMED_OUT, CANCELED }
}
