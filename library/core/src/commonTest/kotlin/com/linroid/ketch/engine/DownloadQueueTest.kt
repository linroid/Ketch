package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.DownloadQueue
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Comprehensive tests for queue management via DownloadQueue:
 * priority ordering, concurrency limits, per-host limits,
 * auto-scheduling, preemption, and dequeue/cancel in queue.
 */
class DownloadQueueTest {

  private fun createRequest(
    url: String = "https://example.com/file.zip",
    priority: DownloadPriority = DownloadPriority.NORMAL,
  ) = DownloadRequest(
    url = url,
    destination = Destination("/tmp/"),
    priority = priority,
  )

  private fun createHandle(
    taskId: String,
    request: DownloadRequest = createRequest(),
    createdAt: Instant = Clock.System.now(),
  ): TaskHandle {
    val record = TaskRecord(
      taskId = taskId,
      request = request,
      state = TaskState.QUEUED,
      createdAt = createdAt,
      updatedAt = createdAt,
    )
    return object : TaskHandle {
      override val taskId = taskId
      override val request = request
      override val createdAt = createdAt
      override val mutableState =
        MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments =
        MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val record = AtomicSaver(record) {}
    }
  }

  private fun createScheduler(
    maxConcurrent: Int = 10,
    maxPerHost: Int = 4,
  ): DownloadQueue {
    val engine = FakeHttpEngine()
    val source = HttpDownloadSource(
      httpEngine = engine,
    )
    val coordinator = DownloadCoordinator(
      sourceResolver = SourceResolver(listOf(source)),
      config = { DownloadConfig() },
      fileNameResolver = DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(
        main = Dispatchers.Default,
        network = Dispatchers.Default,
        io = Dispatchers.Default,
      ),
    )
    return DownloadQueue(
      maxConcurrentDownloads = maxConcurrent,
      maxConnectionsPerHost = maxPerHost,
      coordinator = coordinator,
    )
  }

  @Test
  fun retry_beforeTerminalNotification_promotesHigherPriorityTaskFirst() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coordinator = DownloadCoordinator(
      sourceResolver = SourceResolver(listOf(HttpDownloadSource(FakeHttpEngine(failOnHead = true)))),
      config = { DownloadConfig(retryCount = 0) },
      fileNameResolver = DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    val queue = DownloadQueue(1, 1, coordinator)
    try {
      val retry = createHandle("retry")
      queue.enqueue(retry)
      runCurrent()
      assertIs<DownloadState.Failed>(retry.mutableState.value)
      // Deliberately delay the terminal notification, leaving the failed entry active.
      val high = createHandle("high", createRequest(priority = DownloadPriority.HIGH))
      queue.enqueue(high)
      queue.enqueue(retry, preferResume = true)
      runCurrent()
      assertEquals(DownloadState.Queued, retry.mutableState.value)
      // The fake rejects HEAD: failure proves the high-priority task was started.
      assertIs<DownloadState.Failed>(high.mutableState.value)
    } finally {
      coordinator.close()
    }
  }

  // ---- Priority ordering tests ----

  @Test
  fun higherPriority_isQueuedBeforeLowerPriority() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        // maxConcurrent=1 so only one task can run; the rest queue
        val scheduler = createScheduler(maxConcurrent = 1)

        // Fill the single active slot
        val active = createHandle("task-active")
        scheduler.enqueue(active)

        // Enqueue LOW priority
        val low = createHandle(
          "task-low",
          createRequest(priority = DownloadPriority.LOW),
        )
        scheduler.enqueue(low)
        assertIs<DownloadState.Queued>(low.mutableState.value)

        // Enqueue HIGH priority
        val high = createHandle(
          "task-high",
          createRequest(priority = DownloadPriority.HIGH),
        )
        scheduler.enqueue(high)
        assertIs<DownloadState.Queued>(high.mutableState.value)

        // Enqueue NORMAL priority
        val normal = createHandle(
          "task-normal",
          createRequest(priority = DownloadPriority.NORMAL),
        )
        scheduler.enqueue(normal)
        assertIs<DownloadState.Queued>(normal.mutableState.value)

        // When the active task completes, HIGH should be promoted
        // first, then NORMAL, then LOW.
        // Signal completion of active task
        scheduler.onTaskCompleted("task-active")

        // HIGH should have been promoted (moved past Queued)
        withTimeout(2.seconds) {
          high.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }

        // NORMAL and LOW should still be queued
        assertIs<DownloadState.Queued>(normal.mutableState.value)
        assertIs<DownloadState.Queued>(low.mutableState.value)
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun samePriority_fifoOrdering() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(maxConcurrent = 1)

        // Fill the active slot
        val h0 = createHandle("task-0")
        scheduler.enqueue(h0)

