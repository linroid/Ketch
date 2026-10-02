package com.linroid.ketch.app.state

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class PauseActiveTasksTest {

  private val pauses = mutableListOf<String>()

  /** A task whose pause runs [onPause], such as the queue starting another task. */
  private inner class QueueTask(
    override val taskId: String,
    state: DownloadState,
    private val failure: Exception? = null,
    private val onPause: () -> Unit = {},
  ) : DownloadTask {
    override val request: DownloadRequest = DownloadRequest(url = "https://example.com/$taskId")
    override val requestState: StateFlow<DownloadRequest> = MutableStateFlow(request)
    override val createdAt: Instant = Instant.parse("2026-10-01T12:00:00Z")
    override val state = MutableStateFlow(state)
    override val segments: StateFlow<List<Segment>> = MutableStateFlow(emptyList())

    override suspend fun pause() {
      pauses += taskId
      failure?.let { throw it }
      state.value = DownloadState.Paused(DownloadProgress(0, 1000))
      onPause()
    }

    override suspend fun resume(destination: Destination?) {}
    override suspend fun cancel() {}
    override suspend fun setSpeedLimit(limit: SpeedLimit) {}
    override suspend fun setPriority(priority: DownloadPriority) {}
    override suspend fun setConnections(connections: Int) {}
    override suspend fun reschedule(
      schedule: DownloadSchedule,
      conditions: List<DownloadCondition>,
    ) {}
    override suspend fun remove(deleteFiles: Boolean) {}
  }

  private fun downloading() = DownloadState.Downloading(DownloadProgress(0, 1000, 100))

  private fun List<DownloadTask>.active(): List<String> = filter {
    it.state.value is DownloadState.Queued || it.state.value is DownloadState.Downloading
  }.map { it.taskId }

  @Test
  fun pauseActiveTasks_queuedAndDownloading_pausesQueuedFirst() = runTest {
    val tasks = listOf(
      QueueTask("running", downloading()),
      QueueTask("queued", DownloadState.Queued)
    )

    val failures = pauseActiveTasks({ tasks }).failures

    assertEquals(listOf("queued", "running"), pauses)
    assertTrue(tasks.active().isEmpty())
    assertTrue(failures.isEmpty())
  }

  @Test
  fun pauseActiveTasks_queueStartsAnotherTask_pausesItInALaterRound() = runTest {
    val requeued = QueueTask("requeued", DownloadState.Scheduled(DownloadSchedule.Immediate))
    val running = QueueTask("running", downloading()) { requeued.state.value = downloading() }
    val tasks = listOf(running, requeued)

    pauseActiveTasks({ tasks })

    assertEquals(listOf("running", "requeued"), pauses)
    assertTrue(tasks.active().isEmpty())
  }

  @Test
  fun pauseActiveTasks_onePauseFails_pausesTheOthersAndReportsIt() = runTest {
    val error = IllegalStateException("offline")
    val stuck = QueueTask("stuck", downloading(), failure = error)
    val tasks = listOf(stuck, QueueTask("other", downloading()))

    val failures = pauseActiveTasks({ tasks }).failures

    assertEquals(listOf(stuck to error), failures)
    assertEquals(listOf("stuck", "other"), pauses)
    assertEquals(listOf("stuck"), tasks.active())
  }
}
