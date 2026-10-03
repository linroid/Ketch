package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.PauseReason

/**
 * Whether a task in this state waits in its device's queue and starts on its own: queued, or
 * paused for an urgent download. Older daemons report such a task as queued.
 */
val DownloadState.waitsInQueue: Boolean
  get() = this is DownloadState.Queued ||
    (this is DownloadState.Paused && reason is PauseReason.Preempted)

/** Whether a task in this state is paused and stays so until someone resumes it. */
val DownloadState.isPausedUntilResumed: Boolean
  get() = this is DownloadState.Paused && reason !is PauseReason.Preempted
