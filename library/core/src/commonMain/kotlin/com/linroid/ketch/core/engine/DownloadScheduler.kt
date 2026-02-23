package com.linroid.ketch.core.engine

import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.task.TaskHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    mutex.withLock {
      val job = scope.launch {
        waitForSchedule(taskId, schedule)
        waitForConditions(taskId, conditions)

        log.i { "Schedule and conditions met for taskId=$taskId, enqueuing" }
        queue.enqueue(handle)

        mutex.withLock { scheduledJobs.remove(taskId) }
      }
      scheduledJobs[taskId] = job
    }
  }

  suspend fun reschedule(
    handle: TaskHandle,
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition>,
  ) {
    val taskId = handle.taskId
    mutex.withLock {
      scheduledJobs.remove(taskId)?.cancel()
    }

    handle.mutableState.value = DownloadState.Scheduled(schedule)
    log.i {
      "Rescheduling download: taskId=$taskId, schedule=$schedule, " +
        "conditions=${conditions.size}"
    }

    mutex.withLock {
      val job = scope.launch {
        waitForSchedule(taskId, schedule)
        waitForConditions(taskId, conditions)

        log.i {
          "Reschedule conditions met for taskId=$taskId, enqueuing " +
            "with preferResume=true"
        }
        queue.enqueue(handle, preferResume = true)

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
