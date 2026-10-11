package com.linroid.ketch.torrent

import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.core.engine.HttpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalAtomicApi::class)
class TorrentV2WebSeedTest {
  private val files = listOf("a b.bin" to 40_000, "c.bin" to 70_000, "ünï.bin" to 20_000)

  /** Where a mirror under `http://mirror.test/pub/` serves [fixture]'s files. */
  private fun mirrorFiles(fixture: TorrentV2Fixture): Map<String, ByteArray> = mapOf(
    "http://mirror.test/pub/pack/a%20b.bin" to fixture.payloads[0],
    "http://mirror.test/pub/pack/c.bin" to fixture.payloads[1],
    "http://mirror.test/pub/pack/%C3%BCn%C3%AF.bin" to fixture.payloads[2],
  )

  private class Run(
    val state: TorrentSessionState,
    val failure: Throwable?,
    val output: Path,
    val throttled: Long,
  )

  /**
   * Downloads [fixture] with no peer at all, discovery ending at once, from the web seeds [engines]
   * serve (see [mirrorHosts]); [check] reads the files before they are deleted.
   */
  private suspend fun download(
    fixture: TorrentV2Fixture,
    vararg engines: HttpEngine,
    selected: Set<String> = emptySet(),
    connections: RecordingConnections = RecordingConnections(),
    check: suspend (Run) -> Unit,
  ) = withContext(Dispatchers.Default) {
    withTimeout(15_000) {
      val output = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "ketch-v2-webseed-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
      val buffers = TorrentBufferBudget(4 * 1024 * 1024)
      val state = TorrentBufferBudget(4 * 1024 * 1024)
      val network = createTorrentNetwork()
      val store = TorrentV2PieceStore(fixture.document, output, selected, "webseed", buffers,
        Semaphore(4))
      val throttled = AtomicLong(0)
      try {
        val session = TorrentV2DownloadSession.open(this, fixture.document, fixture.layout,
          selected, store,
          TorrentV2Runtime(network, ByteArray(20) { 1 }.toByteString(), buffers, state,
            webSeedHttp = TorrentHttp(mirrorHosts(*engines))),
          TorrentV2SessionOptions(maxPeers = 4, discovery = {}, connections = connections,
            throttle = { throttled.addAndFetch(it.toLong()) }, webSeedRetryMs = 5))
        try {
          session.restore(null)
          session.resume()
          val ended = session.state.first {
            it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
          }
          check(Run(ended, session.failure.value, output, throttled.load()))
        } finally {
          withContext(NonCancellable) { session.close() }
        }
        assertEquals(0, buffers.allocated)
      } finally {
        withContext(NonCancellable) {
          network.close()
          torrentFileSystem.deleteRecursively(output, mustExist = false)
        }
      }
    }
  }

  private fun assertFiles(fixture: TorrentV2Fixture, output: Path, indices: Iterable<Int>) {
    for (index in indices) {
      assertContentEquals(fixture.payloads[index],
        torrentFileSystem.read(output / fixture.names[index]) { readByteArray() })
    }
  }

  @Test
  fun document_urlList_isParsedForV2AndHybrid() {
    val seeds = listOf("http://m.test/", "ftp://m.test/x", "https://n.test/x")
    for (hybrid in listOf(false, true)) {
      val fixture = TorrentV2Fixture.build(files, hybrid = hybrid, webSeeds = seeds)
      assertEquals(listOf("http://m.test/", "https://n.test/x"), fixture.document.webSeeds)
    }
  }

  @Test
  fun layout_singleFileV2_urlNamesTheFileOrItsFolder() {
    val fixture = TorrentV2Fixture.build(listOf("data.iso" to 50_000))
    val file = WebSeedV2Layout.of(fixture.document, fixture.layout, "http://m.test/x.iso")
    assertEquals("http://m.test/x.iso", file?.span(1)?.url)
    assertEquals(32_768L..49_999L, file?.span(1)?.range)
    val folder = WebSeedV2Layout.of(fixture.document, fixture.layout, "http://m.test/d/")
    assertEquals("http://m.test/d/data.iso", folder?.span(0)?.url)
  }

  @Test
  fun layout_multiFileWithoutName_hasNoWebSeedUrls() {
    val tree = mapOf("a" to mapOf("" to mapOf("length" to 4L,
      "pieces root" to sha256Digest(byteArrayOf(1, 2, 3, 4)))),
      "b" to mapOf("" to mapOf("length" to 4L,
        "pieces root" to sha256Digest(byteArrayOf(5, 6, 7, 8)))))
    val document = TorrentV2Document.parse(Bencode.encode(mapOf(
      "info" to mapOf("meta version" to 2L, "piece length" to 16_384L, "file tree" to tree),
      "url-list" to "http://m.test/")))
    assertNull(WebSeedV2Layout.of(document, TorrentContentLayout.from(document.info),
      "http://m.test/"))
  }

