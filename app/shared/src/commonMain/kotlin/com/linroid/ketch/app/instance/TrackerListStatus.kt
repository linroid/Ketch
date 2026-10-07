package com.linroid.ketch.app.instance

import kotlin.time.Instant

/**
 * What the embedded instance's subscribed tracker list holds.
 *
 * @property trackers how many usable trackers the list holds.
 * @property updatedAt when the list was last downloaded; `null` before the first download, while
 *   the instance uses the copy it ships, if any.
 * @property failed whether the last download failed.
 * @property updating whether the list is being downloaded.
 */
data class TrackerListStatus(
  val trackers: Int = 0,
  val updatedAt: Instant? = null,
  val failed: Boolean = false,
  val updating: Boolean = false,
)
