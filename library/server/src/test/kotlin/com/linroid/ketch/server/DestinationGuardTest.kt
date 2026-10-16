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

  // The first submission's download may have created the file by the time the request is sent
  // again, which would otherwise give it another name and make the two requests differ.
  @Test
  fun `POST without a token sent again with its request ID keeps the first destination`() =
    testApplication {
      File(downloads, "file.zip").writeText("keep")
      val ketch = createTestKetch(DownloadConfig(defaultDirectory = downloads.path))
      val client = serve(ketch)
      val id = "6f1c2b0e-8a1d-4c5e-9b7a-3d2f1e0c9b8a"

      val first = client.createTask("${downloads.path}/file.zip", id)
      File(downloads, "file (1).zip").writeText("partial")
      val again = client.createTask("${downloads.path}/file.zip", id)

      assertEquals(HttpStatusCode.Created, again.status, again.bodyAsText())
      val firstTask = json.decodeFromString<TaskSnapshot>(first.bodyAsText())
      val againTask = json.decodeFromString<TaskSnapshot>(again.bodyAsText())
      assertEquals(firstTask.taskId, againTask.taskId)
      assertEquals(File(downloads, "file (1).zip").path, againTask.request.destination?.value)
      assertEquals(1, ketch.tasks.value.size)
    }

  @Test
  fun `POST without a token reusing a request ID for another file is refused`() =
    testApplication {
      val ketch = createTestKetch(DownloadConfig(defaultDirectory = downloads.path))
      val client = serve(ketch)
      val id = "6f1c2b0e-8a1d-4c5e-9b7a-3d2f1e0c9b8a"

      client.createTask("${downloads.path}/file.zip", id)
      val other = client.createTask("${downloads.path}/other.zip", id)

      assertEquals(HttpStatusCode.BadRequest, other.status)
      assertEquals(1, ketch.tasks.value.size)
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
  fun `POST with a token never names an existing file`() = testApplication {
    File(outside, "file.zip").writeText("keep")
    val client = serve(
      createTestKetch(DownloadConfig(defaultDirectory = downloads.path)),
      apiToken = TOKEN,
    )

    val response = client.createTask("${outside.path}/file.zip")

    assertEquals(HttpStatusCode.Created, response.status)
    val task = json.decodeFromString<TaskSnapshot>(response.bodyAsText())
    assertEquals(File(outside, "file (1).zip").path, task.request.destination?.value)
  }

  @Test
  fun `POST with a token into a protected folder is refused`() = testApplication {
    val appData = File(temp, "Library").apply { mkdirs() }
    val ketch = createTestKetch(DownloadConfig(defaultDirectory = downloads.path))
    val client = serve(ketch, apiToken = TOKEN, protectedDirectories = listOf(appData.path))

    assertRejected(client.createTask("${appData.path}/LaunchAgents/evil.plist"))
    assertRejected(client.createTask("${temp.path}/library/LaunchAgents/evil.plist"))
    assertTrue(ketch.tasks.value.isEmpty())
  }

  @Test
  fun `POST with a token into a hidden folder is refused`() = testApplication {
    val ketch = createTestKetch(DownloadConfig(defaultDirectory = downloads.path))
    val client = serve(ketch, apiToken = TOKEN)

    assertRejected(client.createTask("${outside.path}/.ssh/authorized_keys"))
    assertRejected(client.createTask("${outside.path}/.zshrc"))
    assertTrue(ketch.tasks.value.isEmpty())
  }

  @Test
  fun `POST with a token into a download folder inside a protected one is accepted`() =
    testApplication {
      val appData = File(temp, "app-data")
      val inside = File(appData, "downloads").apply { mkdirs() }
      val client = serve(
        createTestKetch(DownloadConfig(defaultDirectory = inside.path)),
        apiToken = TOKEN,
        protectedDirectories = listOf(appData.path),
      )

      assertEquals(HttpStatusCode.Created, client.createTask("file.zip").status)
      assertRejected(client.createTask("${appData.path}/file.zip"))
    }

  @Test
  fun `DELETE with a token and deleteFiles of a task in a protected folder is refused`() =
    testApplication {
      val appData = File(temp, "app-data").apply { mkdirs() }
      val task = SavedTask(File(appData, "file.zip").path)
      val client = serve(
        OneTaskKetch(task, downloads),
        apiToken = TOKEN,
        protectedDirectories = listOf(appData.path),
      )

      assertRejected(client.delete("/api/tasks/${task.taskId}?deleteFiles=true"))
      assertTrue(task.removals.isEmpty())
    }

  @Test
  fun `POST with a token to a granted folder URI passes unchanged`() = testApplication {
    val client = serve(
      createTestKetch(DownloadConfig(defaultDirectory = downloads.path)),
      apiToken = TOKEN,
    )
    val uri = "content://com.android.externalstorage.documents/tree/primary%3AMovies"

    val response = client.createTask(uri)

    assertEquals(HttpStatusCode.Created, response.status)
    val task = json.decodeFromString<TaskSnapshot>(response.bodyAsText())
    assertEquals(uri, task.request.destination?.value)
  }

  @Test
  fun `POST with a token to a file URI is refused`() = testApplication {
    val ketch = createTestKetch(DownloadConfig(defaultDirectory = downloads.path))
    val client = serve(ketch, apiToken = TOKEN)

    assertRejected(client.createTask("file://${outside.path}/.zshrc"))
    assertRejected(client.createTask("FILE://${outside.path}/file.zip"))
    assertTrue(ketch.tasks.value.isEmpty())
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
    protectedDirectories: List<String> = emptyList(),
  ): HttpClient {
    application {
      val server = KetchServer(
        ketch,
        apiToken = apiToken,
        allowedDirectories = allowedDirectories,
        protectedDirectories = protectedDirectories,
      )
      with(server) { configureServer() }
    }
    return createClient {
      install(ContentNegotiation) { json(json) }
      if (apiToken != null) defaultRequest { bearerAuth(apiToken) }
    }
  }

  private suspend fun HttpClient.createTask(
    destination: String?,
    requestId: String? = null,
  ): HttpResponse =
    post("/api/tasks") {
      contentType(ContentType.Application.Json)
      setBody(
        DownloadRequest(
          url = "https://example.com/file.zip",
          destination = destination?.let(::Destination),
          requestId = requestId,
        )
      )
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
