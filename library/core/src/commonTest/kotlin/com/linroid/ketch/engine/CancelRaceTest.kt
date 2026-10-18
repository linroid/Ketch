package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.DownloadQueue
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A cancel or remove never waits for the queue, so it can land while the queue starts the task.
 * These tests hold a save of the start (the queue's or the coordinator's) open while the cancel
 * comes, then let it finish: the task must stay canceled, run no more and free its slot. A run
 * that ends as a reschedule stops it stays ended the same way, and a caller cancelled while a
 * task stops or starts leaves no task holding a slot with no execution.
 */
class CancelRaceTest {
  private val folder = "/tmp/ketch-cancel-race/"

  private fun request(
    vararg ids: String,
    priority: DownloadPriority = DownloadPriority.NORMAL,
  ) = DownloadRequest(
    url = "pick:files",
    destination = Destination(folder),
    selectedFileIds = ids.toSet(),
    priority = priority,
  )

  private fun TestScope.ketch(
    source: SelectableSource,
    store: RecordingTaskStore,
    maxConcurrent: Int = 4,
  ): Ketch {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return Ketch(
      httpEngine = FakeHttpEngine(),
      taskStore = store,
      config = DownloadConfig(
        retryCount = 0,
        saveIntervalMs = 60_000,
        maxConcurrentDownloads = maxConcurrent,
      ),
      additionalSources = listOf(source),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
  }

  /** Holds the first save of [store] that [matches] until [release] completes; [held] says so. */
  private class SaveGate(store: RecordingTaskStore, matches: (TaskRecord) -> Boolean) {
    val held = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    init {
      store.onSave = { record ->
        if (matches(record) && !held.isCompleted) {
          held.complete(Unit)
          release.await()
        }
      }
    }
  }

  @Test
  fun resume_canceledWhileTheCoordinatorSavesItsStart_staysCanceled() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      task.pause()
      runCurrent()
      assertIs<DownloadState.Paused>(task.state.value)
      // The resume's own save, which comes before its execution.
      val gate = SaveGate(store) { it.state == TaskState.DOWNLOADING }
      val resuming = launch { task.resume() }
      runCurrent()
      assertTrue(gate.held.isCompleted)

      val canceling = launch { task.cancel() }
      runCurrent()
      gate.release.complete(Unit)
      resuming.join()
      canceling.join()
      runCurrent()
      // An execution still running would report progress and complete.
      source.runs.value.forEach { it.finish.complete(Unit) }
      runCurrent()

      assertIs<DownloadState.Canceled>(task.state.value)
      assertEquals(TaskState.CANCELED, store.load(task.taskId)?.state)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun resume_canceledWhileTheQueueSavesIt_staysCanceledAndFreesItsSlot() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store, maxConcurrent = 1)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      task.pause()
      runCurrent()
      // The queue's save as it takes the task.
      val gate = SaveGate(store) { it.state == TaskState.QUEUED }
      val resuming = launch { task.resume() }
      runCurrent()
      assertTrue(gate.held.isCompleted)

      val canceling = launch { task.cancel() }
      runCurrent()
      gate.release.complete(Unit)
      resuming.join()
      canceling.join()
      runCurrent()

      assertIs<DownloadState.Canceled>(task.state.value)
      assertEquals(TaskState.CANCELED, store.load(task.taskId)?.state)
      assertEquals(1, source.runs.value.size)

