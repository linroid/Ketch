package com.linroid.ketch.server

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.server.api.MAX_RESOLVE_CONTENT_BYTES
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ResolveContentRoutesTest {

  private class ContentSource(private val malformed: Boolean = false) : DownloadSource {
    var received: Pair<ByteArray, String?>? = null
    override val type = "content"
    override fun canHandle(url: String) = false
    override fun canHandleContent(content: ByteArray, fileName: String?) =
      fileName?.endsWith(".torrent") == true
    override suspend fun resolveContent(content: ByteArray, fileName: String?): ResolvedSource {
      received = content to fileName
      if (malformed) throw KetchError.SourceError(type)
      return ResolvedSource(
        url = "torrent:abc",
        sourceType = type,
        totalBytes = 3,
        supportsResume = true,
        suggestedFileName = "pack",
        maxSegments = 1,
      )
    }
    override suspend fun resolve(url: String, properties: Map<String, String>) =
      error("Unexpected URL resolution")
    override suspend fun download(context: DownloadContext) = Unit
    override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) = Unit
    override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long) =
      SourceResumeState(type, "{}")
  }

  @Test
  fun resolveContent_forwardsBytesAndFileName() = testApplication {
    val source = ContentSource()
    val ketch = Ketch(NoOpHttpEngine(), additionalSources = listOf(source))
    application {
      with(createTestServer(ketch)) { configureServer() }
    }
    val client = createClient { install(ContentNegotiation) { json() } }
    try {
      val content = byteArrayOf(1, 2, 3)
      val response = client.post("/api/resolve/content?fileName=pack.torrent") {
        contentType(ContentType.Application.OctetStream)
        setBody(content)
      }
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("torrent:abc", response.body<ResolvedSource>().url)
      assertContentEquals(content, source.received?.first)
      assertEquals("pack.torrent", source.received?.second)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun resolveContent_unrecognizedContent_returnsUnsupportedMediaType() = testApplication {
    val source = ContentSource()
    val ketch = Ketch(NoOpHttpEngine(), additionalSources = listOf(source))
    application {
      with(createTestServer(ketch)) { configureServer() }
    }
    try {
      val response = client.post("/api/resolve/content?fileName=notes.txt") {
        contentType(ContentType.Application.OctetStream)
        setBody(byteArrayOf(1))
      }
      assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
      assertNull(source.received)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun resolveContent_malformedContent_returnsTypedSourceError() = testApplication {
    val ketch = Ketch(NoOpHttpEngine(), additionalSources = listOf(ContentSource(malformed = true)))
    application {
      with(createTestServer(ketch)) { configureServer() }
    }
    val client = createClient { install(ContentNegotiation) { json() } }
    try {
      val response = client.post("/api/resolve/content?fileName=broken.torrent") {
        contentType(ContentType.Application.OctetStream)
        setBody(byteArrayOf(1))
      }
      assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
      val error = assertIs<KetchError.SourceError>(response.body<KetchError>())
      assertEquals("content", error.sourceType)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun resolveContent_oversizedBody_isRejectedBeforeResolving() = testApplication {
    val source = ContentSource()
    val ketch = Ketch(NoOpHttpEngine(), additionalSources = listOf(source))
    application {
      with(createTestServer(ketch)) { configureServer() }
    }
    try {
      val response = client.post("/api/resolve/content?fileName=big.torrent") {
        contentType(ContentType.Application.OctetStream)
        setBody(ByteArray(MAX_RESOLVE_CONTENT_BYTES.toInt() + 1))
      }
      assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
      assertNull(source.received)
    } finally {
      ketch.close()
    }
  }
}
