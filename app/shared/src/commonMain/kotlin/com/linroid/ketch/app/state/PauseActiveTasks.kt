package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask

/**
 * Pauses every queued task of a device, then every downloading one, so the queue cannot start a
 * queued task in the slot a paused download frees. The queue can still start a task that was not
 * queued when a round began, such as one a preemption re-queued, so later rounds pause those.
 * Each task is tried once, and one failing never stops the others.
 *
 * Hosts without an [AppState], such as the Android download service's Pause all button, pause
 * one task at a time; [AppState] pauses each group at once.
 *
 * @param tasks reads the device's tasks as they are now.
 * @param rounds most rounds to run.
 * @param pauseEach pauses a group of tasks and returns each with why it could not be paused, or
 *   `null`; one at a time by default.
 */
suspend fun pauseActiveTasks(
  tasks: () -> List<DownloadTask>,
  rounds: Int = 3,
  pauseEach: suspend (List<DownloadTask>) -> List<Pair<DownloadTask, Throwable?>> = { group ->
    group.map { it to catchingUnlessCancelled { it.pause() }.exceptionOrNull() }
  },
): PausedTasks {
  val paused = ArrayList<DownloadTask>()
  val failures = ArrayList<Pair<DownloadTask, Throwable>>()
  val attempted = HashSet<String>()
  repeat(rounds) {
    val pending = tasks().filter { it.taskId !in attempted }
    val queued = pending.filter { it.state.value is DownloadState.Queued }
    val running = pending.filter { it.state.value is DownloadState.Downloading }
    if (queued.isEmpty() && running.isEmpty()) return PausedTasks(paused, failures)
    for (group in listOf(queued, running)) {
      attempted += group.map { it.taskId }
      pauseEach(group).forEach { (task, error) ->
        if (error == null) paused += task else failures += task to error
      }
    }
  }
  return PausedTasks(paused, failures)
}

/**
 * What [pauseActiveTasks] did.
 *
 * @property paused the tasks it paused.
 * @property failures the tasks that could not be paused, each with why.
 */
class PausedTasks(
  val paused: List<DownloadTask>,
  val failures: List<Pair<DownloadTask, Throwable>>,
)
