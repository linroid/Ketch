package com.linroid.ketch.dash

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.ConnectionHandle
import com.linroid.ketch.core.engine.ConnectionReporter
import com.linroid.ketch.core.engine.ConnectionSpec
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashDownloadSourceTest {
  @Test
  fun canHandle_onlyOwnProtocol_acceptsQueryAndFragment() {
    val source = DashDownloadSource(MediaEngine())
    assertTrue(source.canHandle("HTTPS://example.com/INDEX.MPD?token=x#fragment"))
    assertFalse(source.canHandle("https://example.com/index.m3u8"))
    assertFalse(source.canHandle("ftp://example.com/index.mpd"))
    assertFalse(source.canHandle("https://example.com/file?name=index.mpd"))
  }

  @Test
  fun resolve_reportsProtocolTypeAndOutputName() = runTest {
    val engine = MediaEngine()
    val source = DashDownloadSource(engine)
    val resolved = source.resolve(engine.url)
    assertEquals("dash", resolved.sourceType)
    assertEquals("index.mp4", resolved.suggestedFileName)
    assertEquals("dash", source.buildResumeState(resolved, 0).sourceType)
  }

  @Test
  fun resolve_invalidUrls_reportProtocolType() = runTest {
    val manifest = MediaEngine().manifest
    for (url in listOf(
      "https://example.com/bad path.mpd",
      "https://user:secret@example.com/index.mpd",
      "file:///index.mpd"
    )) {
      val source = DashDownloadSource(MediaEngine())
      val error = assertFailsWith<KetchError.SourceError> { source.resolve(url) }
      assertEquals("dash", error.sourceType)
    }
    for (reference in listOf(
      "https://example.com/bad path",
      "https://user:secret@example.com/part",
      "file:///part",
      "http://example.com/part"
    )) {
      val engines = listOf(
        MediaEngine(responseUrl = reference),
        MediaEngine(manifest = manifest.replace("a.mp4", reference))
      )
      for (engine in engines) {
        val source = DashDownloadSource(engine)
        val error = assertFailsWith<KetchError.SourceError> { source.resolve(engine.url) }
        assertEquals("dash", error.sourceType)
      }
    }
  }

  @Test
  fun download_redirectedPlaylist_joinsSegmentsAndScopesCredentials() = runTest {
    val engine = MediaEngine()
    val source = DashDownloadSource(engine)
    val file = MemoryFile()
    var throttled = 0
    val context = DownloadContext(
      taskId = "media",
      url = engine.url,
      request = DownloadRequest(engine.url),
      fileAccessor = file,
      segments = MutableStateFlow(emptyList()),
      onProgress = { _, _ -> },
      throttle = { throttled += it },
      headers = mapOf("Cookie" to "secret", "User-Agent" to "Ketch"),
    )
    source.download(context)
    assertEquals("firstsecond", file.bytes.decodeToString())
    assertEquals(11, throttled)
    assertEquals(listOf("https://cdn.example/media/a.mp4", "https://cdn.example/media/b.m4s"),
      engine.parts.map { it.first })
    assertEquals(listOf(mapOf("User-Agent" to "Ketch"), mapOf("User-Agent" to "Ketch")),
      engine.parts.map { it.second })
    file.bytes = "stale partial output".encodeToByteArray()
    source.resume(context, SourceResumeState("media", "{}"))
    assertEquals("firstsecond", file.bytes.decodeToString())
  }

  @Test
  fun download_parts_reportOneConnectionFollowingEachPartsHost() = runTest {
    val engine = MediaEngine(
      manifest = MediaEngine().manifest.replace("b.m4s", "https://edge.example:8443/b.m4s"),
    )
    val connections = RecordingConnections()
    val context = DownloadContext(
      taskId = "media",
      url = engine.url,
      request = DownloadRequest(engine.url),
      fileAccessor = MemoryFile(),
      segments = MutableStateFlow(emptyList()),
      onProgress = { _, _ -> },
      throttle = {},
      headers = emptyMap(),
      connections = connections,
    )

    DashDownloadSource(engine).download(context)

    val connection = connections.opened.single()
    assertEquals(listOf("cdn.example" to 443, "edge.example" to 8443),
      connection.specs.map { it.host to it.port })
    assertTrue(connection.specs.all { it.source == "dash" && it.secure == true })
    assertEquals(11L, connection.received)
    assertTrue(connection.closed)
  }

  @Test
  fun download_oversizedManifestAndDynamicManifest_neverWriteOutput() = runTest {
    for (text in listOf("x".repeat(1024 * 1024 + 1), "<MPD type='dynamic'/>")) {
      val engine = MediaEngine(text)
      val source = DashDownloadSource(engine)
      val file = MemoryFile()
      file.bytes = "existing".encodeToByteArray()
      val context = DownloadContext("id", engine.url, DownloadRequest(engine.url), file,
        MutableStateFlow(emptyList()), { _, _ -> }, {}, emptyMap())
      assertFailsWith<KetchError.SourceError> { source.download(context) }
      assertEquals("existing", file.bytes.decodeToString())
      assertEquals(0, engine.parts.size)
    }
  }

  private class MediaEngine(
    val manifest: String = """
      <MPD><Period><AdaptationSet mimeType="video/mp4"><Representation id="v">
        <SegmentList><Initialization sourceURL="a.mp4"/><SegmentURL media="b.m4s"/></SegmentList>
      </Representation></AdaptationSet></Period></MPD>
    """.trimIndent(),
    val responseUrl: String = "https://cdn.example/media/list.mpd",
  ) : HttpEngine {
    val url = "https://example.com/index.mpd"
    val parts = mutableListOf<Pair<String, Map<String, String>>>()
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Media does not require HEAD")
    override suspend fun downloadResource(
      url: String,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ): String {
      onData(manifest.encodeToByteArray())
      return responseUrl
    }
    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      parts += url to headers
      val content = if (url.endsWith("a.mp4")) "first" else "second"
      onData(content.encodeToByteArray())
    }
    override fun close() {}
  }

  /** Records the connections a source opens and every host each one reports. */
  private class RecordingConnections : ConnectionReporter {
    val opened = mutableListOf<Connection>()

    override fun open(spec: ConnectionSpec): ConnectionHandle =
      Connection(spec).also { opened += it }

    class Connection(spec: ConnectionSpec) : ConnectionHandle {
      val specs = mutableListOf(spec)
      var received = 0L
      var closed = false

      override fun received(bytes: Int) {
        received += bytes
      }

      override fun sent(bytes: Int) {}

      override fun describe(spec: ConnectionSpec) {
        specs += spec
      }

      override fun close() {
        closed = true
      }
    }
  }

  private class MemoryFile : FileAccessor {
    var bytes: ByteArray = byteArrayOf()
    override suspend fun writeAt(offset: Long, data: ByteArray) {
      bytes = bytes.copyOf(maxOf(bytes.size, offset.toInt() + data.size))
      data.copyInto(bytes, offset.toInt())
    }
    override suspend fun preallocate(size: Long) { bytes = ByteArray(size.toInt()) }
    override suspend fun size(): Long = bytes.size.toLong()
    override suspend fun delete() { bytes = byteArrayOf() }
    override suspend fun flush() {}
    override fun close() {}
  }
}