  @Test
  fun hybrid_webSeedOnly_completesWithZeroPeersAndNeverAsksForPadding() = runTest {
    val fixture = TorrentV2Fixture.build(files, hybrid = true,
      webSeeds = listOf("http://mirror.test/pub"))
    assertTrue(fixture.document.hybrid!!.padding.isNotEmpty())
    val mirror = MirrorEngine(mirrorFiles(fixture))
    val connections = RecordingConnections()
    download(fixture, mirror, connections = connections) { run ->
      assertEquals(TorrentSessionState.FINISHED, run.state, run.failure?.toString())
      assertFiles(fixture, run.output, files.indices)
      // Pieces stop at the end of their file: no padding, nothing past a file.
      assertTrue(mirror.requests.none { it.first.contains(".pad") })
      for ((url, range) in mirror.requests) {
        assertTrue(checkNotNull(range).last < mirrorFiles(fixture).getValue(url).size)
      }
      assertEquals(fixture.layout.pieceCount.toInt(), mirror.requests.size)
      // Mirrors pay the task's limit as peers do.
      assertEquals(files.sumOf { it.second }.toLong(), run.throttled)
    }
    val seed = connections.opened.first()
    assertEquals("mirror.test", seed.spec.host)
    assertEquals(80, seed.spec.port)
    assertEquals("HTTP", seed.spec.protocol)
    assertEquals(ConnectionDirection.OUTGOING, seed.spec.direction)
    assertEquals(files.sumOf { it.second }.toLong(), connections.opened.sumOf { it.received })
    assertTrue(connections.opened.all { it.closed })
  }

  @Test
  fun v2Only_singleFileWebSeed_completes() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("data.iso" to 100_000),
      webSeeds = listOf("https://mirror.test/data.iso"))
    val mirror = MirrorEngine(mapOf("https://mirror.test/data.iso" to fixture.payloads[0]))
    download(fixture, mirror) { run ->
      assertEquals(TorrentSessionState.FINISHED, run.state, run.failure?.toString())
      assertFiles(fixture, run.output, listOf(0))
      // Whole pieces, one range request each.
      assertEquals(4, mirror.requests.size)
    }
  }

  @Test
  fun v2Only_multiFileWebSeed_completes() = runTest {
    val fixture = TorrentV2Fixture.build(files, webSeeds = listOf("http://mirror.test/pub/"))
    val mirror = MirrorEngine(mirrorFiles(fixture))
    download(fixture, mirror) { run ->
      assertEquals(TorrentSessionState.FINISHED, run.state, run.failure?.toString())
      assertFiles(fixture, run.output, files.indices)
    }
  }

  @Test
  fun corruptSeed_isDroppedWhileAnotherSeedFinishes() = runTest {
    val fixture = TorrentV2Fixture.build(files, hybrid = true,
      webSeeds = listOf("http://mirror0.test/pub", "http://mirror1.test/pub"))
    val bad = MirrorEngine(mirrorFiles(fixture), corrupt = true)
    val good = MirrorEngine(mirrorFiles(fixture))
    download(fixture, bad, good) { run ->
      assertEquals(TorrentSessionState.FINISHED, run.state, run.failure?.toString())
      assertFiles(fixture, run.output, files.indices)
      assertTrue(bad.requests.isNotEmpty())
      // Dropped after its failures in a row, never past them.
      assertTrue(bad.requests.size <= WebSeedWorker.MAX_FAILURES)
    }
  }

  @Test
  fun everySeedDropped_withoutPeers_stopsTheTransfer() = runTest {
    val fixture = TorrentV2Fixture.build(files, webSeeds = listOf("http://mirror.test/pub"))
    val mirror = MirrorEngine(emptyMap())
    download(fixture, mirror) { run ->
      assertEquals(TorrentSessionState.STOPPED, run.state)
      assertIs<IllegalStateException>(run.failure)
      assertEquals(WebSeedWorker.MAX_FAILURES, mirror.requests.size)
    }
  }

  @Test
  fun selection_fetchesOnlySelectedFiles() = runTest {
    val fixture = TorrentV2Fixture.build(files, hybrid = true,
      webSeeds = listOf("http://mirror.test/pub"))
    val mirror = MirrorEngine(mirrorFiles(fixture))
    val id = fixture.layout.files.single { it.v2Index == 1 }.id
    download(fixture, mirror, selected = setOf(id)) { run ->
      assertEquals(TorrentSessionState.FINISHED, run.state, run.failure?.toString())
      assertFiles(fixture, run.output, listOf(1))
      assertEquals(setOf("http://mirror.test/pub/pack/c.bin"),
        mirror.requests.map { it.first }.toSet())
    }
  }
}
