package com.linroid.ketch.server

import com.linroid.ketch.api.KetchStatus
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class KetchServerTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `status endpoint returns server version`() =
    testApplication {
      application {
        val server = createTestServer()
        with(server) { configureServer() }
      }
      val response = client.get("/api/status")
      assertEquals(HttpStatusCode.OK, response.status)
      val status = json.decodeFromString<KetchStatus>(
        response.bodyAsText()
      )
      assertEquals("Ketch", status.name)
    }

  @Test
  fun `tasks endpoint returns empty list`() =
    testApplication {
      application {
        val server = createTestServer()
        with(server) { configureServer() }
      }
      val response = client.get("/api/tasks")
      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      val taskList = json.decodeFromString<
        com.linroid.ketch.endpoints.model.TasksResponse
      >(body)
      assertEquals(0, taskList.tasks.size)
    }

  @Test
  fun `get unknown task returns 404`() = testApplication {
    application {
      val server = createTestServer()
      with(server) { configureServer() }
    }
    val response = client.get("/api/tasks/nonexistent")
    assertEquals(HttpStatusCode.NotFound, response.status)
  }
}
