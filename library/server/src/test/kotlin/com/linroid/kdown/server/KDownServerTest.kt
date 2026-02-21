package com.linroid.kdown.server

import com.linroid.kdown.api.KDownStatus
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class KDownServerTest {

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
      val status = json.decodeFromString<KDownStatus>(
        response.bodyAsText()
      )
      assertEquals("KDown", status.name)
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
      assertEquals("[]", response.bodyAsText())
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
