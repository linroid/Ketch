package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.app.instance.ServerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.sample
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What keeps the Android download service in the foreground, and what its ongoing
 * notification reports.
 *
 * @property downloading number of embedded tasks downloading.
 * @property queued number of embedded tasks waiting for a free download slot.
 * @property serverState state of the local server sharing the embedded device.
 */
data class ForegroundStatus(
  val downloading: Int = 0,
  val queued: Int = 0,
  val serverState: ServerState = ServerState.Stopped,
) {
  /** Whether the service must run in the foreground. */
  val isRequired: Boolean
    get() = downloading > 0 || queued > 0 || serverState is ServerState.Running
}

/**
 * Decides when the Android download service runs in the foreground: while any task of the
 * embedded device is downloading or queued, or while the local server runs.
 *
 * It follows the embedded device rather than the active one, so downloads keep running while
 * the app shows a remote device.
 */
object ForegroundPolicy {
  /** How often [observe] re-evaluates the task states. */
  val samplePeriod: Duration = 1.seconds

  /** The status for tasks in [states] while the local server is in [serverState]. */
  fun evaluate(states: List<DownloadState>, serverState: ServerState): ForegroundStatus =
    ForegroundStatus(
      downloading = states.count { it is DownloadState.Downloading },
      queued = states.count { it is DownloadState.Queued },
      serverState = serverState,
    )

  /**
   * Follows the state of every task in [tasks] and [serverState], emitting the
   * [ForegroundStatus] at most once per [samplePeriod], and only when it changes.
   */
  @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
  fun observe(
    tasks: Flow<List<DownloadTask>>,
    serverState: Flow<ServerState>,
  ): Flow<ForegroundStatus> {
    val states = tasks.flatMapLatest { list ->
      if (list.isEmpty()) {
        flowOf(emptyList())
      } else {
        combine(list.map { it.state }) { it.toList() }
      }
    }
    return combine(states, serverState, ::evaluate)
      .sample(samplePeriod)
      .distinctUntilChanged()
  }
}
