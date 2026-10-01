package com.linroid.ketch.mcp

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

  private class RecordingKetchApi : KetchApi {
    val requests = mutableListOf<DownloadRequest>()
    val resolveProperties = mutableListOf<Map<String, String>>()

    override val backendLabel = "Recording"
    override val tasks: StateFlow<List<DownloadTask>> = MutableStateFlow(emptyList())

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

  private class QueuedTask(override val request: DownloadRequest) : DownloadTask {
    override val taskId = "task-1"
    override val requestState: StateFlow<DownloadRequest> = MutableStateFlow(request)
    override val createdAt = Instant.fromEpochMilliseconds(0)
    override val state: StateFlow<DownloadState> = MutableStateFlow(DownloadState.Queued)
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
}
