package com.linroid.kdown.model

import kotlinx.serialization.Serializable

/**
 * Simplified task state for persistence. Unlike [DownloadState], this enum
 * captures only the high-level lifecycle phase so it can be stored and
 * restored across process restarts.
 */
@Serializable
enum class TaskState {
  PENDING,
  DOWNLOADING,
  PAUSED,
  COMPLETED,
  FAILED,
  CANCELED;

  /** Whether the task reached a final state and cannot be resumed. */
  val isTerminal: Boolean
    get() = this == COMPLETED || this == FAILED || this == CANCELED

  /** Whether the task can be restored/resumed after a process restart. */
  val isRestorable: Boolean
    get() = this == PENDING || this == DOWNLOADING || this == PAUSED
}
