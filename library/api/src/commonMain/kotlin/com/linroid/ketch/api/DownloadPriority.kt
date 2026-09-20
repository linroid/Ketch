package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * Priority level for download tasks in the queue.
 * Higher-priority tasks are started before lower-priority ones
 * when download slots become available. Priority does not weight bandwidth allocation;
 * use task and global speed limits to control transfer rates.
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
  /**
   * Highest priority. When no slot is available, the scheduler
   * pauses the lowest-priority eligible running download to make room, respecting
   * per-host limits. Other URGENT tasks cannot be preempted.
   * The preempted task is re-queued and resumes automatically
   * when a slot opens.
   */
  URGENT
}