        // Enqueue two NORMAL tasks in order
        val first = createHandle(
          "task-first",
          createRequest(priority = DownloadPriority.NORMAL),
        )
        scheduler.enqueue(first)
        assertIs<DownloadState.Queued>(first.mutableState.value)

        // Small delay to ensure different createdAt
        delay(10)

        val second = createHandle(
          "task-second",
          createRequest(priority = DownloadPriority.NORMAL),
        )
        scheduler.enqueue(second)
        assertIs<DownloadState.Queued>(second.mutableState.value)

        // Complete the active task — the first enqueued should
        // be promoted (FIFO within same priority)
        scheduler.onTaskCompleted("task-0")

        withTimeout(2.seconds) {
          first.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }

        // Second should still be queued
        assertIs<DownloadState.Queued>(second.mutableState.value)
      } finally {
        scope.cancel()
      }
    }
  }

  // ---- Concurrency limit tests ----

  @Test
  fun maxConcurrentDownloads_respected() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(maxConcurrent = 2)

        // Fill both slots
        val h1 = createHandle("task-1")
        val h2 = createHandle("task-2")
        scheduler.enqueue(h1)
        scheduler.enqueue(h2)

        // Third task should be queued
        val h3 = createHandle("task-3")
        scheduler.enqueue(h3)
        assertIs<DownloadState.Queued>(h3.mutableState.value)

        // Complete one task — the queued task should be promoted
        scheduler.onTaskCompleted("task-1")

        withTimeout(2.seconds) {
          h3.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun failedTask_freesSlotForQueued() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(maxConcurrent = 1)

        // Fill the slot
        val h1 = createHandle("task-1")
        scheduler.enqueue(h1)

        // Queue a second task
        val h2 = createHandle("task-2")
        scheduler.enqueue(h2)
        assertIs<DownloadState.Queued>(h2.mutableState.value)

        // Signal failure of first task — should promote second
        scheduler.onTaskFailed("task-1")

        withTimeout(2.seconds) {
          h2.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun canceledTask_freesSlotForQueued() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(maxConcurrent = 1)

        // Fill the slot
        val h1 = createHandle("task-1")
        scheduler.enqueue(h1)

        // Queue a second task
        val h2 = createHandle("task-2")
        scheduler.enqueue(h2)
        assertIs<DownloadState.Queued>(h2.mutableState.value)

        // Signal cancellation of first task — should promote second
        scheduler.onTaskCanceled("task-1")

        withTimeout(2.seconds) {
          h2.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  // ---- Per-host limit tests ----

  @Test
  fun perHostLimit_queuesExcessFromSameHost() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        // Allow 10 concurrent but only 1 per host
        val scheduler = createScheduler(
          maxConcurrent = 10, maxPerHost = 1,
        )

        // First task from example.com — should start
        val h1 = createHandle(
          "task-1",
          createRequest(url = "https://example.com/file1.zip"),
        )
        scheduler.enqueue(h1)

        // Second task from same host — should be queued
        val h2 = createHandle(
          "task-2",
          createRequest(url = "https://example.com/file2.zip"),
        )
        scheduler.enqueue(h2)
        assertIs<DownloadState.Queued>(h2.mutableState.value)

        // Task from different host — should start immediately
        val h3 = createHandle(
          "task-3",
          createRequest(url = "https://other.com/file.zip"),
        )
        scheduler.enqueue(h3)

        // task-3 should have moved past Queued (started)
        withTimeout(2.seconds) {
          h3.mutableState.first { it != DownloadState.Queued }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun perHostLimit_promotesFromSameHostAfterCompletion() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(
          maxConcurrent = 10, maxPerHost = 1,
        )

        val h1 = createHandle(
          "task-1",
          createRequest(url = "https://example.com/file1.zip"),
        )
        scheduler.enqueue(h1)

        val h2 = createHandle(
          "task-2",
          createRequest(url = "https://example.com/file2.zip"),
        )
        scheduler.enqueue(h2)
        assertIs<DownloadState.Queued>(h2.mutableState.value)

        // Complete first task — second should be promoted
        scheduler.onTaskCompleted("task-1")

        withTimeout(2.seconds) {
          h2.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun perHostLimit_differentHostsNotAffected() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        // 1 per host, 10 overall
        val scheduler = createScheduler(
          maxConcurrent = 10, maxPerHost = 1,
        )

        // Start tasks from three different hosts
        val hosts = listOf("alpha.com", "beta.com", "gamma.com")
        val handles = hosts.mapIndexed { idx, host ->
          val handle = createHandle(
            "task-$idx",
            createRequest(url = "https://$host/file.zip"),
          )
          scheduler.enqueue(handle)
          handle
        }

        // All should start (no host conflicts)
        for (handle in handles) {
          withTimeout(2.seconds) {
            handle.mutableState.first { it != DownloadState.Queued }
          }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  // ---- Auto-start tests ----

  // ---- Preemption tests ----

  @Test
  fun urgent_cannotPreemptOtherUrgent() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(1, 0, coordinator)
    try {
      val h1 = createHandle("task-urgent-1", httpRequest("urgent-1", DownloadPriority.URGENT))
      queue.enqueue(h1)
      runCurrent()

      // Another URGENT task cannot preempt it and waits instead.
      val h2 = createHandle("task-urgent-2", httpRequest("urgent-2", DownloadPriority.URGENT))
      queue.enqueue(h2)
      runCurrent()

      assertNotPreempted(h1)
      assertEquals(DownloadState.Queued, h2.mutableState.value)
      assertEquals(1, h2.mutableQueuePosition.value)
      assertEquals(listOf(httpRequest("urgent-1").url), engine.probed)
    } finally {
      coordinator.close()
    }
  }

  // ---- setPriority tests ----

  @Test
  fun setPriority_changesQueueOrder() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(maxConcurrent = 1)

        // Fill slot
        val h0 = createHandle("task-0")
        scheduler.enqueue(h0)

        // Queue two NORMAL tasks
        val hA = createHandle(
          "task-A",
          createRequest(priority = DownloadPriority.NORMAL),
        )
        scheduler.enqueue(hA)

        delay(10)

        val hB = createHandle(
          "task-B",
          createRequest(priority = DownloadPriority.NORMAL),
        )
        scheduler.enqueue(hB)

        // Boost task-B to HIGH — it should now be ahead of task-A
        scheduler.setPriority("task-B", DownloadPriority.HIGH)

        // Complete the active task — task-B should be promoted first
        scheduler.onTaskCompleted("task-0")

        withTimeout(2.seconds) {
          hB.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }

        // task-A should still be queued
        assertIs<DownloadState.Queued>(hA.mutableState.value)
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun setPriority_activeUrgent_isProtectedFromPreemption() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(1, 0, coordinator)
    try {
      val active = createHandle("active", httpRequest("active"))
      val queued = createHandle("queued", httpRequest("queued"))
      queue.enqueue(active)
      queue.setPriority("active", DownloadPriority.URGENT)
      queue.enqueue(queued)
      runCurrent()

      queue.setPriority("queued", DownloadPriority.URGENT)
      runCurrent()

      assertNotPreempted(active)
      assertEquals(DownloadState.Queued, queued.mutableState.value)
      assertEquals(1, queued.mutableQueuePosition.value)
      assertEquals(listOf(httpRequest("active").url), engine.probed)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun setPriority_nonExistent_isNoOp() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(maxConcurrent = 10)

        // Should not throw
        scheduler.setPriority("non-existent", DownloadPriority.HIGH)
      } finally {
        scope.cancel()
      }
    }
  }

  // ---- Dequeue tests ----

  @Test
  fun dequeue_removesQueuedTask_doesNotPromote() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(maxConcurrent = 1)

        // Fill slot
        val h1 = createHandle("task-1")
        scheduler.enqueue(h1)

        // Queue two more
        val hA = createHandle("task-A")
        scheduler.enqueue(hA)
        assertIs<DownloadState.Queued>(hA.mutableState.value)

        val hB = createHandle("task-B")
        scheduler.enqueue(hB)
        assertIs<DownloadState.Queued>(hB.mutableState.value)

        // Dequeue task-A
        scheduler.dequeue("task-A")

        // Complete task-1 — task-B should be promoted (not task-A)
        scheduler.onTaskCompleted("task-1")

        withTimeout(2.seconds) {
          hB.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }

        // task-A should remain as Queued (no state change from
        // dequeue itself)
        assertIs<DownloadState.Queued>(hA.mutableState.value)
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun dequeue_activeTask_cancelsAndPromotesNext() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(maxConcurrent = 1)

        val h1 = createHandle("task-1")
        scheduler.enqueue(h1)

        val h2 = createHandle("task-2")
        scheduler.enqueue(h2)
        assertIs<DownloadState.Queued>(h2.mutableState.value)

        // Dequeue the active task — should cancel it and promote
        // the queued task
        scheduler.dequeue("task-1")

        withTimeout(2.seconds) {
          h2.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun dequeue_nonExistent_isNoOp() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(maxConcurrent = 10)

        // Should not throw
        scheduler.dequeue("non-existent")
      } finally {
        scope.cancel()
      }
    }
  }

  // ---- Host extraction tests ----

  @Test
  fun extractHost_httpUrl() {
    val host = DownloadQueue.extractHost(
      "http://cdn.example.com/path/file.zip",
    )
    assertEquals("cdn.example.com", host)
  }

  @Test
  fun extractHost_httpsUrlWithPort() {
    val host = DownloadQueue.extractHost(
      "https://cdn.example.com:443/path/file.zip",
    )
    assertEquals("cdn.example.com", host)
  }

  @Test
  fun extractHost_noPath() {
    val host = DownloadQueue.extractHost(
      "https://example.com",
    )
    assertEquals("example.com", host)
  }

  @Test
  fun extractHost_noScheme_returnsNull() {
    assertNull(DownloadQueue.extractHost("not-a-url"))
    assertNull(DownloadQueue.extractHost("/downloads/file.zip"))
  }

  @Test
  fun extractHost_ftpUserInfo_usesHostNotUser() {
    val host = DownloadQueue.extractHost("ftp://user:p%40ss@ftp.example.com:2121/file")
    assertEquals("ftp.example.com", host)
  }

  @Test
  fun extractHost_passwordContainsAt_usesLastAt() {
    val host = DownloadQueue.extractHost("ftp://user:p@ss@ftp.example.com/file")
    assertEquals("ftp.example.com", host)
  }

  @Test
  fun extractHost_mixedCase_isLowercased() {
    assertEquals(
      DownloadQueue.extractHost("https://example.com/a"),
      DownloadQueue.extractHost("HTTPS://Example.COM/b"),
    )
  }

  @Test
  fun extractHost_ipv6WithPort_stripsBracketsAndPort() {
    assertEquals("::1", DownloadQueue.extractHost("ftp://[::1]:21/file"))
    assertEquals("fe80::1", DownloadQueue.extractHost("http://user@[FE80::1]/file"))
  }

  @Test
  fun extractHost_queryWithoutPath_stopsAtQuery() {
    assertEquals("example.com", DownloadQueue.extractHost("https://example.com?next=/a"))
  }

  @Test
  fun extractHost_hostlessUris_returnNull() {
    assertNull(DownloadQueue.extractHost("magnet:?xt=urn:btih:abc123"))
    assertNull(DownloadQueue.extractHost("torrent:0123456789abcdef"))
    assertNull(DownloadQueue.extractHost("file:///home/user/file.zip"))
    assertNull(DownloadQueue.extractHost("file://localhost/home/user/file.zip"))
  }

  @Test
  fun perHostLimit_hostlessUris_areNotCounted() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(10, 1, coordinator)
    try {
      val first = createHandle("first", createRequest(url = "magnet:?xt=urn:btih:aaa"))
      val second = createHandle("second", createRequest(url = "magnet:?xt=urn:btih:bbb"))
      queue.enqueue(first)
      queue.enqueue(second)
      runCurrent()
      // Both started: without a torrent source they fail instead of waiting in the queue.
      assertIs<DownloadState.Failed>(first.mutableState.value)
      assertIs<DownloadState.Failed>(second.mutableState.value)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun perHostLimit_differentUserInfoAndPort_sharesHostLimit() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(10, 1, coordinator)
    try {
      queue.enqueue(createHandle("alice", createRequest(url = "https://alice@Example.com/a")))
      val bob = createHandle("bob", createRequest(url = "https://bob@example.com:8443/b"))
      queue.enqueue(bob)
      runCurrent()
      assertEquals(listOf("https://alice@Example.com/a"), engine.probed)
      assertEquals(DownloadState.Queued, bob.mutableState.value)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun updateLimits_raisedConcurrency_promotesQueuedTasks() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(1, 0, coordinator)
    try {
      val handles = listOf("a", "b", "c").map {
        createHandle(it, createRequest(url = "https://$it.example.com/file"))
      }
      handles.forEach { queue.enqueue(it) }
      runCurrent()
      assertEquals(1, engine.probed.size)

      queue.updateLimits(maxConcurrentDownloads = 3, maxConnectionsPerHost = 0)
      runCurrent()

      assertEquals(3, engine.probed.size)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun updateLimits_raisedPerHostLimit_promotesSameHostTask() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(10, 1, coordinator)
    try {
      queue.enqueue(createHandle("first", createRequest(url = "https://example.com/1")))
      queue.enqueue(createHandle("second", createRequest(url = "https://example.com/2")))
      runCurrent()
      assertEquals(listOf("https://example.com/1"), engine.probed)

      queue.updateLimits(maxConcurrentDownloads = 10, maxConnectionsPerHost = 2)
      runCurrent()

      assertEquals(listOf("https://example.com/1", "https://example.com/2"), engine.probed)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun updateLimits_lowered_keepsRunningTasksAndHoldsQueue() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(2, 0, coordinator)
    try {
      val first = createHandle("first", createRequest(url = "https://a.example.com/1"))
      val second = createHandle("second", createRequest(url = "https://b.example.com/2"))
      queue.enqueue(first)
      queue.enqueue(second)
      runCurrent()

      queue.updateLimits(maxConcurrentDownloads = 1, maxConnectionsPerHost = 0)
      queue.enqueue(createHandle("third", createRequest(url = "https://c.example.com/3")))
      runCurrent()
      // Lowering the limit neither pauses nor cancels a running task.
      assertTrue(listOf(first, second).none {
        it.mutableState.value is DownloadState.Paused || it.mutableState.value.isTerminal
      })
      assertEquals(2, engine.probed.size)

      queue.onTaskCompleted("first")
      runCurrent()
      // One task is still active, which already fills the lowered limit.
      assertEquals(2, engine.probed.size)

      queue.onTaskCompleted("second")
      runCurrent()
      assertEquals("https://c.example.com/3", engine.probed.last())
    } finally {
      coordinator.close()
    }
  }

  /** Records probed URLs and keeps every started download waiting in its HEAD request. */
  private class BlockingHeadEngine : HttpEngine by FakeHttpEngine() {
    val probed = mutableListOf<String>()

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
      probed += url
      awaitCancellation()
    }
  }

  private fun TestScope.blockingCoordinator(engine: HttpEngine): DownloadCoordinator {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return DownloadCoordinator(
      sourceResolver = SourceResolver(listOf(HttpDownloadSource(engine))),
      config = { DownloadConfig() },
      fileNameResolver = DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
  }

  // ---- Queue positions and preemption, with downloads that never finish on their own ----

  @Test
  fun enqueue_slotsFull_givesWaitingTasksPositionsInStartOrder() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(1, 0, coordinator)
    try {
      val (a, b, c) = listOf("a", "b", "c").map { createHandle(it, httpRequest(it)) }
      queue.enqueue(a)
      queue.enqueue(b)
      queue.enqueue(c)
      runCurrent()

      assertEquals(listOf(null, 1, 2), listOf(a, b, c).map { it.mutableQueuePosition.value })
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun onTaskCompleted_promotesHeadAndShiftsPositions() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(1, 0, coordinator)
    try {
      val (a, b, c) = listOf("a", "b", "c").map { createHandle(it, httpRequest(it)) }
      listOf(a, b, c).forEach { queue.enqueue(it) }
      runCurrent()

      queue.onTaskCompleted("a")
      runCurrent()

      assertNull(b.mutableQueuePosition.value)
      assertEquals(1, c.mutableQueuePosition.value)
      assertEquals(httpRequest("b").url, engine.probed.last())
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun setPriority_high_movesTaskToFront() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(1, 0, coordinator)
    try {
      val (a, b, c) = listOf("a", "b", "c").map { createHandle(it, httpRequest(it)) }
      listOf(a, b, c).forEach { queue.enqueue(it) }

      queue.setPriority("c", DownloadPriority.HIGH)

      assertEquals(1, c.mutableQueuePosition.value)
      assertEquals(2, b.mutableQueuePosition.value)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun dequeue_clearsPositionAndShiftsOthers() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(1, 0, coordinator)
    try {
      val (a, b, c) = listOf("a", "b", "c").map { createHandle(it, httpRequest(it)) }
      listOf(a, b, c).forEach { queue.enqueue(it) }

      queue.dequeue("b")

      assertNull(b.mutableQueuePosition.value)
      assertEquals(1, c.mutableQueuePosition.value)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun updateLimits_raised_clearsPositionsOfStartedTasks() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(1, 0, coordinator)
    try {
      val (a, b, c) = listOf("a", "b", "c").map { createHandle(it, httpRequest(it)) }
      listOf(a, b, c).forEach { queue.enqueue(it) }

      queue.updateLimits(maxConcurrentDownloads = 3, maxConnectionsPerHost = 0)
      runCurrent()

      assertEquals(listOf(null, null), listOf(b, c).map { it.mutableQueuePosition.value })
      assertEquals(3, engine.probed.size)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun enqueue_headHostFull_keepsPositionWhileLaterTaskOfOtherHostStarts() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(2, 1, coordinator)
    try {
      val first = createHandle("first", createRequest(url = "https://example.com/1"))
      val second = createHandle("second", createRequest(url = "https://example.com/2"))
      val other = createHandle("other", createRequest(url = "https://other.com/3"))
      listOf(first, second, other).forEach { queue.enqueue(it) }
      runCurrent()

      // Positions follow the queue order; a full site does not move its task back.
      assertEquals(1, second.mutableQueuePosition.value)
      assertNull(other.mutableQueuePosition.value)
      assertEquals(listOf("https://example.com/1", "https://other.com/3"), engine.probed)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun enqueue_urgent_preemptsVictimAsPausedWithItsReason() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(1, 0, coordinator)
    try {
      val low = createHandle("task-low", httpRequest("low", DownloadPriority.LOW))
      queue.enqueue(low)
      runCurrent()

      val urgent = createHandle("task-urgent", httpRequest("urgent", DownloadPriority.URGENT))
      queue.enqueue(urgent)
      runCurrent()

      assertPreempted(low, byTaskId = "task-urgent", position = 1)
      assertNull(urgent.mutableQueuePosition.value)
      assertEquals(httpRequest("urgent").url, engine.probed.last())
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun enqueue_urgent_victimCompletesWhileStopping_isNotRequeued() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(1, 0, coordinator)
    try {
      val low = createHandle("task-low", httpRequest("low", DownloadPriority.LOW))
      queue.enqueue(low)
      runCurrent()
      val completed = DownloadState.Completed("/tmp/low", 4)
      // Finishes the victim while coordinator.pause waits for its stopping download.
      backgroundScope.launch {
        low.mutableState.first { it is DownloadState.Paused && it.reason is PauseReason.Preempted }
        low.record.update { it.copy(state = TaskState.COMPLETED) }
        low.mutableState.value = completed
      }
      runCurrent()

      val urgent = createHandle("task-urgent", httpRequest("urgent", DownloadPriority.URGENT))
      queue.enqueue(urgent)
      runCurrent()

      assertEquals(completed, low.mutableState.value)
      assertEquals(TaskState.COMPLETED, low.record.value.state)
      assertNull(low.mutableQueuePosition.value)
      assertNull(urgent.mutableQueuePosition.value)
      // A finished victim must not start again when the urgent task frees the slot.
      queue.onTaskCompleted("task-urgent")
      runCurrent()
      assertEquals(listOf(httpRequest("low").url, httpRequest("urgent").url), engine.probed)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun onTaskCompleted_urgentDone_resumesPreemptedVictimAndClearsPosition() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(1, 0, coordinator)
    try {
      val low = createHandle("task-low", httpRequest("low", DownloadPriority.LOW))
      queue.enqueue(low)
      runCurrent()
      queue.enqueue(createHandle("task-urgent", httpRequest("urgent", DownloadPriority.URGENT)))
      runCurrent()

      queue.onTaskCompleted("task-urgent")
      runCurrent()

      assertEquals(DownloadState.Queued, low.mutableState.value)
      assertNull(low.mutableQueuePosition.value)
      assertEquals(
        listOf(httpRequest("low").url, httpRequest("urgent").url, httpRequest("low").url),
        engine.probed,
      )
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun enqueue_urgent_doesNotPreemptTaskThatAlreadyCompleted() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(1, 0, coordinator)
    try {
      val low = createHandle("task-low", httpRequest("low", DownloadPriority.LOW))
      queue.enqueue(low)
      runCurrent()
      // Finished, but Ketch has not reported the completion to the queue yet.
      val completed = DownloadState.Completed("/tmp/low", 4)
      low.record.update { it.copy(state = TaskState.COMPLETED) }
      low.mutableState.value = completed

      val urgent = createHandle("task-urgent", httpRequest("urgent", DownloadPriority.URGENT))
      queue.enqueue(urgent)
      runCurrent()

      assertEquals(completed, low.mutableState.value)
      assertEquals(TaskState.COMPLETED, low.record.value.state)
      assertNull(low.mutableQueuePosition.value)
      assertEquals(1, urgent.mutableQueuePosition.value)
      // The urgent task takes the slot the completion frees, and the finished task stays done.
      queue.onTaskCompleted("task-low", completed)
      runCurrent()
      assertEquals(completed, low.mutableState.value)
      assertNull(low.mutableQueuePosition.value)
      assertNull(urgent.mutableQueuePosition.value)
      assertEquals(listOf(httpRequest("low").url, httpRequest("urgent").url), engine.probed)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun setPriority_urgentOnPreemptedTaskWithoutVictim_keepsItPausedForPreemption() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(1, 0, coordinator)
    try {
      val low = createHandle("task-low", httpRequest("low", DownloadPriority.LOW))
      queue.enqueue(low)
      runCurrent()
      queue.enqueue(createHandle("task-urgent", httpRequest("urgent", DownloadPriority.URGENT)))
      runCurrent()

      queue.setPriority("task-low", DownloadPriority.URGENT)
      runCurrent()

      assertPreempted(low, byTaskId = "task-urgent", position = 1)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun setPriority_urgent_preemptsAndResumesVictim() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(1, 0, coordinator)
    try {
      val active = createHandle("active", httpRequest("active"))
      val queued = createHandle("queued", httpRequest("queued"))
      queue.enqueue(active)
      queue.enqueue(queued)
      runCurrent()

      queue.setPriority("queued", DownloadPriority.URGENT)
      runCurrent()

      assertPreempted(active, byTaskId = "queued", position = 1)
      assertEquals(httpRequest("queued").url, engine.probed.last())
      queue.onTaskCompleted("queued")
      runCurrent()
      assertNull(active.mutableQueuePosition.value)
      assertEquals(httpRequest("active").url, engine.probed.last())
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun setPriority_activeUrgentLowered_startsWaitingUrgentAndResumesVictim() = runTest {
    for (priority in listOf(DownloadPriority.LOW, DownloadPriority.NORMAL, DownloadPriority.HIGH)) {
      val engine = BlockingHeadEngine()
      val coordinator = blockingCoordinator(engine)
      val queue = DownloadQueue(1, 0, coordinator)
      try {
        val active = createHandle("active", httpRequest("active", DownloadPriority.URGENT))
        val queued = createHandle("queued", httpRequest("queued", DownloadPriority.URGENT))
        queue.enqueue(active)
        queue.enqueue(queued)
        runCurrent()
        assertIs<DownloadState.Queued>(queued.mutableState.value)

        queue.setPriority("active", priority)
        runCurrent()

        assertPreempted(active, byTaskId = "queued", position = 1)
        assertEquals(httpRequest("queued").url, engine.probed.last())
        queue.onTaskCompleted("queued")
        runCurrent()
        assertNull(active.mutableQueuePosition.value)
        assertEquals(httpRequest("active").url, engine.probed.last())
      } finally {
        coordinator.close()
      }
    }
  }

  @Test
  fun setPriority_activeUrgentLowered_skipsHostBlockedUrgent() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(2, 1, coordinator)
    try {
      val urgent = DownloadPriority.URGENT
      val protected = createHandle("protected", hostRequest("example.com", "protected", urgent))
      val active = createHandle("active", hostRequest("other.com", "active", urgent))
      val blocked = createHandle(
        "blocked", hostRequest("example.com", "blocked", urgent), Instant.fromEpochMilliseconds(0)
      )
      val eligible = createHandle(
        "eligible", hostRequest("other.com", "eligible", urgent), Instant.fromEpochMilliseconds(1)
      )
      listOf(protected, active, blocked, eligible).forEach { queue.enqueue(it) }
      runCurrent()

      queue.setPriority("active", DownloadPriority.LOW)
      runCurrent()

      assertEquals(DownloadState.Queued, blocked.mutableState.value)
      assertEquals(1, blocked.mutableQueuePosition.value)
      assertPreempted(active, byTaskId = "eligible", position = 2)
      assertEquals("https://other.com/eligible", engine.probed.last())
      assertTrue(protected.mutableState.value !is DownloadState.Paused)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun setPriority_urgent_preemptsSameHostWhenHostLimitIsFull() = runTest {
    val engine = BlockingHeadEngine()
    val coordinator = blockingCoordinator(engine)
    val queue = DownloadQueue(2, 1, coordinator)
    try {
      val unrelated = createHandle(
        "unrelated", hostRequest("other.com", "unrelated", DownloadPriority.LOW)
      )
      val sameHost = createHandle("same-host", hostRequest("example.com", "same-host"))
      val queued = createHandle("queued", hostRequest("example.com", "queued"))
      listOf(unrelated, sameHost, queued).forEach { queue.enqueue(it) }
      runCurrent()

      queue.setPriority("queued", DownloadPriority.URGENT)
      runCurrent()

      assertPreempted(sameHost, byTaskId = "queued", position = 1)
      assertTrue(unrelated.mutableState.value !is DownloadState.Paused)
      assertEquals("https://example.com/queued", engine.probed.last())
    } finally {
      coordinator.close()
    }
  }

  private fun httpRequest(
    name: String,
    priority: DownloadPriority = DownloadPriority.NORMAL,
  ) = hostRequest("example.com", name, priority)

  private fun hostRequest(
    host: String,
    name: String,
    priority: DownloadPriority = DownloadPriority.NORMAL,
  ) = createRequest(url = "https://$host/$name", priority = priority)

  /** [handle] keeps running: it is neither paused nor waiting in the queue. */
  private fun assertNotPreempted(handle: TaskHandle) {
    assertTrue(handle.mutableState.value !is DownloadState.Paused)
    assertNull(handle.mutableQueuePosition.value)
  }

  /** [handle] waits in the queue, paused for the urgent [byTaskId], with a QUEUED record. */
  private fun assertPreempted(handle: TaskHandle, byTaskId: String, position: Int) {
    val state = assertIs<DownloadState.Paused>(handle.mutableState.value)
    assertEquals(PauseReason.Preempted(byTaskId), state.reason)
    assertEquals(TaskState.QUEUED, handle.record.value.state)
    assertEquals(position, handle.mutableQueuePosition.value)
  }

  // ---- Multiple promotions after completion ----

  @Test
  fun multipleSlotsFree_promotesMultipleTasks() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(maxConcurrent = 2)

        // Fill both slots
        val h1 = createHandle("task-1")
        val h2 = createHandle("task-2")
        scheduler.enqueue(h1)
        scheduler.enqueue(h2)

        // Queue two more
        val h3 = createHandle("task-3")
        val h4 = createHandle("task-4")
        scheduler.enqueue(h3)
        scheduler.enqueue(h4)
        assertIs<DownloadState.Queued>(h3.mutableState.value)
        assertIs<DownloadState.Queued>(h4.mutableState.value)

        // Complete both active tasks
        scheduler.onTaskCompleted("task-1")
        scheduler.onTaskCompleted("task-2")

        // Both queued tasks should now be promoted
        withTimeout(2.seconds) {
          h3.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }
        withTimeout(2.seconds) {
          h4.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  // ---- Mixed priority queue ordering ----

  @Test
  fun mixedPriorities_promotedInCorrectOrder() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(maxConcurrent = 1)

        // Fill the slot
        val h0 = createHandle("task-0")
        scheduler.enqueue(h0)

        // Queue LOW, HIGH, NORMAL in that enqueue order
        val low = createHandle(
          "task-low",
          createRequest(priority = DownloadPriority.LOW),
        )
        scheduler.enqueue(low)

        val high = createHandle(
          "task-high",
          createRequest(priority = DownloadPriority.HIGH),
        )
        scheduler.enqueue(high)

        val normal = createHandle(
          "task-normal",
          createRequest(priority = DownloadPriority.NORMAL),
        )
        scheduler.enqueue(normal)

        // Verify all queued
        assertIs<DownloadState.Queued>(low.mutableState.value)
        assertIs<DownloadState.Queued>(high.mutableState.value)
        assertIs<DownloadState.Queued>(normal.mutableState.value)

        // Round 1: complete task-0 — HIGH promoted
        scheduler.onTaskCompleted("task-0")
        withTimeout(2.seconds) {
          high.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }
        assertIs<DownloadState.Queued>(normal.mutableState.value)
        assertIs<DownloadState.Queued>(low.mutableState.value)

        // Round 2: complete task-high — NORMAL promoted
        scheduler.onTaskCompleted("task-high")
        withTimeout(2.seconds) {
          normal.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }
        assertIs<DownloadState.Queued>(low.mutableState.value)

        // Round 3: complete task-normal — LOW promoted
        scheduler.onTaskCompleted("task-normal")
        withTimeout(2.seconds) {
          low.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  // ---- Per-host + global concurrency interaction ----

  @Test
  fun hostLimitAndGlobalLimit_bothApplied() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        // Global limit of 2, per-host limit of 2
        val scheduler = createScheduler(
          maxConcurrent = 2, maxPerHost = 2,
        )

        // Fill both global slots from the same host
        val h1 = createHandle(
          "task-1",
          createRequest(url = "https://example.com/a"),
        )
        val h2 = createHandle(
          "task-2",
          createRequest(url = "https://example.com/b"),
        )
        scheduler.enqueue(h1)
        scheduler.enqueue(h2)

        // A third task from a different host — should be queued
        // because global limit is 2
        val h3 = createHandle(
          "task-3",
          createRequest(url = "https://other.com/c"),
        )
        scheduler.enqueue(h3)
        assertIs<DownloadState.Queued>(h3.mutableState.value)
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun hostLimitSkipsBlockedHost_promotesDifferentHost() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        // 2 concurrent, 1 per host
        val scheduler = createScheduler(
          maxConcurrent = 2, maxPerHost = 1,
        )

        // Start one task from host-A
        val h1 = createHandle(
          "task-1",
          createRequest(url = "https://host-a.com/file"),
        )
        scheduler.enqueue(h1)

        // Start one task from host-B
        val h2 = createHandle(
          "task-2",
          createRequest(url = "https://host-b.com/file"),
        )
        scheduler.enqueue(h2)

        // Queue: another host-A (blocked by per-host limit)
        val hA2 = createHandle(
          "task-a2",
          createRequest(url = "https://host-a.com/file2"),
        )
        scheduler.enqueue(hA2)
        assertIs<DownloadState.Queued>(hA2.mutableState.value)

        // Queue: host-C (should be eligible when slot opens)
        val hC = createHandle(
          "task-c",
          createRequest(url = "https://host-c.com/file"),
        )
        scheduler.enqueue(hC)
        assertIs<DownloadState.Queued>(hC.mutableState.value)

        // Complete task from host-B. Now slot is open.
        // task-a2 is first in queue but host-a is at limit.
        // task-c from host-c should be promoted instead.
        scheduler.onTaskCompleted("task-2")

        withTimeout(2.seconds) {
          hC.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }

        // task-a2 should still be queued (host-a at limit)
        assertIs<DownloadState.Queued>(hA2.mutableState.value)
      } finally {
        scope.cancel()
      }
    }
  }
}
