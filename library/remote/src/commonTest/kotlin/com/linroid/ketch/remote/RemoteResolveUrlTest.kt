package com.linroid.ketch.remote

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.NoDownloadSourceException
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
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
  fun resolveUrl_noSourceHandlesIt_throwsUnsupportedCausedByNoSource() = runTest {
    val error = assertFailsWith<KetchError.Unsupported> {
      resolveWith(HttpStatusCode.UnsupportedMediaType,
        """{"error":"unsupported","message":"No download source handles this URL"}""")
    }
    assertIs<NoDownloadSourceException>(error.cause)
  }

  @Test
  fun resolveUrl_sourceLacksSomething_throwsUnsupportedCausedByTheServerReason() = runTest {
    // Such as a proxy setting the server's HTTP engine cannot use.
    val proxy = assertFailsWith<KetchError.Unsupported> {
      resolveWith(HttpStatusCode.UnsupportedMediaType, """{"error":"unsupported",""" +
        """"message":"Not supported: https_proxy is not an HTTP or SOCKS5 proxy URL"}""")
    }
    assertIsNot<NoDownloadSourceException>(proxy.cause)
    assertEquals("https_proxy is not an HTTP or SOCKS5 proxy URL", proxy.cause?.message)
    // The server leaves out a reason that quotes a URL.
    val unexplained = assertFailsWith<KetchError.Unsupported> {
      resolveWith(HttpStatusCode.UnsupportedMediaType,
        """{"error":"unsupported","message":"Not supported"}""")
    }
    val cause = assertNotNull(unexplained.cause)
    assertIsNot<NoDownloadSourceException>(cause)
    assertNull(cause.message)
  }

  @Test
  fun resolveUrl_415ForAnotherReason_throwsRemoteApiException() = runTest {
    // The server refuses a request body that is not JSON with 415 too.
    val notJson = assertFailsWith<RemoteApiException> {
      resolveWith(HttpStatusCode.UnsupportedMediaType,
        """{"error":"unsupported_media_type","message":"Request body must be application/json"}""")
    }
    assertEquals("unsupported_media_type", notJson.errorCode)
    val unexplained = assertFailsWith<RemoteApiException> {
      resolveWith(HttpStatusCode.UnsupportedMediaType)
    }
    assertEquals(415, unexplained.status)
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