      // The only slot is free again.
      val next = ketch.download(request("1"))
      runCurrent()
      assertIs<DownloadState.Downloading>(next.state.value)
      assertEquals(next.taskId, source.runs.value.last().context.taskId)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun resume_urgentCanceledWhileTheQueueSavesIt_preemptsNobody() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store, maxConcurrent = 1)
    try {
      val running = ketch.download(request("0"))
      val urgent = ketch.download(request("1", priority = DownloadPriority.URGENT))
      runCurrent()
      assertIs<DownloadState.Paused>(running.state.value)
      // Paused, the urgent task gives the slot back to the one it pushed out.
      urgent.pause()
      runCurrent()
      assertIs<DownloadState.Downloading>(running.state.value)
      val runs = source.runs.value.size
      val gate = SaveGate(store) { it.taskId == urgent.taskId && it.state == TaskState.QUEUED }
      val resuming = launch { urgent.resume() }
      runCurrent()
      assertTrue(gate.held.isCompleted)

      val canceling = launch { urgent.cancel() }
      runCurrent()
      gate.release.complete(Unit)
      resuming.join()
      canceling.join()
      runCurrent()

      assertIs<DownloadState.Canceled>(urgent.state.value)
      // Never pushed out for it, the running task keeps its run.
      assertIs<DownloadState.Downloading>(running.state.value)
      assertEquals(runs, source.runs.value.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun schedule_urgentCanceledWhileItsVictimStops_givesTheSlotBack() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store, maxConcurrent = 1)
    try {
      val running = ketch.download(request("0"))
      val urgent = ketch.download(
        request("1", priority = DownloadPriority.URGENT)
          .copy(schedule = DownloadSchedule.AfterDelay(30.seconds)),
      )
      runCurrent()
      assertIs<DownloadState.Downloading>(running.state.value)
      assertIs<DownloadState.Scheduled>(urgent.state.value)
      // The running task's last save waits, so the urgent task's wait, which preempts it, joins
      // its stop when the cancel cancels that wait.
      val stopping = CompletableDeferred<Unit>()
      source.saveGate = stopping
      advanceTimeBy(31.seconds)
      runCurrent()
      assertIs<DownloadState.Paused>(running.state.value)

      val canceling = launch { urgent.cancel() }
      runCurrent()
      stopping.complete(Unit)
      canceling.join()
      runCurrent()

      assertIs<DownloadState.Canceled>(urgent.state.value)
      // Pushed out for a task that never runs, it takes its slot back and runs again.
      assertIs<DownloadState.Downloading>(running.state.value)
      assertEquals(2, source.runs.value.size)
      assertEquals(running.taskId, source.runs.value.last().context.taskId)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun promote_callerCanceledWhileTheTaskStarts_stillRunsIt() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store, maxConcurrent = 1)
    try {
      val paused = ketch.download(request("0"))
      val waiting = ketch.download(request("1"))
      runCurrent()
      assertIs<DownloadState.Downloading>(paused.state.value)
      assertIs<DownloadState.Queued>(waiting.state.value)
      // The pause saves under the coordinator's lock, which the promoted task's start waits for.
      val gate = SaveGate(store) { it.taskId == paused.taskId && it.state == TaskState.PAUSED }
      val pausing = launch { paused.pause() }
      runCurrent()
      assertTrue(gate.held.isCompleted)
      // A second slot promotes the waiting task; the caller gives up while it starts.
      val raising = launch {
        ketch.updateConfig(
          DownloadConfig(retryCount = 0, saveIntervalMs = 60_000, maxConcurrentDownloads = 2),
        )
      }
      runCurrent()
      raising.cancel()
      gate.release.complete(Unit)
      pausing.join()
      runCurrent()

      assertIs<DownloadState.Downloading>(waiting.state.value)
      assertEquals(waiting.taskId, source.runs.value.last().context.taskId)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun resume_removedWhileTheCoordinatorSavesItsStart_leavesNoRecord() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      task.pause()
      runCurrent()
      val gate = SaveGate(store) { it.state == TaskState.DOWNLOADING }
      val resuming = launch { task.resume() }
      runCurrent()
      assertTrue(gate.held.isCompleted)

      val removing = launch { task.remove(deleteFiles = false) }
      runCurrent()
      gate.release.complete(Unit)
      resuming.join()
      removing.join()
      runCurrent()
      // An execution still running would save its record again as it completes.
      source.runs.value.forEach { it.finish.complete(Unit) }
      runCurrent()

      assertTrue(ketch.tasks.value.none { it.taskId == task.taskId })
      assertNull(store.load(task.taskId))
    } finally {
      ketch.close()
    }
  }

  @Test
  fun pause_canceledWhileItSaves_staysCanceled() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store, maxConcurrent = 1)
    try {
      ketch.download(request("0"))
      val waiting = ketch.download(request("1"))
      runCurrent()
      assertIs<DownloadState.Queued>(waiting.state.value)
      val gate = SaveGate(store) { it.taskId == waiting.taskId && it.state == TaskState.PAUSED }
      val pausing = launch { waiting.pause() }
      runCurrent()
      assertTrue(gate.held.isCompleted)

      val canceling = launch { waiting.cancel() }
      runCurrent()
      gate.release.complete(Unit)
      pausing.join()
      canceling.join()
      runCurrent()

      assertIs<DownloadState.Canceled>(waiting.state.value)
      assertEquals(TaskState.CANCELED, store.load(waiting.taskId)?.state)
    } finally {
      ketch.close()
    }
  }

  /**
   * Reschedules the running task and lets [stop] cancel or remove it while the reschedule's pause
   * joins the stopping run, holding the reschedule's own save until the stop shows it canceled.
   */
  private suspend fun TestScope.rescheduleWhile(
    source: SelectableSource,
    store: RecordingTaskStore,
    task: DownloadTask,
    stop: suspend () -> Unit,
  ) {
    // The stopping run's last save waits, so the stop lands during the pause's join.
    val stopping = CompletableDeferred<Unit>()
    source.saveGate = stopping
    val gate = SaveGate(store) { it.state == TaskState.SCHEDULED }
    val rescheduling = launch { task.reschedule(DownloadSchedule.AfterDelay(1.minutes)) }
    runCurrent()
    val stopped = launch { stop() }
    runCurrent()
    stopping.complete(Unit)
    runCurrent()
    gate.release.complete(Unit)
    rescheduling.join()
    stopped.join()
    advanceTimeBy(2.minutes)
    runCurrent()
  }

  @Test
  fun reschedule_canceledDuringItsPause_staysCanceled() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      assertIs<DownloadState.Downloading>(task.state.value)

      rescheduleWhile(source, store, task) { task.cancel() }

      assertIs<DownloadState.Canceled>(task.state.value)
      assertEquals(TaskState.CANCELED, store.load(task.taskId)?.state)
      assertEquals(1, source.runs.value.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun reschedule_removedDuringItsPause_neverRunsAgain() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      assertIs<DownloadState.Downloading>(task.state.value)

      rescheduleWhile(source, store, task) { task.remove(deleteFiles = false) }

      assertEquals(1, source.runs.value.size)
      assertIs<DownloadState.Canceled>(task.state.value)
      assertTrue(ketch.tasks.value.none { it.taskId == task.taskId })
      assertNull(store.load(task.taskId))
    } finally {
      ketch.close()
    }
  }

  @Test
  fun reschedule_completedBeforeItsPause_staysCompleted() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      val other = ketch.download(request("1"))
      runCurrent()
      assertIs<DownloadState.Downloading>(task.state.value)
      // The other task's pause saves under the coordinator's lock, which the reschedule's pause
      // then waits for while the task completes.
      val gate = SaveGate(store) { it.taskId == other.taskId && it.state == TaskState.PAUSED }
      val pausing = launch { other.pause() }
      runCurrent()
      assertTrue(gate.held.isCompleted)
      val rescheduling = launch { task.reschedule(DownloadSchedule.AfterDelay(1.minutes)) }
      runCurrent()
      source.runs.value.single { it.context.taskId == task.taskId }.finish.complete(Unit)
      runCurrent()
      assertIs<DownloadState.Completed>(task.state.value)

      gate.release.complete(Unit)
      pausing.join()
      rescheduling.join()
      advanceTimeBy(2.minutes)
      runCurrent()

      assertIs<DownloadState.Completed>(task.state.value)
      assertEquals(TaskState.COMPLETED, store.load(task.taskId)?.state)
      assertEquals(1, source.runs.value.count { it.context.taskId == task.taskId })
    } finally {
      ketch.close()
    }
  }

  /**
   * Runs [stop], which stops a running task, and cancels it while the task's last save holds the
   * stop, then lets the stop finish.
   */
  private fun TestScope.stopAndGiveUp(
    source: SelectableSource,
    stop: suspend () -> Unit,
  ) {
    val stopping = CompletableDeferred<Unit>()
    source.saveGate = stopping
    val stopped = launch { stop() }
    runCurrent()
    assertTrue(stopped.isActive)
    stopped.cancel()
    runCurrent()
    stopping.complete(Unit)
    runCurrent()
  }

  @Test
  fun pause_callerCanceledWhileTheTaskStops_freesItsSlot() = runTest {
    val source = SelectableSource()
    val ketch = ketch(source, RecordingTaskStore(), maxConcurrent = 1)
    try {
      val task = ketch.download(request("0"))
      val waiting = ketch.download(request("1"))
      runCurrent()
      assertIs<DownloadState.Queued>(waiting.state.value)

      stopAndGiveUp(source) { task.pause() }

      assertIs<DownloadState.Paused>(task.state.value)
      assertIs<DownloadState.Downloading>(waiting.state.value)
      // Waits for the slot again rather than doing nothing.
      task.resume()
      runCurrent()
      assertIs<DownloadState.Queued>(task.state.value)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun reschedule_callerCanceledWhileTheTaskStops_freesItsSlotAndWaits() = runTest {
    val source = SelectableSource()
    val ketch = ketch(source, RecordingTaskStore(), maxConcurrent = 1)
    try {
      val task = ketch.download(request("0"))
      val waiting = ketch.download(request("1"))
      runCurrent()
      assertIs<DownloadState.Queued>(waiting.state.value)

      stopAndGiveUp(source) { task.reschedule(DownloadSchedule.AfterDelay(1.minutes)) }

      assertIs<DownloadState.Scheduled>(task.state.value)
      assertIs<DownloadState.Downloading>(waiting.state.value)
      advanceTimeBy(2.minutes)
      runCurrent()
      assertIs<DownloadState.Queued>(task.state.value)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun reschedule_whileTheTaskStarts_stopsItUntilDue() = runTest {
    val source = SelectableSource()
    val lookup = CompletableDeferred<Unit>()
    source.resolveGate = lookup
    val ketch = ketch(source, RecordingTaskStore())
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      // Still looking its files up, as a magnet finding peers is.
      assertIs<DownloadState.Queued>(task.state.value)
      assertEquals(1, source.resolves)

      task.reschedule(DownloadSchedule.AfterDelay(1.minutes))
      runCurrent()
      lookup.complete(Unit)
      runCurrent()

      assertIs<DownloadState.Scheduled>(task.state.value)
      assertTrue(source.runs.value.isEmpty())
      advanceTimeBy(2.minutes)
      runCurrent()
      assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals(1, source.runs.value.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun remove_writeAfterIt_savesNothing() = runTest {
    val source = SelectableSource()
    val store = RecordingTaskStore()
    val ketch = ketch(source, store)
    try {
      val task = ketch.download(request("0"))
      runCurrent()
      task.remove(deleteFiles = false)
      assertNull(store.load(task.taskId))

      // A caller still holding the task, as a late writer holds its handle.
      task.setSpeedLimit(SpeedLimit.of(1024))
      runCurrent()

      assertNull(store.load(task.taskId))
    } finally {
      ketch.close()
    }
  }

  @Test
  fun start_whileACancelStopsTheTask_isRefused() = runTest {
    val source = SelectableSource()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coordinator = DownloadCoordinator(
      SourceResolver(listOf(source)),
      { DownloadConfig(retryCount = 0, saveIntervalMs = 60_000) },
      DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    val handle = handle(request("0"))
    try {
      coordinator.start(handle)
      runCurrent()
      assertEquals(1, source.runs.value.size)
      // The stopping execution's final save waits, so the cancel is still stopping it.
      val gate = CompletableDeferred<Unit>()
      source.saveGate = gate
      val canceling = launch { coordinator.cancel(handle, deletePartialFile = true) }
      runCurrent()
      assertTrue(canceling.isActive)

      coordinator.start(handle)
      runCurrent()
      gate.complete(Unit)
      canceling.join()
      runCurrent()

      assertEquals(1, source.runs.value.size)
      assertIs<DownloadState.Canceled>(handle.mutableState.value)
      assertEquals(TaskState.CANCELED, handle.record.value.state)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun promote_preemptedTaskCanceledAsItTakesTheSlot_staysCanceled() = runTest {
    val source = SelectableSource()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coordinator = DownloadCoordinator(
      SourceResolver(listOf(source)),
      { DownloadConfig(retryCount = 0, saveIntervalMs = 60_000) },
      DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    val queue = DownloadQueue(1, 0, coordinator)
    val state = RacedStateFlow<DownloadState>(MutableStateFlow(DownloadState.Queued))
    val preempted = handle(request("0"), "preempted", state)
    val urgent = handle(request("1", priority = DownloadPriority.URGENT), "urgent")
    try {
      queue.enqueue(preempted)
      runCurrent()
      queue.enqueue(urgent)
      runCurrent()
      assertIs<DownloadState.Paused>(preempted.mutableState.value)
      val runs = source.runs.value.size

      // A cancel on another thread lands right after the queue reads the task paused.
      state.afterNextRead = { it.value = DownloadState.Canceled }
      queue.onTaskCompleted(urgent.taskId)
      runCurrent()

      assertIs<DownloadState.Canceled>(preempted.mutableState.value)
      assertEquals(runs, source.runs.value.size)
    } finally {
      coordinator.close()
    }
  }

  private fun handle(
    request: DownloadRequest,
    taskId: String = "canceling",
    state: MutableStateFlow<DownloadState> = MutableStateFlow(DownloadState.Queued),
  ): TaskHandle {
    val now = Clock.System.now()
    return object : TaskHandle {
      override val taskId = taskId
      override val request get() = record.value.request
      override val createdAt = now
      override val mutableState = state
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val controlLock = Mutex()
      override val record = AtomicSaver(
        TaskRecord(taskId, request, state = TaskState.QUEUED, createdAt = now, updatedAt = now)
      ) {}
    }
  }

  /**
   * A state flow that runs [afterNextRead] once, right after a read takes its value, as a write
   * from another thread can land between a read and the write that follows it.
   */
  @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
  private class RacedStateFlow<T>(
    private val inner: MutableStateFlow<T>,
  ) : MutableStateFlow<T> by inner {
    var afterNextRead: ((MutableStateFlow<T>) -> Unit)? = null

    override var value: T
      get() {
        val read = inner.value
        afterNextRead?.let { race ->
          afterNextRead = null
          race(inner)
        }
        return read
      }
      set(value) {
        inner.value = value
      }
  }
}
