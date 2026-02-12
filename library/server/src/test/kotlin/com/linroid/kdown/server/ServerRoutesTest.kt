package com.linroid.kdown.server

import com.linroid.kdown.endpoints.model.CreateDownloadRequest
import com.linroid.kdown.endpoints.model.ServerStatus
import com.linroid.kdown.endpoints.model.SpeedLimitRequest
import com.linroid.kdown.endpoints.model.TaskResponse
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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

class ServerRoutesTest {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  @Test
  fun `status includes version`() = testApplication {
    application {
      val server = createTestServer()
      with(server) { configureServer() }
    }
    val response = client.get("/api/status")
    assertEquals(HttpStatusCode.OK, response.status)
    val status = json.decodeFromString<ServerStatus>(
      response.bodyAsText()
    )
    assertEquals("1.0.0", status.version)
  }

  @Test
  fun `status totalTasks increments after download`() =
    testApplication {
      val kdown = createTestKDown()
      application {
        val server = createTestServer(kdown = kdown)
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }

      // Create a task
      client.post("/api/tasks") {
        contentType(ContentType.Application.Json)
        setBody(
          CreateDownloadRequest(
            url = "https://example.com/file.zip",
            directory = "/tmp",
          )
        )
      }

      val response = client.get("/api/status")
      val status = json.decodeFromString<ServerStatus>(
        response.bodyAsText()
      )
      assertEquals(1, status.totalTasks)
    }

  @Test
  fun `status counts canceled tasks as inactive`() =
    testApplication {
      val kdown = createTestKDown()
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
      val task = json.decodeFromString<TaskResponse>(
        createResponse.bodyAsText()
      )
      client.post("/api/tasks/${task.taskId}/cancel")

      val response = client.get("/api/status")
      val status = json.decodeFromString<ServerStatus>(
        response.bodyAsText()
      )
      assertEquals(1, status.totalTasks)
      assertEquals(0, status.activeTasks)
    }

  @Test
  fun `PUT global speed-limit succeeds`() = testApplication {
    application {
      val server = createTestServer()
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    val response = client.put("/api/speed-limit") {
      contentType(ContentType.Application.Json)
      setBody(SpeedLimitRequest(bytesPerSecond = 512000))
    }
    assertEquals(HttpStatusCode.OK, response.status)
    val body = json.decodeFromString<SpeedLimitRequest>(
      response.bodyAsText()
    )
    assertEquals(512000L, body.bytesPerSecond)
  }

  @Test
  fun `PUT global speed-limit with zero means unlimited`() =
    testApplication {
      application {
        val server = createTestServer()
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val response = client.put("/api/speed-limit") {
        contentType(ContentType.Application.Json)
        setBody(SpeedLimitRequest(bytesPerSecond = 0))
      }
      assertEquals(HttpStatusCode.OK, response.status)
    }
}
