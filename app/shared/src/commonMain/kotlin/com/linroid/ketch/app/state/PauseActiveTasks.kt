package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask

/**
 * Pauses every queued task of a device, then every downloading one, so the queue cannot start a
 * queued task in the slot a paused download frees. The queue can still start a task that was not
 * queued when a round began, such as one a preemption re-queued, so later rounds pause those.
 * Each task is tried once, and one failing never stops the others.
 *
 * For hosts without an [AppState], such as the Android download service's Pause all button.
 *
 * @param tasks reads the device's tasks as they are now.
 * @param rounds most rounds to run.
 * @return the tasks that could not be paused, each with why.
 */
suspend fun pauseActiveTasks(
  tasks: () -> List<DownloadTask>,
  rounds: Int = 3,
): List<Pair<DownloadTask, Throwable>> {
  val failures = ArrayList<Pair<DownloadTask, Throwable>>()
  val attempted = HashSet<String>()
  repeat(rounds) {
    val pending = tasks().filter { it.taskId !in attempted }
    val queued = pending.filter { it.state.value is DownloadState.Queued }
    val running = pending.filter { it.state.value is DownloadState.Downloading }
    if (queued.isEmpty() && running.isEmpty()) return failures
    for (task in queued + running) {
      attempted += task.taskId
      catchingUnlessCancelled { task.pause() }.onFailure { failures += task to it }
    }
  }
  return failures
}
