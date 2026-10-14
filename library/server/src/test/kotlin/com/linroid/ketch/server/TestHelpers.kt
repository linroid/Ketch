package com.linroid.ketch.server

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

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

/**
 * A server without a token for [ketch]. Callers may save downloads to `/tmp`, where the tests
 * point their destinations, besides the download directory.
 */
internal fun createTestServer(
  ketch: KetchApi = createTestKetch(),
  allowedDirectories: List<String> = listOf("/tmp"),
): KetchServer {
  return KetchServer(ketch, allowedDirectories = allowedDirectories)
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

  override val backendLabel: String get() = delegate.backendLabel

  // Read through, not copied by a coroutine: a request right after `download` must find the task.
  override val tasks: StateFlow<List<DownloadTask>> =
    MappedStateFlow(delegate.tasks) { list -> list.map { RecordingTask(it, removeCalls) } }

  override suspend fun download(request: DownloadRequest): DownloadTask =
    RecordingTask(delegate.download(request), removeCalls)

  override suspend fun resolve(input: ResolveInput): ResolvedSource = delegate.resolve(input)

  override suspend fun start() = delegate.start()
  override suspend fun status(): KetchStatus = delegate.status()
  override suspend fun updateConfig(config: DownloadConfig) =
    delegate.updateConfig(config)
  override fun <T> subscribe(interest: Interest<T>, interval: Duration): Flow<T> = delegate.subscribe(interest, interval)
  override fun close() {
    delegate.close()
  }
}

/** [source] seen through [transform], computed on every read. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MappedStateFlow<T, R>(
  private val source: StateFlow<T>,
  private val transform: (T) -> R,
) : StateFlow<R> {
  override val value: R get() = transform(source.value)
  override val replayCache: List<R> get() = listOf(value)
  override suspend fun collect(collector: FlowCollector<R>): Nothing {
    source.collect { collector.emit(transform(it)) }
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
  override val queuePosition: StateFlow<Int?> get() = delegate.queuePosition

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
