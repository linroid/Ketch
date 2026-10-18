package com.linroid.ketch.core.media

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.engine.SelectionUpdate
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.core.media.Mp4Fixtures.init
import com.linroid.ketch.core.media.Mp4Fixtures.segment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MediaDownloadHelperTest {
  @Test
  fun download_aes128Parts_decryptsWithOneKeyFetch() = runTest {
    val engine = PartsEngine(mapOf(
      "https://keys.example/k" to hex("000102030405060708090a0b0c0d0e0f"),
      "https://example.com/1.ts" to hex("31986406a37445c5762cb21866bada77"),
      "https://example.com/2.ts" to
        hex("26ecb304c12aeecb621951aeb11b498596802b21dd75f4e849518787d666e4ae"),
    ))
    val iv = { n: Int -> ByteArray(16).also { it[15] = n.toByte() } }
    val plan = MediaPlan(listOf(
      MediaPart("https://example.com/1.ts", key = MediaKey("https://keys.example/k", iv(7))),
      MediaPart("https://example.com/2.ts", key = MediaKey("https://keys.example/k", iv(8))),
    ), "ts")
    val file = MemoryFile()

    MediaDownloadHelper(engine, "hls").download(context(file, mapOf("Cookie" to "c")), plan)

    assertEquals("firstsecond segment!!", file.bytes.decodeToString())
    assertEquals(listOf("https://keys.example/k", "https://example.com/1.ts",
      "https://example.com/2.ts"), engine.requests.map { it.first })
    // The key lives on another origin, so it gets no cookie.
    assertEquals(emptyMap(), engine.requests.first().second)
  }

  @Test
  fun download_wrongKey_failsAsSourceError() = runTest {
    val engine = PartsEngine(mapOf(
      "https://example.com/k" to ByteArray(16),
      "https://example.com/2.ts" to
        hex("26ecb304c12aeecb621951aeb11b498596802b21dd75f4e849518787d666e4ae"),
    ))
    val plan = MediaPlan(listOf(MediaPart("https://example.com/2.ts",
      key = MediaKey("https://example.com/k", ByteArray(16)))), "ts")
    val error = assertFailsWith<KetchError.SourceError> {
      MediaDownloadHelper(engine, "hls").download(context(MemoryFile()), plan)
    }
    assertEquals("hls", error.sourceType)
  }

  @Test
  fun download_twoTracks_writesOneMovieWithInterleavedFragments() = runTest {
    val engine = PartsEngine(mapOf(
      "https://example.com/v/init.mp4" to init(1, 1000, 0, 0),
      "https://example.com/a/init.mp4" to init(1, 48000, 0, 0),
      "https://example.com/v/1.m4s" to segment(1, 1, "v1"),
      "https://example.com/v/2.m4s" to segment(1, 2, "v2"),
      "https://example.com/a/1.m4s" to segment(1, 1, "a1"),
      "https://example.com/a/2.m4s" to segment(1, 2, "a2"),
    ))
    fun track(name: String) = MediaPlan(
      listOf(MediaPart("https://example.com/$name/init.mp4", init = true)) +
        (1..2).map { MediaPart("https://example.com/$name/$it.m4s", startSeconds = it * 2.0) },
      "mp4",
    )
    val file = MemoryFile()

    MediaDownloadHelper(engine, "dash")
      .download(context(file), mergeTracks(track("v"), track("a")))

    val out = file.bytes
    val boxes = mp4Boxes(out)
    assertEquals(listOf("ftyp", "moov") + List(4) { listOf("moof", "mdat") }.flatten(),
      boxes.map { it.type })
    val media = boxes.filter { it.type == "mdat" }
      .map { out.copyOfRange(it.payload, it.end).decodeToString() }
    assertEquals(listOf("v1", "a1", "v2", "a2"), media)
    val fragments = boxes.filter { it.type == "moof" }.map { moof ->
      val children = mp4Boxes(out, moof.payload, moof.end)
      val tfhd = mp4Boxes(out, children[1].payload, children[1].end).first()
      u32(out, children[0].payload + 4) to u32(out, tfhd.payload + 4)
    }
    assertEquals(listOf(1L to 1L, 2L to 2L, 3L to 1L, 4L to 2L), fragments)
  }

  @Test
  fun download_otherContainerThanTheChosenName_isRefusedUnlessTheUserNamedIt() = runTest {
    val engine = PartsEngine(mapOf("https://example.com/a.ts" to "ts".encodeToByteArray()))
    val plan = MediaPlan(listOf(MediaPart("https://example.com/a.ts")), "ts")
    val helper = MediaDownloadHelper(engine, "hls")
    assertFailsWith<KetchError.SourceError> {
      helper.download(context(MemoryFile(), outputPath = "/downloads/index.mp4"), plan)
    }
    val file = MemoryFile()
    helper.download(context(file, outputPath = "/downloads/index.mp4",
      destination = Destination("/downloads/index.mp4")), plan)
    assertEquals("ts", file.bytes.decodeToString())
  }

  @Test
  fun downloadFollowingSelection_newVariant_restartsAndAcknowledges() = runTest {
    val started = CompletableDeferred<Unit>()
    val engine = PartsEngine(mapOf(
      "https://example.com/low.ts" to "low".encodeToByteArray(),
      "https://example.com/high.ts" to "high".encodeToByteArray(),
    )) { url ->
      if (url.endsWith("high.ts")) {
        started.complete(Unit)
        CompletableDeferred<Unit>().await()
      }
    }
    val selection = MutableStateFlow(SelectionUpdate(setOf("0"), 0))
    val file = MemoryFile()
    val context = context(file, selection = selection)
    val planned = mutableListOf<Set<String>>()
    val job = launch {
      MediaDownloadHelper(engine, "hls").downloadFollowingSelection(context) { selected ->
        planned += selected
        val name = if (selected == setOf("0")) "high" else "low"
        MediaPlan(listOf(MediaPart("https://example.com/$name.ts")), "ts")
      }
    }
    started.await()
    selection.value = SelectionUpdate(setOf("1"), 1)
    runCurrent()
    job.join()

    assertEquals(listOf(setOf("0"), setOf("1")), planned)
    assertEquals(1, context.acknowledgedSelection.value)
    assertEquals("low", file.bytes.decodeToString())
  }

  @Test
  fun planSelection_checksOneKnownVariant() {
    val helper = MediaDownloadHelper(PartsEngine(emptyMap()), "hls")
    val resolved = helper.resolve(
      "https://example.com/index.m3u8",
      MediaPlan(listOf(MediaPart("https://example.com/a.ts")), "ts"),
      listOf(MediaVariant("0", "1080p", 5_000_000), MediaVariant("1", "720p", 2_000_000)),
      chosen = "0",
    )
    assertEquals(FileSelectionMode.SINGLE, resolved.selectionMode)
    assertEquals(listOf("0", "1"), resolved.files.map { it.id })
    assertEquals("5000000", resolved.files[0].metadata[MediaDownloadHelper.BANDWIDTH_KEY])
    fun request(ids: Set<String>?, current: Set<String> = emptySet()) =
      SelectionRequest<Unit>("t", ids, current, null, resolved, null, null, false)
    val plan = helper.planSelection(request(setOf("1")))
    assertTrue(plan.changed && plan.expands)
    assertEquals(-1, plan.totalBytes)
    assertTrue(!helper.planSelection(request(setOf("0"))).changed)
    assertFailsWith<IllegalArgumentException> { helper.planSelection(request(setOf("0", "1"))) }
    assertFailsWith<IllegalArgumentException> { helper.planSelection(request(setOf("9"))) }
  }

  private fun context(
    file: MemoryFile,
    headers: Map<String, String> = emptyMap(),
    selection: MutableStateFlow<SelectionUpdate> =
      MutableStateFlow(SelectionUpdate(emptySet(), 0)),
    outputPath: String? = null,
    destination: Destination? = null,
  ) = DownloadContext(
    taskId = "media",
    url = "https://example.com/index.m3u8",
    request = DownloadRequest("https://example.com/index.m3u8", destination = destination),
    outputPath = outputPath,
    fileAccessor = file,
    segments = MutableStateFlow(emptyList()),
    onProgress = { _, _ -> },
    throttle = {},
    headers = headers,
    selection = selection,
  )

  private fun u32(bytes: ByteArray, at: Int): Long =
    ((bytes[at].toLong() and 0xff) shl 24) or ((bytes[at + 1].toLong() and 0xff) shl 16) or
      ((bytes[at + 2].toLong() and 0xff) shl 8) or (bytes[at + 3].toLong() and 0xff)

  private class PartsEngine(
    val bodies: Map<String, ByteArray>,
    val before: suspend (String) -> Unit = {},
  ) : HttpEngine {
    val requests = mutableListOf<Pair<String, Map<String, String>>>()
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Media does not require HEAD")
    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      requests += url to headers
      before(url)
      val body = bodies.getValue(url)
      // Two pieces, so transformations see a part arrive in several chunks.
      onData(body.copyOfRange(0, body.size / 2))
      onData(body.copyOfRange(body.size / 2, body.size))
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
