package com.linroid.ketch.app.instance

import kotlin.time.Instant

/**
 * What the embedded instance's subscribed tracker list holds.
 *
 * @property trackers the usable announce URLs the list holds, in its order.
 * @property updatedAt when the list was last downloaded; `null` before the first download, while
 *   the instance uses the copy it ships, if any.
 * @property failed whether the last download failed.
 * @property updating whether the list is being downloaded.
 */
data class TrackerListStatus(
  val trackers: List<String> = emptyList(),
  val updatedAt: Instant? = null,
  val failed: Boolean = false,
  val updating: Boolean = false,
)
