package com.linroid.ketch.server.stream

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Segment
import com.linroid.ketch.endpoints.model.TaskSnapshot
import com.linroid.ketch.endpoints.stream.TaskProgressUpdate
import com.linroid.ketch.endpoints.stream.TaskSegmentsUpdate
import com.linroid.ketch.endpoints.stream.TaskStateUpdate
import com.linroid.ketch.server.TaskMapper
import com.linroid.ketch.server.api.ServerJson
import kotlin.time.Instant

/**
 * What every event stream knows of a task at one moment, shared by all of them: each stream
 * compares the versions with those it sent, and the payloads are encoded once, when the first
 * stream needs them.
 *
 * Versions come from one counter per [TaskTracker], so a newer frame's changed fields always
 * have higher versions.
 *
 * @property stateVersion changes with anything but a downloading task's progress and segments
 * @property progressVersion changes with a downloading task's progress
 */
internal class TaskFrame private constructor(
  val taskId: String,
  val createdAt: Instant,
  private val sourceRequest: DownloadRequest,
  val request: DownloadRequest,
  val state: DownloadState,
  val segments: List<Segment>,
  val queuePosition: Int?,
  val stateVersion: Long,
  val requestVersion: Long,
  val progressVersion: Long,
  val segmentsVersion: Long,
) {
  val snapshot: TaskSnapshot
    get() = TaskSnapshot(taskId, request, state, segments, createdAt, queuePosition)

  /** A `task.added` payload. */
  val addedJson: String by lazy { ServerJson.encodeToString(snapshot) }

  /** A `task.state` payload with the request. */
  val stateJson: String by lazy {
    ServerJson.encodeToString(TaskStateUpdate(taskId, state, queuePosition, request))
  }

  /** A `task.state` payload without the request, for streams that have it. */
  val stateOnlyJson: String by lazy {
    ServerJson.encodeToString(TaskStateUpdate(taskId, state, queuePosition))
  }

  /** A `task.progress` payload; only for a [DownloadState.Downloading] frame. */
  val progressJson: String by lazy {
    val progress = (state as DownloadState.Downloading).progress
    ServerJson.encodeToString(TaskProgressUpdate(taskId, progress))
  }

  /** A `task.segments` payload. */
  val segmentsJson: String by lazy {
    ServerJson.encodeToString(TaskSegmentsUpdate(taskId, segments))
  }

  /**
   * The frame after the task reported these values, or this frame when nothing changed. The
   * request and segments are compared by identity, as tasks replace them whenever they change.
   */
  fun next(
    request: DownloadRequest,
    state: DownloadState,
    segments: List<Segment>,
    queuePosition: Int?,
    nextVersion: () -> Long,
  ): TaskFrame {
    val requestChanged = request !== sourceRequest
    val bothDownloading =
      state is DownloadState.Downloading && this.state is DownloadState.Downloading
    val stateChanged = requestChanged || queuePosition != this.queuePosition ||
      (!bothDownloading && state != this.state)
    val progressChanged = bothDownloading && state != this.state
    val segmentsChanged = segments !== this.segments
    if (!stateChanged && !progressChanged && !segmentsChanged) return this
    val version = nextVersion()
    return TaskFrame(
      taskId = taskId,
      createdAt = createdAt,
      sourceRequest = request,
      request = if (requestChanged) TaskMapper.viewOf(request) else this.request,
      state = state,
      segments = segments,
      queuePosition = queuePosition,
      // A state change carries the progress too.
      stateVersion = if (stateChanged) version else stateVersion,
      requestVersion = if (requestChanged) version else requestVersion,
      progressVersion = if (stateChanged || progressChanged) version else progressVersion,
      segmentsVersion = if (segmentsChanged) version else segmentsVersion,
    )
  }

  companion object {
    /** The frame of [task] as it is now, every field at [version]. */
    fun of(task: DownloadTask, version: Long): TaskFrame {
      val request = task.request
      return TaskFrame(
        taskId = task.taskId,
        createdAt = task.createdAt,
        sourceRequest = request,
        request = TaskMapper.viewOf(request),
        state = task.state.value,
        segments = task.segments.value,
        queuePosition = task.queuePosition.value,
        stateVersion = version,
        requestVersion = version,
        progressVersion = version,
        segmentsVersion = version,
      )
    }
  }
}
