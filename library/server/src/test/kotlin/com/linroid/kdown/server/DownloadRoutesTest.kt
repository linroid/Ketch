package com.linroid.kdown.server

import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.endpoints.model.CreateDownloadRequest
import com.linroid.kdown.endpoints.model.ErrorResponse
import com.linroid.kdown.endpoints.model.PriorityRequest
import com.linroid.kdown.endpoints.model.SpeedLimitRequest
import com.linroid.kdown.endpoints.model.TaskResponse
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

  private fun createKDown(): KDownApi = createTestKDown()

  @Test
  fun `POST creates a download and returns 201`() =
    testApplication {
      val kdown = createKDown()
      application {
        val server = createTestServer(kdown = kdown)
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val response = client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          CreateDownloadRequest(
            url = "https://example.com/file.zip",
            directory = "/tmp/downloads",
          )
        )
      }
      assertEquals(HttpStatusCode.Created, response.status)
      val task = json.decodeFromString<TaskResponse>(
        response.bodyAsText()
      )
      assertEquals(
        "https://example.com/file.zip", task.url
      )
      assertEquals("/tmp/downloads", task.directory)
      assertEquals("pending", task.state)
      assertEquals("NORMAL", task.priority)
    }

  @Test
  fun `POST with custom priority and connections`() =
    testApplication {
      val kdown = createKDown()
      application {
        val server = createTestServer(kdown = kdown)
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val response = client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          CreateDownloadRequest(
            url = "https://example.com/file.zip",
            directory = "/tmp/downloads",
            connections = 4,
            priority = "HIGH",
            speedLimitBytesPerSecond = 1024000,
          )
        )
      }
      assertEquals(HttpStatusCode.Created, response.status)
      val task = json.decodeFromString<TaskResponse>(
        response.bodyAsText()
      )
      assertEquals("HIGH", task.priority)
      assertEquals(1024000L, task.speedLimitBytesPerSecond)
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
          CreateDownloadRequest(
            url = "https://example.com/file.zip",
            directory = "/tmp/downloads",
            priority = "INVALID",
          )
        )
      }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val error = json.decodeFromString<ErrorResponse>(
        response.bodyAsText()
      )
      assertEquals("invalid_priority", error.error)
    }

  @Test
  fun `GET by ID returns created task`() = testApplication {
    val kdown = createKDown()
    application {
      val server = createTestServer(kdown = kdown)
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    // Create a task
    val createResponse = client.post("/api/tasks") {
      contentType(ContentType.Application.Json)
      setBody(
        CreateDownloadRequest(
          url = "https://example.com/file.zip",
          directory = "/tmp/downloads",
        )
      )
    }
    val created = json.decodeFromString<TaskResponse>(
      createResponse.bodyAsText()
    )

    // Fetch by ID
    val getResponse = client.get(
      "/api/tasks/${created.taskId}"
    )
    assertEquals(HttpStatusCode.OK, getResponse.status)
    val fetched = json.decodeFromString<TaskResponse>(
      getResponse.bodyAsText()
    )
    assertEquals(created.taskId, fetched.taskId)
    assertEquals(created.url, fetched.url)
  }

  @Test
  fun `GET list returns all created tasks`() =
    testApplication {
      val kdown = createKDown()
      application {
        val server = createTestServer(kdown = kdown)
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      // Create two tasks
      client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          CreateDownloadRequest(
            url = "https://example.com/a.zip",
            directory = "/tmp",
          )
        )
      }
      client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          CreateDownloadRequest(
            url = "https://example.com/b.zip",
            directory = "/tmp",
          )
        )
      }

      val listResponse = client.get("/api/tasks")
      assertEquals(HttpStatusCode.OK, listResponse.status)
      val tasks = json.decodeFromString<List<TaskResponse>>(
        listResponse.bodyAsText()
      )
      assertEquals(2, tasks.size)
    }

  @Test
  fun `POST cancel on task returns response`() =
    testApplication {
      val kdown = createKDown()
      application {
        val server = createTestServer(kdown = kdown)
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val createResponse = client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          CreateDownloadRequest(
            url = "https://example.com/file.zip",
            directory = "/tmp",
          )
        )
      }
      val created = json.decodeFromString<TaskResponse>(
        createResponse.bodyAsText()
      )

      val cancelResponse = client.post(
        "/api/tasks/${created.taskId}/cancel"
      )
      assertEquals(HttpStatusCode.OK, cancelResponse.status)
      val result = json.decodeFromString<TaskResponse>(
        cancelResponse.bodyAsText()
      )
      // Task reaches a terminal state (canceled or failed,
      // depending on timing with the NoOpHttpEngine)
      assertTrue(
        result.state == "canceled" ||
          result.state == "failed",
        "Expected terminal state, got: ${result.state}"
      )
    }

  @Test
  fun `DELETE removes task`() = testApplication {
    val kdown = createKDown()
    application {
      val server = createTestServer(kdown = kdown)
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    val createResponse = client.post("/api/tasks") {
      contentType(ContentType.Application.Json)
      setBody(
        CreateDownloadRequest(
          url = "https://example.com/file.zip",
          directory = "/tmp",
        )
      )
    }
    val created = json.decodeFromString<TaskResponse>(
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
        setBody(SpeedLimitRequest(bytesPerSecond = 1024))
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
        setBody(PriorityRequest(priority = "HIGH"))
      }
      assertEquals(HttpStatusCode.NotFound, response.status)
    }

  @Test
  fun `PUT priority with invalid value returns 400`() =
    testApplication {
      val kdown = createKDown()
      application {
        val server = createTestServer(kdown = kdown)
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val createResponse = client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          CreateDownloadRequest(
            url = "https://example.com/file.zip",
            directory = "/tmp",
          )
        )
      }
      val created = json.decodeFromString<TaskResponse>(
        createResponse.bodyAsText()
      )

      val response = client.put(
        "/api/tasks/${created.taskId}/priority"
      ) {
        contentType(ContentType.Application.Json)
        setBody(PriorityRequest(priority = "BOGUS"))
      }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val error = json.decodeFromString<ErrorResponse>(
        response.bodyAsText()
      )
      assertEquals("invalid_priority", error.error)
    }

  @Test
  fun `POST with fileName preserves it`() = testApplication {
    val kdown = createKDown()
    application {
      val server = createTestServer(kdown = kdown)
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    val response = client.post("/api/tasks") {
      contentType(ContentType.Application.Json)
      setBody(
        CreateDownloadRequest(
          url = "https://example.com/file.zip",
          directory = "/tmp",
          fileName = "custom.zip",
        )
      )
    }
    assertEquals(HttpStatusCode.Created, response.status)
    val task = json.decodeFromString<TaskResponse>(
      response.bodyAsText()
    )
    assertEquals("custom.zip", task.fileName)
  }
}
