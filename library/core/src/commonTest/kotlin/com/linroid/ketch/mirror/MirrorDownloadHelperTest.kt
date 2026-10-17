package com.linroid.ketch.mirror

import com.linroid.ketch.api.Checksum
import com.linroid.ketch.api.ChecksumAlgorithm
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.digest.sha256Digest
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.core.mirror.Mirror
import com.linroid.ketch.core.mirror.MirrorDownloadHelper
import com.linroid.ketch.core.mirror.PieceHashes
import com.linroid.ketch.core.mirror.cover
import com.linroid.ketch.core.mirror.mergedRanges
import com.linroid.ketch.core.mirror.mirrorHeaders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MirrorDownloadHelperTest {
  private val content = ByteArray(4096) { (it * 31 % 251).toByte() }
  private val origin = "https://origin.example/file.meta4"
  private val a = "https://a.example/file.iso"
  private val b = "https://b.example/file.iso"
  private val c = "https://origin.example/mirror/file.iso"

  private fun pieces(length: Long = 512) = PieceHashes(
    ChecksumAlgorithm.SHA_256,
    length,
    (0 until content.size step length.toInt()).map { start ->
      val end = minOf(start + length.toInt(), content.size)
      Checksum.of(ChecksumAlgorithm.SHA_256, sha256Digest(content.copyOfRange(start, end)))
    },
  )

  private fun context(file: MemoryFile, segments: List<Segment> = emptyList()) = DownloadContext(
    taskId = "mirrors",
    url = origin,
    request = DownloadRequest(origin, connections = 4),
    fileAccessor = file,
    segments = MutableStateFlow(segments),
    onProgress = { _, _ -> },
    throttle = {},
    headers = mapOf("Cookie" to "secret", "User-Agent" to "Ketch"),
  )

  private fun TestScope.helper(engine: HttpEngine) =
    MirrorDownloadHelper(engine, "metalink", testScheduler.timeSource)

  @Test
  fun download_severalMirrors_spreadsSegmentsAndScopesCredentials() = runTest {
    val engine = MirrorEngine(content)
    val file = MemoryFile()

    helper(engine).download(context(file), listOf(Mirror(a), Mirror(b), Mirror(c)), 4096)

    assertContentEquals(content, file.bytes)
    assertEquals(setOf(a, b, c), engine.requests.map { it.url }.toSet())
    assertEquals(4, engine.requests.size)
    // Only the mirror on the Metalink's own origin gets the cookie.
    assertEquals("secret", engine.requests.first { it.url == c }.headers["Cookie"])
    val others = engine.requests.filter { it.url != c }
    assertTrue(others.all { it.headers == mapOf("User-Agent" to "Ketch") })
  }

  @Test
  fun download_preferredMirrorsFirst_whenThereAreMoreMirrorsThanConnections() = runTest {
    val engine = MirrorEngine(content)
    val mirrors = (1..8).map { Mirror("https://m$it.example/f", priority = 9 - it) }

    helper(engine).download(context(MemoryFile()), mirrors, 4096)

    assertEquals(
      setOf("https://m8.example/f", "https://m7.example/f", "https://m6.example/f",
        "https://m5.example/f"),
      engine.requests.map { it.url }.toSet(),
    )
  }

  @Test
  fun download_mirrorRefuses_isDroppedAndOthersFinish() = runTest {
    val engine = MirrorEngine(content).apply { fail[a] = { KetchError.Http(404) } }
    val file = MemoryFile()

    helper(engine).download(context(file), listOf(Mirror(a), Mirror(b)), 4096)

    assertContentEquals(content, file.bytes)
    // Asked once by each segment that started on it, never again once it refused.
    assertEquals(2, engine.requests.count { it.url == a })
  }

  @Test
  fun download_mirrorDropsMidway_continuesFromTheByteItReached() = runTest {
    val engine = MirrorEngine(content).apply { failAfter[a] = 128 }
    val file = MemoryFile()

    helper(engine).download(context(file), listOf(Mirror(a), Mirror(b)), 4096)

    assertContentEquals(content, file.bytes)
    val broken = engine.requests.filter { it.url == a }.map { it.range!! }
    for (range in broken) {
      // The rest of each broken range came from the other mirror, from where it stopped.
      assertTrue(engine.requests.any { it.url == b && it.range!!.first == range.first + 128 })
    }
  }

  @Test
  fun download_corruptMirror_refetchesItsPiecesFromAnother() = runTest {
    val engine = MirrorEngine(content).apply { corrupt += a }
    val file = MemoryFile()

    helper(engine).download(context(file), listOf(Mirror(a), Mirror(b)), 4096, pieces())

    assertContentEquals(content, file.bytes)
    val first = engine.requests.indexOfLast { it.url == a }
    assertTrue(first >= 0)
    // Once its pieces failed, the corrupt mirror is never asked again.
    assertTrue(engine.requests.drop(first + 1).isNotEmpty())
    assertTrue(engine.requests.drop(first + 1).none { it.url == a })
  }

  @Test
  fun download_everyMirrorCorrupt_failsAfterRepairs() = runTest {
    val engine = MirrorEngine(content).apply { corrupt += listOf(a, b) }

    val error = assertFailsWith<KetchError.SourceError> {
      helper(engine).download(context(MemoryFile()), listOf(Mirror(a), Mirror(b)), 4096, pieces())
    }
    assertEquals("metalink", error.sourceType)
  }

  @Test
  fun download_lastMirrorFails_leavesTheRetryToKetch() = runTest {
    val engine = MirrorEngine(content).apply { failAfter[a] = 10 }

    assertFailsWith<KetchError.Network> {
      helper(engine).download(context(MemoryFile()), listOf(Mirror(a)), 4096)
    }
  }

  @Test
  fun download_savedSegments_fetchesOnlyWhatIsLeft() = runTest {
    val engine = MirrorEngine(content)
    val file = MemoryFile().apply {
      bytes = ByteArray(4096).also { content.copyInto(it, 0, 0, 2048) }
    }
    val saved = listOf(Segment(0, 0, 2047, 2048), Segment(1, 2048, 4095, 0))
    val ctx = context(file, saved).apply { maxConnections.value = 1 }

    helper(engine).download(ctx, listOf(Mirror(a), Mirror(b)), 4096, pieces())

    assertContentEquals(content, file.bytes)
    assertEquals(listOf(2048L..4095L), engine.requests.map { it.range })
  }

  @Test
  fun download_segmentsOfAnotherSize_startOver() = runTest {
    val engine = MirrorEngine(content)
    val file = MemoryFile()
    val ctx = context(file, listOf(Segment(0, 0, 99, 100))).apply { maxConnections.value = 1 }

    helper(engine).download(ctx, listOf(Mirror(a)), 4096)

    assertContentEquals(content, file.bytes)
    assertEquals(listOf(0L..4095L), engine.requests.map { it.range })
  }

  @Test
  fun download_withoutMirrorsOrSize_isASourceError() = runTest {
    val helper = helper(MirrorEngine(content))
    assertFailsWith<KetchError.SourceError> {
      helper.download(context(MemoryFile()), emptyList(), 4096)
    }
    assertFailsWith<KetchError.SourceError> {
      helper.download(context(MemoryFile()), listOf(Mirror(a)), -1)
    }
  }

  @Test
  fun mergedRanges_joinsAdjacentPiecesAndCutsTheLast() {
    assertEquals(
      listOf(0L..1023L, 2048L..2559L, 3584L..3999L),
      mergedRanges(listOf(7, 0, 1, 4), 512, 4000),
    )
  }

  @Test
  fun cover_marksEverythingElseDone() {
    assertEquals(
      listOf(
        Segment(0, 0, 99, 100),
        Segment(1, 100, 199, 0),
        Segment(2, 200, 299, 100),
        Segment(3, 300, 399, 0),
      ),
      cover(listOf(100L..199L, 300L..399L), 400),
    )
  }

  @Test
  fun mirrorHeaders_keepCredentialsOnTheSameOriginOnly() {
    val headers = mapOf("Cookie" to "x", "Accept" to "*/*")
    assertEquals(headers, mirrorHeaders("https://a.example/x", "https://A.example:443/y", headers))
    assertEquals(mapOf("Accept" to "*/*"),
      mirrorHeaders("https://a.example/x", "http://a.example/y", headers))
    assertEquals(mapOf("Accept" to "*/*"),
      mirrorHeaders("metalink:abc", "https://a.example/y", headers))
    assertFalse("Cookie" in mirrorHeaders("https://a.example/", "https://b.example/", headers))
  }

  private class Request(val url: String, val range: LongRange?, val headers: Map<String, String>)

  /** Serves [content] at every URL, in 64-byte chunks, unless told to fail or corrupt it. */
  private class MirrorEngine(private val content: ByteArray) : HttpEngine {
    val requests = mutableListOf<Request>()
    val fail = mutableMapOf<String, () -> KetchError>()
    val failAfter = mutableMapOf<String, Int>()
    val corrupt = mutableSetOf<String>()

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      ServerInfo(
        contentLength = content.size.toLong(), acceptRanges = true, etag = null,
        lastModified = null,
      )

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      requests += Request(url, range, headers)
      fail[url]?.let { throw it() }
      val wanted = range ?: content.indices.let { 0L..it.last.toLong() }
      var offset = wanted.first
      var sent = 0
      while (offset <= wanted.last) {
        yield()
        val limit = failAfter[url]
        if (limit != null && sent >= limit) {
          throw KetchError.Network(Exception("Mirror went away"))
        }
        val end = minOf(offset + 64, wanted.last + 1)
        val chunk = content.copyOfRange(offset.toInt(), end.toInt())
        if (url in corrupt) chunk.indices.forEach { chunk[it] = (chunk[it] + 1).toByte() }
        onData(chunk)
        sent += chunk.size
        offset = end
      }
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
