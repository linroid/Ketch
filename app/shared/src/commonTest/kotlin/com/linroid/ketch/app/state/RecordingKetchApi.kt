package com.linroid.ketch.app.state

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.FakeKetchApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

/**
 * A task that records its commands and changes state roughly like the engine: pausing a
 * download frees its slot for the next queued task, and an urgent waiting task preempts one,
 * which then waits paused for it (or queued, like an older daemon's). Tasks waiting behind full
 * slots report their [queuePosition].
 */
internal class RecordingTask(
  override val taskId: String,
  request: DownloadRequest,
  initial: DownloadState,
  private val api: RecordingKetchApi,
) : DownloadTask {
  override val requestState = MutableStateFlow(request)
  override val request: DownloadRequest get() = requestState.value
  override val createdAt: Instant = Instant.fromEpochSeconds(taskId.hashCode().toLong())
  override val state = MutableStateFlow(initial)
  override val segments = MutableStateFlow(emptyList<Segment>())

  override val queuePosition = MutableStateFlow<Int?>(null)

  /** Commands received, in order, such as "pause" or "remove deleteFiles=false". */
  val calls = mutableListOf<String>()

  /** Thrown by every command when set. */
  var failure: Throwable? = null

  private fun record(call: String) {
    calls += call
    failure?.let { throw it }
  }

  override suspend fun pause() {
    record("pause")
    val wasRunning = state.value is DownloadState.Downloading
    state.value = DownloadState.Paused(PROGRESS)
    if (wasRunning) api.promoteNext() else api.publishPositions()
  }

  override suspend fun resume(destination: Destination?) {
    record("resume")
    // Paused for an urgent download, it already waits in the queue.
    if (state.value.waitsInQueue) return
    state.value = DownloadState.Queued
    api.promoteNext()
  }

  override suspend fun cancel() {
    record("cancel")
    state.value = DownloadState.Canceled
    api.promoteNext()
  }

  override suspend fun setSpeedLimit(limit: SpeedLimit) {
    record("speed")
    requestState.update { it.copy(speedLimit = limit) }
  }

  override suspend fun setPriority(priority: DownloadPriority) {
    record("priority $priority")
    requestState.update { it.copy(priority = priority) }
    if (priority == DownloadPriority.URGENT && state.value.waitsInQueue) {
      api.preemptFor(this)
    }
  }

  override suspend fun setConnections(connections: Int) {
    record("connections $connections")
    requestState.update { it.copy(connections = connections) }
  }

  override suspend fun reschedule(
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition>,
  ) {
    record("reschedule")
    requestState.update { it.copy(schedule = schedule) }
  }

  override suspend fun remove(deleteFiles: Boolean) {
    record("remove deleteFiles=$deleteFiles")
    api.removeTask(this)
  }

  companion object {
    val PROGRESS = DownloadProgress(downloadedBytes = 10, totalBytes = 100)
  }
}

/**
 * A device whose tasks are [RecordingTask]s. At most [maxActive] tasks download at once; the
 * rest of the added ones wait in the queue. While every slot is taken, the waiting tasks report
 * positions 1, 2, ... in list order, the order [promoteNext] starts them in; with a slot free a
 * waiting task reports none, as the engine's does while it starts.
 *
 * @param preemptsToPaused whether a task an urgent one pushes out of its slot waits paused for
 *   it, as this version of the engine does; `false` sends it back to the queue as an older
 *   daemon does.
 */
internal class RecordingKetchApi(
  label: String = "Recording",
  private val maxActive: Int = Int.MAX_VALUE,
  private val preemptsToPaused: Boolean = true,
) : KetchApi by FakeKetchApi(label) {
  private val taskList = MutableStateFlow<List<DownloadTask>>(emptyList())
  private var nextId = 1

  override val tasks: StateFlow<List<DownloadTask>> = taskList

  /** Requests passed to [download], in order. */
  val requests = mutableListOf<DownloadRequest>()

  /** Returns the failure [download] throws for a request, or `null` to add it. */
  var downloadFailure: (DownloadRequest) -> Throwable? = { null }

  /** Adds a task in [state] directly, without going through the queue. */
  fun add(
    state: DownloadState,
    request: DownloadRequest = DownloadRequest("https://example.com/file$nextId.bin"),
  ): RecordingTask {
    val task = RecordingTask("t${nextId++}", request, state, this)
    taskList.update { it + task }
    publishPositions()
    return task
  }

  override suspend fun download(request: DownloadRequest): DownloadTask {
    downloadFailure(request)?.let { throw it }
    requests += request
    return add(DownloadState.Queued, request).also { promoteNext() }
  }

  /** Starts waiting tasks, queued or preempted, in list order while a slot is free. */
  fun promoteNext() {
    for (task in taskList.value.filterIsInstance<RecordingTask>()) {
      if (activeCount() >= maxActive) break
      if (task.state.value.waitsInQueue) {
        task.state.value = DownloadState.Downloading(RecordingTask.PROGRESS)
      }
    }
    publishPositions()
  }

  /** Starts [task] at once, making a running task wait for it when no slot is free. */
  fun preemptFor(task: RecordingTask) {
    if (activeCount() >= maxActive) {
      val victim = taskList.value.filterIsInstance<RecordingTask>()
        .firstOrNull { it !== task && it.state.value is DownloadState.Downloading }
      victim?.state?.value = if (preemptsToPaused) {
        DownloadState.Paused(RecordingTask.PROGRESS, PauseReason.Preempted(task.taskId))
      } else {
        DownloadState.Queued
      }
    }
    task.state.value = DownloadState.Downloading(RecordingTask.PROGRESS)
    publishPositions()
  }

  fun removeTask(task: DownloadTask) {
    taskList.update { it - task }
    (task as? RecordingTask)?.queuePosition?.value = null
    publishPositions()
  }

  /** Numbers the tasks waiting behind full slots, and clears the others' positions. */
  fun publishPositions() {
    val full = activeCount() >= maxActive
    var next = 1
    for (task in taskList.value.filterIsInstance<RecordingTask>()) {
      task.queuePosition.value = if (full && task.state.value.waitsInQueue) next++ else null
    }
  }

  private fun activeCount(): Int =
    taskList.value.count { it.state.value is DownloadState.Downloading }
}
