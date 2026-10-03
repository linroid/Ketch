package com.linroid.ketch.app.state

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.i18n.plain
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.util.RowContext
import com.linroid.ketch.app.util.rowContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/** A task whose flows tests set directly. */
internal class ListTestTask(
  override val taskId: String,
  state: DownloadState,
  request: DownloadRequest = DownloadRequest("https://example.com/$taskId.bin"),
  override val createdAt: Instant = ListFixtures.START,
  segments: List<Segment> = emptyList(),
) : DownloadTask {
  override val requestState = MutableStateFlow(request)
  override val request: DownloadRequest
    get() = requestState.value
  override val state = MutableStateFlow(state)
  override val segments = MutableStateFlow(segments)

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

internal object ListFixtures {
  /** 2026-10-01 12:00 UTC, a Thursday. */
  val START: Instant = Instant.parse("2026-10-01T12:00:00Z")

  val device: DeviceInfo = DeviceInfo(verbatim("This Mac"), RowCapabilities.local())

  fun downloading(downloaded: Long, total: Long = 1000, speed: Long = 100): DownloadState =
    DownloadState.Downloading(DownloadProgress(downloaded, total, speed))

  /** A row of a task in [state], built like [TaskListModel] builds one. */
  fun row(
    id: String,
    state: DownloadState,
    createdAt: Instant = START,
    request: DownloadRequest = DownloadRequest("https://example.com/$id.bin"),
    deviceId: String = LOCAL_DEVICE_ID,
    device: DeviceInfo = this.device,
    speedSamples: List<Long> = emptyList(),
    now: Instant = START,
  ): TaskRow {
    val context = RowContext(device, now, TimeZone.UTC)
    return TaskRow(
      key = TaskKey(deviceId, id),
      task = ListTestTask(id, state, request, createdAt),
      request = request,
      state = state,
      segments = emptyList(),
      createdAt = createdAt,
      device = device,
      content = rowContent(request, state, createdAt, context),
      deviceName = device.name.plain,
      errorTitle = null,
      speedSamples = speedSamples,
    )
  }

  /** A clock that reads the virtual time of [scope], starting at [START]. */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun clock(scope: TestScope): Clock = object : Clock {
    override fun now(): Instant =
      Instant.fromEpochMilliseconds(START.toEpochMilliseconds() + scope.testScheduler.currentTime)
  }
}
