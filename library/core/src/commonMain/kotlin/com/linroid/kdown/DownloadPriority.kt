package com.linroid.kdown

import kotlinx.serialization.Serializable

/**
 * Priority level for download tasks in the queue.
 * Higher-priority tasks are started before lower-priority ones
 * when download slots become available.
 *
 * Ordinal comparison: [LOW] < [NORMAL] < [HIGH] < [URGENT].
 */
@Serializable
enum class DownloadPriority {
  /** Lowest priority. Started only when no higher-priority tasks are queued. */
  LOW,
  /** Default priority for new downloads. */
  NORMAL,
  /** Elevated priority. Started before [NORMAL] and [LOW] tasks. */
  HIGH,
  /** Highest priority. Started as soon as a slot is available. */
  URGENT
}
