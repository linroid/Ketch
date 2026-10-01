package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadState

/**
 * Status tabs of the Downloads page. The Pulse bar, the tray and device rows count tasks with
 * the same definitions.
 *
 * @property label name shown on the tab.
 */
enum class StatusFilter(val label: String) {
  All("All"),
  Downloading("Downloading"),

  /** Queued for a free slot, or scheduled to start later. */
  Waiting("Waiting"),
  Paused("Paused"),
  Done("Done"),

  /** Failed or canceled. */
  Failed("Failed");

  /** Whether a task in [state] belongs on this tab. */
  fun matches(state: DownloadState): Boolean = when (this) {
    All -> true
    Downloading -> state is DownloadState.Downloading
    Waiting -> state is DownloadState.Queued || state is DownloadState.Scheduled
    Paused -> state is DownloadState.Paused
    Done -> state is DownloadState.Completed
    Failed -> state is DownloadState.Failed || state is DownloadState.Canceled
  }

  /** Number of the tasks in [states] that belong on this tab. */
  fun count(states: Collection<DownloadState>): Int =
    if (this == All) states.size else states.count(::matches)

  companion object {
    /** Number of the tasks in [states] on each tab. */
    fun counts(states: Collection<DownloadState>): Map<StatusFilter, Int> =
      entries.associateWith { it.count(states) }
  }
}
