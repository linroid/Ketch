package com.linroid.ketch.webdav

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.canHandle
import com.linroid.ketch.core.engine.resolve
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavDownloadSourceTest {
  private val content = ByteArray(10_000) { (it * 31 % 251).toByte() }

  @Test
  fun canHandle_webDavSchemesOnly() {
    val source = WebDavDownloadSource(FakeDavServer(content))
    assertTrue(source.canHandle("webdav://host/a"))
    assertTrue(source.canHandle("webdavs://host/a"))
    assertTrue(source.canHandle("dav://host/a"))
    assertTrue(source.canHandle("DAVS://host/a"))
    assertFalse(source.canHandle("https://host/a"))
    assertFalse(source.canHandle("ftp://host/a"))
    assertFalse(source.canHandle("magnet:?xt=urn:btih:abc"))
  }

  @Test
  fun resolve_readsThePropfindAnswer() = runTest {
    val server = FakeDavServer(content)
    val resolved = WebDavDownloadSource(server).resolve(URL)
    assertEquals(URL, resolved.url)
    assertEquals(WebDavDownloadSource.TYPE, resolved.sourceType)
    assertEquals(10_000L, resolved.totalBytes)
    assertTrue(resolved.supportsResume)
    assertEquals("file.bin", resolved.suggestedFileName)
    assertEquals("\"v1\"", resolved.metadata[WebDavDownloadSource.META_ETAG])
    assertEquals("true", resolved.metadata[WebDavDownloadSource.META_DAV])
    assertEquals(listOf("PROPFIND+", "GET+"), server.requests)
  }

  @Test
  fun resolve_folder_isRefused() = runTest {
    val server = FakeDavServer(content).apply { collection = true }
    val error = assertFailsWith<KetchError.SourceError> {
      WebDavDownloadSource(server).resolve(URL)
    }
    assertEquals(WebDavDownloadSource.TYPE, error.sourceType)
  }

  @Test
  fun resolve_withoutPropfind_readsTheHeaders() = runTest {
    val server = FakeDavServer(content).apply { dav = false }
    val resolved = WebDavDownloadSource(server).resolve(URL)
    assertEquals(10_000L, resolved.totalBytes)
    assertEquals("\"v1\"", resolved.metadata[WebDavDownloadSource.META_ETAG])
    assertNull(resolved.metadata[WebDavDownloadSource.META_DAV])
  }

  @Test
  fun download_inSegments() = runTest {
    val server = FakeDavServer(content)
    val source = WebDavDownloadSource(server)
    val file = MemoryFile()
    val context = context(file, connections = 4, preResolved = source.resolve(URL))
    source.download(context, null)
    assertContentEquals(content, file.data)
    assertEquals(4, server.ranged.size)
    assertTrue(context.segments.value.all { it.isComplete })
    assertEquals("\"v1\"", source.store(context)?.etag)
  }

  @Test
  fun download_withoutRanges_usesOneConnection() = runTest {
    val server = FakeDavServer(content).apply { ranges = false }
    val source = WebDavDownloadSource(server)
    val file = MemoryFile()
    source.download(context(file, connections = 4), null)
    assertContentEquals(content, file.data)
    assertEquals(listOf<LongRange?>(0L..9_999L), server.ranged)
  }

  @Test
  fun download_unknownSize_streams() = runTest {
    val server = FakeDavServer(content).apply { reportsLength = false }
    val source = WebDavDownloadSource(server)
    val file = MemoryFile()
    val context = context(file)
    source.download(context, null)
    assertContentEquals(content, file.data)
    assertEquals(listOf<LongRange?>(null), server.ranged)
    assertTrue(context.segments.value.isEmpty())
  }

  @Test
  fun digest_answersTheChallenge() = runTest {
    val server = FakeDavServer(content, auth = FakeDavServer.Auth.Digest)
    val source = WebDavDownloadSource(server)
    val file = MemoryFile()
    source.download(context(file, connections = 2, url = AUTH_URL), null)
    assertContentEquals(content, file.data)
    assertEquals(listOf("PROPFIND", "PROPFIND+", "GET+", "GET+", "GET+"), server.requests)
  }

  @Test
  fun digest_expiredNonce_isAnsweredAgain() = runTest {
    val server = FakeDavServer(content, auth = FakeDavServer.Auth.Digest)
    val file = MemoryFile()
    // The nonce expires once resolving answered the first one.
    val engine = object : com.linroid.ketch.core.engine.HttpEngine by server {
      var probed = false
      override suspend fun probe(
        url: String,
        headers: Map<String, String>,
      ): com.linroid.ketch.core.engine.ServerInfo {
        val info = server.probe(url, headers)
        if (!probed) server.nonce = "nonce-2"
        probed = true
        return info
      }
    }
    WebDavDownloadSource(engine).download(context(file, connections = 1, url = AUTH_URL), null)
    assertContentEquals(content, file.data)
    // The segment's GET is refused, a HEAD fetches the new challenge, and the GET is sent again.
    assertEquals(
      listOf("PROPFIND", "PROPFIND+", "GET+", "GET", "HEAD", "GET+"),
      server.requests,
    )
  }

  @Test
  fun basic_fromTheAuthorizationHeader() = runTest {
    val server = FakeDavServer(content, auth = FakeDavServer.Auth.Basic)
    val source = WebDavDownloadSource(server)
    val file = MemoryFile()
    val headers = mapOf("Authorization" to "Basic dXNlcjpzZWNyZXQ=")
    source.download(context(file, headers = headers), null)
    assertContentEquals(content, file.data)
    // Credentials wait for the server's challenge.
    assertEquals("PROPFIND", server.requests.first())
  }

  @Test
  fun bearerHeader_isSentAsItIs() = runTest {
    val server = FakeDavServer(content)
    val seen = mutableListOf<String?>()
    val engine = object : com.linroid.ketch.core.engine.HttpEngine by server {
      override suspend fun probe(
        url: String,
        headers: Map<String, String>,
      ): com.linroid.ketch.core.engine.ServerInfo {
        seen += headers["Authorization"]
        return server.probe(url, headers)
      }
    }
    WebDavDownloadSource(engine).resolve(URL, mapOf("Authorization" to "Bearer t"))
    assertEquals(listOf<String?>("Bearer t"), seen)
  }

  @Test
  fun missingOrWrongCredentials_failAuthentication() = runTest {
    val server = FakeDavServer(content, auth = FakeDavServer.Auth.Digest)
    val source = WebDavDownloadSource(server)
    assertFailsWith<KetchError.AuthenticationFailed> { source.resolve(URL) }
    server.password = "other"
    assertFailsWith<KetchError.AuthenticationFailed> { source.resolve(AUTH_URL) }
    val basic = FakeDavServer(content, auth = FakeDavServer.Auth.Basic).apply { password = "x" }
    assertFailsWith<KetchError.AuthenticationFailed> {
      WebDavDownloadSource(basic).resolve(AUTH_URL)
    }
  }

  @Test
  fun resume_continuesFromTheBytesWritten() = runTest {
    val server = FakeDavServer(content).apply { dropAfter = 1_000 }
    val source = WebDavDownloadSource(server)
    val file = MemoryFile()
    val context = context(file, connections = 1, preResolved = source.resolve(URL))
    assertFailsWith<KetchError.Network> { source.download(context, null) }
    val state = source.store(context)
    assertEquals(1_000L, context.segments.value.sumOf { it.downloadedBytes })

    server.ranged.clear()
    source.download(context, state)
    assertContentEquals(content, file.data)
    assertEquals(listOf<LongRange?>(1_000L..9_999L), server.ranged)
  }

  @Test
  fun resume_changedFile_fails() = runTest {
    val server = FakeDavServer(content).apply { dropAfter = 1_000 }
    val source = WebDavDownloadSource(server)
    val context = context(MemoryFile(), connections = 1, preResolved = source.resolve(URL))
    assertFailsWith<KetchError.Network> { source.download(context, null) }
    val state = source.store(context)

    server.etag = "W/\"v2\""
    assertFailsWith<KetchError.FileChanged> { source.download(context, state) }
    server.etag = "W/\"v1\""
    server.content = content.copyOf(20_000)
    assertFailsWith<KetchError.FileChanged> { source.download(context, state) }
  }

  @Test
  fun resume_emptiedLocalFile_restartsFromZero() = runTest {
    val server = FakeDavServer(content).apply { dropAfter = 1_000 }
    val source = WebDavDownloadSource(server)
    val file = MemoryFile()
    val context = context(file, connections = 1, preResolved = source.resolve(URL))
    assertFailsWith<KetchError.Network> { source.download(context, null) }
    val state = source.store(context)
    file.data = ByteArray(0)

    server.ranged.clear()
    source.download(context, state)
    assertContentEquals(content, file.data)
    assertEquals(listOf<LongRange?>(0L..9_999L), server.ranged)
  }

  @Test
  fun segments_carryTheStrongEtag_andFailWhenTheFileChanges() = runTest {
    val server = FakeDavServer(content)
    val engine = object : com.linroid.ketch.core.engine.HttpEngine by server {
      override suspend fun probe(
        url: String,
        headers: Map<String, String>,
      ): com.linroid.ketch.core.engine.ServerInfo {
        val info = server.probe(url, headers)
        // A new version of the same size lands between the inspection and the segments.
        server.etag = "\"v2\""
        return info
      }
    }
    val source = WebDavDownloadSource(engine)
    assertFailsWith<KetchError.FileChanged> {
      source.download(context(MemoryFile(), connections = 2), null)
    }
    assertTrue(server.conditions.isNotEmpty())
    assertTrue(server.conditions.all { it == listOf("If-Match: \"v1\"") })
  }

  @Test
  fun segments_withAWeakEtag_carryTheModificationTime() = runTest {
    val server = FakeDavServer(content, etag = "W/\"v1\"")
    val file = MemoryFile()
    WebDavDownloadSource(server).download(context(file, connections = 2), null)
    assertContentEquals(content, file.data)
    assertEquals(
      List(2) { listOf("If-Unmodified-Since: Mon, 12 Oct 2026 08:00:00 GMT") },
      server.conditions,
    )
  }

  @Test
  fun resume_withAWeakEtag_comparesTheModificationTime() = runTest {
    val server = FakeDavServer(content, etag = "W/\"v1\"").apply { dropAfter = 1_000 }
    val source = WebDavDownloadSource(server)
    val file = MemoryFile()
    val context = context(file, connections = 1, preResolved = source.resolve(URL))
    assertFailsWith<KetchError.Network> { source.download(context, null) }
    val state = source.store(context)

    // Weak tags that differ do not decide; the unchanged modification time keeps the bytes.
    server.etag = "W/\"v1-gzip\""
    server.ranged.clear()
    source.download(context, state)
    assertContentEquals(content, file.data)
    assertEquals(listOf<LongRange?>(1_000L..9_999L), server.ranged)

    server.lastModified = "Tue, 13 Oct 2026 08:00:00 GMT"
    assertFailsWith<KetchError.FileChanged> { source.download(context, state) }
  }

  @Test
  fun resume_withoutAValidator_startsOver() = runTest {
    val server = FakeDavServer(content, etag = "W/\"v1\"", lastModified = null)
      .apply { dropAfter = 1_000 }
    val source = WebDavDownloadSource(server)
    val file = MemoryFile()
    val context = context(file, connections = 1, preResolved = source.resolve(URL))
    assertFailsWith<KetchError.Network> { source.download(context, null) }
    val state = source.store(context)

    server.ranged.clear()
    server.conditions.clear()
    source.download(context, state)
    assertContentEquals(content, file.data)
    assertEquals(listOf<LongRange?>(0L..9_999L), server.ranged)
    assertEquals(listOf(emptyList()), server.conditions)
  }

  @Test
  fun store_beforeTheRun_usesTheResolution() = runTest {
    val source = WebDavDownloadSource(FakeDavServer(content))
    val context = context(MemoryFile(), preResolved = source.resolve(URL))
    assertEquals(WebDavSourceState("\"v1\"", "Mon, 12 Oct 2026 08:00:00 GMT", 10_000),
      source.store(context))
    assertNull(source.store(context(MemoryFile())))
  }

  private fun context(
    file: MemoryFile,
    connections: Int = 0,
    url: String = URL,
    headers: Map<String, String> = emptyMap(),
    preResolved: ResolvedSource? = null,
  ) = DownloadContext(
    taskId = "webdav",
    url = url,
    request = DownloadRequest(url, connections = connections),
    fileAccessor = file,
    segments = MutableStateFlow(emptyList()),
    onProgress = { _, _ -> },
    throttle = {},
    headers = headers,
    preResolved = preResolved,
    maxConnections = MutableStateFlow(connections),
  )

  private companion object {
    const val URL = "davs://cloud.example/dav/file.bin"
    const val AUTH_URL = "davs://user:secret@cloud.example/dav/file.bin"
  }
}
