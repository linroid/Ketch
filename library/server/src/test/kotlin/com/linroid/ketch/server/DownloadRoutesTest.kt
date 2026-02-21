package com.linroid.ketch.server

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.PriorityRequest
import com.linroid.ketch.endpoints.model.SpeedLimitRequest
import com.linroid.ketch.endpoints.model.TaskSnapshot
import com.linroid.ketch.endpoints.model.TasksResponse
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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
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
      assertEquals(DownloadState.Pending, task.state)
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
}
