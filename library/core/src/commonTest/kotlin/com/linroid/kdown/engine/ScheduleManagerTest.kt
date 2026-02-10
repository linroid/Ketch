package com.linroid.kdown.engine

import com.linroid.kdown.DownloadCondition
import com.linroid.kdown.DownloadPriority
import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.DownloadSchedule
import com.linroid.kdown.DownloadState
import com.linroid.kdown.QueueConfig
import com.linroid.kdown.segment.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScheduleManagerTest {

  private fun createRequest(
    schedule: DownloadSchedule = DownloadSchedule.Immediate,
    conditions: List<DownloadCondition> = emptyList()
  ) = DownloadRequest(
    url = "https://example.com/file.zip",
    directory = Path("/tmp"),
    schedule = schedule,
    conditions = conditions,
    priority = DownloadPriority.NORMAL
  )

  @Test
  fun afterDelay_enqueuesAfterDelay() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val stateFlow =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow =
          MutableStateFlow<List<Segment>>(emptyList())

        val engine = FakeHttpEngine()
        val coordinator = DownloadCoordinator(
          httpEngine = engine,
          taskStore = com.linroid.kdown.task.InMemoryTaskStore(),
          config = com.linroid.kdown.DownloadConfig(),
          fileAccessorFactory = { throw UnsupportedOperationException() },
          fileNameResolver = com.linroid.kdown.file.DefaultFileNameResolver()
        )
        val scheduler = DownloadScheduler(
          queueConfig = QueueConfig(maxConcurrentDownloads = 10),
          coordinator = coordinator,
          scope = scope
        )
        val manager = ScheduleManager(scheduler, scope)

        val request = createRequest(
          schedule = DownloadSchedule.AfterDelay(200.milliseconds)
        )

        manager.schedule(
          "task-1", request, Clock.System.now(),
          stateFlow, segmentsFlow
        )

        assertIs<DownloadState.Scheduled>(stateFlow.value)
        assertTrue(manager.isScheduled("task-1"))
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun immediate_withConditions_waitsForCondition() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val stateFlow =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow =
          MutableStateFlow<List<Segment>>(emptyList())

        val conditionMet = MutableStateFlow(false)
        val condition = object : DownloadCondition {
          override fun isMet(): Flow<Boolean> = conditionMet
        }

        val engine = FakeHttpEngine()
        val coordinator = DownloadCoordinator(
          httpEngine = engine,
          taskStore = com.linroid.kdown.task.InMemoryTaskStore(),
          config = com.linroid.kdown.DownloadConfig(),
          fileAccessorFactory = { throw UnsupportedOperationException() },
          fileNameResolver = com.linroid.kdown.file.DefaultFileNameResolver()
        )
        val scheduler = DownloadScheduler(
          queueConfig = QueueConfig(maxConcurrentDownloads = 10),
          coordinator = coordinator,
          scope = scope
        )
        val manager = ScheduleManager(scheduler, scope)

        val request = createRequest(
          schedule = DownloadSchedule.Immediate,
          conditions = listOf(condition)
        )

        manager.schedule(
          "task-1", request, Clock.System.now(),
          stateFlow, segmentsFlow
        )

        // Should be in Scheduled state while waiting for condition
        assertIs<DownloadState.Scheduled>(stateFlow.value)

        // Meet the condition
        conditionMet.value = true

        // Wait for the scheduler to pick it up
        withTimeout(2.seconds) {
          while (stateFlow.value is DownloadState.Scheduled) {
            kotlinx.coroutines.delay(50)
          }
        }

        // Should have moved past Scheduled
        val state = stateFlow.value
        assertTrue(
          state !is DownloadState.Scheduled,
          "Expected non-Scheduled state, got $state"
        )
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun cancel_stopsScheduledTask() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val stateFlow =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow =
          MutableStateFlow<List<Segment>>(emptyList())

        val engine = FakeHttpEngine()
        val coordinator = DownloadCoordinator(
          httpEngine = engine,
          taskStore = com.linroid.kdown.task.InMemoryTaskStore(),
          config = com.linroid.kdown.DownloadConfig(),
          fileAccessorFactory = { throw UnsupportedOperationException() },
          fileNameResolver = com.linroid.kdown.file.DefaultFileNameResolver()
        )
        val scheduler = DownloadScheduler(
          queueConfig = QueueConfig(maxConcurrentDownloads = 10),
          coordinator = coordinator,
          scope = scope
        )
        val manager = ScheduleManager(scheduler, scope)

        // Schedule with long delay
        val request = createRequest(
          schedule = DownloadSchedule.AfterDelay(10.seconds)
        )
        manager.schedule(
          "task-1", request, Clock.System.now(),
          stateFlow, segmentsFlow
        )

        assertTrue(manager.isScheduled("task-1"))

        // Cancel it
        manager.cancel("task-1")

        kotlinx.coroutines.delay(100)
        assertTrue(
          !manager.isScheduled("task-1"),
          "Task should no longer be scheduled after cancel"
        )
      } finally {
        scope.cancel()
      }
    }
  }
}
