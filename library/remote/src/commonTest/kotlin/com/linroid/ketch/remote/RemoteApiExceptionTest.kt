package com.linroid.ketch.remote

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.endpoints.model.ErrorResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RemoteApiExceptionTest {
  private val request = DownloadRequest(
    url = "https://example.com/file",
    destination = Destination("/etc/cron.d/file"),
  )

  @Test
  fun download_pathRejected_throwsServerCodeAndMessage() = runTest {
    val message = "/etc/cron.d/file is outside the folders this device saves downloads to"
    val error = downloadFailure(
      HttpStatusCode.Forbidden,
      Json.encodeToString(ErrorResponse("path_rejected", message))
    )

    assertEquals(403, error.status)
    assertEquals("path_rejected", error.errorCode)
    assertEquals(message, error.message)
    assertNull(error.retryAfterSeconds)
  }

  @Test
  fun download_tooManyAttempts_keepsRetryAfter() = runTest {
    val error = downloadFailure(
      HttpStatusCode.TooManyRequests,
      Json.encodeToString(ErrorResponse("too_many_attempts", "Too many wrong access tokens")),
      headersOf(
        HttpHeaders.ContentType to listOf("application/json"),
        HttpHeaders.RetryAfter to listOf("42")
      )
    )

    assertEquals("too_many_attempts", error.errorCode)
    assertEquals(42L, error.retryAfterSeconds)
  }

  @Test
  fun download_responseWithoutErrorResponse_reportsStatus() = runTest {
    // An older server, a proxy's page, or an error body without a message.
    val bodies = listOf(
      "" to "application/json",
      "<html><body>Bad gateway</body></html>" to "text/html",
      """{"error":"bad_gateway","message":" "}""" to "application/json"
    )
    for ((body, type) in bodies) {
      val error = downloadFailure(
        HttpStatusCode.BadGateway,
        body,
        headersOf(HttpHeaders.ContentType, type)
      )

      assertEquals("HTTP 502: Bad Gateway", error.message)
    }
  }

  private suspend fun downloadFailure(
    status: HttpStatusCode,
    body: String,
    headers: Headers = headersOf(HttpHeaders.ContentType, "application/json"),
  ): RemoteApiException {
    val remote = RemoteKetch("remote.example", 8642, null, false, MockEngine {
      respond(body, status, headers)
    })
    try {
      return assertFailsWith<RemoteApiException> { remote.download(request) }
    } finally {
      remote.close()
    }
  }
}
