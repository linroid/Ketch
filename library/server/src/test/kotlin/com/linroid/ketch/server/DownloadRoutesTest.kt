package com.linroid.ketch.server

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.endpoints.model.ConnectionsRequest
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.PriorityRequest
import com.linroid.ketch.endpoints.model.SpeedLimitRequest
import com.linroid.ketch.endpoints.model.TaskSnapshot
import com.linroid.ketch.endpoints.model.TasksResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRoutesTest {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private fun createKetch(): KetchApi = createTestKetch()

  @Test
  fun `POST creates a download and returns 201`() =
    testApplication {
      val ketch = createKetch()
      application {
        val server = createTestServer(ketch = ketch)
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val response = client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          DownloadRequest(
            url = "https://example.com/file.zip",
            destination = Destination("/tmp/downloads/"),
          )
        )
      }
      assertEquals(HttpStatusCode.Created, response.status)
      val task = json.decodeFromString<TaskSnapshot>(
        response.bodyAsText()
      )
      assertEquals(
        "https://example.com/file.zip", task.request.url
      )
      assertEquals(
        "/tmp/downloads/",
        task.request.destination?.value,
      )
      // Execution may already have started by the time the route snapshots the task.
      assertTrue(task.taskId.isNotBlank())
      assertEquals(
        DownloadPriority.NORMAL, task.request.priority
      )
    }

  @Test
  fun `POST with custom priority and connections`() =
    testApplication {
      val ketch = createKetch()
      application {
        val server = createTestServer(ketch = ketch)
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val response = client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          DownloadRequest(
            url = "https://example.com/file.zip",
            destination = Destination("/tmp/downloads/"),
            connections = 4,
            priority = DownloadPriority.HIGH,
            speedLimit = SpeedLimit.of(1024000),
          )
        )
      }
      assertEquals(HttpStatusCode.Created, response.status)
      val task = json.decodeFromString<TaskSnapshot>(
        response.bodyAsText()
      )
      assertEquals(
        DownloadPriority.HIGH, task.request.priority
      )
      assertEquals(
        SpeedLimit.of(1024000),
        task.request.speedLimit,
      )
    }

  @Test
  fun `POST with invalid priority returns 400`() =
    testApplication {
      application {
        val server = createTestServer()
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val response = client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          """{"url":"https://example.com/file.zip","priority":"INVALID"}"""
        )
      }
      assertEquals(HttpStatusCode.BadRequest, response.status)
    }

  @Test
  fun `GET by ID returns created task`() = testApplication {
    val ketch = createKetch()
    application {
      val server = createTestServer(ketch = ketch)
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    // Create a task
    val createResponse = client.post("/api/tasks") {
      contentType(ContentType.Application.Json)
      setBody(
        DownloadRequest(
          url = "https://example.com/file.zip",
          destination = Destination("/tmp/downloads/"),
        )
      )
    }
    val created = json.decodeFromString<TaskSnapshot>(
      createResponse.bodyAsText()
    )

    // Fetch by ID
    val getResponse = client.get(
      "/api/tasks/${created.taskId}"
    )
    assertEquals(HttpStatusCode.OK, getResponse.status)
    val fetched = json.decodeFromString<TaskSnapshot>(
      getResponse.bodyAsText()
    )
    assertEquals(created.taskId, fetched.taskId)
    assertEquals(created.request.url, fetched.request.url)
  }

  @Test
  fun `GET list returns all created tasks`() =
    testApplication {
      val ketch = createKetch()
      application {
        val server = createTestServer(ketch = ketch)
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      // Create two tasks
      client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          DownloadRequest(
            url = "https://example.com/a.zip",
            destination = Destination("/tmp/"),
          )
        )
      }
      client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          DownloadRequest(
            url = "https://example.com/b.zip",
            destination = Destination("/tmp/"),
          )
        )
      }

      val listResponse = client.get("/api/tasks")
      assertEquals(HttpStatusCode.OK, listResponse.status)
      val taskList = json.decodeFromString<TasksResponse>(
        listResponse.bodyAsText()
      )
      assertEquals(2, taskList.tasks.size)
    }

  @Test
  fun `POST cancel on task returns response`() =
    testApplication {
      val ketch = createKetch()
      application {
        val server = createTestServer(ketch = ketch)
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val createResponse = client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          DownloadRequest(
            url = "https://example.com/file.zip",
            destination = Destination("/tmp/"),
          )
        )
      }
      val created = json.decodeFromString<TaskSnapshot>(
        createResponse.bodyAsText()
      )

      val cancelResponse = client.post(
        "/api/tasks/${created.taskId}/cancel"
      )
      assertEquals(HttpStatusCode.OK, cancelResponse.status)
      val result = json.decodeFromString<TaskSnapshot>(
        cancelResponse.bodyAsText()
      )
      // Task reaches a terminal state (canceled or failed,
      // depending on timing with the NoOpHttpEngine)
      assertTrue(
        result.state.isTerminal,
        "Expected terminal state, got: ${result.state}"
      )
    }

  @Test
  fun `DELETE removes task`() = testApplication {
    val ketch = createKetch()
    application {
      val server = createTestServer(ketch = ketch)
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    val createResponse = client.post("/api/tasks") {
      contentType(ContentType.Application.Json)
      setBody(
        DownloadRequest(
          url = "https://example.com/file.zip",
          destination = Destination("/tmp/"),
        )
      )
    }
    val created = json.decodeFromString<TaskSnapshot>(
      createResponse.bodyAsText()
    )

    val deleteResponse = client.delete(
      "/api/tasks/${created.taskId}"
    )
    assertEquals(
      HttpStatusCode.NoContent, deleteResponse.status
    )

    // Verify it's gone
    val getResponse = client.get(
      "/api/tasks/${created.taskId}"
    )
    assertEquals(HttpStatusCode.NotFound, getResponse.status)
  }

  @Test
  fun `DELETE with deleteFiles=true forwards flag to task`() = testApplication {
    val ketch = createKetch()
    val recording = RecordingKetchApi(ketch)
    application {
      val server = createTestServer(ketch = recording)
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    val created = json.decodeFromString<TaskSnapshot>(
      client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          DownloadRequest(
            url = "https://example.com/file.zip",
            destination = Destination("/tmp/"),
          )
        )
      }.bodyAsText()
    )

    val response = client.delete(
      "/api/tasks/${created.taskId}?deleteFiles=true"
    )
    assertEquals(HttpStatusCode.NoContent, response.status)
    assertEquals(listOf(true), recording.removeCalls)
  }

  @Test
  fun `DELETE without deleteFiles defaults to false`() = testApplication {
    val ketch = createKetch()
    val recording = RecordingKetchApi(ketch)
    application {
      val server = createTestServer(ketch = recording)
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    val created = json.decodeFromString<TaskSnapshot>(
      client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          DownloadRequest(
            url = "https://example.com/file.zip",
            destination = Destination("/tmp/"),
          )
        )
      }.bodyAsText()
    )

    val response = client.delete("/api/tasks/${created.taskId}")
    assertEquals(HttpStatusCode.NoContent, response.status)
    assertEquals(listOf(false), recording.removeCalls)
  }

  @Test
  fun `DELETE with invalid deleteFiles value defaults to false`() = testApplication {
    val ketch = createKetch()
    val recording = RecordingKetchApi(ketch)
    application {
      val server = createTestServer(ketch = recording)
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    val created = json.decodeFromString<TaskSnapshot>(
      client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          DownloadRequest(
            url = "https://example.com/file.zip",
            destination = Destination("/tmp/"),
          )
        )
      }.bodyAsText()
    )

    val response = client.delete(
      "/api/tasks/${created.taskId}?deleteFiles=banana"
    )
    assertEquals(HttpStatusCode.NoContent, response.status)
    assertEquals(listOf(false), recording.removeCalls)
  }

  @Test
  fun `pause on nonexistent task returns 404`() =
    testApplication {
      application {
        val server = createTestServer()
        with(server) { configureServer() }
      }
      val response = client.post(
        "/api/tasks/nonexistent/pause"
      )
      assertEquals(HttpStatusCode.NotFound, response.status)
    }

  @Test
  fun `resume on nonexistent task returns 404`() =
    testApplication {
      application {
        val server = createTestServer()
        with(server) { configureServer() }
      }
      val response = client.post(
        "/api/tasks/nonexistent/resume"
      )
      assertEquals(HttpStatusCode.NotFound, response.status)
    }

  @Test
  fun `cancel on nonexistent task returns 404`() =
    testApplication {
      application {
        val server = createTestServer()
        with(server) { configureServer() }
      }
      val response = client.post(
        "/api/tasks/nonexistent/cancel"
      )
      assertEquals(HttpStatusCode.NotFound, response.status)
    }

  @Test
  fun `delete on nonexistent task returns 404`() =
    testApplication {
      application {
        val server = createTestServer()
        with(server) { configureServer() }
      }
      val response = client.delete(
        "/api/tasks/nonexistent"
      )
      assertEquals(HttpStatusCode.NotFound, response.status)
    }

  @Test
  fun `PUT speed-limit on nonexistent task returns 404`() =
    testApplication {
      application {
        val server = createTestServer()
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val response = client.put(
        "/api/tasks/nonexistent/speed-limit"
      ) {
        contentType(ContentType.Application.Json)
        setBody(
          SpeedLimitRequest(limit = SpeedLimit.of(1024))
        )
      }
      assertEquals(HttpStatusCode.NotFound, response.status)
    }

  @Test
  fun `PUT priority on nonexistent task returns 404`() =
    testApplication {
      application {
        val server = createTestServer()
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val response = client.put(
        "/api/tasks/nonexistent/priority"
      ) {
        contentType(ContentType.Application.Json)
        setBody(
          PriorityRequest(priority = DownloadPriority.HIGH)
        )
      }
      assertEquals(HttpStatusCode.NotFound, response.status)
    }

  @Test
  fun `PUT priority returns and retains updated request`() =
    testApplication {
      val ketch = createKetch()
      application {
        val server = createTestServer(ketch = ketch)
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val createResponse = client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          DownloadRequest(
            url = "https://example.com/file.zip",
            destination = Destination("/tmp/"),
          )
        )
      }
      val created = json.decodeFromString<TaskSnapshot>(
        createResponse.bodyAsText()
      )

      val response = client.put(
        "/api/tasks/${created.taskId}/priority"
      ) {
        contentType(ContentType.Application.Json)
        setBody(PriorityRequest(priority = DownloadPriority.HIGH))
      }
      assertEquals(HttpStatusCode.OK, response.status)
      val updated = json.decodeFromString<TaskSnapshot>(response.bodyAsText())
      assertEquals(DownloadPriority.HIGH, updated.request.priority)
      val fetched = client.get("/api/tasks/${created.taskId}")
      val snapshot = json.decodeFromString<TaskSnapshot>(fetched.bodyAsText())
      assertEquals(DownloadPriority.HIGH, snapshot.request.priority)
    }

  @Test
  fun `PUT priority with invalid value returns 400`() =
    testApplication {
      val ketch = createKetch()
      application {
        val server = createTestServer(ketch = ketch)
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val createResponse = client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          DownloadRequest(
            url = "https://example.com/file.zip",
            destination = Destination("/tmp/"),
          )
        )
      }
      val created = json.decodeFromString<TaskSnapshot>(
        createResponse.bodyAsText()
      )

      val response = client.put(
        "/api/tasks/${created.taskId}/priority"
      ) {
        contentType(ContentType.Application.Json)
        setBody("""{"priority":"BOGUS"}""")
      }
      assertEquals(HttpStatusCode.BadRequest, response.status)
    }

  @Test
  fun `POST with destination preserves it`() = testApplication {
    val ketch = createKetch()
    application {
      val server = createTestServer(ketch = ketch)
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    val response = client.post("/api/tasks") {
      contentType(ContentType.Application.Json)
      setBody(
        DownloadRequest(
          url = "https://example.com/file.zip",
          destination = Destination("custom.zip"),
        )
      )
    }
    assertEquals(HttpStatusCode.Created, response.status)
    val task = json.decodeFromString<TaskSnapshot>(
      response.bodyAsText()
    )
    assertEquals(
      "custom.zip", task.request.destination?.value
    )
  }

  @Test
  fun putConnections_zero_setsAuto() = testApplication {
    val ketch = createKetch()
    application {
      val server = createTestServer(ketch = ketch)
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    val created = createTask(client, connections = 4)

    val response = client.put("/api/tasks/${created.taskId}/connections") {
      contentType(ContentType.Application.Json)
      setBody(ConnectionsRequest(connections = 0))
    }
    assertEquals(HttpStatusCode.OK, response.status)
    val updated = json.decodeFromString<TaskSnapshot>(response.bodyAsText())
    assertEquals(0, updated.request.connections)
    assertEquals(0, ketch.tasks.value.single().request.connections)
  }

  @Test
  fun putConnections_negative_rejected() = testApplication {
    val ketch = createKetch()
    application {
      val server = createTestServer(ketch = ketch)
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    val created = createTask(client, connections = 4)

    val response = client.put("/api/tasks/${created.taskId}/connections") {
      contentType(ContentType.Application.Json)
      setBody(ConnectionsRequest(connections = -1))
    }
    assertEquals(HttpStatusCode.BadRequest, response.status)
    val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
    assertEquals("invalid_connections", error.error)
    assertEquals(4, ketch.tasks.value.single().request.connections)
  }

  @Test
  fun getTask_queuedBehindAnother_includesQueuePosition() = testApplication {
    // The first download holds the only slot: its HEAD request never answers.
    val ketch = Ketch(
      httpEngine = HangingHttpEngine(),
      config = DownloadConfig(maxConcurrentDownloads = 1),
    )
    application {
      val server = createTestServer(ketch = ketch)
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    try {
      val running = createTask(client, url = "https://example.com/first.zip")
      val waiting = createTask(client, url = "https://example.com/second.zip")

      val runningSnapshot = json.decodeFromString<TaskSnapshot>(
        client.get("/api/tasks/${running.taskId}").bodyAsText(),
      )
      assertNull(runningSnapshot.queuePosition)
      val response = client.get("/api/tasks/${waiting.taskId}")
      assertEquals(HttpStatusCode.OK, response.status)
      val snapshot = json.decodeFromString<TaskSnapshot>(response.bodyAsText())
      assertEquals(DownloadState.Queued, snapshot.state)
      assertEquals(1, snapshot.queuePosition)
    } finally {
      ketch.close()
    }
  }

  private suspend fun createTask(
    client: HttpClient,
    url: String = "https://example.com/file.zip",
    connections: Int = 0,
  ): TaskSnapshot {
    val response = client.post("/api/tasks") {
      contentType(ContentType.Application.Json)
      setBody(
        DownloadRequest(
          url = url,
          destination = Destination("/tmp/"),
          connections = connections,
        ),
      )
    }
    assertEquals(HttpStatusCode.Created, response.status)
    return json.decodeFromString<TaskSnapshot>(response.bodyAsText())
  }

  private class HangingHttpEngine : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      awaitCancellation()

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ): Unit = awaitCancellation()

    override fun close() {}
  }
}
