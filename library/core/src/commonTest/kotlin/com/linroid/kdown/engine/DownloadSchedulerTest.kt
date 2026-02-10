package com.linroid.kdown.engine

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class DownloadSchedulerTest {

  private fun createRequest(
    priority: DownloadPriority = DownloadPriority.NORMAL
  ) = DownloadRequest(
    url = "https://example.com/file.zip",
    directory = Path("/tmp"),
    priority = priority
  )

  private fun createScheduler(
    scope: CoroutineScope,
    maxConcurrent: Int = 10,
    autoStart: Boolean = true
  ): DownloadScheduler {
    val engine = FakeHttpEngine()
    val coordinator = DownloadCoordinator(
      httpEngine = engine,
      taskStore = InMemoryTaskStore(),
      config = DownloadConfig(),
      fileAccessorFactory = { throw UnsupportedOperationException() },
      fileNameResolver = DefaultFileNameResolver()
    )
    return DownloadScheduler(
      queueConfig = QueueConfig(
        maxConcurrentDownloads = maxConcurrent,
        autoStart = autoStart
      ),
      coordinator = coordinator,
      scope = scope
    )
  }

  @Test
  fun enqueue_autoStart_movesToActiveState() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(scope)
        val stateFlow =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow =
          MutableStateFlow<List<Segment>>(emptyList())

        scheduler.enqueue(
          "task-1", createRequest(), Clock.System.now(),
          stateFlow, segmentsFlow
        )

        // Should have moved past Pending (to Downloading or failed
        // since fileAccessor throws)
        withTimeout(2.seconds) {
          stateFlow.first { it != DownloadState.Pending }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun enqueue_noAutoStart_staysQueued() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(scope, autoStart = false)
        val stateFlow =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow =
          MutableStateFlow<List<Segment>>(emptyList())

        scheduler.enqueue(
          "task-1", createRequest(), Clock.System.now(),
          stateFlow, segmentsFlow
        )

        assertIs<DownloadState.Queued>(stateFlow.value)
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun enqueue_exceedingMaxConcurrent_queuesTask() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(scope, maxConcurrent = 1)

        // Fill the single slot
        val stateFlow1 =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow1 =
          MutableStateFlow<List<Segment>>(emptyList())
        scheduler.enqueue(
          "task-1", createRequest(), Clock.System.now(),
          stateFlow1, segmentsFlow1
        )

        // Second task should be queued
        val stateFlow2 =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow2 =
          MutableStateFlow<List<Segment>>(emptyList())
        scheduler.enqueue(
          "task-2", createRequest(), Clock.System.now(),
          stateFlow2, segmentsFlow2
        )

        assertIs<DownloadState.Queued>(stateFlow2.value)
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun enqueue_preferResume_setsPreemptedFlag() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(scope)
        val stateFlow =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow =
          MutableStateFlow<List<Segment>>(emptyList())

        // Enqueue with preferResume — the task will be started
        // with resume() first (which may fail since no record
        // exists), then fall back to start()
        scheduler.enqueue(
          "task-1", createRequest(), Clock.System.now(),
          stateFlow, segmentsFlow, preferResume = true
        )

        // Should have attempted to start (moved past Pending)
        withTimeout(2.seconds) {
          stateFlow.first { it != DownloadState.Pending }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun dequeue_removesQueuedTask() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(scope, maxConcurrent = 1)

        // Fill the single slot
        val stateFlow1 =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow1 =
          MutableStateFlow<List<Segment>>(emptyList())
        scheduler.enqueue(
          "task-1", createRequest(), Clock.System.now(),
          stateFlow1, segmentsFlow1
        )

        // Queue a second task
        val stateFlow2 =
          MutableStateFlow<DownloadState>(DownloadState.Pending)
        val segmentsFlow2 =
          MutableStateFlow<List<Segment>>(emptyList())
        scheduler.enqueue(
          "task-2", createRequest(), Clock.System.now(),
          stateFlow2, segmentsFlow2
        )
        assertIs<DownloadState.Queued>(stateFlow2.value)

        // Dequeue the second task — it should be removed from queue
        scheduler.dequeue("task-2")

        // Verify it stays Queued (not promoted) — the dequeue
        // only removes, it doesn't change state
        delay(100)
        assertIs<DownloadState.Queued>(stateFlow2.value)
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun extractHost_parsesCorrectly() {
    val host = DownloadScheduler.extractHost(
      "https://cdn.example.com:8080/path/file.zip"
    )
    assertTrue(
      host == "cdn.example.com",
      "Expected 'cdn.example.com', got '$host'"
    )
  }

  @Test
  fun extractHost_simpleUrl() {
    val host = DownloadScheduler.extractHost(
      "https://example.com/file.zip"
    )
    assertTrue(
      host == "example.com",
      "Expected 'example.com', got '$host'"
    )
  }
}
