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
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.TaskSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class DestinationGuardTest {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
  private val temp = Files.createTempDirectory("ketch-guard").toFile().canonicalFile
  private val downloads = File(temp, "downloads").apply { mkdirs() }
  private val media = File(temp, "media").apply { mkdirs() }
  private val outside = File(temp, "outside").apply { mkdirs() }

  @AfterTest
  fun cleanup() {
    temp.deleteRecursively()
  }

  @Test
  fun `POST without a token outside the download folder is refused`() = testApplication {
    val ketch = createTestKetch(DownloadConfig(defaultDirectory = downloads.path))
    val client = serve(ketch)

    val response = client.createTask("${outside.path}/")

    assertRejected(response)
    assertTrue(ketch.tasks.value.isEmpty())
  }

  @Test
  fun `POST without a token saves a relative destination in the download folder`() =
    testApplication {
      val client = serve(createTestKetch(DownloadConfig(defaultDirectory = downloads.path)))

      val response = client.createTask("movies/../shows/")

      assertEquals(HttpStatusCode.Created, response.status)
      val task = json.decodeFromString<TaskSnapshot>(response.bodyAsText())
      assertEquals("${File(downloads, "shows").path}/", task.request.destination?.value)
    }

  @Test
  fun `POST without a token never names an existing file`() = testApplication {
    File(downloads, "file.zip").writeText("keep")
    val client = serve(createTestKetch(DownloadConfig(defaultDirectory = downloads.path)))

    val response = client.createTask("${downloads.path}/file.zip")

    assertEquals(HttpStatusCode.Created, response.status)
    val task = json.decodeFromString<TaskSnapshot>(response.bodyAsText())
    assertEquals(File(downloads, "file (1).zip").path, task.request.destination?.value)
  }

  @Test
  fun `POST with a token saves wherever the caller chooses`() = testApplication {
    val client = serve(
      createTestKetch(DownloadConfig(defaultDirectory = downloads.path)),
      apiToken = TOKEN,
    )

    val response = client.createTask("${outside.path}/")

    assertEquals(HttpStatusCode.Created, response.status)
    val task = json.decodeFromString<TaskSnapshot>(response.bodyAsText())
    assertEquals("${outside.path}/", task.request.destination?.value)
  }

  @Test
  fun `POST with a token and allowed folders keeps callers to them`() = testApplication {
    val client = serve(
      createTestKetch(DownloadConfig(defaultDirectory = downloads.path)),
      apiToken = TOKEN,
      allowedDirectories = listOf(media.path),
    )

    assertRejected(client.createTask("${outside.path}/"))
    assertEquals(HttpStatusCode.Created, client.createTask("${media.path}/").status)
  }

  @Test
  fun `resume without a token outside the folders is refused`() = testApplication {
    val client = serve(createTestKetch(DownloadConfig(defaultDirectory = downloads.path)))
    val created = json.decodeFromString<TaskSnapshot>(client.createTask(null).bodyAsText())

    for (destination in listOf("${outside.path}/file.zip", "file.zip")) {
      val response = client.post("/api/tasks/${created.taskId}/resume") {
        parameter("destination", destination)
      }

      assertRejected(response)
    }
  }

  @Test
  fun `PUT config moving the download folder outside is refused`() = testApplication {
    val ketch = createTestKetch(DownloadConfig(defaultDirectory = downloads.path))
    val client = serve(ketch)

    val response = client.put("/api/config") {
      contentType(ContentType.Application.Json)
      setBody(DownloadConfig(defaultDirectory = outside.path))
    }

    assertRejected(response)
    assertEquals(downloads.path, ketch.status().config.defaultDirectory)
  }

  @Test
  fun `PUT config moving the download folder to an allowed folder applies`() =
    testApplication {
      val ketch = createTestKetch(DownloadConfig(defaultDirectory = downloads.path))
      val client = serve(ketch, allowedDirectories = listOf(media.path))

      val response = client.put("/api/config") {
        contentType(ContentType.Application.Json)
        setBody(DownloadConfig(defaultDirectory = media.path))
      }

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(media.path, ketch.status().config.defaultDirectory)
    }

  @Test
  fun `DELETE with deleteFiles of a task saved outside the folders is refused`() =
    testApplication {
      val task = SavedTask("${outside.path}/file.zip")
      val client = serve(OneTaskKetch(task, downloads))

      assertRejected(client.delete("/api/tasks/${task.taskId}?deleteFiles=true"))
      assertEquals(emptyList(), task.removals)

      val kept = client.delete("/api/tasks/${task.taskId}")
      assertEquals(HttpStatusCode.NoContent, kept.status)
      assertEquals(listOf(false), task.removals)
    }

  @Test
  fun `DELETE with deleteFiles of a task saved in the download folder deletes them`() =
    testApplication {
      val task = SavedTask("${downloads.path}/file.zip")
      val client = serve(OneTaskKetch(task, downloads))

      val response = client.delete("/api/tasks/${task.taskId}?deleteFiles=true")

      assertEquals(HttpStatusCode.NoContent, response.status)
      assertEquals(listOf(true), task.removals)
    }

  private fun ApplicationTestBuilder.serve(
    ketch: KetchApi,
    apiToken: String? = null,
    allowedDirectories: List<String> = emptyList(),
  ): HttpClient {
    application {
      val server = KetchServer(ketch, apiToken = apiToken, allowedDirectories = allowedDirectories)
      with(server) { configureServer() }
    }
    return createClient {
      install(ContentNegotiation) { json(json) }
      if (apiToken != null) defaultRequest { bearerAuth(apiToken) }
    }
  }

  private suspend fun HttpClient.createTask(destination: String?): HttpResponse =
    post("/api/tasks") {
      contentType(ContentType.Application.Json)
      setBody(DownloadRequest("https://example.com/file.zip", destination?.let(::Destination)))
    }

  private suspend fun assertRejected(response: HttpResponse) {
    assertEquals(HttpStatusCode.Forbidden, response.status)
    assertEquals("path_rejected", json.decodeFromString<ErrorResponse>(response.bodyAsText()).error)
  }

  /** A real test instance, for its status and download directory, that lists only [task]. */
  private class OneTaskKetch(
    task: DownloadTask,
    downloads: File,
    delegate: KetchApi = createTestKetch(DownloadConfig(defaultDirectory = downloads.path)),
  ) : KetchApi by delegate {
    override val tasks: StateFlow<List<DownloadTask>> = MutableStateFlow(listOf(task))
  }

  /** A queued task whose files are at [outputPath], recording how it is removed. */
  private class SavedTask(override val outputPath: String) : DownloadTask {
    val removals = mutableListOf<Boolean>()
    override val taskId = "saved"
    override val requestState = MutableStateFlow(DownloadRequest("https://example.com/file"))
    override val request get() = requestState.value
    override val createdAt = Instant.fromEpochMilliseconds(0)
    override val state = MutableStateFlow<DownloadState>(DownloadState.Queued)
    override val segments = MutableStateFlow<List<Segment>>(emptyList())
    override suspend fun remove(deleteFiles: Boolean) {
      removals += deleteFiles
    }
    override suspend fun pause() = error("Unexpected call")
    override suspend fun resume(destination: Destination?) = error("Unexpected call")
    override suspend fun cancel() = error("Unexpected call")
    override suspend fun setSpeedLimit(limit: SpeedLimit) = error("Unexpected call")
    override suspend fun setPriority(priority: DownloadPriority) = error("Unexpected call")
    override suspend fun setConnections(connections: Int) = error("Unexpected call")
    override suspend fun reschedule(
      schedule: DownloadSchedule,
      conditions: List<DownloadCondition>,
    ) = error("Unexpected call")
  }

  private companion object {
    const val TOKEN = "secret-token"
  }
}
