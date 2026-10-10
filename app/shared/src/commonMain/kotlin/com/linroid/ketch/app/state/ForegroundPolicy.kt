package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchFeatures
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
 * @property starting number of embedded tasks that hold a slot while they get ready
 *   ([isStarting]), such as a magnet link looking for its metadata.
 * @property serverPort port of the local server sharing the embedded device, or `null` when it
 *   is not running. Only the port is kept, so the status never carries the server's API token.
 * @property discovering number of Discover searches running or waiting to start, including
 *   those waiting for the user's OK to open a website.
 */
data class ForegroundStatus(
  val downloading: Int = 0,
  val queued: Int = 0,
  val starting: Int = 0,
  val serverPort: Int? = null,
  val discovering: Int = 0,
) {
  /** Whether the service must run in the foreground. */
  val isRequired: Boolean
    get() = downloading > 0 || queued > 0 || starting > 0 || serverPort != null || discovering > 0

  /**
   * Whether the system should stay awake, as [KeepAwake] keeps it: while downloads run, start or
   * wait in the queue. The local server and Discover never do, as the server may run all day and
   * a search can wait hours for the user's OK.
   */
  val keepsAwake: Boolean
    get() = downloading > 0 || queued > 0 || starting > 0
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
   * The status for tasks in [states], [starting] of which are queued but hold a slot
   * ([isStarting]), while the local server is in [serverState] and [discovering] Discover
   * searches run or wait to start.
   */
  fun evaluate(
    states: List<DownloadState>,
    serverState: ServerState,
    discovering: Int = 0,
    starting: Int = 0,
  ): ForegroundStatus = ForegroundStatus(
    downloading = states.count { it is DownloadState.Downloading },
    queued = states.count { it.waitsInQueue } - starting,
    starting = starting,
    serverPort = (serverState as? ServerState.Running)?.port,
    discovering = discovering,
  )

  /**
   * Follows the state and queue position of every task in [tasks], those of a device that
   * reports [features], [serverState] and the number of Discover searches [discovering],
   * emitting the [ForegroundStatus] at most once per [samplePeriod], and only when it changes.
   * A queued task that leaves the queue to start changes it, though its state stays the same.
   */
  @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
  fun observe(
    tasks: Flow<List<DownloadTask>>,
    serverState: Flow<ServerState>,
    discovering: Flow<Int> = flowOf(0),
    features: Set<String> = KetchFeatures.ALL,
  ): Flow<ForegroundStatus> {
    val samples = tasks.flatMapLatest { list ->
      if (list.isEmpty()) {
        flowOf(emptyList())
      } else {
        combine(list.map { task -> combine(task.state, task.queuePosition, ::Pair) }) {
          it.toList()
        }
      }
    }
    return combine(samples, serverState, discovering) { tasks, server, searches ->
      evaluate(
        states = tasks.map { it.first },
        serverState = server,
        discovering = searches,
        starting = tasks.count { (state, position) -> state.isStarting(position, features) },
      )
    }
      .sample(samplePeriod)
      .distinctUntilChanged()
  }
}
