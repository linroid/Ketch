package com.linroid.ketch.metalink

import com.linroid.ketch.api.Checksum
import com.linroid.ketch.api.ChecksumAlgorithm
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.core.digest.sha256Digest
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.canHandle
import com.linroid.ketch.core.engine.resolve
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetalinkDownloadSourceTest {
  private val docUrl = "https://example.com/release.meta4"
  private val iso = ByteArray(2000) { (it % 199).toByte() }
  private val notes = "release notes".encodeToByteArray()

  private fun hex(bytes: ByteArray) =
    Checksum.of(ChecksumAlgorithm.SHA_256, sha256Digest(bytes)).value

  private fun pieces(bytes: ByteArray, length: Int) = (bytes.indices step length).joinToString("") {
    "<hash>${hex(bytes.copyOfRange(it, minOf(it + length, bytes.size)))}</hash>"
  }

  private fun document(
    withSize: Boolean = true,
    second: Boolean = false,
    secondSize: Boolean = true,
  ) = """
    <?xml version="1.0" encoding="UTF-8"?>
    <metalink xmlns="urn:ietf:params:xml:ns:metalink">
      <file name="release.iso">
        ${if (withSize) "<size>${iso.size}</size>" else ""}
        <hash type="sha-256">${hex(iso)}</hash>
        <pieces length="512" type="sha-256">${pieces(iso, 512)}</pieces>
        <url priority="1">https://a.example/release.iso</url>
        <url priority="2">https://b.example/release.iso</url>
      </file>
      ${if (second) """
      <file name="NOTES.txt">
        ${if (secondSize) "<size>${notes.size}</size>" else ""}
        <hash type="sha-256">${hex(notes)}</hash>
        <url>https://example.com/NOTES.txt</url>
      </file>""" else ""}
    </metalink>
  """.trimIndent()

  private fun TestScope.source(engine: HttpEngine) =
    MetalinkDownloadSource(engine, testScheduler.timeSource)

  @Test
  fun canHandle_metalinkUrlsAndContent() {
    val source = MetalinkDownloadSource(Engine(""))
    assertTrue(source.canHandle("https://example.com/a.meta4?sig=1#x"))
    assertTrue(source.canHandle("HTTP://example.com/A.METALINK"))
    assertTrue(source.canHandle("metalink:0123"))
    assertFalse(source.canHandle("https://example.com/a.iso"))
    assertFalse(source.canHandle("https://example.com/download?file=a.meta4"))
    assertFalse(source.canHandle("ftp://example.com/a.meta4"))
    assertTrue(source.canHandle(ResolveInput.Content(byteArrayOf(), "x.meta4")))
    assertTrue(source.canHandle(ResolveInput.Content(document().encodeToByteArray())))
    assertFalse(source.canHandle(ResolveInput.Content("d8:announce".encodeToByteArray())))
    assertTrue(KetchFeatures.METALINK in source.features)
  }

  @Test
  fun resolve_url_keepsTheDocumentAndPublishesTheChecksum() = runTest {
    val source = source(Engine(document()))

    val resolved = source.resolve(docUrl)

    assertEquals("metalink", resolved.sourceType)
    assertEquals(2000, resolved.totalBytes)
    assertEquals("release.iso", resolved.suggestedFileName)
    assertEquals(Checksum(ChecksumAlgorithm.SHA_256, hex(iso)), resolved.checksum)
    assertEquals("2", resolved.metadata[MetalinkDownloadSource.META_MIRRORS])
    assertNotNull(resolved.metadata[ResolvedSource.METAINFO_KEY])
    assertTrue(resolved.files.isEmpty())
    assertTrue(resolved.supportsResume)
  }

  @Test
  fun resolve_severalFiles_offersThemAsASingleChoice() = runTest {
    val resolved = source(Engine(document(second = true))).resolve(docUrl)

    assertEquals(FileSelectionMode.SINGLE, resolved.selectionMode)
    assertEquals(listOf("0", "1"), resolved.files.map { it.id })
    assertEquals("NOTES.txt", resolved.files[1].metadata[SourceFile.FILE_NAME_KEY])
    assertEquals(notes.size.toLong(), resolved.files[1].size)
  }

  @Test
  fun resolve_withoutSize_asksTheMirrors() = runTest {
    val engine = Engine(document(withSize = false))
    val resolved = source(engine).resolve(docUrl)
    assertEquals(2000, resolved.totalBytes)
    assertEquals(1, engine.heads.size)
  }

  @Test
  fun resolve_otherFileWithoutSize_asksItsMirrorsWithTheRequestHeaders() = runTest {
    val engine = Engine(document(second = true, secondSize = false))
    val source = source(engine)
    val resolved = source.resolve(docUrl, mapOf("Cookie" to "secret"))

    assertEquals(notes.size.toLong(), resolved.files[1].size)
    // The mirror shares the Metalink's origin, so it gets the cookie.
    assertEquals("secret", engine.headHeaders.single()["Cookie"])
    val plan = source.planSelection(
      SelectionRequest<MetalinkSourceState>(
        "t", setOf("1"), emptySet(), null, resolved, null, null, completed = false,
      ),
    )
    assertEquals(notes.size.toLong(), plan.totalBytes)
    assertEquals(1, engine.heads.size)
  }

  @Test
  fun resolve_content_downloadsFromAMetalinkUrl() = runTest {
    val source = source(Engine(""))
    val resolved = source.resolve(ResolveInput.Content(document().encodeToByteArray(), "r.meta4"))
    assertTrue(resolved.url.startsWith("metalink:"))
    assertTrue(source.canHandle(resolved.url))
    assertEquals(2000, resolved.totalBytes)
    assertFailsWith<KetchError.SourceError> { source.resolve(resolved.url) }
  }

  @Test
  fun resolve_notAMetalink_isASourceError() = runTest {
    val error = assertFailsWith<KetchError.SourceError> {
      source(Engine("<html></html>")).resolve(docUrl)
    }
    assertEquals("metalink", error.sourceType)
  }

  @Test
  fun download_fromTheKeptDocument_writesTheFileFromEveryMirror() = runTest {
    val engine = Engine(document())
    val source = source(engine)
    val resolved = source.resolve(docUrl)
    val file = MemoryFile()
    val context = context(file, resolved)

    source.download(context, null)

    assertContentEquals(iso, file.bytes)
    assertEquals(resolved.checksum, context.publishedChecksum)
    assertEquals(1, engine.documents)
    assertEquals(setOf("https://a.example/release.iso", "https://b.example/release.iso"),
      engine.ranges.map { it.first }.toSet())
    assertEquals(MetalinkSourceState("0"), source.store(context))
  }

  @Test
  fun download_chosenFile_writesThatFile() = runTest {
    val source = source(Engine(document(second = true)))
    val resolved = source.resolve(docUrl)
    val file = MemoryFile()
    val context = context(file, resolved, setOf("1"))

    source.download(context, null)

    assertContentEquals(notes, file.bytes)
    assertEquals(Checksum(ChecksumAlgorithm.SHA_256, hex(notes)), context.publishedChecksum)
  }

  @Test
  fun planSelection_anotherFileOnceStarted_isRefused() = runTest {
    val source = source(Engine(document(second = true)))
    val resolved = source.resolve(docUrl)
    fun request(outputPath: String?) = SelectionRequest<MetalinkSourceState>(
      taskId = "t", fileIds = setOf("1"), current = setOf("0"), sourceState = null,
      resolved = resolved, segments = null, outputPath = outputPath, completed = false,
    )

    val plan = source.planSelection(request(outputPath = null))
    assertEquals(notes.size.toLong(), plan.totalBytes)
    assertTrue(plan.changed)
    assertFailsWith<IllegalStateException> { source.planSelection(request("/tmp/release.iso")) }
    assertFailsWith<IllegalArgumentException> {
      source.planSelection(
        SelectionRequest(
          "t", setOf("9"), emptySet(), null, resolved, null, null, completed = false,
        ),
      )
    }
  }

  private fun context(
    file: MemoryFile,
    resolved: ResolvedSource,
    selected: Set<String> = emptySet(),
  ) = DownloadContext(
    taskId = "metalink",
    url = resolved.url,
    request = DownloadRequest(resolved.url, connections = 4, selectedFileIds = selected),
    fileAccessor = file,
    segments = MutableStateFlow(emptyList()),
    onProgress = { _, _ -> },
    throttle = {},
    headers = emptyMap(),
    preResolved = resolved,
  )

  private inner class Engine(private val document: String) : HttpEngine {
    var documents = 0
    val heads = mutableListOf<String>()
    val headHeaders = mutableListOf<Map<String, String>>()
    val ranges = mutableListOf<Pair<String, LongRange?>>()

    private fun content(url: String) = if (url.endsWith("NOTES.txt")) notes else iso

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
      heads += url
      headHeaders += headers
      return ServerInfo(
        contentLength = content(url).size.toLong(), acceptRanges = true, etag = null,
        lastModified = null,
      )
    }

    override suspend fun downloadResource(
      url: String,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ): String {
      documents++
      onData(document.encodeToByteArray())
      return url
    }

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      ranges += url to range
      val bytes = content(url)
      val wanted = range ?: 0L..bytes.lastIndex.toLong()
      onData(bytes.copyOfRange(wanted.first.toInt(), wanted.last.toInt() + 1))
    }

    override fun close() {}
  }

  private class MemoryFile : FileAccessor {
    var bytes: ByteArray = byteArrayOf()

    override suspend fun writeAt(offset: Long, data: ByteArray) {
      bytes = bytes.copyOf(maxOf(bytes.size, offset.toInt() + data.size))
      data.copyInto(bytes, offset.toInt())
    }

    override suspend fun readAt(
      offset: Long,
      buffer: ByteArray,
      bufferOffset: Int,
      byteCount: Int,
    ): Int {
      if (offset >= bytes.size) return -1
      val count = minOf(byteCount.toLong(), bytes.size - offset).toInt()
      bytes.copyInto(buffer, bufferOffset, offset.toInt(), offset.toInt() + count)
      return count
    }

    override suspend fun preallocate(size: Long) {
      bytes = ByteArray(size.toInt())
    }

    override suspend fun size(): Long = bytes.size.toLong()
    override suspend fun delete() {
      bytes = byteArrayOf()
    }

    override suspend fun flush() {}
    override fun close() {}
  }
}
