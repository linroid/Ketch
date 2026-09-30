package com.linroid.ketch.server

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.endpoints.model.ErrorResponse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossOriginTest {

  private fun ApplicationTestBuilder.serve(server: KetchServer) {
    application { with(server) { configureServer() } }
  }

  private suspend fun ApplicationTestBuilder.preflight(origin: String): HttpResponse {
    return client.options("/api/tasks") {
      header(HttpHeaders.Host, LOCAL_HOST)
      header(HttpHeaders.Origin, origin)
      header(HttpHeaders.AccessControlRequestMethod, "POST")
      header(HttpHeaders.AccessControlRequestHeaders, "content-type")
    }
  }

  @Test
  fun `preflight from another site is refused without a token`() = testApplication {
    serve(KetchServer(createTestKetch()))
    val response = preflight("https://attacker.example")
    assertEquals(HttpStatusCode.Forbidden, response.status)
    assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    val error = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
    assertEquals("origin_not_allowed", error.error)
  }

  @Test
  fun `simple request from another site cannot create a download`() = testApplication {
    val ketch: KetchApi = createTestKetch()
    serve(KetchServer(ketch))
    // A page may send text/plain without a preflight; `null` is a sandboxed frame's origin.
    for (origin in listOf("https://attacker.example", "http://localhost:3000", "null")) {
      val response = client.post("/api/tasks") {
        header(HttpHeaders.Host, LOCAL_HOST)
        header(HttpHeaders.Origin, origin)
        contentType(ContentType.Text.Plain)
        setBody("""{"url":"https://example.com/f.zip","destination":"/tmp/x/"}""")
      }
      assertEquals(HttpStatusCode.Forbidden, response.status, origin)
    }
    assertTrue(ketch.tasks.value.isEmpty())
  }

  @Test
  fun `cors allowed hosts are ignored without a token`() = testApplication {
    val allowed = listOf("*", "http://localhost:3000")
    serve(KetchServer(createTestKetch(), corsAllowedHosts = allowed))
    for (origin in listOf("https://attacker.example", "http://localhost:3000")) {
      assertEquals(HttpStatusCode.Forbidden, preflight(origin).status, origin)
    }
  }

  @Test
  fun `web ui served by the server passes without a token`() = testApplication {
    // Names other than this machine's must pass the host check first.
    serve(KetchServer(createTestKetch(), allowedHosts = listOf("ketch.local", "ketch.example")))
    val cases = listOf(
      "http://127.0.0.1:8642" to LOCAL_HOST,
      "http://[::1]:8642" to "[::1]:8642",
      "http://Ketch.local:8642" to "ketch.local:8642",
      // Behind a proxy that terminates TLS and keeps the Host header.
      "https://ketch.example" to "ketch.example",
    )
    for ((origin, host) in cases) {
      val response = client.get("/api/tasks") {
        header(HttpHeaders.Host, host)
        header(HttpHeaders.Origin, origin)
      }
      assertEquals(HttpStatusCode.OK, response.status, origin)
    }
  }

  @Test
  fun `other port on the same host is another origin`() = testApplication {
    serve(KetchServer(createTestKetch()))
    val response = client.get("/api/tasks") {
      header(HttpHeaders.Host, LOCAL_HOST)
      header(HttpHeaders.Origin, "http://127.0.0.1:3000")
    }
    assertEquals(HttpStatusCode.Forbidden, response.status)
  }

  @Test
  fun `host check answers first when both checks refuse a request`() = testApplication {
    serve(KetchServer(createTestKetch()))
    val response = client.get("/api/tasks") {
      header(HttpHeaders.Host, "attacker.example:8642")
      header(HttpHeaders.Origin, "https://other.example")
    }
    assertEquals(HttpStatusCode.Forbidden, response.status)
    val error = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
    assertEquals("host_not_allowed", error.error)
  }

  @Test
  fun `browser extension passes without a token and gets no cors grant`() = testApplication {
    serve(KetchServer(createTestKetch()))
    for (origin in listOf("chrome-extension://abcdefghijklmnop", "moz-extension://1b2c3d4e")) {
      val response = client.get("/api/status") {
        header(HttpHeaders.Host, LOCAL_HOST)
        header(HttpHeaders.Origin, origin)
      }
      assertEquals(HttpStatusCode.OK, response.status, origin)
      assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin], origin)
    }
  }

  @Test
  fun `any origin gets cors with a token but still needs the token`() = testApplication {
    serve(KetchServer(createTestKetch(), apiToken = "secret", corsAllowedHosts = listOf("*")))
    val preflight = preflight("https://web.example")
    assertEquals(HttpStatusCode.OK, preflight.status)
    assertEquals("*", preflight.headers[HttpHeaders.AccessControlAllowOrigin])
    val anonymous = client.get("/api/status") {
      header(HttpHeaders.Host, LOCAL_HOST)
      header(HttpHeaders.Origin, "https://web.example")
    }
    assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
  }

  @Test
  fun `listed origin with a scheme gets cors with a token`() = testApplication {
    val allowed = listOf("http://localhost:3000")
    serve(KetchServer(createTestKetch(), apiToken = "secret", corsAllowedHosts = allowed))
    val listed = preflight("http://localhost:3000")
    assertEquals(HttpStatusCode.OK, listed.status)
    assertEquals("http://localhost:3000", listed.headers[HttpHeaders.AccessControlAllowOrigin])
    assertEquals(HttpStatusCode.Forbidden, preflight("https://localhost:3000").status)
    assertEquals(HttpStatusCode.Forbidden, preflight("https://attacker.example").status)
  }

  @Test
  fun `browser extension is not refused by a cors list`() = testApplication {
    val allowed = listOf("localhost:3000")
    serve(KetchServer(createTestKetch(), apiToken = "secret", corsAllowedHosts = allowed))
    val response = client.get("/api/status") {
      header(HttpHeaders.Host, LOCAL_HOST)
      header(HttpHeaders.Origin, "chrome-extension://abcdefghijklmnop")
      header(HttpHeaders.Authorization, "Bearer secret")
    }
    assertEquals(HttpStatusCode.OK, response.status)
  }

  @Test
  fun `isForeignWebOrigin compares host and port without the scheme`() {
    assertFalse(isForeignWebOrigin("http://localhost", "localhost:80"))
    assertFalse(isForeignWebOrigin("https://ketch.example/", "KETCH.example:443"))
    assertTrue(isForeignWebOrigin("https://ketch.example", "ketch.example:80"))
    assertTrue(isForeignWebOrigin("http://127.0.0.1:8642.attacker.example", LOCAL_HOST))
    assertTrue(isForeignWebOrigin("http://user@127.0.0.1:8642", LOCAL_HOST))
    assertTrue(isForeignWebOrigin("http://127.0.0.1:8642", null))
  }

  private companion object {
    const val LOCAL_HOST = "127.0.0.1:8642"
  }
}
