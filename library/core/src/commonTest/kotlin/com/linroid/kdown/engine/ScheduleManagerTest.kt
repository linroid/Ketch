package com.linroid.kdown.engine

import com.linroid.kdown.DownloadCondition
import com.linroid.kdown.DownloadConfig
import com.linroid.kdown.DownloadPriority
import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.DownloadSchedule
import com.linroid.kdown.DownloadState
import com.linroid.kdown.QueueConfig
import com.linroid.kdown.file.DefaultFileNameResolver
import com.linroid.kdown.segment.Segment
import com.linroid.kdown.task.InMemoryTaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

  private fun createTestComponents(
    scope: CoroutineScope
  ): Pair<DownloadScheduler, ScheduleManager> {
    val engine = FakeHttpEngine()
    val coordinator = DownloadCoordinator(
      httpEngine = engine,
      taskStore = InMemoryTaskStore(),
      config = DownloadConfig(),
      fileAccessorFactory = { throw UnsupportedOperationException() },
      fileNameResolver = DefaultFileNameResolver()
    )
    val scheduler = DownloadScheduler(
      queueConfig = QueueConfig(maxConcurrentDownloads = 10),
      coordinator = coordinator,
      scope = scope
    )
    val manager = ScheduleManager(scheduler, scope)
    return scheduler to manager
  }

  @Test
  fun afterDelay_enqueuesAfterDelay() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val stateFlow =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow =
          MutableStateFlow<List<Segment>>(emptyList())

        val (_, manager) = createTestComponents(scope)

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
  fun atTime_setsScheduledState() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val stateFlow =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow =
          MutableStateFlow<List<Segment>>(emptyList())

        val (_, manager) = createTestComponents(scope)

        val futureTime = Clock.System.now() + 10.seconds
        val request = createRequest(
          schedule = DownloadSchedule.AtTime(futureTime)
        )

        manager.schedule(
          "task-1", request, Clock.System.now(),
          stateFlow, segmentsFlow
        )

        val state = stateFlow.value
        assertIs<DownloadState.Scheduled>(state)
        assertEquals(
          DownloadSchedule.AtTime(futureTime),
          state.schedule
        )
        assertTrue(manager.isScheduled("task-1"))
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun atTime_pastTime_enqueuesImmediately() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val stateFlow =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow =
          MutableStateFlow<List<Segment>>(emptyList())

        val (_, manager) = createTestComponents(scope)

        // Schedule in the past — should fire immediately
        val pastTime = Clock.System.now() - 1.seconds
        val request = createRequest(
          schedule = DownloadSchedule.AtTime(pastTime)
        )

        manager.schedule(
          "task-1", request, Clock.System.now(),
          stateFlow, segmentsFlow
        )

        // Should transition out of Scheduled quickly
        withTimeout(2.seconds) {
          stateFlow.first { it !is DownloadState.Scheduled }
        }
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

        val (_, manager) = createTestComponents(scope)

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
          stateFlow.first { it !is DownloadState.Scheduled }
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
  fun multipleConditions_allMustBeMet() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val stateFlow =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow =
          MutableStateFlow<List<Segment>>(emptyList())

        val condition1Met = MutableStateFlow(false)
        val condition2Met = MutableStateFlow(false)
        val cond1 = object : DownloadCondition {
          override fun isMet(): Flow<Boolean> = condition1Met
        }
        val cond2 = object : DownloadCondition {
          override fun isMet(): Flow<Boolean> = condition2Met
        }

        val (_, manager) = createTestComponents(scope)

        val request = createRequest(
          conditions = listOf(cond1, cond2)
        )

        manager.schedule(
          "task-1", request, Clock.System.now(),
          stateFlow, segmentsFlow
        )

        assertIs<DownloadState.Scheduled>(stateFlow.value)

        // Meet only the first condition — should still wait
        condition1Met.value = true
        delay(200)
        assertIs<DownloadState.Scheduled>(stateFlow.value)

        // Meet the second condition — now should proceed
        condition2Met.value = true

        withTimeout(2.seconds) {
          stateFlow.first { it !is DownloadState.Scheduled }
        }
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

        val (_, manager) = createTestComponents(scope)

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

        delay(100)
        assertTrue(
          !manager.isScheduled("task-1"),
          "Task should no longer be scheduled after cancel"
        )
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun cancel_nonExistentTask_doesNothing() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val (_, manager) = createTestComponents(scope)

        // Should not throw
        manager.cancel("non-existent")
        assertFalse(manager.isScheduled("non-existent"))
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun isScheduled_returnsFalseForUnknownTask() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val (_, manager) = createTestComponents(scope)
        assertFalse(manager.isScheduled("unknown"))
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun reschedule_setsScheduledState() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val stateFlow =
          MutableStateFlow<DownloadState>(DownloadState.Paused(
            com.linroid.kdown.DownloadProgress(500, 1000)
          ))
        val segmentsFlow =
          MutableStateFlow<List<Segment>>(emptyList())

        val (_, manager) = createTestComponents(scope)
        val request = createRequest()
        val newSchedule = DownloadSchedule.AfterDelay(10.seconds)

        manager.reschedule(
          "task-1", request, newSchedule, emptyList(),
          Clock.System.now(), stateFlow, segmentsFlow
        )

        val state = stateFlow.value
        assertIs<DownloadState.Scheduled>(state)
        assertEquals(newSchedule, state.schedule)
        assertTrue(manager.isScheduled("task-1"))
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun reschedule_cancelsOldScheduleJob() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val stateFlow =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow =
          MutableStateFlow<List<Segment>>(emptyList())

        val (_, manager) = createTestComponents(scope)
        val request = createRequest(
          schedule = DownloadSchedule.AfterDelay(10.seconds)
        )

        // Schedule with long delay
        manager.schedule(
          "task-1", request, Clock.System.now(),
          stateFlow, segmentsFlow
        )
        assertTrue(manager.isScheduled("task-1"))

        // Reschedule with short delay — old job should be canceled
        val shortDelay = DownloadSchedule.AfterDelay(100.milliseconds)
        manager.reschedule(
          "task-1", request, shortDelay, emptyList(),
          Clock.System.now(), stateFlow, segmentsFlow
        )

        val state = stateFlow.value
        assertIs<DownloadState.Scheduled>(state)
        assertEquals(shortDelay, state.schedule)

        // New schedule should fire quickly
        withTimeout(2.seconds) {
          stateFlow.first { it !is DownloadState.Scheduled }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun reschedule_withConditions_waitsForConditions() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val stateFlow =
          MutableStateFlow<DownloadState>(DownloadState.Paused(
            com.linroid.kdown.DownloadProgress(500, 1000)
          ))
        val segmentsFlow =
          MutableStateFlow<List<Segment>>(emptyList())

        val conditionMet = MutableStateFlow(false)
        val condition = object : DownloadCondition {
          override fun isMet(): Flow<Boolean> = conditionMet
        }

        val (_, manager) = createTestComponents(scope)
        val request = createRequest()

        manager.reschedule(
          "task-1", request, DownloadSchedule.Immediate,
          listOf(condition),
          Clock.System.now(), stateFlow, segmentsFlow
        )

        // Should be Scheduled while waiting for condition
        assertIs<DownloadState.Scheduled>(stateFlow.value)

        // Still waiting
        delay(200)
        assertIs<DownloadState.Scheduled>(stateFlow.value)

        // Meet the condition
        conditionMet.value = true

        withTimeout(2.seconds) {
          stateFlow.first { it !is DownloadState.Scheduled }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun afterDelay_enqueuesMovesPastScheduled() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val stateFlow =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow =
          MutableStateFlow<List<Segment>>(emptyList())

        val (_, manager) = createTestComponents(scope)

        val request = createRequest(
          schedule = DownloadSchedule.AfterDelay(100.milliseconds)
        )

        manager.schedule(
          "task-1", request, Clock.System.now(),
          stateFlow, segmentsFlow
        )

        assertIs<DownloadState.Scheduled>(stateFlow.value)

        // After the delay fires, state should move past Scheduled
        withTimeout(2.seconds) {
          stateFlow.first { it !is DownloadState.Scheduled }
        }

        // And the job should be cleaned up
        delay(100)
        assertFalse(manager.isScheduled("task-1"))
      } finally {
        scope.cancel()
      }
    }
  }
}
