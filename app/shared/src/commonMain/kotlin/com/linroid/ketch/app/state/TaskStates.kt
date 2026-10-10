package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.PauseReason

/**
 * Whether a task in this state waits in its device's queue and starts on its own: queued, or
 * paused for an urgent download. Older daemons report such a task as queued. A queued task that
 * [isStarting] already left the queue.
 */
val DownloadState.waitsInQueue: Boolean
  get() = this is DownloadState.Queued ||
    (this is DownloadState.Paused && reason is PauseReason.Preempted)

/** Whether a task in this state is paused and stays so until someone resumes it. */
val DownloadState.isPausedUntilResumed: Boolean
  get() = this is DownloadState.Paused && reason !is PauseReason.Preempted

/**
 * Whether a task in this state, at [queuePosition], holds a slot of its device while it gets
 * ready to download, such as a magnet link looking for its metadata: the engine reports it as
 * queued, but out of the queue. Only devices whose [features] list
 * [KetchFeatures.QUEUE_POSITION] tell it from a task that waits.
 */
fun DownloadState.isStarting(queuePosition: Int?, features: Set<String>): Boolean =
  this is DownloadState.Queued && queuePosition == null &&
    KetchFeatures.QUEUE_POSITION in features
