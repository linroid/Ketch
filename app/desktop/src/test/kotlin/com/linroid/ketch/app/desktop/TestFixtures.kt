package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.ControlKey
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SpeedLimitMode
import com.linroid.ketch.api.SpeedModeChange
import com.linroid.ketch.api.SpeedModeState
import com.linroid.ketch.api.SpeedModes
import com.linroid.ketch.api.SystemInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A device that records the downloads and the settings it is given, and switches between speed
 * modes when it [runsSpeedModes].
 */
internal class FakeDevice(runsSpeedModes: Boolean = false) : KetchApi {
  private val list = MutableStateFlow<List<DownloadTask>>(emptyList())

  /** Its speed modes: switching between them only, with the slow lane at 1 MB/s. */
  private val speedModes = if (runsSpeedModes) FakeSpeedModes() else null

  @Suppress("UNCHECKED_CAST")
  override fun <T> subscribe(interest: Interest<T>, interval: Duration): Flow<T> =
    if (interest == Interest.SpeedMode && speedModes != null) {
      speedModes.states as Flow<T>
    } else {
      emptyFlow()
    }

  @Suppress("UNCHECKED_CAST")
  override fun <T : Any> controller(key: ControlKey<T>): T? =
    speedModes.takeIf { key === SpeedModes.Key } as T?

  /** Requests passed to [download], in order. */
  val requests = mutableListOf<DownloadRequest>()

  /** Configs passed to [updateConfig], in order. */
  val configs = mutableListOf<DownloadConfig>()

  var config = DownloadConfig()
    private set

  override val backendLabel: String = "Fake"
  override val tasks: StateFlow<List<DownloadTask>> = list

  /** Adds a task in [state]. */
  fun add(
    state: DownloadState,
    request: DownloadRequest = DownloadRequest("https://example.com/${list.value.size}.bin"),
  ): FakeTask = FakeTask("t${list.value.size}", request, state).also { task ->
    list.update { it + task }
  }

  override suspend fun download(request: DownloadRequest): DownloadTask {
    requests += request
    return add(DownloadState.Queued, request)
  }

  override suspend fun resolve(input: ResolveInput): ResolvedSource =
    error("Not used")

  override suspend fun start() {}

  override suspend fun status(): KetchStatus = KetchStatus(
    name = "Fake",
    version = "0.0.1",
    revision = "test",
    uptime = 0,
    config = config,
    system = SYSTEM,
  )

  override suspend fun updateConfig(config: DownloadConfig) {
    configs += config
    this.config = config
  }

  override fun close() {}
}

/** Speed modes that only switch their mode. */
private class FakeSpeedModes : SpeedModes {
  val states = MutableStateFlow(SpeedModeState())

  override suspend fun state(): SpeedModeState = states.value

  override suspend fun update(change: SpeedModeChange): SpeedModeState = states.updateAndGet {
    val mode = change.mode ?: it.settings.mode
    it.copy(settings = it.settings.copy(mode = mode), inSlowLane = mode == SpeedLimitMode.SlowLane)
  }
}

/** A task that changes state as it is told to. */
internal class FakeTask(
  override val taskId: String,
  request: DownloadRequest,
  initial: DownloadState,
) : DownloadTask {
  override val requestState = MutableStateFlow(request)
  override val request: DownloadRequest get() = requestState.value
  override val createdAt: Instant = Instant.fromEpochSeconds(0)
  override val state = MutableStateFlow(initial)
  override val segments = MutableStateFlow(emptyList<Segment>())

  override suspend fun pause() {
    state.value = DownloadState.Paused(DownloadProgress(10, 100))
  }

  override suspend fun resume(destination: Destination?) {
    state.value = DownloadState.Queued
  }

  override suspend fun cancel() {
    state.value = DownloadState.Canceled
  }

  override suspend fun setSpeedLimit(limit: SpeedLimit) {}

  override suspend fun setPriority(priority: DownloadPriority) {}

  override suspend fun setConnections(connections: Int) {}

  override suspend fun reschedule(
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition>,
  ) {}

  override suspend fun remove(deleteFiles: Boolean) {}
}

private val SYSTEM = SystemInfo(
  os = "Linux",
  arch = "x64",
  separator = "/",
  javaVersion = "21",
  availableProcessors = 4,
  maxMemory = 0,
  totalMemory = 0,
  freeMemory = 0,
  downloadDirectory = "/downloads",
  totalSpace = 0,
  freeSpace = 0,
  usableSpace = 0,
)
