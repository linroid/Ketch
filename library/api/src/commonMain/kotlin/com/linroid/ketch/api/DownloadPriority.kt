package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * Queue priority, from [LOW] to [URGENT]: higher ones start first. It does not share out
 * bandwidth; speed limits do.
 */
@Serializable
enum class DownloadPriority {
  LOW,

  /** The default. */
  NORMAL,
  HIGH,

  /**
   * Without a free slot, pauses the lowest-priority running download other than an URGENT one
   * ([PauseReason.Preempted]), within per-host limits; it resumes when a slot frees.
   */
  URGENT
}
