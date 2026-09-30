package com.linroid.ketch.server

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.endpoints.model.ErrorResponse
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostValidationTest {

  private fun ApplicationTestBuilder.serve(server: KetchServer) {
    application { with(server) { configureServer() } }
  }

  @Test
  fun `rebound host is rejected without a token`() = testApplication {
    serve(KetchServer(createTestKetch()))
    val response = client.get("/api/tasks") {
      header(HttpHeaders.Host, "attacker.example:8642")
    }
    assertEquals(HttpStatusCode.Forbidden, response.status)
    val error = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
    assertEquals("host_not_allowed", error.error)
  }

  @Test
  fun `rebound host cannot create a download`() = testApplication {
    val ketch: KetchApi = createTestKetch()
    serve(KetchServer(ketch))
    val client = createClient { install(ContentNegotiation) { json() } }
    val response = client.post("/api/tasks") {
      header(HttpHeaders.Host, "attacker.example:8642")
      contentType(ContentType.Application.Json)
      setBody(DownloadRequest("https://example.com/f.zip", Destination("/tmp/x/")))
    }
    assertEquals(HttpStatusCode.Forbidden, response.status)
    assertTrue(ketch.tasks.value.isEmpty())
  }

  @Test
  fun `cors preflight from a rebound host is rejected`() = testApplication {
    serve(KetchServer(createTestKetch(), corsAllowedHosts = listOf("*")))
    val response = client.options("/api/tasks") {
      header(HttpHeaders.Host, "attacker.example:8642")
      header(HttpHeaders.Origin, "http://attacker.example:8642")
      header(HttpHeaders.AccessControlRequestMethod, "POST")
    }
    assertEquals(HttpStatusCode.Forbidden, response.status)
  }

  @Test
  fun `loopback and ip literal hosts pass without a token`() = testApplication {
    serve(KetchServer(createTestKetch()))
    for (host in listOf("localhost:8642", "ketch.localhost", "127.0.0.1:8642", "[::1]:8642")) {
      val response = client.get("/api/status") { header(HttpHeaders.Host, host) }
      assertEquals(HttpStatusCode.OK, response.status, host)
    }
  }

  @Test
  fun `allowed host passes without a token`() = testApplication {
    serve(KetchServer(createTestKetch(), allowedHosts = listOf("nas.example", "203.0.113.7")))
    for (host in listOf("NAS.example:8642", "203.0.113.7:8642")) {
      val response = client.get("/api/status") { header(HttpHeaders.Host, host) }
      assertEquals(HttpStatusCode.OK, response.status, host)
    }
    val other = client.get("/api/status") { header(HttpHeaders.Host, "other.example") }
    assertEquals(HttpStatusCode.Forbidden, other.status)
  }

  @Test
  fun `any host passes with a token and still needs the token`() = testApplication {
    serve(KetchServer(createTestKetch(), apiToken = "secret"))
    val authorized = client.get("/api/status") {
      header(HttpHeaders.Host, "nas.example:8642")
      header(HttpHeaders.Authorization, "Bearer secret")
    }
    assertEquals(HttpStatusCode.OK, authorized.status)
    val anonymous = client.get("/api/status") {
      header(HttpHeaders.Host, "attacker.example:8642")
    }
    assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
  }
}
