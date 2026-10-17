package com.linroid.ketch.remote

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.resolve
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteResolveUrlTest {
  private suspend fun resolveWith(status: HttpStatusCode, body: String = "") {
    val remote = RemoteKetch("remote.example", 8642, null, false, MockEngine {
      assertEquals("/api/resolve", it.url.encodedPath)
      respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    })
    try {
      remote.resolve("https://example.com/large.torrent")
    } finally {
      remote.close()
    }
  }

  @Test
  fun resolveUrl_sourceRefusesIt_rethrowsTheServerSourceErrorWithItsReason() = runTest {
    val reason = "The torrent's pieces are 64 MiB, larger than the 16 MiB Ketch supports"
    val refusal: KetchError = KetchError.SourceError("torrent", detail = reason)
    val typed = Json.encodeToJsonElement(refusal)
    // The server adds an ErrorResponse's code for older clients.
    val body = JsonObject(typed.jsonObject + ("error" to JsonPrimitive("source_error")))
    val error = assertFailsWith<KetchError.SourceError> {
      resolveWith(HttpStatusCode.UnprocessableEntity, body.toString())
    }
    assertEquals("torrent", error.sourceType)
    assertEquals(reason, error.message)
  }

  @Test
  fun resolveUrl_422WithoutATypedError_throwsRemoteApiException() = runTest {
    val page = assertFailsWith<RemoteApiException> {
      resolveWith(HttpStatusCode.UnprocessableEntity, "<html><body>Unprocessable</body></html>")
    }
    assertEquals(422, page.status)
    val untyped = assertFailsWith<RemoteApiException> {
      resolveWith(HttpStatusCode.UnprocessableEntity, """{"error":"invalid","message":"Nope"}""")
    }
    assertEquals("invalid", untyped.errorCode)
    assertEquals("Nope", untyped.message)
  }

  @Test
  fun resolveUrl_noSourceHandlesIt_throwsUnsupported() = runTest {
    assertFailsWith<KetchError.Unsupported> {
      resolveWith(HttpStatusCode.UnsupportedMediaType,
        """{"error":"unsupported","message":"No download source handles this URL"}""")
    }
  }

  @Test
  fun resolveUrl_olderServerAnswers500_throwsRemoteApiException() = runTest {
    val error = assertFailsWith<RemoteApiException> {
      resolveWith(HttpStatusCode.InternalServerError,
        """{"error":"internal_error","message":"Source 'torrent' error"}""")
    }
    assertEquals(500, error.status)
  }
}
