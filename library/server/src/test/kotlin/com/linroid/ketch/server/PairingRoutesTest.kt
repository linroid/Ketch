package com.linroid.ketch.server

import com.linroid.ketch.endpoints.model.PairingRequest
import com.linroid.ketch.endpoints.model.PairingState
import com.linroid.ketch.endpoints.model.PairingStatus
import com.linroid.ketch.endpoints.model.PairingTicket
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PairingRoutesTest {

  private val request = PairingRequest(name = "Pixel 9", code = "4821", os = "Android 16")

  private fun ApplicationTestBuilder.pairingServer(
    apiToken: String? = TOKEN,
    approver: PairingApprover? = PairingApprover { _, _ -> true },
  ): HttpClient {
    application {
      val server = KetchServer(
        ketch = createTestKetch(),
        apiToken = apiToken,
        corsAllowedHosts = listOf("*"),
        pairingApprover = approver,
      )
      with(server) { configureServer() }
    }
    return createClient { install(ContentNegotiation) { json() } }
  }

  private suspend fun HttpClient.ask(
    body: PairingRequest = request,
    origin: String? = null,
  ): HttpResponse = post("/api/pairing") {
    contentType(ContentType.Application.Json)
    origin?.let { header(HttpHeaders.Origin, it) }
    setBody(body)
  }

  private suspend fun HttpClient.status(id: String): PairingStatus =
    get("/api/pairing/$id").body()

  private suspend fun HttpClient.answer(id: String): PairingStatus = withTimeout(5.seconds) {
    var status = status(id)
    while (status.state == PairingState.PENDING) {
      delay(10.milliseconds)
      status = status(id)
    }
    status
  }

  @Test
  fun `allowed request receives the token without sending one`() = testApplication {
    val asked = CompletableDeferred<Pair<PairingRequest, String>>()
    val decision = CompletableDeferred<Boolean>()
    val client = pairingServer(approver = { sent, address ->
      asked.complete(sent to address)
      decision.await()
    })

    val response = client.ask()
    assertEquals(HttpStatusCode.Accepted, response.status)
    val ticket = response.body<PairingTicket>()
    assertEquals(120, ticket.expiresInSeconds)
    assertEquals(PairingStatus(PairingState.PENDING), client.status(ticket.id))
    assertEquals(request, asked.await().first)

    decision.complete(true)
    assertEquals(PairingStatus(PairingState.ALLOWED, TOKEN), client.answer(ticket.id))
  }

  @Test
  fun `denied request receives no token`() = testApplication {
    val client = pairingServer(approver = { _, _ -> false })

    val ticket = client.ask().body<PairingTicket>()

    assertEquals(PairingStatus(PairingState.DENIED), client.answer(ticket.id))
  }

  @Test
  fun `failing approver denies the request`() = testApplication {
    val client = pairingServer(approver = { _, _ -> error("no window") })

    val ticket = client.ask().body<PairingTicket>()

    assertEquals(PairingStatus(PairingState.DENIED), client.answer(ticket.id))
  }

  @Test
  fun `web pages cannot pair even when CORS allows them`() = testApplication {
    var asked = false
    val client = pairingServer(approver = { _, _ ->
      asked = true
      true
    })

    for (origin in listOf("https://evil.example", "http://192.168.1.20:8642", "null")) {
      assertEquals(HttpStatusCode.Forbidden, client.ask(origin = origin).status, origin)
    }
    assertEquals(HttpStatusCode.Forbidden, client.get("/api/pairing/abc") {
      header(HttpHeaders.Origin, "https://evil.example")
    }.status)
    assertEquals(false, asked)
  }

  @Test
  fun `second request from the same address waits its turn`() = testApplication {
    val client = pairingServer(approver = { _, _ -> awaitCancellation() })

    assertEquals(HttpStatusCode.Accepted, client.ask().status)

    assertEquals(HttpStatusCode.TooManyRequests, client.ask().status)
  }

  @Test
  fun `withdrawn request stops asking and is forgotten`() = testApplication {
    val asking = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val client = pairingServer(approver = { _, _ ->
      asking.complete(Unit)
      try {
        awaitCancellation()
      } finally {
        cancelled.complete(Unit)
      }
    })
    val ticket = client.ask().body<PairingTicket>()
    withTimeout(5.seconds) { asking.await() }

    assertEquals(HttpStatusCode.NoContent, client.delete("/api/pairing/${ticket.id}").status)

    withTimeout(5.seconds) { cancelled.await() }
    assertEquals(HttpStatusCode.NotFound, client.get("/api/pairing/${ticket.id}").status)
    assertEquals(HttpStatusCode.Accepted, client.ask().status)
  }

  @Test
  fun `malformed requests are rejected`() = testApplication {
    val client = pairingServer()

    assertEquals(HttpStatusCode.BadRequest, client.ask(request.copy(code = "48a1")).status)
    assertEquals(HttpStatusCode.BadRequest, client.ask(request.copy(code = "48211")).status)
    assertEquals(HttpStatusCode.BadRequest, client.ask(request.copy(name = "  ")).status)
  }

  @Test
  fun `servers without an approver or a token take no requests`() {
    testApplication {
      assertEquals(HttpStatusCode.NotFound, pairingServer(approver = null).ask().status)
    }
    testApplication {
      assertEquals(HttpStatusCode.NotFound, pairingServer(apiToken = null).ask().status)
    }
  }

  @Test
  fun `unanswered request expires`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val sessions = PairingSessions(
        approver = { _, _ -> awaitCancellation() },
        token = TOKEN,
        scope = scope,
        timeout = 50.milliseconds,
        retention = 5.seconds,
      )
      val ticket = assertNotNull(sessions.open(request, "192.168.1.30"))

      val status = withTimeout(5.seconds) {
        var status = sessions.status(ticket.id)
        while (status?.state == PairingState.PENDING) {
          delay(10.milliseconds)
          status = sessions.status(ticket.id)
        }
        status
      }

      assertEquals(PairingStatus(PairingState.EXPIRED), status)
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun `at most four requests wait at a time`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val sessions = PairingSessions({ _, _ -> awaitCancellation() }, TOKEN, scope)

      repeat(PairingSessions.MAX_PENDING) {
        assertNotNull(sessions.open(request, "192.168.1.${30 + it}"))
      }

      assertNull(sessions.open(request, "192.168.1.99"))
    } finally {
      scope.cancel()
    }
  }

  private companion object {
    const val TOKEN = "secret-token"
  }
}
