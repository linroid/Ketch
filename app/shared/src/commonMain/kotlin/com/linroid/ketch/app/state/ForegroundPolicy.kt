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
 * @property serverPort port of the local server sharing the embedded device, or `null` when it
 *   is not running. Only the port is kept, so the status never carries the server's API token.
 * @property discovering number of Discover searches running or waiting to start, including
 *   those waiting for the user's OK to open a website.
 */
data class ForegroundStatus(
  val downloading: Int = 0,
  val queued: Int = 0,
  val serverPort: Int? = null,
  val discovering: Int = 0,
) {
  /** Whether the service must run in the foreground. */
  val isRequired: Boolean
    get() = downloading > 0 || queued > 0 || serverPort != null || discovering > 0
}

/**
 * Decides when the Android download service runs in the foreground: while any task of the
 * embedded device is downloading or queued, while the local server runs, or while Discover
 * searches, which runs in the app's process and would otherwise stop once Android freezes it
 * in the background, before asking for the OK that would notify the user.
 *
 * It follows the embedded device rather than the active one, so downloads keep running while
 * the app shows a remote device.
 */
object ForegroundPolicy {
  /** How often [observe] re-evaluates the task states. */
  val samplePeriod: Duration = 1.seconds

  /**
   * The status for tasks in [states] while the local server is in [serverState] and
   * [discovering] Discover searches run or wait to start.
   */
  fun evaluate(
    states: List<DownloadState>,
    serverState: ServerState,
    discovering: Int = 0,
  ): ForegroundStatus = ForegroundStatus(
    downloading = states.count { it is DownloadState.Downloading },
    queued = states.count { it.waitsInQueue },
    serverPort = (serverState as? ServerState.Running)?.port,
    discovering = discovering,
  )

  /**
   * Follows the state of every task in [tasks], [serverState] and the number of Discover
   * searches [discovering], emitting the [ForegroundStatus] at most once per [samplePeriod], and
   * only when it changes.
   */
  @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
  fun observe(
    tasks: Flow<List<DownloadTask>>,
    serverState: Flow<ServerState>,
    discovering: Flow<Int> = flowOf(0),
  ): Flow<ForegroundStatus> {
    val states = tasks.flatMapLatest { list ->
      if (list.isEmpty()) {
        flowOf(emptyList())
      } else {
        combine(list.map { it.state }) { it.toList() }
      }
    }
    return combine(states, serverState, discovering, ::evaluate)
      .sample(samplePeriod)
      .distinctUntilChanged()
  }
}
