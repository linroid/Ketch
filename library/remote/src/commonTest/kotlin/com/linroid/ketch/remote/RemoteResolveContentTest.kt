package com.linroid.ketch.remote

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteResolveContentTest {
  @Test
  fun resolveContent_uploadsRawBytesWithFileName() = runTest {
    val content = byteArrayOf(0, 1, 2, -1)
    val resolved = ResolvedSource(
      url = "torrent:abc",
      sourceType = "torrent",
      totalBytes = 3,
      supportsResume = true,
      suggestedFileName = "pack",
      maxSegments = 1,
    )
    val transport = MockEngine { request ->
      assertEquals(HttpMethod.Post, request.method)
      assertEquals("/api/resolve/content", request.url.encodedPath)
      assertEquals("my pack.torrent", request.url.parameters["fileName"])
      assertEquals(ContentType.Application.OctetStream, request.body.contentType)
      assertContentEquals(content, request.body.toByteArray())
      respond(
        Json.encodeToString(resolved),
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    val remote = RemoteKetch("remote.example", 8642, null, false, transport)
    try {
      assertEquals(resolved, remote.resolveContent(content, "my pack.torrent"))
    } finally {
      remote.close()
    }
  }

  @Test
  fun resolveContent_errorStatuses_mapToTypedErrors() = runTest {
    suspend fun resolveWith(status: HttpStatusCode) {
      val remote = RemoteKetch("remote.example", 8642, null, false, MockEngine {
        respond("", status)
      })
      try {
        remote.resolveContent(byteArrayOf(1), "a.torrent")
      } finally {
        remote.close()
      }
    }
    assertFailsWith<KetchError.Unsupported> {
      resolveWith(HttpStatusCode.UnsupportedMediaType)
    }
    assertFailsWith<IllegalArgumentException> {
      resolveWith(HttpStatusCode.PayloadTooLarge)
    }
    for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.NotImplemented)) {
      assertFailsWith<UnsupportedOperationException> { resolveWith(status) }
    }
  }

  @Test
  fun resolveContent_invalidContent_rethrowsServerSourceError() = runTest {
    val remote = RemoteKetch("remote.example", 8642, null, false, MockEngine {
      respond(
        Json.encodeToString<KetchError>(KetchError.SourceError("torrent")),
        HttpStatusCode.UnprocessableEntity,
        headersOf(HttpHeaders.ContentType, "application/json"),
      )
    })
    try {
      val error = assertFailsWith<KetchError.SourceError> {
        remote.resolveContent(byteArrayOf(1), "broken.torrent")
      }
      assertEquals("torrent", error.sourceType)
    } finally {
      remote.close()
    }
  }
}
