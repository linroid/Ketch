package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.config.DownloadConfig
import com.linroid.ketch.api.config.QueueConfig
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.DownloadQueue
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.task.InMemoryTaskStore
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

  private fun createScheduler(
    scope: CoroutineScope,
    maxConcurrent: Int = 10,
    maxPerHost: Int = 4,
    autoStart: Boolean = true,
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
      scope = scope,
    )
    return DownloadQueue(
      queueConfig = QueueConfig(
        maxConcurrentDownloads = maxConcurrent,
        maxConnectionsPerHost = maxPerHost,
        autoStart = autoStart,
      ),
      coordinator = coordinator,
      scope = scope,
    )
  }

  private fun newFlows(): Pair<
    MutableStateFlow<DownloadState>,
    MutableStateFlow<List<Segment>>
  > {
    return MutableStateFlow<DownloadState>(DownloadState.Queued) to
      MutableStateFlow<List<Segment>>(emptyList())
  }

  // ---- Priority ordering tests ----

  @Test
  fun higherPriority_isQueuedBeforeLowerPriority() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        // maxConcurrent=1 so only one task can run; the rest queue
        val scheduler = createScheduler(scope, maxConcurrent = 1)

        // Fill the single active slot
        val (sf1, seg1) = newFlows()
        scheduler.enqueue(
          "task-active", createRequest(), Clock.System.now(),
          sf1, seg1
        )

        // Enqueue LOW priority
        val (sfLow, segLow) = newFlows()
        scheduler.enqueue(
          "task-low",
          createRequest(priority = DownloadPriority.LOW),
          Clock.System.now(),
          sfLow, segLow
        )
        assertIs<DownloadState.Queued>(sfLow.value)

        // Enqueue HIGH priority
        val (sfHigh, segHigh) = newFlows()
        scheduler.enqueue(
          "task-high",
          createRequest(priority = DownloadPriority.HIGH),
          Clock.System.now(),
          sfHigh, segHigh
        )
        assertIs<DownloadState.Queued>(sfHigh.value)

        // Enqueue NORMAL priority
        val (sfNorm, segNorm) = newFlows()
        scheduler.enqueue(
          "task-normal",
          createRequest(priority = DownloadPriority.NORMAL),
          Clock.System.now(),
          sfNorm, segNorm
        )
        assertIs<DownloadState.Queued>(sfNorm.value)

        // When the active task completes, HIGH should be promoted
        // first, then NORMAL, then LOW.
        // Signal completion of active task
        scheduler.onTaskCompleted("task-active")

        // HIGH should have been promoted (moved past Queued)
        withTimeout(2.seconds) {
          sfHigh.first { it != DownloadState.Queued && it !is DownloadState.Queued }
        }

        // NORMAL and LOW should still be queued
        assertIs<DownloadState.Queued>(sfNorm.value)
        assertIs<DownloadState.Queued>(sfLow.value)
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
        val scheduler = createScheduler(scope, maxConcurrent = 1)

        // Fill the active slot
        val (sf0, seg0) = newFlows()
        scheduler.enqueue(
          "task-0", createRequest(), Clock.System.now(),
          sf0, seg0
        )

        // Enqueue two NORMAL tasks in order
        val (sfFirst, segFirst) = newFlows()
        scheduler.enqueue(
          "task-first",
          createRequest(priority = DownloadPriority.NORMAL),
          Clock.System.now(),
          sfFirst, segFirst
        )
        assertIs<DownloadState.Queued>(sfFirst.value)

        // Small delay to ensure different createdAt
        delay(10)

        val (sfSecond, segSecond) = newFlows()
        scheduler.enqueue(
          "task-second",
          createRequest(priority = DownloadPriority.NORMAL),
          Clock.System.now(),
          sfSecond, segSecond
        )
        assertIs<DownloadState.Queued>(sfSecond.value)

        // Complete the active task — the first enqueued should
        // be promoted (FIFO within same priority)
        scheduler.onTaskCompleted("task-0")

        withTimeout(2.seconds) {
          sfFirst.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }

        // Second should still be queued
        assertIs<DownloadState.Queued>(sfSecond.value)
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
        val scheduler = createScheduler(scope, maxConcurrent = 2)

        // Fill both slots
        val (sf1, seg1) = newFlows()
        val (sf2, seg2) = newFlows()
        scheduler.enqueue(
          "task-1", createRequest(), Clock.System.now(),
          sf1, seg1
        )
        scheduler.enqueue(
          "task-2", createRequest(), Clock.System.now(),
          sf2, seg2
        )

        // Third task should be queued
        val (sf3, seg3) = newFlows()
        scheduler.enqueue(
          "task-3", createRequest(), Clock.System.now(),
          sf3, seg3
        )
        assertIs<DownloadState.Queued>(sf3.value)

        // Complete one task — the queued task should be promoted
        scheduler.onTaskCompleted("task-1")

        withTimeout(2.seconds) {
          sf3.first {
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
        val scheduler = createScheduler(scope, maxConcurrent = 1)

        // Fill the slot
        val (sf1, seg1) = newFlows()
        scheduler.enqueue(
          "task-1", createRequest(), Clock.System.now(),
          sf1, seg1
        )

        // Queue a second task
        val (sf2, seg2) = newFlows()
        scheduler.enqueue(
          "task-2", createRequest(), Clock.System.now(),
          sf2, seg2
        )
        assertIs<DownloadState.Queued>(sf2.value)

        // Signal failure of first task — should promote second
        scheduler.onTaskFailed("task-1")

        withTimeout(2.seconds) {
          sf2.first {
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
        val scheduler = createScheduler(scope, maxConcurrent = 1)

        // Fill the slot
        val (sf1, seg1) = newFlows()
        scheduler.enqueue(
          "task-1", createRequest(), Clock.System.now(),
          sf1, seg1
        )

        // Queue a second task
        val (sf2, seg2) = newFlows()
        scheduler.enqueue(
          "task-2", createRequest(), Clock.System.now(),
          sf2, seg2
        )
        assertIs<DownloadState.Queued>(sf2.value)

        // Signal cancellation of first task — should promote second
        scheduler.onTaskCanceled("task-1")

        withTimeout(2.seconds) {
          sf2.first {
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
          scope, maxConcurrent = 10, maxPerHost = 1,
        )

        // First task from example.com — should start
        val (sf1, seg1) = newFlows()
        scheduler.enqueue(
          "task-1",
          createRequest(url = "https://example.com/file1.zip"),
          Clock.System.now(), sf1, seg1
        )

        // Second task from same host — should be queued
        val (sf2, seg2) = newFlows()
        scheduler.enqueue(
          "task-2",
          createRequest(url = "https://example.com/file2.zip"),
          Clock.System.now(), sf2, seg2
        )
        assertIs<DownloadState.Queued>(sf2.value)

        // Task from different host — should start immediately
        val (sf3, seg3) = newFlows()
        scheduler.enqueue(
          "task-3",
          createRequest(url = "https://other.com/file.zip"),
          Clock.System.now(), sf3, seg3
        )

        // task-3 should have moved past Queued (started)
        withTimeout(2.seconds) {
          sf3.first { it != DownloadState.Queued }
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
          scope, maxConcurrent = 10, maxPerHost = 1,
        )

        val (sf1, seg1) = newFlows()
        scheduler.enqueue(
          "task-1",
          createRequest(url = "https://example.com/file1.zip"),
          Clock.System.now(), sf1, seg1
        )

        val (sf2, seg2) = newFlows()
        scheduler.enqueue(
          "task-2",
          createRequest(url = "https://example.com/file2.zip"),
          Clock.System.now(), sf2, seg2
        )
        assertIs<DownloadState.Queued>(sf2.value)

        // Complete first task — second should be promoted
        scheduler.onTaskCompleted("task-1")

        withTimeout(2.seconds) {
          sf2.first {
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
          scope, maxConcurrent = 10, maxPerHost = 1,
        )

        // Start tasks from three different hosts
        val hosts = listOf("alpha.com", "beta.com", "gamma.com")
        val flows = hosts.mapIndexed { idx, host ->
          val (sf, seg) = newFlows()
          scheduler.enqueue(
            "task-$idx",
            createRequest(url = "https://$host/file.zip"),
            Clock.System.now(), sf, seg
          )
          sf
        }

        // All should start (no host conflicts)
        for ((idx, sf) in flows.withIndex()) {
          withTimeout(2.seconds) {
            sf.first { it != DownloadState.Queued }
          }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  // ---- Auto-start tests ----

  @Test
  fun autoStartDisabled_allTasksQueued() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(
          scope, maxConcurrent = 10, autoStart = false,
        )

        val (sf1, seg1) = newFlows()
        scheduler.enqueue(
          "task-1", createRequest(), Clock.System.now(),
          sf1, seg1
        )
        assertIs<DownloadState.Queued>(sf1.value)

        val (sf2, seg2) = newFlows()
        scheduler.enqueue(
          "task-2", createRequest(), Clock.System.now(),
          sf2, seg2
        )
        assertIs<DownloadState.Queued>(sf2.value)
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun autoStartDisabled_promotionDoesNotOccur() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(
          scope, maxConcurrent = 10, autoStart = false,
        )

        val (sf1, seg1) = newFlows()
        scheduler.enqueue(
          "task-1", createRequest(), Clock.System.now(),
          sf1, seg1
        )
        assertIs<DownloadState.Queued>(sf1.value)

        // Signal completion from outside — promotion should not
        // happen because autoStart is false
        scheduler.onTaskCompleted("task-1")

        delay(200)
        assertIs<DownloadState.Queued>(sf1.value)
      } finally {
        scope.cancel()
      }
    }
  }

  // ---- Preemption tests ----

  @Test
  fun urgent_preemptsLowestPriorityActive() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        // maxConcurrent=1 so the URGENT task will try to preempt
        val scheduler = createScheduler(scope, maxConcurrent = 1)

        // Start a LOW priority task
        val (sfLow, segLow) = newFlows()
        scheduler.enqueue(
          "task-low",
          createRequest(priority = DownloadPriority.LOW),
          Clock.System.now(), sfLow, segLow
        )

        // Wait for it to start
        withTimeout(2.seconds) {
          sfLow.first { it != DownloadState.Queued }
        }

        // Enqueue URGENT — should preempt the LOW task
        val (sfUrgent, segUrgent) = newFlows()
        scheduler.enqueue(
          "task-urgent",
          createRequest(priority = DownloadPriority.URGENT),
          Clock.System.now(), sfUrgent, segUrgent
        )

        // The LOW task should be re-queued
        withTimeout(2.seconds) {
          sfLow.first { it is DownloadState.Queued }
        }

        // The URGENT task should have started
        withTimeout(2.seconds) {
          sfUrgent.first {
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
        val scheduler = createScheduler(scope, maxConcurrent = 1)

        // Start an URGENT task
        val (sf1, seg1) = newFlows()
        scheduler.enqueue(
          "task-urgent-1",
          createRequest(priority = DownloadPriority.URGENT),
          Clock.System.now(), sf1, seg1
        )

        withTimeout(2.seconds) {
          sf1.first { it != DownloadState.Queued }
        }

        // Enqueue another URGENT — cannot preempt, should be queued
        val (sf2, seg2) = newFlows()
        scheduler.enqueue(
          "task-urgent-2",
          createRequest(priority = DownloadPriority.URGENT),
          Clock.System.now(), sf2, seg2
        )

        assertIs<DownloadState.Queued>(sf2.value)
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
        val scheduler = createScheduler(scope, maxConcurrent = 1)

        // Fill slot
        val (sf0, seg0) = newFlows()
        scheduler.enqueue(
          "task-0", createRequest(), Clock.System.now(),
          sf0, seg0
        )

        // Queue two NORMAL tasks
        val (sfA, segA) = newFlows()
        scheduler.enqueue(
          "task-A",
          createRequest(priority = DownloadPriority.NORMAL),
          Clock.System.now(), sfA, segA
        )

        delay(10)

        val (sfB, segB) = newFlows()
        scheduler.enqueue(
          "task-B",
          createRequest(priority = DownloadPriority.NORMAL),
          Clock.System.now(), sfB, segB
        )

        // Boost task-B to HIGH — it should now be ahead of task-A
        scheduler.setPriority("task-B", DownloadPriority.HIGH)

        // Complete the active task — task-B should be promoted first
        scheduler.onTaskCompleted("task-0")

        withTimeout(2.seconds) {
          sfB.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }

        // task-A should still be queued
        assertIs<DownloadState.Queued>(sfA.value)
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
        val scheduler = createScheduler(scope, maxConcurrent = 10)

        val (sf1, seg1) = newFlows()
        scheduler.enqueue(
          "task-1", createRequest(), Clock.System.now(),
          sf1, seg1
        )

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
        val scheduler = createScheduler(scope, maxConcurrent = 10)

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
        val scheduler = createScheduler(scope, maxConcurrent = 1)

        // Fill slot
        val (sf1, seg1) = newFlows()
        scheduler.enqueue(
          "task-1", createRequest(), Clock.System.now(),
          sf1, seg1
        )

        // Queue two more
        val (sfA, segA) = newFlows()
        scheduler.enqueue(
          "task-A", createRequest(), Clock.System.now(),
          sfA, segA
        )
        assertIs<DownloadState.Queued>(sfA.value)

        val (sfB, segB) = newFlows()
        scheduler.enqueue(
          "task-B", createRequest(), Clock.System.now(),
          sfB, segB
        )
        assertIs<DownloadState.Queued>(sfB.value)

        // Dequeue task-A
        scheduler.dequeue("task-A")

        // Complete task-1 — task-B should be promoted (not task-A)
        scheduler.onTaskCompleted("task-1")

        withTimeout(2.seconds) {
          sfB.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }

        // task-A should remain as Queued (no state change from
        // dequeue itself)
        assertIs<DownloadState.Queued>(sfA.value)
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
        val scheduler = createScheduler(scope, maxConcurrent = 1)

        val (sf1, seg1) = newFlows()
        scheduler.enqueue(
          "task-1", createRequest(), Clock.System.now(),
          sf1, seg1
        )

        val (sf2, seg2) = newFlows()
        scheduler.enqueue(
          "task-2", createRequest(), Clock.System.now(),
          sf2, seg2
        )
        assertIs<DownloadState.Queued>(sf2.value)

        // Dequeue the active task — should cancel it and promote
        // the queued task
        scheduler.dequeue("task-1")

        withTimeout(2.seconds) {
          sf2.first {
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
        val scheduler = createScheduler(scope, maxConcurrent = 10)

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
      "http://cdn.example.com/path/file.zip"
    )
    assertEquals("cdn.example.com", host)
  }

  @Test
  fun extractHost_httpsUrlWithPort() {
    val host = DownloadQueue.extractHost(
      "https://cdn.example.com:443/path/file.zip"
    )
    assertEquals("cdn.example.com", host)
  }

  @Test
  fun extractHost_noPath() {
    val host = DownloadQueue.extractHost(
      "https://example.com"
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
      "https://192.168.1.1:8080/file"
    )
    assertEquals("192.168.1.1", host)
  }

  // ---- Multiple promotions after completion ----

  @Test
  fun multipleSlotsFree_promotesMultipleTasks() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val scheduler = createScheduler(scope, maxConcurrent = 2)

        // Fill both slots
        val (sf1, seg1) = newFlows()
        val (sf2, seg2) = newFlows()
        scheduler.enqueue(
          "task-1", createRequest(), Clock.System.now(),
          sf1, seg1
        )
        scheduler.enqueue(
          "task-2", createRequest(), Clock.System.now(),
          sf2, seg2
        )

        // Queue two more
        val (sf3, seg3) = newFlows()
        val (sf4, seg4) = newFlows()
        scheduler.enqueue(
          "task-3", createRequest(), Clock.System.now(),
          sf3, seg3
        )
        scheduler.enqueue(
          "task-4", createRequest(), Clock.System.now(),
          sf4, seg4
        )
        assertIs<DownloadState.Queued>(sf3.value)
        assertIs<DownloadState.Queued>(sf4.value)

        // Complete both active tasks
        scheduler.onTaskCompleted("task-1")
        scheduler.onTaskCompleted("task-2")

        // Both queued tasks should now be promoted
        withTimeout(2.seconds) {
          sf3.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }
        withTimeout(2.seconds) {
          sf4.first {
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
        val scheduler = createScheduler(scope, maxConcurrent = 1)

        // Fill the slot
        val (sf0, seg0) = newFlows()
        scheduler.enqueue(
          "task-0", createRequest(), Clock.System.now(),
          sf0, seg0
        )

        // Queue LOW, HIGH, NORMAL in that enqueue order
        val (sfLow, segLow) = newFlows()
        scheduler.enqueue(
          "task-low",
          createRequest(priority = DownloadPriority.LOW),
          Clock.System.now(), sfLow, segLow
        )

        val (sfHigh, segHigh) = newFlows()
        scheduler.enqueue(
          "task-high",
          createRequest(priority = DownloadPriority.HIGH),
          Clock.System.now(), sfHigh, segHigh
        )

        val (sfNorm, segNorm) = newFlows()
        scheduler.enqueue(
          "task-normal",
          createRequest(priority = DownloadPriority.NORMAL),
          Clock.System.now(), sfNorm, segNorm
        )

        // Verify all queued
        assertIs<DownloadState.Queued>(sfLow.value)
        assertIs<DownloadState.Queued>(sfHigh.value)
        assertIs<DownloadState.Queued>(sfNorm.value)

        // Round 1: complete task-0 — HIGH promoted
        scheduler.onTaskCompleted("task-0")
        withTimeout(2.seconds) {
          sfHigh.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }
        assertIs<DownloadState.Queued>(sfNorm.value)
        assertIs<DownloadState.Queued>(sfLow.value)

        // Round 2: complete task-high — NORMAL promoted
        scheduler.onTaskCompleted("task-high")
        withTimeout(2.seconds) {
          sfNorm.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }
        assertIs<DownloadState.Queued>(sfLow.value)

        // Round 3: complete task-normal — LOW promoted
        scheduler.onTaskCompleted("task-normal")
        withTimeout(2.seconds) {
          sfLow.first {
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
          scope, maxConcurrent = 2, maxPerHost = 2,
        )

        // Fill both global slots from the same host
        val (sf1, seg1) = newFlows()
        val (sf2, seg2) = newFlows()
        scheduler.enqueue(
          "task-1",
          createRequest(url = "https://example.com/a"),
          Clock.System.now(), sf1, seg1
        )
        scheduler.enqueue(
          "task-2",
          createRequest(url = "https://example.com/b"),
          Clock.System.now(), sf2, seg2
        )

        // A third task from a different host — should be queued
        // because global limit is 2
        val (sf3, seg3) = newFlows()
        scheduler.enqueue(
          "task-3",
          createRequest(url = "https://other.com/c"),
          Clock.System.now(), sf3, seg3
        )
        assertIs<DownloadState.Queued>(sf3.value)
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
          scope, maxConcurrent = 2, maxPerHost = 1,
        )

        // Start one task from host-A
        val (sf1, seg1) = newFlows()
        scheduler.enqueue(
          "task-1",
          createRequest(url = "https://host-a.com/file"),
          Clock.System.now(), sf1, seg1
        )

        // Start one task from host-B
        val (sf2, seg2) = newFlows()
        scheduler.enqueue(
          "task-2",
          createRequest(url = "https://host-b.com/file"),
          Clock.System.now(), sf2, seg2
        )

        // Queue: another host-A (blocked by per-host limit)
        val (sfA2, segA2) = newFlows()
        scheduler.enqueue(
          "task-a2",
          createRequest(url = "https://host-a.com/file2"),
          Clock.System.now(), sfA2, segA2
        )
        assertIs<DownloadState.Queued>(sfA2.value)

        // Queue: host-C (should be eligible when slot opens)
        val (sfC, segC) = newFlows()
        scheduler.enqueue(
          "task-c",
          createRequest(url = "https://host-c.com/file"),
          Clock.System.now(), sfC, segC
        )
        assertIs<DownloadState.Queued>(sfC.value)

        // Complete task from host-B. Now slot is open.
        // task-a2 is first in queue but host-a is at limit.
        // task-c from host-c should be promoted instead.
        scheduler.onTaskCompleted("task-2")

        withTimeout(2.seconds) {
          sfC.first {
            it != DownloadState.Queued && it !is DownloadState.Queued
          }
        }

        // task-a2 should still be queued (host-a at limit)
        assertIs<DownloadState.Queued>(sfA2.value)
      } finally {
        scope.cancel()
      }
    }
  }
}
