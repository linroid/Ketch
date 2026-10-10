package com.linroid.ketch.server

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SelectionPlan
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.FileSelectionRequest
import com.linroid.ketch.endpoints.model.TaskSnapshot
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** `PUT /api/tasks/{id}/files` over a [Ketch] whose source has files to choose. */
class FileSelectionRoutesTest {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  /**
   * A source of two files ("0" of 10 bytes, "1" of 20) that downloads until it is stopped;
   * `files:unknown` never finds its file list.
   */
  private class FilesSource : DownloadSource {
    override val type = "files"
    override val managesOwnFileIo = true
    private val ids = setOf("0", "1")
    private val sizes = mapOf("0" to 10L, "1" to 20L)

    override fun canHandle(url: String) = url.startsWith("files:")

    override suspend fun resolve(url: String, properties: Map<String, String>) = ResolvedSource(
      url = url, sourceType = type, totalBytes = 30, supportsResume = true,
      suggestedFileName = "pack", maxSegments = 2,
      files = listOf(SourceFile("0", "a.bin", 10), SourceFile("1", "b.bin", 20)),
      selectionMode = FileSelectionMode.MULTIPLE,
    )

    override suspend fun resolveForDownload(
      url: String,
      properties: Map<String, String>,
      config: DownloadConfig,
    ): ResolvedSource {
      if (url == "files:unknown") awaitCancellation()
      return resolve(url, properties)
    }

    override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long) =
      SourceResumeState(type, resolved.url)

    override suspend fun planSelection(request: SelectionRequest): SelectionPlan {
      check(request.resumeState != null || request.resolved != null) {
        "The file list is not known yet"
      }
      val chosen = request.fileIds ?: ids
      require(chosen.all { it in ids }) { "Unknown file id" }
      val effective = request.current.ifEmpty { ids }
      return SelectionPlan(
        fileIds = chosen,
        totalBytes = chosen.sumOf { sizes.getValue(it) },
        changed = chosen != effective,
        expands = (chosen - effective).isNotEmpty(),
        segments = null,
      )
    }

    override suspend fun download(context: DownloadContext) {
      context.onProgress(0, 30)
      awaitCancellation()
    }

    override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) =
      download(context)
  }

  private fun ketch(): KetchApi =
    Ketch(httpEngine = NoOpHttpEngine(), additionalSources = listOf(FilesSource()))

  private suspend fun KetchApi.started(url: String, vararg ids: String): String {
    val task = download(
      DownloadRequest(
        url,
        destination = Destination("/tmp/ketch-files/"),
        selectedFileIds = ids.toSet(),
      )
    )
    return task.taskId
  }

  private suspend fun KetchApi.awaitState(taskId: String, done: (DownloadState) -> Boolean) {
    withTimeout(10.seconds) {
      while (!done(tasks.value.single { it.taskId == taskId }.state.value)) delay(10)
    }
  }

  private suspend fun ApplicationTestBuilder.putFiles(
    taskId: String,
    ids: Set<String>,
  ): HttpResponse = client.put("/api/tasks/$taskId/files") {
    contentType(ContentType.Application.Json)
    setBody(json.encodeToString(FileSelectionRequest(ids)))
  }

  private suspend fun HttpResponse.error(): ErrorResponse = json.decodeFromString(bodyAsText())

  @Test
  fun putFiles_changesSelectionAndReturnsSnapshot() = testApplication {
    val ketch = ketch()
    application { with(createTestServer(ketch)) { configureServer() } }
    try {
      val taskId = ketch.started("files:pack", "0")
      ketch.awaitState(taskId) { it is DownloadState.Downloading }

      val response = putFiles(taskId, setOf("0", "1"))

      assertEquals(HttpStatusCode.OK, response.status)
      val snapshot = json.decodeFromString<TaskSnapshot>(response.bodyAsText())
      assertEquals(setOf("0", "1"), snapshot.request.selectedFileIds)
      assertEquals(setOf("0", "1"), ketch.tasks.value.single().request.selectedFileIds)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun putFiles_unknownId_answersInvalidSelection() = testApplication {
    val ketch = ketch()
    application { with(createTestServer(ketch)) { configureServer() } }
    try {
      val taskId = ketch.started("files:pack", "0")
      ketch.awaitState(taskId) { it is DownloadState.Downloading }

      val response = putFiles(taskId, setOf("0", "9"))

      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertEquals("invalid_selection", response.error().error)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun putFiles_metadataUnknown_answersConflict() = testApplication {
    val ketch = ketch()
    application { with(createTestServer(ketch)) { configureServer() } }
    try {
      val taskId = ketch.started("files:unknown")
      ketch.awaitState(taskId) { it == DownloadState.Queued }

      val response = putFiles(taskId, setOf("0"))

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("selection_unavailable", response.error().error)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun putFiles_httpTask_answersUnsupported() = testApplication {
    val ketch = ketch()
    application { with(createTestServer(ketch)) { configureServer() } }
    try {
      val taskId = ketch.started("https://example.com/file.zip")

      val response = putFiles(taskId, setOf("0"))

      assertEquals(HttpStatusCode.NotImplemented, response.status)
      assertEquals("unsupported", response.error().error)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun putFiles_tooManyIds_answersBadRequest() = testApplication {
    val ketch = ketch()
    application { with(createTestServer(ketch)) { configureServer() } }
    try {
      val taskId = ketch.started("files:pack", "0")

      val tooMany = putFiles(taskId, (0..100_000).mapTo(HashSet()) { it.toString(36) })
      val tooLong = putFiles(taskId, setOf("x".repeat(129)))
      val none = putFiles(taskId, emptySet())
      val missing = putFiles("missing", setOf("0"))

      for (response in listOf(tooMany, tooLong, none)) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_selection", response.error().error)
      }
      assertEquals(HttpStatusCode.NotFound, missing.status)
      assertEquals(setOf("0"), ketch.tasks.value.single().request.selectedFileIds)
    } finally {
      ketch.close()
    }
  }
}
