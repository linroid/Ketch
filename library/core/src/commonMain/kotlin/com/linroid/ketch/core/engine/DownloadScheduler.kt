package com.linroid.ketch.core.engine

import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

internal class DownloadScheduler(
  private val queue: DownloadQueue,
  private val scope: CoroutineScope,
) {
  private val log = KetchLogger("DownloadScheduler")
  private val mutex = Mutex()
  private val scheduledJobs = mutableMapOf<String, Job>()

  suspend fun schedule(handle: TaskHandle) {
    val taskId = handle.taskId
    val schedule = handle.request.schedule
    val conditions = handle.request.conditions

    handle.mutableState.value = DownloadState.Scheduled(schedule)
    log.i {
      "Scheduling download: taskId=$taskId, schedule=$schedule, " +
        "conditions=${conditions.size}"
    }
    // A task rescheduled after it had started is restored here after a restart; resuming keeps
    // its progress and falls back to a fresh start without segments.
    launchWhenDue(handle, schedule, conditions)
  }

  /**
   * Waits for [schedule] and [conditions] again, then enqueues the task. A task that ended
   * meanwhile, as when it completed, failed, was canceled or removed while the caller paused it,
   * keeps its record and state, and nothing waits to enqueue it.
   */
  suspend fun reschedule(
    handle: TaskHandle,
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition>,
  ) {
    val taskId = handle.taskId
    mutex.withLock {
      scheduledJobs.remove(taskId)?.cancel()
    }

    // Persist so a restart restores the new schedule. Conditions are kept in memory only.
    handle.record.update {
      if (it.state.isTerminal) {
        it
      } else {
        it.copy(
          request = it.request.copy(schedule = schedule, conditions = conditions),
          state = TaskState.SCHEDULED,
          updatedAt = Clock.System.now(),
        )
      }
    }
    handle.mutableState.update { state ->
      if (state.isTerminal) state else DownloadState.Scheduled(schedule)
    }
    log.i {
      "Rescheduling download: taskId=$taskId, schedule=$schedule, " +
        "conditions=${conditions.size}"
    }
    launchWhenDue(handle, schedule, conditions)
  }

  /**
   * Enqueues the task, resuming its progress, once [schedule] and [conditions] are met. A task
   * that ended (completed, failed or canceled) is never enqueued, as it would download again:
   * nothing waits for one that ended already, and one that ends while it waits is checked again.
   * A cancel shown after the check here then [cancel]s the wait.
   */
  private suspend fun launchWhenDue(
    handle: TaskHandle,
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition>,
  ) {
    val taskId = handle.taskId
    mutex.withLock {
      if (handle.mutableState.value.isTerminal) {
        log.d { "Not scheduling taskId=$taskId, which has ended" }
        return
      }
      val job = scope.launch {
        waitForSchedule(taskId, schedule)
        waitForConditions(taskId, conditions)

        if (handle.mutableState.value.isTerminal) {
          log.d { "Not enqueuing taskId=$taskId, which has ended" }
        } else {
          log.i { "Schedule and conditions met for taskId=$taskId, enqueuing" }
          queue.enqueue(handle, preferResume = true)
        }

        mutex.withLock { scheduledJobs.remove(taskId) }
      }
      scheduledJobs[taskId] = job
    }
  }

  suspend fun cancel(taskId: String) {
    mutex.withLock {
      scheduledJobs.remove(taskId)?.let { job ->
        job.cancel()
        log.d { "Canceled scheduled task: taskId=$taskId" }
      }
    }
  }

  /** Whether a task is currently waiting for its schedule/conditions. */
  suspend fun isScheduled(taskId: String): Boolean {
    return mutex.withLock { scheduledJobs.containsKey(taskId) }
  }

  private suspend fun waitForSchedule(
    taskId: String,
    schedule: DownloadSchedule,
  ) {
    when (schedule) {
      is DownloadSchedule.Immediate -> return
      is DownloadSchedule.AtTime -> {
        val now = Clock.System.now()
        val waitDuration = schedule.startAt - now
        if (waitDuration.isPositive()) {
          log.d { "Waiting $waitDuration for taskId=$taskId (startAt=${schedule.startAt})" }
          delay(waitDuration)
        }
      }

      is DownloadSchedule.AfterDelay -> {
        log.d { "Waiting ${schedule.delay} delay for taskId=$taskId" }
        delay(schedule.delay)
      }
    }
  }

  private suspend fun waitForConditions(
    taskId: String,
    conditions: List<DownloadCondition>,
  ) {
    if (conditions.isEmpty()) return

    log.i { "Waiting for ${conditions.size} condition(s) for taskId=$taskId" }

    val flows = conditions.map { it.isMet() }
    val combined = when (flows.size) {
      1 -> flows[0]
      else -> combine(flows) { values -> values.all { it } }
    }

    combined.first { it }

    log.i { "All conditions met for taskId=$taskId" }
  }
}
