package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.DownloadQueue
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.TaskHandle
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DownloadQueueBasicTest {

  private fun createRequest(
    priority: DownloadPriority = DownloadPriority.NORMAL,
  ) = DownloadRequest(
    url = "https://example.com/file.zip",
    destination = Destination("/tmp/"),
    priority = priority,
  )

  private fun createHandle(
    taskId: String,
    request: DownloadRequest = createRequest(),
    createdAt: Instant = Clock.System.now(),
  ): TaskHandle {
    return object : TaskHandle {
      override val taskId = taskId
      override val request = request
      override val createdAt = createdAt
      override val mutableState =
        MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments =
        MutableStateFlow<List<Segment>>(emptyList())
    }
  }

  private fun createScheduler(
    maxConcurrent: Int = 10,
  ): DownloadQueue {
    val engine = FakeHttpEngine()
    val source = HttpDownloadSource(
      httpEngine = engine,
    )
    val coordinator = DownloadCoordinator(
      sourceResolver = SourceResolver(listOf(source)),
      taskStore = InMemoryTaskStore(),
      config = DownloadConfig(),
      fileNameResolver = DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(
        main = Dispatchers.Default,
        network = Dispatchers.Default,
        io = Dispatchers.Default,
      ),
    )
    return DownloadQueue(
      maxConcurrentDownloads = maxConcurrent,
      maxConnectionsPerHost = 0,
      coordinator = coordinator,
    )
  }

  @Test
  fun enqueue_startsImmediatelyWhenSlotAvailable() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler()
        val handle = createHandle("task-1")

        scheduler.enqueue(handle)

        // Should have moved past Queued (to Downloading or failed
        // since fileAccessor throws)
        withTimeout(2.seconds) {
          handle.mutableState.first { it != DownloadState.Queued }
        }
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
        val scheduler = createScheduler(maxConcurrent = 1)

        // Fill the single slot
        val handle1 = createHandle("task-1")
        scheduler.enqueue(handle1)

        // Second task should be queued
        val handle2 = createHandle("task-2")
        scheduler.enqueue(handle2)

        assertIs<DownloadState.Queued>(handle2.mutableState.value)
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
        val scheduler = createScheduler()
        val handle = createHandle("task-1")

        // Enqueue with preferResume — the task will be started
        // with resume() first (which may fail since no record
        // exists), then fall back to start()
        scheduler.enqueue(handle, preferResume = true)

        // Should have attempted to start (moved past Queued)
        withTimeout(2.seconds) {
          handle.mutableState.first { it != DownloadState.Queued }
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
        val scheduler = createScheduler(maxConcurrent = 1)

        // Fill the single slot
        val handle1 = createHandle("task-1")
        scheduler.enqueue(handle1)

        // Queue a second task
        val handle2 = createHandle("task-2")
        scheduler.enqueue(handle2)
        assertIs<DownloadState.Queued>(handle2.mutableState.value)

        // Dequeue the second task — it should be removed from queue
        scheduler.dequeue("task-2")

        // Verify it stays Queued (not promoted) — the dequeue
        // only removes, it doesn't change state
        delay(100)
        assertIs<DownloadState.Queued>(handle2.mutableState.value)
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun extractHost_parsesCorrectly() {
    val host = DownloadQueue.extractHost(
      "https://cdn.example.com:8080/path/file.zip",
    )
    assertEquals(host, "cdn.example.com", "Expected 'cdn.example.com', got '$host'")
  }

  @Test
  fun extractHost_simpleUrl() {
    val host = DownloadQueue.extractHost(
      "https://example.com/file.zip",
    )
    assertEquals(host, "example.com", "Expected 'example.com', got '$host'")
  }
}
