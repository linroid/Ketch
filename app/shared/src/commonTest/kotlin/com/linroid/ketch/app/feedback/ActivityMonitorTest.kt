package com.linroid.ketch.app.feedback

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.state.ListTestTask
import com.linroid.ketch.app.state.TaskKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityMonitorTest {

  private val startedAt = Instant.parse("2026-10-01T12:00:00Z")
  private val clock = object : Clock {
    override fun now(): Instant = startedAt
  }
  private val old = startedAt - 1.hours
  private val new = startedAt + 1.seconds

  private class Harness(
    val tasks: MutableStateFlow<List<DownloadTask>>,
    val online: MutableStateFlow<Boolean>,
    val events: MutableList<ActivityEvent>,
  )

  private fun TestScope.monitor(
    tasks: List<DownloadTask> = emptyList(),
    online: Boolean = true,
  ): Harness {
    val harness = Harness(MutableStateFlow(tasks), MutableStateFlow(online), mutableListOf())
    val source = ActivitySource(DEVICE, harness.tasks, harness.online)
    val monitor = ActivityMonitor(MutableStateFlow(listOf(source)), backgroundScope, clock)
    backgroundScope.launch { monitor.events.collect { harness.events += it } }
    runCurrent()
    return harness
  }

  private fun downloading() = DownloadState.Downloading(DownloadProgress(0, 1000, 100))

  private fun completed(bytes: Long) = DownloadState.Completed("/downloads/file", bytes)

  private fun task(id: String, state: DownloadState, createdAt: Instant = old) =
    ListTestTask(id, state, DownloadRequest(url = "https://example.com/$id"), createdAt)

  @Test
  fun events_tasksTerminalAtLoad_emitNothing() = runTest {
    val harness = monitor(
      listOf(
        task("done", completed(10)),
        task("failed", DownloadState.Failed(KetchError.Network())),
        task("canceled", DownloadState.Canceled)
      )
    )
    advanceTimeBy(1.hours)

    assertEquals(emptyList(), harness.events)
  }

  @Test
  fun events_terminalTasksRestoredAfterEmptyList_emitNothing() = runTest {
    val harness = monitor()

    harness.tasks.value = listOf(
      task("done", completed(10)),
      task("failed", DownloadState.Failed(KetchError.Network()))
    )
    runCurrent()
    advanceTimeBy(1.hours)

    assertEquals(emptyList(), harness.events)
  }

  @Test
  fun events_activeTasksAtLoad_reportRecovered() = runTest {
    val harness = monitor(
      listOf(
        task("a", DownloadState.Queued),
        task("b", DownloadState.Queued),
        task("c", downloading()),
        task("d", DownloadState.Paused(DownloadProgress(0, 1000)))
      )
    )

    assertEquals(listOf<ActivityEvent>(ActivityEvent.Recovered(DEVICE, 3)), harness.events)
  }

  @Test
  fun events_deviceLeavesAndReturns_reportsRecoveredOnce() = runTest {
    val tasks = MutableStateFlow<List<DownloadTask>>(listOf(task("a", downloading())))
    val source = ActivitySource(DEVICE, tasks, MutableStateFlow(true))
    val devices = MutableStateFlow(listOf(source))
    val events = mutableListOf<ActivityEvent>()
    val monitor = ActivityMonitor(devices, backgroundScope, clock)
    backgroundScope.launch { monitor.events.collect { events += it } }
    runCurrent()

    devices.value = emptyList()
    runCurrent()
    devices.value = listOf(source)
    runCurrent()

    assertEquals(listOf<ActivityEvent>(ActivityEvent.Recovered(DEVICE, 1)), events)
  }

  @Test
  fun events_activeTasksRestoredAfterEmptyList_reportRecoveredNotAdded() = runTest {
    val harness = monitor()

    harness.tasks.value = listOf(task("a", DownloadState.Queued), task("b", DownloadState.Queued))
    runCurrent()

    assertEquals(listOf<ActivityEvent>(ActivityEvent.Recovered(DEVICE, 2)), harness.events)
  }

  @Test
  fun events_newTask_reportsAdded() = runTest {
    val harness = monitor()
    val added = task("a", DownloadState.Queued, createdAt = new)

    harness.tasks.value = listOf(added)
    runCurrent()

    assertEquals(
      listOf<ActivityEvent>(ActivityEvent.Added(TaskKey(DEVICE, "a"), added.request)),
      harness.events
    )
  }

  @Test
  fun events_taskFails_reportsFailedAtOnce() = runTest {
    val running = task("a", downloading())
    val harness = monitor(listOf(running, task("b", downloading())))
    harness.events.clear()
    val failure = DownloadState.Failed(KetchError.Network())

    running.state.value = failure
    runCurrent()

    assertEquals(
      listOf<ActivityEvent>(ActivityEvent.Failed(TaskKey(DEVICE, "a"), running.request, failure)),
      harness.events
    )
  }

  @Test
  fun events_progressUpdates_emitNothing() = runTest {
    val running = task("a", downloading())
    val harness = monitor(listOf(running))
    harness.events.clear()

    running.state.value = DownloadState.Downloading(DownloadProgress(500, 1000, 100))
    runCurrent()

    assertEquals(emptyList(), harness.events)
  }

  @Test
  fun events_onlyTaskCompletes_reportedAtOnce() = runTest {
    val running = task("a", downloading())
    val harness = monitor(listOf(running))
    harness.events.clear()

    running.state.value = completed(1000)
    runCurrent()

    assertEquals(
      listOf<ActivityEvent>(
        ActivityEvent.Completed(TaskKey(DEVICE, "a"), running.request, completed(1000))
      ),
      harness.events
    )
  }

  @Test
  fun events_completionWhileOthersRun_heldForWindow() = runTest {
    val first = task("a", downloading())
    val harness = monitor(listOf(first, task("b", downloading())))
    harness.events.clear()

    first.state.value = completed(1000)
    advanceTimeBy(ActivityMonitor.COALESCE_WINDOW - 1.milliseconds)
    assertEquals(emptyList(), harness.events)

    advanceTimeBy(1.milliseconds)
    runCurrent()
    assertEquals(
      listOf<ActivityEvent>(
        ActivityEvent.Completed(TaskKey(DEVICE, "a"), first.request, completed(1000))
      ),
      harness.events
    )
  }

  @Test
  fun events_fourCompletionsWithinWindow_coalescedIntoOneEvent() = runTest {
    val finishing = List(4) { task("t$it", downloading()) }
    val harness = monitor(finishing + task("still", downloading()))
    harness.events.clear()

    finishing.forEachIndexed { index, finished ->
      finished.state.value = completed(1000L * (index + 1))
      advanceTimeBy(2.seconds)
    }
    advanceTimeBy(ActivityMonitor.COALESCE_WINDOW)

    val batch = assertIs<ActivityEvent.CompletedBatch>(harness.events.single())
    assertEquals(finishing.map { it.taskId }, batch.completions.map { it.taskKey.taskId })
  }

  @Test
  fun events_threeCompletionsWithinWindow_reportedOneByOne() = runTest {
    val finishing = List(3) { task("t$it", downloading()) }
    val harness = monitor(finishing + task("still", downloading()))
    harness.events.clear()

    finishing.forEach { it.state.value = completed(1000) }
    advanceTimeBy(ActivityMonitor.COALESCE_WINDOW)
    runCurrent()

    assertEquals(3, harness.events.size)
    harness.events.forEach { assertIs<ActivityEvent.Completed>(it) }
  }

  @Test
  fun events_lastCompletionDrainsQueue_reportsQueueDrainedInstead() = runTest {
    val finishing = List(3) { task("t$it", downloading()) }
    val harness = monitor(finishing)
    harness.events.clear()

    finishing[0].state.value = completed(1000)
    finishing[1].state.value = completed(2000)
    runCurrent()
    finishing[2].state.value = completed(3000)
    runCurrent()

    assertEquals(
      listOf<ActivityEvent>(ActivityEvent.QueueDrained(DEVICE, files = 3, bytes = 6000)),
      harness.events
    )
  }

  @Test
  fun events_queueDrainsAfterReportedCompletion_countsWholeRun() = runTest {
    val first = task("a", downloading())
    val last = task("b", downloading())
    val harness = monitor(listOf(first, last))
    harness.events.clear()

    first.state.value = completed(1000)
    advanceTimeBy(ActivityMonitor.COALESCE_WINDOW)
    runCurrent()
    last.state.value = completed(2000)
    runCurrent()

    assertEquals(
      listOf(
        ActivityEvent.Completed(TaskKey(DEVICE, "a"), first.request, completed(1000)),
        ActivityEvent.QueueDrained(DEVICE, files = 2, bytes = 3000)
      ),
      harness.events
    )
  }

  @Test
  fun events_deviceOfflineBeyondGrace_reportsOfflineThenOnline() = runTest {
    val harness = monitor()

    harness.online.value = false
    advanceTimeBy(ActivityMonitor.OFFLINE_GRACE)
    runCurrent()
    harness.online.value = true
    runCurrent()

    assertEquals(
      listOf(ActivityEvent.DeviceOffline(DEVICE), ActivityEvent.DeviceOnline(DEVICE)),
      harness.events
    )
  }

  @Test
  fun events_briefDisconnect_emitNothing() = runTest {
    val harness = monitor()

    harness.online.value = false
    advanceTimeBy(ActivityMonitor.OFFLINE_GRACE / 2)
    harness.online.value = true
    advanceTimeBy(ActivityMonitor.OFFLINE_GRACE)

    assertEquals(emptyList(), harness.events)
  }

  @Test
  fun events_firstConnection_emitNothing() = runTest {
    val harness = monitor(online = false)

    harness.online.value = true
    runCurrent()

    assertEquals(emptyList(), harness.events)
  }

  private companion object {
    const val DEVICE = "nas"
  }
}
