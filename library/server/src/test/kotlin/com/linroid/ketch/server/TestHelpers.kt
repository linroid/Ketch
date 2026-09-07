package com.linroid.ketch.server

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal class NoOpHttpEngine : HttpEngine {
  override suspend fun head(
    url: String,
    headers: Map<String, String>,
  ): ServerInfo {
    throw KetchError.Network(
      RuntimeException(
        "NoOpHttpEngine does not support requests"
      )
    )
  }

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    throw KetchError.Network(
      RuntimeException(
        "NoOpHttpEngine does not support requests"
      )
    )
  }

  override fun close() {}
}

internal fun createTestKetch(
  config: DownloadConfig = DownloadConfig.Default,
): KetchApi {
  return Ketch(httpEngine = NoOpHttpEngine(), config = config)
}

internal fun createTestServer(
  ketch: KetchApi = createTestKetch(),
): KetchServer {
  return KetchServer(ketch)
}

/**
 * Wraps a [KetchApi], routing all calls through to the delegate but
 * substituting [DownloadTask] instances with [RecordingTask] proxies
 * that capture `remove(deleteFiles)` calls.
 */
internal class RecordingKetchApi(
  private val delegate: KetchApi,
) : KetchApi {
  val removeCalls = mutableListOf<Boolean>()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override val backendLabel: String get() = delegate.backendLabel

  override val tasks: StateFlow<List<DownloadTask>> =
    delegate.tasks
      .map { list -> list.map { RecordingTask(it, removeCalls) } }
      .stateIn(
        scope,
        kotlinx.coroutines.flow.SharingStarted.Eagerly,
        delegate.tasks.value.map { RecordingTask(it, removeCalls) },
      )

  override suspend fun download(request: DownloadRequest): DownloadTask =
    RecordingTask(delegate.download(request), removeCalls)

  override suspend fun resolve(
    url: String,
    properties: Map<String, String>,
  ): ResolvedSource = delegate.resolve(url, properties)

  override suspend fun start() = delegate.start()
  override suspend fun status(): KetchStatus = delegate.status()
  override suspend fun updateConfig(config: DownloadConfig) =
    delegate.updateConfig(config)
  override fun close() {
    scope.cancel()
    delegate.close()
  }
}

private class RecordingTask(
  private val delegate: DownloadTask,
  private val removeCalls: MutableList<Boolean>,
) : DownloadTask {
  override val taskId: String get() = delegate.taskId
  override val request: DownloadRequest get() = delegate.request
  override val requestState: StateFlow<DownloadRequest> get() = delegate.requestState
  override val createdAt = delegate.createdAt
  override val state: StateFlow<DownloadState> get() = delegate.state
  override val segments: StateFlow<List<Segment>> get() = delegate.segments

  override suspend fun pause() = delegate.pause()
  override suspend fun resume(destination: Destination?) =
    delegate.resume(destination)
  override suspend fun cancel() = delegate.cancel()
  override suspend fun setSpeedLimit(limit: SpeedLimit) =
    delegate.setSpeedLimit(limit)
  override suspend fun setPriority(priority: DownloadPriority) =
    delegate.setPriority(priority)
  override suspend fun setConnections(connections: Int) =
    delegate.setConnections(connections)
  override suspend fun reschedule(
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition>,
  ) = delegate.reschedule(schedule, conditions)
  override suspend fun remove(deleteFiles: Boolean) {
    removeCalls.add(deleteFiles)
    delegate.remove(deleteFiles)
  }
}
