package com.linroid.ketch.hooks

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class WebhookSenderTest {
  private val requests = mutableListOf<HttpRequestData>()

  private fun TestScope.sender(
    vararg answers: suspend MockRequestHandleScope.() -> HttpResponseData,
  ): WebhookSender {
    val config = MockEngineConfig().apply {
      // On virtual time, so timeouts and retry delays pass at once.
      dispatcher = StandardTestDispatcher(testScheduler)
      addHandler { request ->
        requests += request
        answers[minOf(requests.size, answers.size) - 1]()
      }
    }
    return WebhookSender(HttpClient(MockEngine(config)) { expectSuccess = false })
  }

  private suspend fun WebhookSender.send(secret: String? = null) = send(
    url = "https://hooks.example.com/ketch?token=abc",
    headers = mapOf("Authorization" to "Bearer t0ken"),
    secret = secret,
    event = "completed",
    deliveryId = "d1",
    body = """{"event":"completed"}""",
    timeout = 5.seconds,
  )

  @Test
  fun send_signsTheBodyAndSendsTheHeaders() = runTest {
    val outcome = sender({ respondOk() }).send(secret = "s3cret")

    assertEquals(HookOutcome.Done, outcome)
    val request = requests.single()
    assertEquals(
      // printf '{"event":"completed"}' | openssl dgst -sha256 -hmac s3cret
      WebhookSender.signature("s3cret", """{"event":"completed"}"""),
      request.headers[WebhookSender.SIGNATURE_HEADER],
    )
    assertEquals("Bearer t0ken", request.headers[HttpHeaders.Authorization])
    assertEquals("completed", request.headers[WebhookSender.EVENT_HEADER])
    assertEquals("d1", request.headers[WebhookSender.DELIVERY_HEADER])
    assertEquals("""{"event":"completed"}""", (request.body as TextContent).text)
  }

  @Test
  fun signature_isTheHexHmacSha256() {
    assertEquals(
      "sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
      WebhookSender.signature("key", "The quick brown fox jumps over the lazy dog"),
    )
  }

  @Test
  fun send_withoutSecret_sendsNoSignature() = runTest {
    sender({ respondOk() }).send()
    assertEquals(null, requests.single().headers[WebhookSender.SIGNATURE_HEADER])
  }

  @Test
  fun send_serverErrors_retryUntilDelivered() = runTest {
    val outcome = sender(
      { respondError(HttpStatusCode.BadGateway) },
      { throw IOException("connection reset to https://hooks.example.com/?token=abc") },
      { respondOk() },
    ).send()

    assertEquals(HookOutcome.Done, outcome)
    assertEquals(3, requests.size)
  }

  @Test
  fun send_failingEveryAttempt_givesUpAfterTheRetries() = runTest {
    val outcome = sender({ respondError(HttpStatusCode.ServiceUnavailable) }).send()

    assertEquals(HookOutcome.Failed("answered 503"), outcome)
    assertEquals(3, requests.size)
  }

  @Test
  fun send_clientErrorsAndRedirects_areNotRetried() = runTest {
    assertEquals(
      HookOutcome.Failed("answered 401"),
      sender({ respondError(HttpStatusCode.Unauthorized) }).send(),
    )
    requests.clear()
    val redirected = sender({
      respond("", HttpStatusCode.Found, io.ktor.http.headersOf(HttpHeaders.Location, "https://x"))
    }).send()
    assertEquals(HookOutcome.Failed("answered 302"), redirected)
    assertEquals(1, requests.size)
  }

  @Test
  fun send_slowReceiver_timesOutWithoutQuotingTheUrl() = runTest {
    val outcome = sender({
      delay(60.seconds)
      respondOk()
    }).send()

    assertIs<HookOutcome.Failed>(outcome)
    assertFalse("token" in outcome.reason, outcome.reason)
    assertEquals(3, requests.size)
  }
}
