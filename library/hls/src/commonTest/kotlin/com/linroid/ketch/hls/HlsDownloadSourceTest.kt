package com.linroid.ketch.hls

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.core.engine.ConnectionHandle
import com.linroid.ketch.core.engine.ConnectionReporter
import com.linroid.ketch.core.engine.ConnectionSpec
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.SourceState
import com.linroid.ketch.core.engine.canHandle
import com.linroid.ketch.core.engine.resolve
import com.linroid.ketch.core.engine.decodeState
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.core.media.MediaDownloadHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlsDownloadSourceTest {
  @Test
  fun canHandle_onlyOwnProtocol_acceptsQueryAndFragment() {
    val source = HlsDownloadSource(MediaEngine())
    assertTrue(source.canHandle("HTTPS://example.com/INDEX.M3U8?token=x#fragment"))
    assertFalse(source.canHandle("https://example.com/index.mpd"))
    assertFalse(source.canHandle("ftp://example.com/index.m3u8"))
    assertFalse(source.canHandle("https://example.com/file?name=index.m3u8"))
  }

  @Test
  fun resolve_reportsProtocolTypeAndOutputName() = runTest {
    val engine = MediaEngine()
    val source = HlsDownloadSource(engine)
    val resolved = source.resolve(engine.url)
    assertEquals("hls", resolved.sourceType)
    assertEquals("index.ts", resolved.suggestedFileName)
  }

  @Test
  fun resolve_invalidUrls_reportProtocolType() = runTest {
    val manifest = MediaEngine().manifest
    for (url in listOf(
      "https://example.com/bad path.m3u8",
      "https://user:secret@example.com/index.m3u8",
      "file:///index.m3u8"
    )) {
      val source = HlsDownloadSource(MediaEngine())
      val error = assertFailsWith<KetchError.SourceError> { source.resolve(url) }
      assertEquals("hls", error.sourceType)
    }
    for (reference in listOf(
      "https://example.com/bad path",
      "https://user:secret@example.com/part",
      "file:///part",
      "http://example.com/part"
    )) {
      val engines = listOf(
        MediaEngine(responseUrl = reference),
        MediaEngine(manifest = manifest.replace("a.ts", reference))
      )
      for (engine in engines) {
        val source = HlsDownloadSource(engine)
        val error = assertFailsWith<KetchError.SourceError> { source.resolve(engine.url) }
        assertEquals("hls", error.sourceType)
      }
    }
  }

  @Test
  fun download_redirectedPlaylist_joinsSegmentsAndScopesCredentials() = runTest {
    val engine = MediaEngine()
    val source = HlsDownloadSource(engine)
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
    source.download(context, null)
    assertEquals("firstsecond", file.bytes.decodeToString())
    assertEquals(11, throttled)
    assertEquals(listOf("https://cdn.example/media/a.ts", "https://cdn.example/media/b.ts"),
      engine.parts.map { it.first })
    assertEquals(listOf(mapOf("User-Agent" to "Ketch"), mapOf("User-Agent" to "Ketch")),
      engine.parts.map { it.second })
    file.bytes = "stale partial output".encodeToByteArray()
    // Tasks saved before media sources split carry the "media" type and an empty object.
    source.download(context, source.decodeState(SourceState("media", "{}")))
    assertEquals("firstsecond", file.bytes.decodeToString())
  }

  @Test
  fun download_parts_reportOneConnectionFollowingEachPartsHost() = runTest {
    val engine = MediaEngine(
      manifest = MediaEngine().manifest.replace("b.ts", "https://edge.example:8443/b.ts"),
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

    HlsDownloadSource(engine).download(context, null)

    val connection = connections.opened.single()
    assertEquals(listOf("cdn.example" to 443, "edge.example" to 8443),
      connection.specs.map { it.host to it.port })
    assertTrue(connection.specs.all { it.source == "hls" && it.secure == true })
    assertEquals(11L, connection.received)
    assertTrue(connection.closed)
  }

  @Test
  fun download_oversizedManifestAndLivePlaylist_neverWriteOutput() = runTest {
    for (text in listOf("x".repeat(1024 * 1024 + 1), "#EXTM3U\n#EXTINF:1,\na.ts")) {
      val engine = MediaEngine(text)
      val source = HlsDownloadSource(engine)
      val file = MemoryFile()
      file.bytes = "existing".encodeToByteArray()
      val context = DownloadContext("id", engine.url, DownloadRequest(engine.url), file,
        MutableStateFlow(emptyList()), { _, _ -> }, {}, emptyMap())
      assertFailsWith<KetchError.SourceError> { source.download(context, null) }
      assertEquals("existing", file.bytes.decodeToString())
      assertEquals(0, engine.parts.size)
    }
  }

  @Test
  fun resolve_master_listsVariantsBestFirstAsASingleChoice() = runTest {
    val resolved = HlsDownloadSource(MasterEngine()).resolve(MasterEngine.URL)
    assertEquals(FileSelectionMode.SINGLE, resolved.selectionMode)
    assertEquals(listOf("1080p · 5.0 Mbps", "720p · 2.5 Mbps", "480p · 1.0 Mbps"),
      resolved.files.map { it.name })
    assertEquals(3, resolved.files.map { it.id }.toSet().size)
    assertEquals("1920x1080", resolved.files[0].metadata["resolution"])
    assertEquals(resolved.files[0].id, resolved.metadata[MediaDownloadHelper.VARIANT_KEY])
    assertTrue(KetchFeatures.FILE_SELECTION in HlsDownloadSource(MasterEngine()).features)
  }

  @Test
  fun download_selectedVariant_fetchesOnlyItsSegments() = runTest {
    val engine = MasterEngine()
    val source = HlsDownloadSource(engine)
    val resolved = source.resolve(MasterEngine.URL)
    val request = DownloadRequest(MasterEngine.URL, selectedFileIds = setOf(resolved.files[2].id))
    val file = MemoryFile()
    source.download(DownloadContext("t", MasterEngine.URL, request, file,
      MutableStateFlow(emptyList()), { _, _ -> }, {}, emptyMap(), preResolved = resolved), null)
    assertEquals("480-a480-b", file.bytes.decodeToString())
    assertEquals(listOf("https://example.com/480/a.ts", "https://example.com/480/b.ts"),
      engine.parts)

    // A manifest whose variants changed since the task chose one is not guessed at.
    val stale = resolved.copy(files = resolved.files.map {
      it.copy(metadata = it.metadata + (MediaDownloadHelper.BANDWIDTH_KEY to "1"))
    })
    assertFailsWith<KetchError.SourceError> {
      source.download(DownloadContext("t", MasterEngine.URL, request, MemoryFile(),
        MutableStateFlow(emptyList()), { _, _ -> }, {}, emptyMap(), preResolved = stale), null)
    }
  }

  @Test
  fun resolve_reorderedOrSignedAgain_keepsEachVariantsId() = runTest {
    val first = HlsDownloadSource(MasterEngine()).resolve(MasterEngine.URL)
    val master = MasterEngine().manifests.getValue(MasterEngine.URL)
    val lines = master.lines()
    // The 1080p variant first, and every variant URL signed anew.
    val refreshed = (listOf(lines[0]) + lines.subList(3, 5) + lines.subList(1, 3) + lines.drop(5))
      .joinToString("\n") { if (it.endsWith(".m3u8")) "$it?token=new" else it }
    val engine = MasterEngine(MasterEngine().manifests.mapKeys { (url, _) ->
      if (url == MasterEngine.URL) url else "$url?token=new"
    } + (MasterEngine.URL to refreshed))
    val second = HlsDownloadSource(engine).resolve(MasterEngine.URL)
    assertEquals(first.files.associate { it.name to it.id },
      second.files.associate { it.name to it.id })
  }

  @Test
  fun resolve_separateAudio_mergesFragmentedMp4AndRefusesTransportStreams() = runTest {
    val master = """
      #EXTM3U
      #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="English",DEFAULT=YES,URI="audio.m3u8"
      #EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO="a"
      video.m3u8
    """.trimIndent()
    fun media(segment: String) = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:1,\n$segment\n" +
      "#EXT-X-ENDLIST"
    val fmp4 = MasterEngine(mapOf(
      MasterEngine.URL to master,
      "https://example.com/video.m3u8" to media("v.m4s"),
      "https://example.com/audio.m3u8" to media("a.m4s"),
    ))
    val resolved = HlsDownloadSource(fmp4).resolve(MasterEngine.URL)
    assertEquals("2", resolved.metadata[MediaDownloadHelper.TRACKS_KEY])
    assertEquals("master.mp4", resolved.suggestedFileName)

    val ts = MasterEngine(mapOf(
      MasterEngine.URL to master,
      "https://example.com/video.m3u8" to "#EXTM3U\n#EXTINF:1,\nv.ts\n#EXT-X-ENDLIST",
      "https://example.com/audio.m3u8" to "#EXTM3U\n#EXTINF:1,\na.aac\n#EXT-X-ENDLIST",
    ))
    assertFailsWith<KetchError.SourceError> { HlsDownloadSource(ts).resolve(MasterEngine.URL) }
  }

  @Test
  fun planSelection_unknownVariant_isRefused() = runTest {
    val source = HlsDownloadSource(MasterEngine())
    val resolved = source.resolve(MasterEngine.URL)
    fun request(ids: Set<String>) =
      SelectionRequest<Unit>("t", ids, emptySet(), null, resolved, null, null, false)
    val id = resolved.files[1].id
    assertEquals(setOf(id), source.planSelection(request(setOf(id))).fileIds)
    assertFailsWith<IllegalArgumentException> { source.planSelection(request(setOf("7"))) }
  }

  /** A master playlist of three TS variants, by URL. */
  private class MasterEngine(
    val manifests: Map<String, String> = mapOf(
      URL to """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=854x480
        480/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028"
        1080/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
        720/index.m3u8
      """.trimIndent(),
    ) + listOf("480", "720", "1080").associate {
      "https://example.com/$it/index.m3u8" to
        "#EXTM3U\n#EXTINF:1,\na.ts\n#EXTINF:1,\nb.ts\n#EXT-X-ENDLIST"
    },
  ) : HttpEngine {
    val parts = mutableListOf<String>()
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Media does not require HEAD")
    override suspend fun downloadResource(
      url: String,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ): String {
      onData(manifests.getValue(url).encodeToByteArray())
      return url
    }
    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      parts += url
      val name = url.removePrefix("https://example.com/").removeSuffix(".ts").replace('/', '-')
      onData(name.encodeToByteArray())
    }
    override fun close() {}

    companion object {
      const val URL = "https://example.com/master.m3u8"
    }
  }

  private class MediaEngine(
    val manifest: String = "#EXTM3U\n#EXTINF:1,\na.ts\n#EXTINF:1,\nb.ts\n#EXT-X-ENDLIST",
    val responseUrl: String = "https://cdn.example/media/list.m3u8",
  ) : HttpEngine {
    val url = "https://example.com/index.m3u8"
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
      val content = if (url.endsWith("a.ts")) "first" else "second"
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
