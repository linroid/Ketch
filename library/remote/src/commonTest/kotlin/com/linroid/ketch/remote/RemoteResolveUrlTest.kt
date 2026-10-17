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
    val body = Json.encodeToString<KetchError>(KetchError.SourceError("torrent", detail = reason))
    val error = assertFailsWith<KetchError.SourceError> {
      resolveWith(HttpStatusCode.UnprocessableEntity, body)
    }
    assertEquals("torrent", error.sourceType)
    assertEquals(reason, error.message)
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
