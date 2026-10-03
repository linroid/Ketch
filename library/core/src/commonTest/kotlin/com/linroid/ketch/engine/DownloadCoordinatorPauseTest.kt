package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Verifies that [DownloadCoordinator.pause] keeps the segments the execution saves while
 * stopping. With an unconfined network dispatcher, the `job.cancel()` inside `pause()` runs
 * the execution's final checkpoint before `pause()` persists anything, which forces the
 * interleaving that once made a restarted task resume from stale offsets.
 */
class DownloadCoordinatorPauseTest {
  @Test
  fun pause_sourcePublishesProgressWhileStopping_keepsFinalSegments() = runTest {
    val final = listOf(Segment(0, 0, 99, 60))
    val record = pauseWhileSourceStops(published = listOf(Segment(0, 0, 99, 20)), final = final)
    assertEquals(TaskState.PAUSED, record.state)
    assertEquals(final, record.segments)
  }

  @Test
  fun pause_sourceResetsSegmentsWhileStopping_keepsResetSegments() = runTest {
    // Less progress is legitimate, e.g. after a failed local-file check; keeping the larger
    // snapshot would resume past bytes that are not on disk.
    val final = listOf(Segment(0, 0, 99, 0))
    val record = pauseWhileSourceStops(published = listOf(Segment(0, 0, 99, 60)), final = final)
    assertEquals(TaskState.PAUSED, record.state)
    assertEquals(final, record.segments)
  }

  @Test
  fun pause_preempted_writesQueuedRecordBeforeJoining() = runTest {
    val checkpoint = CompletableDeferred<Unit>()
    val saved = mutableListOf<TaskState>()
    val handle = handle { saved += it.state }
    val coordinator = coordinator(StoppingSource(checkpoint))
    try {
      coordinator.start(handle)
      runCurrent()
      saved.clear()

      val pause = launch { coordinator.pause(handle.taskId, PauseReason.Preempted("urgent")) }
      runCurrent()

      // The source is still saving its checkpoint, so pause() has not returned yet.
      assertFalse(pause.isCompleted)
      assertEquals(TaskState.QUEUED, handle.record.value.state)
      assertEquals(
        DownloadState.Paused(DownloadProgress(20, 100), PauseReason.Preempted("urgent")),
        handle.mutableState.value,
      )
      checkpoint.complete(Unit)
      runCurrent()
      assertTrue(pause.isCompleted)
      assertTrue(TaskState.PAUSED !in saved, "Saved states: $saved")
      assertEquals(TaskState.QUEUED, handle.record.value.state)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun pause_afterCompletion_keepsCompletedStateAndRecord() = runTest {
    val handle = handle()
    val coordinator = coordinator(StoppingSource(CompletableDeferred(Unit)))
    try {
      coordinator.start(handle)
      runCurrent()
      // The execution finished; its finally has not yet taken it out of the coordinator.
      val completed = DownloadState.Completed("/tmp/ketch-pause-checkpoint", 100)
      handle.record.update { it.copy(state = TaskState.COMPLETED) }
      handle.mutableState.value = completed

      coordinator.pause(handle.taskId, PauseReason.Preempted("urgent"))
      runCurrent()

      assertEquals(completed, handle.mutableState.value)
      assertEquals(TaskState.COMPLETED, handle.record.value.state)
    } finally {
      coordinator.close()
    }
  }

  /** Publishes some progress, then waits for [checkpoint] while it stops. */
  private class StoppingSource(private val checkpoint: CompletableDeferred<Unit>) : DownloadSource {
    override val type = "fixture"
    override val managesOwnFileIo = true
    override fun canHandle(url: String) = true
    override suspend fun resolve(url: String, properties: Map<String, String>) = ResolvedSource(
      url = url, sourceType = type, totalBytes = 100, supportsResume = true,
      suggestedFileName = "fixture", maxSegments = 1,
    )
    override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long) =
      SourceResumeState(type, "")
    override suspend fun download(context: DownloadContext) {
      context.segments.value = listOf(Segment(0, 0, 99, 20))
      try {
        awaitCancellation()
      } finally {
        withContext(NonCancellable) { checkpoint.await() }
      }
    }
    override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) = Unit
  }

  private fun handle(onSave: (TaskRecord) -> Unit = {}): TaskHandle {
    val now = Clock.System.now()
    val request = DownloadRequest("fixture:input",
      destination = Destination("/tmp/ketch-pause-checkpoint"))
    return object : TaskHandle {
      override val taskId = "pause-checkpoint"
      override val request = request
      override val createdAt = now
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val record = AtomicSaver(TaskRecord(taskId, request, state = TaskState.QUEUED,
        createdAt = now, updatedAt = now)) { onSave(it) }
    }
  }

  private fun TestScope.coordinator(source: DownloadSource): DownloadCoordinator {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return DownloadCoordinator(SourceResolver(listOf(source)),
      { DownloadConfig(saveIntervalMs = 60_000) }, DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher))
  }

  private suspend fun pauseWhileSourceStops(
    published: List<Segment>,
    final: List<Segment>,
  ): TaskRecord {
    val source = object : DownloadSource {
      override val type = "fixture"
      override val managesOwnFileIo = true
      override fun canHandle(url: String) = true
      override suspend fun resolve(url: String, properties: Map<String, String>) = ResolvedSource(
        url = url, sourceType = type, totalBytes = 100, supportsResume = true,
        suggestedFileName = "fixture", maxSegments = 1,
      )
      override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long) =
        SourceResumeState(type, "")
      override suspend fun download(context: DownloadContext) {
        context.segments.value = published
        // Like SegmentedDownloadHelper, which publishes throttled progress when a batch stops.
        try { awaitCancellation() } finally { context.segments.value = final }
      }
      override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) = Unit
    }
    val now = Clock.System.now()
    val request = DownloadRequest("fixture:input",
      destination = Destination("/tmp/ketch-pause-checkpoint"))
    val handle = object : TaskHandle {
      override val taskId = "pause-checkpoint"
      override val request = request
      override val createdAt = now
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val record = AtomicSaver(TaskRecord(taskId, request, state = TaskState.QUEUED,
        createdAt = now, updatedAt = now)) {}
    }
    val unconfined = Dispatchers.Unconfined
    val coordinator = DownloadCoordinator(SourceResolver(listOf(source)),
      { DownloadConfig(saveIntervalMs = 60_000) }, DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(unconfined, unconfined, unconfined))
    try {
      // Unconfined, start() returns once the source is waiting to be cancelled.
      coordinator.start(handle)
      assertEquals(published, handle.mutableSegments.value)
      coordinator.pause(handle.taskId)
    } finally {
      coordinator.close()
    }
    return handle.record.value
  }
}
