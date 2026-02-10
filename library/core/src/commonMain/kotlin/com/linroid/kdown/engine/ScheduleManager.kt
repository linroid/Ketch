package com.linroid.kdown.engine

import com.linroid.kdown.DownloadCondition
import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.DownloadSchedule
import com.linroid.kdown.DownloadState
import com.linroid.kdown.log.KDownLogger
import com.linroid.kdown.segment.Segment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

internal class ScheduleManager(
  private val scheduler: DownloadScheduler,
  private val scope: CoroutineScope
) {
  private val mutex = Mutex()
  private val scheduledJobs = mutableMapOf<String, Job>()

  suspend fun schedule(
    taskId: String,
    request: DownloadRequest,
    createdAt: Instant,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>
  ) {
    val schedule = request.schedule
    val conditions = request.conditions

    stateFlow.value = DownloadState.Scheduled(schedule)
    KDownLogger.i("ScheduleManager") {
      "Scheduling download: taskId=$taskId, schedule=$schedule, " +
        "conditions=${conditions.size}"
    }

    mutex.withLock {
      val job = scope.launch {
        waitForSchedule(taskId, schedule)
        waitForConditions(taskId, conditions)

        KDownLogger.i("ScheduleManager") {
          "Schedule and conditions met for taskId=$taskId, enqueuing"
        }
        scheduler.enqueue(taskId, request, createdAt, stateFlow, segmentsFlow)

        mutex.withLock { scheduledJobs.remove(taskId) }
      }
      scheduledJobs[taskId] = job
    }
  }

  suspend fun reschedule(
    taskId: String,
    request: DownloadRequest,
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition>,
    createdAt: Instant,
    stateFlow: MutableStateFlow<DownloadState>,
    segmentsFlow: MutableStateFlow<List<Segment>>
  ) {
    mutex.withLock {
      scheduledJobs.remove(taskId)?.cancel()
    }

    stateFlow.value = DownloadState.Scheduled(schedule)
    KDownLogger.i("ScheduleManager") {
      "Rescheduling download: taskId=$taskId, schedule=$schedule, " +
        "conditions=${conditions.size}"
    }

    mutex.withLock {
      val job = scope.launch {
        waitForSchedule(taskId, schedule)
        waitForConditions(taskId, conditions)

        KDownLogger.i("ScheduleManager") {
          "Reschedule conditions met for taskId=$taskId, enqueuing " +
            "with preferResume=true"
        }
        scheduler.enqueue(
          taskId, request, createdAt, stateFlow, segmentsFlow,
          preferResume = true
        )

        mutex.withLock { scheduledJobs.remove(taskId) }
      }
      scheduledJobs[taskId] = job
    }
  }

  suspend fun cancel(taskId: String) {
    mutex.withLock {
      scheduledJobs.remove(taskId)?.let { job ->
        job.cancel()
        KDownLogger.d("ScheduleManager") {
          "Canceled scheduled task: taskId=$taskId"
        }
      }
    }
  }

  private suspend fun waitForSchedule(
    taskId: String,
    schedule: DownloadSchedule
  ) {
    when (schedule) {
      is DownloadSchedule.Immediate -> return
      is DownloadSchedule.AtTime -> {
        val now = Clock.System.now()
        val waitDuration = schedule.startAt - now
        if (waitDuration.isPositive()) {
          KDownLogger.d("ScheduleManager") {
            "Waiting ${waitDuration.inWholeSeconds}s for " +
              "taskId=$taskId (startAt=${schedule.startAt})"
          }
          delay(waitDuration)
        }
      }
      is DownloadSchedule.AfterDelay -> {
        KDownLogger.d("ScheduleManager") {
          "Waiting ${schedule.delay.inWholeSeconds}s delay " +
            "for taskId=$taskId"
        }
        delay(schedule.delay)
      }
    }
  }

  private suspend fun waitForConditions(
    taskId: String,
    conditions: List<DownloadCondition>
  ) {
    if (conditions.isEmpty()) return

    KDownLogger.d("ScheduleManager") {
      "Waiting for ${conditions.size} condition(s) for taskId=$taskId"
    }

    val flows = conditions.map { it.isMet() }
    val combined = when (flows.size) {
      1 -> flows[0]
      else -> combine(flows) { values -> values.all { it } }
    }

    combined.first { it }

    KDownLogger.d("ScheduleManager") {
      "All conditions met for taskId=$taskId"
    }
  }

  /** Whether a task is currently waiting for its schedule/conditions. */
  suspend fun isScheduled(taskId: String): Boolean {
    return mutex.withLock { scheduledJobs.containsKey(taskId) }
  }
}
