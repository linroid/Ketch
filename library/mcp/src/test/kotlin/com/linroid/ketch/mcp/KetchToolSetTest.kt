package com.linroid.ketch.mcp

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

class KetchToolSetTest {

  @Test
  fun startDownload_anyRequest_tagsItWithTheAgentOrigin() = runTest {
    val ketch = RecordingKetchApi()

    KetchToolSet(ketch).startDownload(
      url = "https://example.com/a.iso",
      destination = "a.iso",
      priority = "high",
      speedLimit = "1m",
    )

    val request = ketch.requests.single()
    assertEquals(mapOf("ketch.origin" to "agent"), request.properties)
    assertEquals(emptyMap(), request.headers)
    assertEquals(Destination("a.iso"), request.destination)
    assertEquals(DownloadPriority.HIGH, request.priority)
  }

  @Test
  fun resolveUrl_anyUrl_sendsNoOriginToResolve() = runTest {
    val ketch = RecordingKetchApi()

    KetchToolSet(ketch).resolveUrl("https://example.com/a.iso")

    assertEquals(listOf(emptyMap()), ketch.resolveProperties)
  }

  @Test
  fun getDownload_queuedTask_includesQueuePosition() {
    val task = QueuedTask(request(), queuePosition = 2)

    val json = getDownload(task)

    assertEquals("queued", json.getValue("state").jsonPrimitive.content)
    assertEquals(2, json.getValue("queuePosition").jsonPrimitive.int)
  }

  @Test
  fun getDownload_preemptedTask_includesPauseReasonAndPreemptedBy() {
    val state = DownloadState.Paused(
      DownloadProgress(10, 100),
      PauseReason.Preempted("task-urgent"),
    )
    val task = QueuedTask(request(), state, queuePosition = 1)

    val json = getDownload(task)

    assertEquals("paused", json.getValue("state").jsonPrimitive.content)
    assertEquals("preempted", json.getValue("pauseReason").jsonPrimitive.content)
    assertEquals("task-urgent", json.getValue("preemptedBy").jsonPrimitive.content)
    assertEquals(1, json.getValue("queuePosition").jsonPrimitive.int)
  }

  @Test
  fun getDownload_completedTask_includesCompletedAt() {
    val completedAt = Instant.parse("2026-10-03T08:00:03.123Z")
    val state = DownloadState.Completed("/d/a.iso", 100, completedAt = completedAt)
    val task = QueuedTask(request(), state)

    val json = getDownload(task)

    assertEquals("2026-10-03T08:00:03.123Z", json.getValue("completedAt").jsonPrimitive.content)
    assertFalse("queuePosition" in json)
  }

  private fun request() = DownloadRequest(url = "https://example.com/a.iso")

  private fun getDownload(task: DownloadTask): JsonObject {
    val result = KetchToolSet(RecordingKetchApi(listOf(task))).getDownload(task.taskId)
    return Json.parseToJsonElement(result).jsonObject
  }

  private class RecordingKetchApi(
    tasks: List<DownloadTask> = emptyList(),
  ) : KetchApi {
    val requests = mutableListOf<DownloadRequest>()
    val resolveProperties = mutableListOf<Map<String, String>>()

    override val backendLabel = "Recording"
    override val tasks: StateFlow<List<DownloadTask>> = MutableStateFlow(tasks)

    override suspend fun download(request: DownloadRequest): DownloadTask {
      requests += request
      return QueuedTask(request)
    }

    override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource {
      resolveProperties += properties
      return ResolvedSource(
        url = url,
        sourceType = "http",
        totalBytes = 1,
        supportsResume = false,
        suggestedFileName = null,
        maxSegments = 1,
      )
    }

    override suspend fun resolveContent(content: ByteArray, fileName: String?): ResolvedSource =
      throw UnsupportedOperationException()

    override suspend fun start() {}

    override suspend fun status(): KetchStatus = throw UnsupportedOperationException()

    override suspend fun updateConfig(config: DownloadConfig) {}

    override fun close() {}
  }

  private class QueuedTask(
    override val request: DownloadRequest,
    state: DownloadState = DownloadState.Queued,
    queuePosition: Int? = null,
  ) : DownloadTask {
    override val taskId = "task-1"
    override val requestState: StateFlow<DownloadRequest> = MutableStateFlow(request)
    override val createdAt = Instant.fromEpochMilliseconds(0)
    override val state: StateFlow<DownloadState> = MutableStateFlow(state)
    override val segments: StateFlow<List<Segment>> = MutableStateFlow(emptyList())
    override val queuePosition: StateFlow<Int?> = MutableStateFlow(queuePosition)

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
}
