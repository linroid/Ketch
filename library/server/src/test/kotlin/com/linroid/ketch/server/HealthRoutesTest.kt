package com.linroid.ketch.server

import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.HealthResponse
import com.linroid.ketch.endpoints.model.HealthStatus
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRoutesTest {

  @Test
  fun health_notReady_answers503UntilMarkedReady() = testApplication {
    val server = KetchServer(createTestKetch(), ready = false)
    application { with(server) { configureServer() } }

    val starting = client.get("/api/health")
    assertEquals(HttpStatusCode.ServiceUnavailable, starting.status)
    assertEquals(HealthStatus.Starting, health(starting.bodyAsText()))

    server.markReady()
    val ready = client.get("/api/health")
    assertEquals(HttpStatusCode.OK, ready.status)
    assertEquals(HealthStatus.Ready, health(ready.bodyAsText()))
  }

  @Test
  fun api_notReady_answers503StartingUntilMarkedReady() = testApplication {
    val server = KetchServer(createTestKetch(), ready = false)
    application { with(server) { configureServer() } }

    val listing = client.get("/api/tasks")
    assertEquals(HttpStatusCode.ServiceUnavailable, listing.status)
    assertEquals("1", listing.headers[HttpHeaders.RetryAfter])
    assertEquals("starting", Json.decodeFromString<ErrorResponse>(listing.bodyAsText()).error)
    val adding = client.post("/api/tasks") {
      contentType(ContentType.Application.Json)
      setBody("""{"url":"https://example.com/file.zip"}""")
    }
    assertEquals(HttpStatusCode.ServiceUnavailable, adding.status)

    server.markReady()
    assertEquals(HttpStatusCode.OK, client.get("/api/tasks").status)
  }

  @Test
  fun api_notReadyWithToken_answers503BeforeCheckingTheToken() = testApplication {
    val server = KetchServer(createTestKetch(), apiToken = "secret-token", ready = false)
    application { with(server) { configureServer() } }

    assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/status").status)
    assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/health").status)
  }

  @Test
  fun health_byDefault_ready() = testApplication {
    application { with(KetchServer(createTestKetch())) { configureServer() } }

    assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
  }

  @Test
  fun health_withToken_answersRequestsWithoutIt() = testApplication {
    application {
      with(KetchServer(createTestKetch(), apiToken = "secret-token")) { configureServer() }
    }

    assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/status").status)
  }

  private fun health(body: String): HealthStatus =
    Json.decodeFromString<HealthResponse>(body).status
}
