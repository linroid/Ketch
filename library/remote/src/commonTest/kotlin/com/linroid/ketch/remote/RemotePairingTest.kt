package com.linroid.ketch.remote

import com.linroid.ketch.endpoints.model.PairingRequest
import com.linroid.ketch.endpoints.model.PairingState
import com.linroid.ketch.endpoints.model.PairingStatus
import com.linroid.ketch.endpoints.model.PairingTicket
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class RemotePairingTest {

  private val request = PairingRequest(name = "Pixel 9", code = "4821", os = "Android 16")

  private fun pairing(
    handler: suspend MockRequestHandleScope.(HttpMethod, String) -> HttpResponseData,
  ): RemotePairing {
    val transport = MockEngine { handler(it.method, it.url.encodedPath) }
    return RemotePairing("192.168.1.20", 8642, false, transport, 1.milliseconds)
  }

  private fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
  ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

  private fun MockRequestHandleScope.ticket() =
    json(Json.encodeToString(PairingTicket("abc", 120)), HttpStatusCode.Accepted)

  private fun MockRequestHandleScope.status(state: PairingState, token: String? = null) =
    json(Json.encodeToString(PairingStatus(state, token)))

  @Test
  fun allowedAfterWaiting_returnsTheToken() = runTest {
    var sent: PairingRequest? = null
    var polls = 0
    val transport = MockEngine { call ->
      assertNull(call.headers[HttpHeaders.Authorization])
      when (call.method) {
        HttpMethod.Post -> {
          assertEquals("/api/pairing", call.url.encodedPath)
          sent = Json.decodeFromString(call.body.toByteArray().decodeToString())
          ticket()
        }
        else -> {
          assertEquals("/api/pairing/abc", call.url.encodedPath)
          if (++polls < 3) status(PairingState.PENDING) else status(PairingState.ALLOWED, "secret")
        }
      }
    }
    RemotePairing("192.168.1.20", 8642, false, transport, 1.milliseconds).use { pairing ->
      assertEquals(PairingResult.Allowed("secret"), pairing.request(request))
    }
    assertEquals(request, sent)
    assertEquals(3, polls)
  }

  @Test
  fun ownersAnswer_isReported() = runTest {
    val answers = mapOf(
      PairingState.DENIED to PairingResult.Denied,
      PairingState.EXPIRED to PairingResult.Expired,
    )
    for ((state, result) in answers) {
      pairing { method, _ -> if (method == HttpMethod.Post) ticket() else status(state) }.use {
        assertEquals(result, it.request(request))
      }
    }
  }

  @Test
  fun forgottenRequest_hasExpired() = runTest {
    pairing { method, _ ->
      if (method == HttpMethod.Post) ticket() else respond("", HttpStatusCode.NotFound)
    }.use {
      assertEquals(PairingResult.Expired, it.request(request))
    }
  }

  @Test
  fun serverWithoutPairing_isUnsupported() = runTest {
    for (code in listOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)) {
      pairing { _, _ -> respond("", code) }.use {
        assertEquals(PairingResult.Unsupported, it.request(request))
      }
    }
  }

  @Test
  fun anotherRequestWaiting_isBusy() = runTest {
    pairing { _, _ -> respond("", HttpStatusCode.TooManyRequests) }.use {
      assertEquals(PairingResult.Busy, it.request(request))
    }
  }

  @Test
  fun refused_throwsServerCodeAndMessage() = runTest {
    val body = """{"error":"origin_not_allowed","message":"Web pages cannot pair"}"""
    pairing { _, _ -> json(body, HttpStatusCode.Forbidden) }.use {
      val error = assertFailsWith<RemoteApiException> { it.request(request) }
      assertEquals("origin_not_allowed", error.errorCode)
      assertEquals("Web pages cannot pair", error.message)
    }
  }

  @Test
  fun cancelling_withdrawsTheRequest() = runTest {
    val polled = CompletableDeferred<Unit>()
    val withdrawn = CompletableDeferred<String>()
    pairing { method, path ->
      when (method) {
        HttpMethod.Post -> ticket()
        HttpMethod.Delete -> {
          withdrawn.complete(path)
          respond("", HttpStatusCode.NoContent)
        }
        else -> {
          polled.complete(Unit)
          status(PairingState.PENDING)
        }
      }
    }.use { pairing ->
      val waiting = async { pairing.request(request) }
      polled.await()
      waiting.cancel()
      assertEquals("/api/pairing/abc", withdrawn.await())
    }
  }
}
