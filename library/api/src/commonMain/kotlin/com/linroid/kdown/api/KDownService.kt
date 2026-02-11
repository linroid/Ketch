package com.linroid.kdown.api

import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.SpeedLimit
import com.linroid.kdown.api.model.ServerStatus
import com.linroid.kdown.api.model.TaskEvent
import com.linroid.kdown.task.DownloadTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Service interface for managing downloads. Both embedded (in-process)
 * and remote (HTTP+SSE) backends implement this interface, allowing
 * the UI to work identically regardless of backend mode.
 */
interface KDownService {

  /** Connection health. Always [ConnectionState.Connected] for embedded. */
  val connectionState: StateFlow<ConnectionState>

  /** Human-readable label: "Embedded" or "Remote · host:port". */
  val backendLabel: String

  /** Reactive task list updated on any state change. */
  val tasks: StateFlow<List<DownloadTask>>

  /** Create a new download and return the task handle. */
  suspend fun download(request: DownloadRequest): DownloadTask

  /** Set global speed limit (use [SpeedLimit.Unlimited] to remove). */
  suspend fun setGlobalSpeedLimit(limit: SpeedLimit)

  /** Get server/engine status. */
  suspend fun getStatus(): ServerStatus

  /** Real-time event stream for all tasks. */
  fun events(): Flow<TaskEvent>

  /** Release resources (HTTP client, SSE connection, etc.). */
  fun close()
}
