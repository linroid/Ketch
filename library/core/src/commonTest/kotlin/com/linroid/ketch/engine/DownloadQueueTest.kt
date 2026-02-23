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
    maxPerHost: Int = 4,
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
      maxConnectionsPerHost = maxPerHost,
      coordinator = coordinator,
    )
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
  fun urgent_preemptsLowestPriorityActive() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        // maxConcurrent=1 so the URGENT task will try to preempt
        val scheduler = createScheduler(maxConcurrent = 1)

        // Start a LOW priority task
        val low = createHandle(
          "task-low",
          createRequest(priority = DownloadPriority.LOW),
        )
        scheduler.enqueue(low)

        // Wait for it to start
        withTimeout(2.seconds) {
          low.mutableState.first { it != DownloadState.Queued }
        }

        // Enqueue URGENT — should preempt the LOW task
        val urgent = createHandle(
          "task-urgent",
          createRequest(priority = DownloadPriority.URGENT),
        )
        scheduler.enqueue(urgent)

        // The LOW task should be re-queued
        withTimeout(2.seconds) {
          low.mutableState.first { it is DownloadState.Queued }
        }

        // The URGENT task should have started
        withTimeout(2.seconds) {
          urgent.mutableState.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun urgent_cannotPreemptOtherUrgent() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(maxConcurrent = 1)

        // Start an URGENT task
        val h1 = createHandle(
          "task-urgent-1",
          createRequest(priority = DownloadPriority.URGENT),
        )
        scheduler.enqueue(h1)

        withTimeout(2.seconds) {
          h1.mutableState.first { it != DownloadState.Queued }
        }

        // Enqueue another URGENT — cannot preempt, should be queued
        val h2 = createHandle(
          "task-urgent-2",
          createRequest(priority = DownloadPriority.URGENT),
        )
        scheduler.enqueue(h2)

        assertIs<DownloadState.Queued>(h2.mutableState.value)
      } finally {
        scope.cancel()
      }
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
  fun setPriority_activeTask_isNoOp() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(maxConcurrent = 10)

        val h1 = createHandle("task-1")
        scheduler.enqueue(h1)

        // task-1 is active, setPriority should be a no-op
        scheduler.setPriority("task-1", DownloadPriority.HIGH)

        // Should not throw or change state
        delay(100)
      } finally {
        scope.cancel()
      }
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
  fun extractHost_noScheme_returnsInput() {
    val host = DownloadQueue.extractHost("not-a-url")
    assertEquals("not-a-url", host)
  }

  @Test
  fun extractHost_ipAddress() {
    val host = DownloadQueue.extractHost(
      "https://192.168.1.1:8080/file",
    )
    assertEquals("192.168.1.1", host)
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
