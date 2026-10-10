package com.linroid.ketch.mcp

import ai.koog.agents.core.tools.ToolException
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
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SystemInfo
import com.linroid.ketch.api.torrent.TorrentCapabilities
import com.linroid.ketch.api.torrent.TorrentCommandError
import com.linroid.ketch.api.torrent.TorrentCommandException
import com.linroid.ketch.api.torrent.TorrentController
import com.linroid.ketch.api.torrent.TorrentFileEntry
import com.linroid.ketch.api.torrent.TorrentFileOrder
import com.linroid.ketch.api.torrent.TorrentFilePage
import com.linroid.ketch.api.torrent.TorrentPageRequest
import com.linroid.ketch.api.torrent.TorrentRevision
import com.linroid.ketch.api.torrent.TorrentSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
  fun startDownload_headerLines_becomeRequestHeaders() = runTest {
    val ketch = RecordingKetchApi()

    KetchToolSet(ketch).startDownload(
      url = "https://example.com/a.iso",
      headers = "Cookie: sid=1; theme=dark\n\nReferer:https://example.com/downloads\r\n",
    )

    assertEquals(
      mapOf("Cookie" to "sid=1; theme=dark", "Referer" to "https://example.com/downloads"),
      ketch.requests.single().headers,
    )
  }

  @Test
  fun startDownload_headerLineWithoutName_isRejected() = runTest {
    val ketch = RecordingKetchApi()

    assertFailsWith<IllegalArgumentException> {
      KetchToolSet(ketch).startDownload(url = "https://example.com/a.iso", headers = "sid=1")
    }
    assertTrue(ketch.requests.isEmpty())
  }

  @Test
  fun startDownload_requestId_isSentWithTheRequest() = runTest {
    val ketch = RecordingKetchApi()

    KetchToolSet(ketch).startDownload(
      url = "https://example.com/a.iso",
      requestId = "6f1c2b0e-8a1d-4c5e-9b7a-3d2f1e0c9b8a",
    )

    assertEquals("6f1c2b0e-8a1d-4c5e-9b7a-3d2f1e0c9b8a", ketch.requests.single().requestId)
  }

  @Test
  fun startDownload_requestIdNotAUuid_isRejected() = runTest {
    val ketch = RecordingKetchApi()

    assertFailsWith<IllegalArgumentException> {
      KetchToolSet(ketch).startDownload(url = "https://example.com/a.iso", requestId = "job-42")
    }
    assertTrue(ketch.requests.isEmpty())
  }

  // An instance without request IDs drops the field, so a retried call would add the download
  // again.
  @Test
  fun startDownload_requestIdOnAnInstanceWithoutRequestIds_isRejected() = runTest {
    val ketch = RecordingKetchApi(features = emptySet())

    assertFailsWith<IllegalArgumentException> {
      KetchToolSet(ketch).startDownload(
        url = "https://example.com/a.iso",
        requestId = "6f1c2b0e-8a1d-4c5e-9b7a-3d2f1e0c9b8a",
      )
    }
    assertTrue(ketch.requests.isEmpty())

    KetchToolSet(ketch).startDownload(url = "https://example.com/a.iso")
    assertEquals(1, ketch.requests.size)
  }

  // `ketch mcp` hands its tools the instance it is attached to, which changes when the instance
  // restarts, so every call asks for it again.
  @Test
  fun tools_connectProvider_askedOnEveryCall() = runTest {
    val first = RecordingKetchApi()
    val second = RecordingKetchApi()
    var current: KetchApi = first
    val tools = KetchToolSet({ current })

    tools.startDownload(url = "https://example.com/a.iso")
    current = second
    tools.startDownload(url = "https://example.com/b.iso")

    assertEquals(listOf("https://example.com/a.iso"), first.requests.map { it.url })
    assertEquals(listOf("https://example.com/b.iso"), second.requests.map { it.url })
  }

  @Test
  fun resolveUrl_anyUrl_sendsNoOriginToResolve() = runTest {
    val ketch = RecordingKetchApi()

    KetchToolSet(ketch).resolveUrl("https://example.com/a.iso")

    assertEquals(listOf(emptyMap()), ketch.resolveProperties)
  }

  @Test
  fun resolveUrl_torrent_leavesOutMetainfo() = runTest {
    val ketch = RecordingKetchApi(
      resolved = torrentSource(metadata = mapOf("metainfo" to "ZDQ6aW5mb2Vl", "infoHash" to "ab")),
    )

    val json = Json.parseToJsonElement(KetchToolSet(ketch).resolveUrl(MAGNET)).jsonObject

    val metadata = json.getValue("metadata").jsonObject
    assertEquals(setOf("infoHash"), metadata.keys)
    assertEquals(3, json.getValue("files").jsonArray.size)
  }

  @Test
  fun startDownload_fileIds_selectsThem() = runTest {
    val ketch = RecordingKetchApi()

    KetchToolSet(ketch).startDownload(url = MAGNET, fileIds = " 2, 0 ,2,")

    assertEquals(setOf("2", "0"), ketch.requests.single().selectedFileIds)
  }

  @Test
  fun startDownload_noFileIds_downloadsEveryFile() = runTest {
    val ketch = RecordingKetchApi()

    KetchToolSet(ketch).startDownload(url = MAGNET)

    assertEquals(emptySet(), ketch.requests.single().selectedFileIds)
  }

  @Test
  fun selectDownloadFiles_commaSeparatedIds_selectsThem() = runTest {
    val task = QueuedTask(torrentRequest())

    val json = Json.parseToJsonElement(
      KetchToolSet(RecordingKetchApi(listOf(task))).selectDownloadFiles(task.taskId, "1, 2 ,1"),
    ).jsonObject

    assertEquals(listOf(setOf("1", "2")), task.selections)
    assertEquals(task.taskId, json.getValue("taskId").jsonPrimitive.content)
  }

  @Test
  fun selectDownloadFiles_noIds_failsValidation() = runTest {
    val task = QueuedTask(torrentRequest())

    assertFailsWith<ToolException.ValidationFailure> {
      KetchToolSet(RecordingKetchApi(listOf(task))).selectDownloadFiles(task.taskId, " , ")
    }
    assertTrue(task.selections.isEmpty())
  }

  @Test
  fun listDownloadFiles_pagesTheController() = runTest {
    val task = QueuedTask(torrentRequest())
    val controller = PagingController()
    val tools = KetchToolSet(RecordingKetchApi(listOf(task), torrents = controller))

    val json = Json.parseToJsonElement(
      tools.listDownloadFiles(
        task.taskId,
        cursor = "abc",
        limit = 2,
        sort = "size",
        descending = true,
      ),
    ).jsonObject

    assertEquals(
      listOf(Paged(task.taskId, TorrentPageRequest(2, "abc"), TorrentFileOrder.SIZE, true)),
      controller.calls,
    )
    assertEquals(3, json.getValue("totalFiles").jsonPrimitive.int)
    assertEquals(4, json.getValue("selectionGeneration").jsonPrimitive.long)
    assertEquals("next", json.getValue("nextCursor").jsonPrimitive.content)
    val file = json.getValue("files").jsonArray.single().jsonObject
    assertEquals("2", file.getValue("id").jsonPrimitive.content)
    assertEquals("Pack/Episode 10.mkv", file.getValue("path").jsonPrimitive.content)
    assertEquals(300, file.getValue("size").jsonPrimitive.long)
    assertFalse(file.getValue("selected").jsonPrimitive.boolean)
  }

  @Test
  fun listDownloadFiles_withoutController_pagesTheRequestFiles() = runTest {
    val task = QueuedTask(torrentRequest(selected = setOf("0", "2")))
    val tools = KetchToolSet(RecordingKetchApi(listOf(task)))

    val first = Json.parseToJsonElement(
      tools.listDownloadFiles(task.taskId, limit = 2, sort = "name"),
    ).jsonObject
    val cursor = first.getValue("nextCursor").jsonPrimitive.content
    val second = Json.parseToJsonElement(
      tools.listDownloadFiles(task.taskId, cursor = cursor, limit = 2, sort = "name"),
    ).jsonObject

    val files = (first.getValue("files").jsonArray + second.getValue("files").jsonArray)
      .map { it.jsonObject }
    assertEquals(
      listOf("Pack/Episode 2.mkv", "Pack/Episode 10.mkv", "Pack/notes.txt"),
      files.map { it.getValue("path").jsonPrimitive.content },
    )
    assertEquals(
      listOf(true, true, false),
      files.map { it.getValue("selected").jsonPrimitive.boolean },
    )
    assertEquals(3, first.getValue("totalFiles").jsonPrimitive.int)
    assertNull(second["nextCursor"])
  }

  @Test
  fun listDownloadFiles_unsupportedController_pagesTheRequestFilesInReverse() = runTest {
    val task = QueuedTask(torrentRequest())
    val ketch = RecordingKetchApi(listOf(task), torrents = PagingController(supported = false))

    val json = Json.parseToJsonElement(
      KetchToolSet(ketch).listDownloadFiles(task.taskId, sort = "size", descending = true),
    ).jsonObject

    assertEquals(
      listOf("2", "0", "1"),
      json.getValue("files").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content },
    )
  }

  @Test
  fun listDownloadFiles_cursorOfAnotherOrder_failsValidation() = runTest {
    val task = QueuedTask(torrentRequest())
    val tools = KetchToolSet(RecordingKetchApi(listOf(task)))
    val cursor = Json.parseToJsonElement(tools.listDownloadFiles(task.taskId, limit = 1))
      .jsonObject.getValue("nextCursor").jsonPrimitive.content

    assertFailsWith<ToolException.ValidationFailure> {
      tools.listDownloadFiles(task.taskId, cursor = cursor, limit = 1, sort = "name")
    }
  }

  @Test
  fun listDownloadFiles_unknownSortOrLimit_failsValidation() = runTest {
    val task = QueuedTask(torrentRequest())
    val tools = KetchToolSet(RecordingKetchApi(listOf(task)))

    assertFailsWith<ToolException.ValidationFailure> {
      tools.listDownloadFiles(task.taskId, sort = "kind")
    }
    assertFailsWith<ToolException.ValidationFailure> {
      tools.listDownloadFiles(task.taskId, limit = 1001)
    }
  }

  @Test
  fun getDownload_seedingTorrent_includesSeedingAndSelectedFiles() = runTest {
    val state = DownloadState.Completed("/d/Pack", 400, seeding = true)
    val task = QueuedTask(torrentRequest(selected = setOf("0", "2")), state)

    val json = getDownload(task)

    assertTrue(json.getValue("seeding").jsonPrimitive.boolean)
    assertEquals(2, json.getValue("selectedFiles").jsonPrimitive.int)
    assertEquals(3, json.getValue("totalFiles").jsonPrimitive.int)
  }

  @Test
  fun getDownload_torrentAwaitingFiles_selectsNone() = runTest {
    val state = DownloadState.Paused(DownloadProgress(0, 0), PauseReason.AwaitingFileSelection)
    val task = QueuedTask(torrentRequest(), state)

    val json = getDownload(task)

    assertEquals("awaiting_file_selection", json.getValue("pauseReason").jsonPrimitive.content)
    assertEquals(0, json.getValue("selectedFiles").jsonPrimitive.int)
  }

  @Test
  fun getDownload_queuedTask_includesQueuePosition() = runTest {
    val task = QueuedTask(request(), queuePosition = 2)

    val json = getDownload(task)

    assertEquals("queued", json.getValue("state").jsonPrimitive.content)
    assertEquals(2, json.getValue("queuePosition").jsonPrimitive.int)
  }

  @Test
  fun getDownload_preemptedTask_includesPauseReasonAndPreemptedBy() = runTest {
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
  fun getDownload_completedTask_includesCompletedAt() = runTest {
    val completedAt = Instant.parse("2026-10-03T08:00:03.123Z")
    val state = DownloadState.Completed("/d/a.iso", 100, completedAt = completedAt)
    val task = QueuedTask(request(), state)

    val json = getDownload(task)

    assertEquals("2026-10-03T08:00:03.123Z", json.getValue("completedAt").jsonPrimitive.content)
    assertFalse("queuePosition" in json)
  }

  private fun request() = DownloadRequest(url = "https://example.com/a.iso")

  private fun torrentSource(metadata: Map<String, String> = emptyMap()) = ResolvedSource(
    url = MAGNET,
    sourceType = "torrent",
    totalBytes = 600,
    supportsResume = true,
    suggestedFileName = "Pack",
    maxSegments = 1,
    metadata = metadata,
    files = listOf(
      SourceFile("0", "Pack/Episode 2.mkv", 200, mapOf("path" to "Pack/Episode 2.mkv")),
      SourceFile("1", "Pack/notes.txt", 100, mapOf("path" to "Pack/notes.txt")),
      SourceFile("2", "Pack/Episode 10.mkv", 300, mapOf("path" to "Pack/Episode 10.mkv")),
    ),
  )

  private fun torrentRequest(selected: Set<String> = emptySet()) = DownloadRequest(
    url = MAGNET,
    selectedFileIds = selected,
    resolvedSource = torrentSource(),
  )

  private data class Paged(
    val taskId: String,
    val page: TorrentPageRequest,
    val order: TorrentFileOrder,
    val descending: Boolean,
  )

  /** Answers every page with one file, or refuses pages when not [supported]. */
  private class PagingController(private val supported: Boolean = true) : TorrentController {
    val calls = mutableListOf<Paged>()

    override suspend fun capabilities() = TorrentCapabilities()

    override suspend fun snapshot(taskId: String): TorrentSnapshot? = null

    override fun observe(taskId: String): Flow<TorrentSnapshot?> = emptyFlow()

    override suspend fun files(
      taskId: String,
      page: TorrentPageRequest,
      order: TorrentFileOrder,
      descending: Boolean,
    ): TorrentFilePage {
      if (!supported) {
        throw TorrentCommandException(TorrentCommandError.UNSUPPORTED, "Unavailable")
      }
      calls += Paged(taskId, page, order, descending)
      return TorrentFilePage(
        taskId = taskId,
        revision = TorrentRevision("epoch", 7),
        selectionGeneration = 4,
        totalFiles = 3,
        files = listOf(TorrentFileEntry("2", "Pack/Episode 10.mkv", 300, selected = false)),
        nextCursor = "next",
      )
    }
  }

  private suspend fun getDownload(task: DownloadTask): JsonObject {
    val result = KetchToolSet(RecordingKetchApi(listOf(task))).getDownload(task.taskId)
    return Json.parseToJsonElement(result).jsonObject
  }

  private class RecordingKetchApi(
    tasks: List<DownloadTask> = emptyList(),
    private val features: Set<String> = setOf(KetchFeatures.REQUEST_ID),
    override val torrents: TorrentController? = null,
    private val resolved: ResolvedSource? = null,
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
      return resolved ?: ResolvedSource(
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

    override suspend fun status(): KetchStatus = KetchStatus(
      name = "Ketch",
      version = "1.0.0",
      revision = "abc1234",
      uptime = 0,
      config = DownloadConfig(),
      system = SystemInfo(
        os = "TestOS",
        arch = "test",
        separator = "/",
        javaVersion = "21",
        availableProcessors = 1,
        maxMemory = 0,
        totalMemory = 0,
        freeMemory = 0,
        downloadDirectory = "/downloads",
        totalSpace = 0,
        freeSpace = 0,
        usableSpace = 0,
      ),
      features = features,
    )

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
    val selections = mutableListOf<Set<String>>()

    override suspend fun selectFiles(fileIds: Set<String>) {
      selections += fileIds
    }

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

private const val MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
