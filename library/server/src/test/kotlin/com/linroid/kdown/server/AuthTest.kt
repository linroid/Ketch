package com.linroid.kdown.server

import com.linroid.kdown.api.config.ServerConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthTest {

  @Test
  fun `request without token is rejected when auth is configured`() =
    testApplication {
      application {
        val server = createTestServer(
          ServerConfig(apiToken = "secret-token"),
        )
        with(server) { configureServer() }
      }
      val response = client.get("/api/status")
      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

  @Test
  fun `request with valid token succeeds`() = testApplication {
    application {
      val server = createTestServer(
        ServerConfig(apiToken = "secret-token"),
      )
      with(server) { configureServer() }
    }
    val response = client.get("/api/status") {
      header(HttpHeaders.Authorization, "Bearer secret-token")
    }
    assertEquals(HttpStatusCode.OK, response.status)
  }

  @Test
  fun `request with wrong token is rejected`() = testApplication {
    application {
      val server = createTestServer(
        ServerConfig(apiToken = "secret-token"),
      )
      with(server) { configureServer() }
    }
    val response = client.get("/api/status") {
      header(HttpHeaders.Authorization, "Bearer wrong-token")
    }
    assertEquals(HttpStatusCode.Unauthorized, response.status)
  }

  @Test
  fun `no auth required when apiToken is null`() =
    testApplication {
      application {
        val server = createTestServer(
          ServerConfig(apiToken = null),
        )
        with(server) { configureServer() }
      }
      val response = client.get("/api/status")
      assertEquals(HttpStatusCode.OK, response.status)
    }
}
