package com.linroid.ketch.core.media

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
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

class MediaDownloadSourceTest {
  @Test
  fun download_redirectedPlaylist_joinsSegmentsAndScopesCredentials() = runTest {
    val engine = MediaEngine()
    val source = MediaDownloadSource(engine)
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
    assertEquals(listOf("https://cdn.example/media/a.ts", "https://cdn.example/media/b.ts"),
      engine.parts.map { it.first })
    assertEquals(listOf(mapOf("User-Agent" to "Ketch"), mapOf("User-Agent" to "Ketch")),
      engine.parts.map { it.second })
    file.bytes = "stale partial output".encodeToByteArray()
    source.resume(context, SourceResumeState("media", "{}"))
    assertEquals("firstsecond", file.bytes.decodeToString())
  }

  @Test
  fun download_oversizedManifestAndLivePlaylist_neverWriteOutput() = runTest {
    for (text in listOf("x".repeat(1024 * 1024 + 1), "#EXTM3U\n#EXTINF:1,\na.ts")) {
      val engine = MediaEngine(text)
      val source = MediaDownloadSource(engine)
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
    val manifest: String = "#EXTM3U\n#EXTINF:1,\na.ts\n#EXTINF:1,\nb.ts\n#EXT-X-ENDLIST",
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
      return "https://cdn.example/media/list.m3u8"
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
