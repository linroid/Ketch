package com.linroid.ketch.hooks

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
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

internal class FakeKetch : KetchApi {
  override val backendLabel: String = "Fake"
  override val tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

  fun add(task: DownloadTask) {
    tasks.value += task
  }

  fun remove(task: DownloadTask) {
    tasks.value -= task
  }

  override fun <T> subscribe(interest: Interest<T>, interval: Duration): Flow<T> =
    flow { throw UnsupportedOperationException("unused") }
  override suspend fun download(request: DownloadRequest): DownloadTask = error("unused")
  override suspend fun resolve(input: ResolveInput): ResolvedSource =
    error("unused")
  override suspend fun start() {}
  override suspend fun status(): KetchStatus = error("no status")
  override suspend fun updateConfig(config: DownloadConfig) {}
  override fun close() {}
}

internal class FakeTask(
  override val taskId: String,
  override val createdAt: Instant,
  url: String = "https://example.com/file.iso",
  properties: Map<String, String> = emptyMap(),
  initial: DownloadState = DownloadState.Queued,
) : DownloadTask {
  override val request = DownloadRequest(url = url, properties = properties)
  override val requestState: StateFlow<DownloadRequest> = MutableStateFlow(request)
  override val state = MutableStateFlow(initial)
  override val segments: StateFlow<List<Segment>> = MutableStateFlow(emptyList())
  override suspend fun pause() {}
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

internal class FixedClock(var now: Instant) : Clock {
  override fun now(): Instant = now
}

/** Records each launch; its processes exit with [exitCode], or never when [hangs]. */
internal class RecordingLauncher(
  private val exitCode: Int = 0,
  private val hangs: Boolean = false,
) : ProcessLauncher {
  val launches = mutableListOf<Launch>()

  class Launch(
    val command: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File?,
    val process: FakeProcess,
  ) {
    val stdin: String get() = process.input.toString(Charsets.UTF_8)
  }

  override fun launch(
    command: List<String>,
    environment: Map<String, String>,
    workingDirectory: File?,
  ): HookProcess {
    val process = FakeProcess(exitCode, hangs)
    launches += Launch(command, environment, workingDirectory, process)
    return process
  }
}

internal class FakeProcess(override val exitCode: Int, private val hangs: Boolean) : HookProcess {
  val input = ByteArrayOutputStream()
  var killed = false
  var waitedFor: Duration? = null

  override val stdin: OutputStream get() = input
  override fun waitFor(timeout: Duration): Boolean {
    waitedFor = timeout
    return !hangs
  }
  override fun kill() {
    killed = true
  }
}
