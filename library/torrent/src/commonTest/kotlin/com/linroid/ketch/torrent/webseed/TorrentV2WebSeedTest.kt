package com.linroid.ketch.torrent.webseed

import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.ConnectionReporter
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.torrent.RecordingConnections
import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.api.TorrentStallReason
import com.linroid.ketch.torrent.crypto.sha256Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.engine.TorrentSessionState
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentContentLayout
import com.linroid.ketch.torrent.metainfo.TorrentV2Document
import com.linroid.ketch.torrent.monotonicClock
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.TorrentListener
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerIdentityHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerOrigin
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.storage.TorrentV2PieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import com.linroid.ketch.torrent.v2.PeerTopic
import com.linroid.ketch.torrent.v2.TorrentV2DiscoverySink
import com.linroid.ketch.torrent.v2.TorrentV2DownloadSession
import com.linroid.ketch.torrent.v2.TorrentV2PieceScheduler
import com.linroid.ketch.torrent.v2.TorrentV2Runtime
import com.linroid.ketch.torrent.v2.TorrentV2SessionOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
      // Every piece in one run: a range request per file, each file whole, side by side.
      assertEquals(files.indices.map { 0L..<files[it].second.toLong() }.sortedBy { it.last },
        mirror.requests.map { checkNotNull(it.second) }.sortedBy { it.last })
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
      // The four pieces in one run, one range request.
      assertEquals(listOf(0L..<100_000L), mirror.requests.map { it.second })
    }
  }

  @Test
  fun v2Only_sixteenPieces_fetchesTheRunInOneRequest() = runTest {
    val size = 16 * 32_768
    val fixture = TorrentV2Fixture.build(listOf("data.iso" to size),
      webSeeds = listOf("http://mirror.test/data.iso"))
    val mirror = MirrorEngine(mapOf("http://mirror.test/data.iso" to fixture.payloads[0]))
    download(fixture, mirror) { run ->
      assertEquals(TorrentSessionState.FINISHED, run.state, run.failure?.toString())
      assertFiles(fixture, run.output, listOf(0))
      assertEquals(listOf(0L..<size.toLong()), mirror.requests.map { it.second })
    }
  }

  @Test
  fun hybrid_corruptPieceInARun_failsOnlyThatPieceAndIsFetchedAgain() = runTest {
    val size = 8 * 32_768
    val fixture = TorrentV2Fixture.build(listOf("data.iso" to size), hybrid = true,
      webSeeds = listOf("http://mirror.test/pub"))
    // A hybrid's v1 view lists its files, so the URL names the folder.
    val url = "http://mirror.test/pub/pack/data.iso"
    // A byte of piece 5 is wrong in the first response only.
    val mirror = MirrorEngine(mapOf(url to fixture.payloads[0]),
      damage = mapOf(url to 5L * 32_768 + 7))
    download(fixture, mirror) { run ->
      assertEquals(TorrentSessionState.FINISHED, run.state, run.failure?.toString())
      assertFiles(fixture, run.output, listOf(0))
      assertEquals(listOf(0L..<size.toLong(), 5L * 32_768..<size.toLong()),
        mirror.requests.map { it.second })
    }
  }

  @Test
  fun v2Only_connectionDroppedMidRun_keepsTheVerifiedPieces() = runTest {
    val size = 8 * 32_768
    val fixture = TorrentV2Fixture.build(listOf("data.iso" to size),
      webSeeds = listOf("http://mirror.test/data.iso"))
    val url = "http://mirror.test/data.iso"
    // The first response ends inside piece 2.
    val mirror = MirrorEngine(mapOf(url to fixture.payloads[0]),
      drop = mapOf(url to 2 * 32_768 + 1_000))
    download(fixture, mirror) { run ->
      assertEquals(TorrentSessionState.FINISHED, run.state, run.failure?.toString())
      assertFiles(fixture, run.output, listOf(0))
      assertEquals(listOf(0L..<size.toLong(), 2L * 32_768..<size.toLong()),
        mirror.requests.map { it.second })
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
      // Each missing file is asked once; the seed goes once nothing is left it can serve.
      assertTrue(mirror.requests.size <= WebSeedWorker.MAX_FAILURES)
      assertEquals(mirror.requests.size, mirror.requests.map { it.first }.toSet().size)
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

  /** A download [stalling] runs: its session, the sink its discovery got, and its clock. */
  private class Stalling(
    val session: TorrentV2DownloadSession,
    val sink: TorrentV2DiscoverySink,
    private val skipped: AtomicLong,
  ) {
    /** Moves the session's clock [ms] on, as if that long passed without a byte. */
    fun skip(ms: Long) {
      skipped.addAndFetch(ms)
    }
  }

  /**
   * Downloads the [selected] files of [fixture] (all when empty) from the web seed [engine]
   * serves, without any peer, its discovery open and its clock one the test moves on; [body]
   * watches it before it is closed.
   */
  private suspend fun stalling(
    fixture: TorrentV2Fixture,
    engine: HttpEngine,
    selected: Set<String> = emptySet(),
    body: suspend (Stalling) -> Unit,
  ) = withContext(Dispatchers.Default) {
    withTimeout(15_000) {
      val output = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "ketch-v2-stall-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
      val buffers = TorrentBufferBudget(4 * 1024 * 1024)
      val state = TorrentBufferBudget(4 * 1024 * 1024)
      val network = createTorrentNetwork()
      val store = TorrentV2PieceStore(fixture.document, output, selected, "stall", buffers,
        Semaphore(4))
      val clock = monotonicClock()
      val skipped = AtomicLong(0)
      val sinks = CompletableDeferred<TorrentV2DiscoverySink>()
      try {
        val session = TorrentV2DownloadSession.open(this, fixture.document, fixture.layout,
          selected, store,
          TorrentV2Runtime(network, ByteArray(20) { 1 }.toByteString(), buffers, state,
            webSeedHttp = TorrentHttp(engine)),
          TorrentV2SessionOptions(maxPeers = 4, webSeedRetryMs = 5,
            nowMs = { clock() + skipped.load() },
            discovery = { sink ->
              sinks.complete(sink)
              awaitCancellation()
            }))
        try {
          session.restore(null)
          session.resume()
          body(Stalling(session, sinks.await(), skipped))
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

  @Test
  fun lostFile_isReportedAsAStallNamingIt() = runTest {
    val fixture = TorrentV2Fixture.build(files, webSeeds = listOf("http://mirror.test/pub/"))
    // The only source lost c.bin, as mirrors lose files they regenerate.
    val mirror = MirrorEngine(mirrorFiles(fixture),
      missing = setOf("http://mirror.test/pub/pack/c.bin"))
    val lost = fixture.layout.files.single { it.v2Index == 1 }.id
    stalling(fixture, mirror) { run ->
      // Every other file arrives.
      val others = (files[0].second + files[2].second).toLong()
      run.session.downloadedBytes.first { it == others }
      // A check a second sees it arrive.
      delay(1_500)
      assertNull(run.session.stall.value)
      // Nothing more for 40 s: a stall, which names the file the download waits for.
      run.skip(40_000)
      val stall = assertNotNull(run.session.stall.first { it != null })
      assertEquals(TorrentStallReason.MISSING_PIECES, stall.reason)
      // c.bin's 70,000 bytes are three pieces.
      assertEquals(3, stall.missingPieces)
      assertEquals(listOf(lost), stall.missingFileIds)
      assertEquals(0, stall.peers)
      // Paused, the download is not stalled any more.
      run.session.pause()
      assertNull(run.session.stall.value)
    }
  }

  @Test
  fun droppedSeed_isStartedAgainOnceTheStallLastsTwoMinutes() = runTest {
    val fixture = TorrentV2Fixture.build(files, webSeeds = listOf("http://mirror.test/pub/"))
    val mirror = MirrorEngine(mirrorFiles(fixture))
    // The mirror is down at first, so the seed is dropped, and back later.
    val online = AtomicBoolean(false)
    val requests = AtomicLong(0)
    val flaky = object : HttpEngine {
      override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
        error("unused")

      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) {
        requests.addAndFetch(1)
        if (!online.load()) throw KetchError.Http(404)
        mirror.download(url, range, headers, onData)
      }

      override fun close() = Unit
    }
    stalling(fixture, flaky) { run ->
      // Every file is asked for and missing: the seed goes, and the stall names every file.
      while (requests.load() < files.size) delay(10)
      delay(1_500)
      run.skip(40_000)
      val stall = run.session.stall.first { it?.reason == TorrentStallReason.MISSING_PIECES }
      assertEquals(fixture.layout.files.map { it.id }.toSet(), stall?.missingFileIds?.toSet())
      assertEquals(0, stall?.peers)
      assertEquals(0L, run.sink.reannounceRequests())
      // Two minutes on, it is started again and finds the mirror back; the trackers are asked
      // for peers again too.
      online.store(true)
      run.skip(90_000)
      run.session.downloadedBytes.first { it == files.sumOf { file -> file.second }.toLong() }
      assertTrue(run.sink.reannounceRequests() > 0)
      assertTrue(mirror.requests.isNotEmpty())
    }
  }

  @Test
  fun relaunchedSeed_keepsWhatWasMissingUntilItsBytesArrive() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("bad.bin" to 20_000, "good.bin" to 40_000),
      webSeeds = listOf("http://mirror.test/pub/"))
    val bad = "http://mirror.test/pub/pack/bad.bin"
    val good = "http://mirror.test/pub/pack/good.bin"
    // The only source lacks bad.bin, the one file wanted, until it is fixed; started again, it
    // sends it slowly, a part when the test lets it, and no peer connects.
    val lacking = MirrorEngine(mapOf(good to fixture.payloads[1]))
    val fixed = Stepped(MirrorEngine(mapOf(bad to fixture.payloads[0],
      good to fixture.payloads[1])))
    val holding = AtomicBoolean(false)
    val mirror = object : HttpEngine by lacking {
      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) = (if (holding.load()) fixed else lacking).download(url, range, headers, onData)
    }
    val wanted = fixture.layout.files.single { it.v2Index == 0 }.id
    stalling(fixture, mirror, selected = setOf(wanted)) { run ->
      while (lacking.requests.none { it.first == bad }) delay(10)
      delay(2_000)
      run.skip(40_000)
      assertEquals(TorrentStallReason.MISSING_PIECES,
        run.session.stall.first { it != null }?.reason)
      // Two minutes into the stall the seed starts again and asks once more: it offers every
      // piece, but has yet to answer, so bad.bin stays missing as long as the answer takes.
      holding.store(true)
      run.skip(90_000)
      fixed.asked.await()
      run.skip(20_000)
      // A check a second sees it.
      delay(1_500)
      val waiting = assertNotNull(run.session.stall.value)
      assertEquals(TorrentStallReason.MISSING_PIECES, waiting.reason)
      assertEquals(1, waiting.missingPieces)
      assertEquals(listOf(wanted), waiting.missingFileIds)
      // The first bytes of bad.bin arrive: the mirror serves it, so it is not missing while the
      // rest of its piece comes.
      fixed.steps.send(Unit)
      while (fixed.delivered == 0) delay(10)
      delay(1_500)
      val streaming = assertNotNull(run.session.stall.value)
      assertEquals(TorrentStallReason.NO_PEERS, streaming.reason)
      assertEquals(0, streaming.missingPieces)
      // The rest arrives: the download finishes.
      coroutineScope {
        val sending = launch { while (true) fixed.steps.send(Unit) }
        run.session.downloadedBytes.first { it == 20_000L }
        sending.cancel()
      }
    }
  }

  @Test
  fun peerReconnectingAfterARelaunch_clearsWhatWasMissingOnlyWithThosePieces() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("bad.bin" to 20_000, "good.bin" to 40_000),
      webSeeds = listOf("http://mirror.test/pub/"))
    val bad = "http://mirror.test/pub/pack/bad.bin"
    // The only web seed lacks bad.bin, the one file wanted, and once started again its request
    // waits.
    val lacking = MirrorEngine(mapOf("http://mirror.test/pub/pack/good.bin" to fixture.payloads[1]))
    val holding = AtomicBoolean(false)
    val asked = CompletableDeferred<Unit>()
    val mirror = object : HttpEngine by lacking {
      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) {
        if (!holding.load()) return lacking.download(url, range, headers, onData)
        asked.complete(Unit)
        awaitCancellation()
      }
    }
    val wanted = fixture.layout.files.single { it.v2Index == 0 }.id
    stalling(fixture, mirror, selected = setOf(wanted)) { run ->
      val network = createTorrentNetwork()
      val first = network.listen(PeerEndpoint("127.0.0.1", 0))
      val again = network.listen(PeerEndpoint("127.0.0.1", 0))
      val holder = network.listen(PeerEndpoint("127.0.0.1", 0))
      try {
        coroutineScope {
          // A peer with [bitfield] (bad.bin is piece 0, good.bin pieces 1 and 2) that never
          // unchokes, until [leave] completes.
          fun peer(
            listener: TorrentListener,
            id: Byte,
            bitfield: Byte,
            leave: CompletableDeferred<Unit>,
          ) = launch {
            val connection = listener.accept()
            try {
              PeerIdentityHandshake(fixture.document.identity).respond(connection,
                ByteArray(20) { id }.toByteString(), TorrentBufferBudget(1024 * 1024))
              PeerWire(connection, pieceCount = 3).send(PeerMessage.Bitfield(byteArrayOf(bitfield)))
              leave.await()
            } finally {
              connection.close()
            }
          }
          while (lacking.requests.none { it.first == bad }) delay(10)
          delay(2_000)
          // A peer with good.bin alone connects; nothing arrives for 40 s: bad.bin is missing.
          val leave = CompletableDeferred<Unit>()
          peer(first, 2, 0x60, leave)
          run.sink.offer(first.local, PeerTopic.V2, PeerOrigin.TRACKER)
          run.skip(40_000)
          val missing = assertNotNull(run.session.stall.first { it?.peers == 1 })
          assertEquals(TorrentStallReason.MISSING_PIECES, missing.reason)
          // Two minutes into the stall the seed starts again, and asks once more.
          holding.store(true)
          run.skip(90_000)
          asked.await()
          // The peer leaves and comes back, still without bad.bin: it changes nothing.
          leave.complete(Unit)
          first.close()
          run.session.stall.first { it?.peers == 0 }
          val stay = CompletableDeferred<Unit>()
          peer(again, 3, 0x60, stay)
          run.sink.offer(again.local, PeerTopic.V2, PeerOrigin.TRACKER)
          val back = assertNotNull(run.session.stall.first { it?.peers == 1 })
          assertEquals(TorrentStallReason.MISSING_PIECES, back.reason)
          assertEquals(1, back.missingPieces)
          assertEquals(listOf(wanted), back.missingFileIds)
          // A peer with bad.bin connects: nothing is missing now.
          peer(holder, 4, 0xE0.toByte(), stay)
          run.sink.offer(holder.local, PeerTopic.V2, PeerOrigin.TRACKER)
          val heard = assertNotNull(run.session.stall.first { it?.peers == 2 })
          assertEquals(TorrentStallReason.CHOKED, heard.reason)
          assertEquals(0, heard.missingPieces)
          stay.complete(Unit)
        }
      } finally {
        first.close()
        again.close()
        holder.close()
        network.close()
      }
    }
  }

  @Test
  fun deadRelaunchedSeed_keepsWhatWasMissingThroughItsRetriesAndOnceDropped() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("bad.bin" to 20_000, "good.bin" to 40_000),
      webSeeds = listOf("http://mirror.test/pub/"))
    val bad = "http://mirror.test/pub/pack/bad.bin"
    // The only web seed lacks bad.bin, the one file wanted, and once started again it is down:
    // each request fails when the test lets it. The one peer lacks bad.bin too.
    val lacking = MirrorEngine(mapOf("http://mirror.test/pub/pack/good.bin" to fixture.payloads[1]))
    val holding = AtomicBoolean(false)
    val asked = AtomicLong(0)
    val failures = Channel<Unit>(Channel.UNLIMITED)
    val mirror = object : HttpEngine by lacking {
      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) {
        if (!holding.load()) return lacking.download(url, range, headers, onData)
        asked.addAndFetch(1)
        failures.receive()
        throw KetchError.Network()
      }
    }
    val wanted = fixture.layout.files.single { it.v2Index == 0 }.id
    stalling(fixture, mirror, selected = setOf(wanted)) { run ->
      val network = createTorrentNetwork()
      val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
      try {
        coroutineScope {
          val peer = launch {
            val connection = listener.accept()
            try {
              PeerIdentityHandshake(fixture.document.identity).respond(connection,
                ByteArray(20) { 2 }.toByteString(), TorrentBufferBudget(1024 * 1024))
              // bad.bin is piece 0, good.bin pieces 1 and 2.
              PeerWire(connection, pieceCount = 3).send(PeerMessage.Bitfield(byteArrayOf(0x60)))
              awaitCancellation()
            } finally {
              connection.close()
            }
          }
          while (lacking.requests.none { it.first == bad }) delay(10)
          run.sink.offer(listener.local, PeerTopic.V2, PeerOrigin.TRACKER)
          delay(2_000)
          run.skip(40_000)
          val missing = assertNotNull(run.session.stall.first { it?.peers == 1 })
          assertEquals(TorrentStallReason.MISSING_PIECES, missing.reason)
          // Two minutes into the stall the seed starts again, and every request it sends fails:
          // bad.bin stays missing while it tries, and once it is dropped again.
          holding.store(true)
          run.skip(90_000)
          var failed = 0L
          while (true) {
            withTimeoutOrNull(1_500) { while (asked.load() == failed) delay(10) } ?: break
            // A check a second sees it, at the first try and the last.
            if (failed == 0L || failed == 1L + WebSeedWorker.MAX_FAILURES) {
              delay(1_100)
              val trying = assertNotNull(run.session.stall.value)
              assertEquals(TorrentStallReason.MISSING_PIECES, trying.reason)
              assertEquals(listOf(wanted), trying.missingFileIds)
            }
            failures.send(Unit)
            failed++
          }
          assertEquals(2L + WebSeedWorker.MAX_FAILURES, failed)
          val dropped = assertNotNull(run.session.stall.value)
          assertEquals(TorrentStallReason.MISSING_PIECES, dropped.reason)
          assertEquals(1, dropped.missingPieces)
          assertEquals(listOf(wanted), dropped.missingFileIds)
          assertEquals(1, dropped.peers)
          peer.cancel()
        }
      } finally {
        listener.close()
        network.close()
      }
    }
  }

  @Test
  fun droppedSeedWithoutPeers_reportsTheCorruptFileAsMissing() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("bad.bin" to 20_000, "good.bin" to 40_000),
      webSeeds = listOf("http://mirror.test/pub/"))
    val bad = "http://mirror.test/pub/pack/bad.bin"
    // The one file wanted is another bad.bin at the only source, and no peer connects.
    val mirror = MirrorEngine(mapOf(bad to fixture.payloads[0].reversedArray(),
      "http://mirror.test/pub/pack/good.bin" to fixture.payloads[1]))
    val wanted = fixture.layout.files.single { it.v2Index == 0 }.id
    stalling(fixture, mirror, selected = setOf(wanted)) { run ->
      // Sent corrupt twice, the seed has nothing left to fetch and is dropped.
      while (mirror.requests.count { it.first == bad } < 2) delay(10)
      delay(2_000)
      run.skip(40_000)
      val stall = assertNotNull(run.session.stall.first { it != null })
      assertEquals(TorrentStallReason.MISSING_PIECES, stall.reason)
      assertEquals(1, stall.missingPieces)
      assertEquals(listOf(wanted), stall.missingFileIds)
      assertEquals(0, stall.peers)
    }
  }

  @Test
  fun pieceOneSeedLacked_isMissingOnlyOnceEverySeedLacksIt() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("bad.bin" to 20_000, "good.bin" to 40_000),
      webSeeds = listOf("http://mirror0.test/pub/", "http://mirror1.test/pub/"))
    val bad = "http://mirror.test/pub/pack/bad.bin"
    val files = mapOf(bad to fixture.payloads[0],
      "http://mirror.test/pub/pack/good.bin" to fixture.payloads[1])
    // The first seed lacks bad.bin, the one file wanted; the second is down, and lacks it too
    // once back. Both are dropped, and no peer connects.
    val lacking = MirrorEngine(files - bad)
    val second = MirrorEngine(files - bad)
    val back = AtomicBoolean(false)
    val down = Outage(second) { !back.load() }
    val wanted = fixture.layout.files.single { it.v2Index == 0 }.id
    stalling(fixture, mirrorHosts(lacking, down), selected = setOf(wanted)) { run ->
      while (lacking.requests.none { it.first == bad }) delay(10)
      awaitDropped(down)
      // Nothing arrives for 40 s: the second seed may still serve bad.bin once started again, so
      // it is not missing, and the download waits for a source.
      run.skip(40_000)
      val stall = assertNotNull(run.session.stall.first { it != null })
      assertEquals(TorrentStallReason.NO_PEERS, stall.reason)
      assertEquals(0, stall.missingPieces)
      // Two minutes into the stall both start again, and neither has bad.bin: missing.
      back.store(true)
      run.skip(90_000)
      val missing = run.session.stall.first { it?.reason == TorrentStallReason.MISSING_PIECES }
      assertEquals(1, missing?.missingPieces)
      assertEquals(listOf(wanted), missing?.missingFileIds)
      assertEquals(2, lacking.requests.count { it.first == bad })
      assertEquals(1, second.requests.count { it.first == bad })
    }
  }

  @Test
  fun pieceOneSeedLacked_isMissingOnceTheOtherIsDownAcrossARelaunch() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("bad.bin" to 20_000, "good.bin" to 40_000),
      webSeeds = listOf("http://mirror0.test/pub/", "http://mirror1.test/pub/"))
    val bad = "http://mirror.test/pub/pack/bad.bin"
    val files = mapOf(bad to fixture.payloads[0],
      "http://mirror.test/pub/pack/good.bin" to fixture.payloads[1])
    // The first seed lacks bad.bin, the one file wanted; the second stays down. Both are dropped,
    // and no peer connects.
    val lacking = MirrorEngine(files - bad)
    val down = Outage(MirrorEngine(files)) { true }
    val wanted = fixture.layout.files.single { it.v2Index == 0 }.id
    stalling(fixture, mirrorHosts(lacking, down), selected = setOf(wanted)) { run ->
      while (lacking.requests.none { it.first == bad }) delay(10)
      awaitDropped(down)
      // Nothing arrives for 40 s: the second seed may only be down for a while, and serve bad.bin
      // once started again, so it is not missing yet.
      run.skip(40_000)
      val stall = assertNotNull(run.session.stall.first { it != null })
      assertEquals(TorrentStallReason.NO_PEERS, stall.reason)
      assertEquals(0, stall.missingPieces)
      // Two minutes into the stall both start again: the first lacks bad.bin again, and the
      // second, still down, is dropped again without an answer, which says nothing of bad.bin
      // any more: missing.
      val refused = down.refused
      run.skip(90_000)
      while (down.refused < refused + WebSeedWorker.MAX_FAILURES) delay(10)
      val missing = run.session.stall.first { it?.reason == TorrentStallReason.MISSING_PIECES }
      assertEquals(1, missing?.missingPieces)
      assertEquals(listOf(wanted), missing?.missingFileIds)
      assertEquals(2, lacking.requests.count { it.first == bad })
    }
  }

  @Test
  fun pieceTheRunningSeedLacks_isMissingOnlyOnceTheStoppedSeedLacksItToo() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("bad.bin" to 20_000, "good.bin" to 40_000),
      webSeeds = listOf("http://mirror0.test/pub/", "http://mirror1.test/pub/"))
    val bad = "http://mirror.test/pub/pack/bad.bin"
    val files = mapOf(bad to fixture.payloads[0],
      "http://mirror.test/pub/pack/good.bin" to fixture.payloads[1])
    // The first seed lacks bad.bin and serves good.bin, so it keeps running; the second is down,
    // so it is dropped, and lacks bad.bin too once back. No peer connects.
    val lacking = MirrorEngine(files - bad)
    val second = MirrorEngine(files - bad)
    val back = AtomicBoolean(false)
    val down = Outage(second) { !back.load() }
    val wanted = fixture.layout.files.single { it.v2Index == 0 }.id
    stalling(fixture, mirrorHosts(lacking, down)) { run ->
      run.session.downloadedBytes.first { it == 40_000L }
      while (lacking.requests.none { it.first == bad }) delay(10)
      awaitDropped(down)
      // Nothing arrives for 40 s: the dropped seed may still serve bad.bin once started again, so
      // it is not missing while the other seed runs, and the download waits for a source.
      run.skip(40_000)
      val stall = assertNotNull(run.session.stall.first { it != null })
      assertEquals(TorrentStallReason.NO_PEERS, stall.reason)
      assertEquals(0, stall.missingPieces)
      // Two minutes into the stall the dropped seed starts again, without bad.bin either: missing.
      back.store(true)
      run.skip(90_000)
      val missing = run.session.stall.first { it?.reason == TorrentStallReason.MISSING_PIECES }
      assertEquals(1, missing?.missingPieces)
      assertEquals(listOf(wanted), missing?.missingFileIds)
      assertEquals(1, second.requests.count { it.first == bad })
    }
  }

  @Test
  fun peerLackingAPiece_isMissingOnceTheDownSeedFailsAcrossARelaunch() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("bad.bin" to 20_000, "good.bin" to 40_000),
      webSeeds = listOf("http://mirror.test/pub/"))
    // The only web seed stays down, and the one peer has good.bin but not bad.bin.
    val down = Outage(MirrorEngine(emptyMap())) { true }
    val wanted = fixture.layout.files.single { it.v2Index == 0 }.id
    stalling(fixture, down) { run ->
      val network = createTorrentNetwork()
      val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
      val interested = CompletableDeferred<Unit>()
      try {
        coroutineScope {
          val peer = launch {
            val connection = listener.accept()
            try {
              PeerIdentityHandshake(fixture.document.identity).respond(connection,
                ByteArray(20) { 2 }.toByteString(), TorrentBufferBudget(1024 * 1024))
              val wire = PeerWire(connection, pieceCount = 3)
              // bad.bin is piece 0, good.bin pieces 1 and 2.
              wire.send(PeerMessage.Bitfield(byteArrayOf(0x60)))
              // Interested, the session took the peer's pieces; the peer never unchokes it.
              do {
                val message = wire.read()
              } while (message != PeerMessage.Control(PeerMessage.Signal.INTERESTED))
              interested.complete(Unit)
              awaitCancellation()
            } finally {
              connection.close()
            }
          }
          run.sink.offer(listener.local, PeerTopic.V2, PeerOrigin.TRACKER)
          interested.await()
          awaitDropped(down)
          // Nothing arrives for 40 s: the seed may only be down for a while, and serve bad.bin
          // once started again, so it is not missing yet.
          run.skip(40_000)
          val choked = assertNotNull(run.session.stall.first { it != null })
          assertEquals(TorrentStallReason.CHOKED, choked.reason)
          assertEquals(0, choked.missingPieces)
          assertEquals(1, choked.peers)
          // Two minutes into the stall it starts again, and is dropped again without an answer:
          // it says nothing of bad.bin any more, and the peer lacks it: missing.
          val refused = down.refused
          run.skip(90_000)
          while (down.refused < refused + WebSeedWorker.MAX_FAILURES) delay(10)
          val missing = run.session.stall.first { it?.reason == TorrentStallReason.MISSING_PIECES }
          assertEquals(1, missing?.missingPieces)
          assertEquals(listOf(wanted), missing?.missingFileIds)
          assertEquals(1, missing?.peers)
          peer.cancel()
        }
      } finally {
        listener.close()
        network.close()
      }
    }
  }

  @Test
  fun corruptPieceAfterAStall_isNoProgress() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("bad.bin" to 20_000, "good.bin" to 40_000),
      webSeeds = listOf("http://mirror.test/pub/"))
    val bad = "http://mirror.test/pub/pack/bad.bin"
    // The one file wanted is another bad.bin at the only source, which sends it slowly.
    val mirror = Stepped(MirrorEngine(mapOf(bad to fixture.payloads[0].reversedArray(),
      "http://mirror.test/pub/pack/good.bin" to fixture.payloads[1])))
    val wanted = fixture.layout.files.single { it.v2Index == 0 }.id
    stalling(fixture, mirror, selected = setOf(wanted)) { run ->
      // Nothing for longer than a piece may take: a stall, and no peer to help.
      mirror.asked.await()
      run.skip(70_000)
      assertEquals(TorrentStallReason.NO_PEERS, run.session.stall.first { it != null }?.reason)
      // The piece arrives, corrupt, twice, a part a second: what may still be thrown away is no
      // progress, and once thrown away it never was.
      val seen = AtomicReference(emptyList<TorrentStall?>())
      coroutineScope {
        val watching = launch { run.session.stall.collect { stall -> seen.update { it + stall } } }
        while (seen.load().lastOrNull()?.reason != TorrentStallReason.MISSING_PIECES) {
          mirror.steps.trySend(Unit)
          delay(100)
          run.skip(1_000)
          delay(400)
        }
        watching.cancel()
      }
      assertTrue(seen.load().all { it != null }, seen.load().toString())
    }
  }

  @Test
  fun corruptPieceWithinOneCheck_stallsHalfAMinuteAfterTheLastKeptPayload() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("data.bin" to 65_536, "other.bin" to 20_000),
      webSeeds = listOf("http://mirror.test/pub/"))
    val data = "http://mirror.test/pub/pack/data.bin"
    // The only source sends a part of 7,001 bytes when the test lets it, and its first copy of
    // piece 1 is corrupt.
    val mirror = MirrorEngine(mapOf(data to fixture.payloads[0],
      "http://mirror.test/pub/pack/other.bin" to fixture.payloads[1]),
      damage = mapOf(data to 32_768L + 100))
    val stepped = Stepped(mirror)
    suspend fun deliver(parts: Int) {
      val delivered = stepped.delivered + parts
      repeat(parts) { stepped.steps.send(Unit) }
      while (stepped.delivered < delivered) delay(10)
    }
    val wanted = fixture.layout.files.single { it.v2Index == 0 }.id
    stalling(fixture, stepped, selected = setOf(wanted)) { run ->
      stepped.asked.await()
      // Payload that may still be kept is payload: a part at 25 s, though nothing verified yet,
      // keeps the download from stalling 15 s later.
      run.skip(25_000)
      deliver(1)
      run.skip(14_000)
      delay(1_200)
      assertNull(run.session.stall.value)
      // The next parts verify piece 0 and bring the first bytes of piece 1: the last payload
      // kept, which a check counts before the clock moves on.
      deliver(4)
      delay(1_200)
      // 20 s later the rest of piece 1 comes within one check, corrupt: the last part fails the
      // run, and the seed asks for the piece again.
      run.skip(20_000)
      deliver(4)
      stepped.steps.send(Unit)
      withTimeout(5_000) { while (mirror.requests.count { it.first == data } < 2) delay(10) }
      // Thrown away, piece 1 never was payload: only the no-payload clock can stall the download
      // before a minute has passed since piece 0, and it does once 30 s have.
      run.skip(15_000)
      val stall = withTimeout(5_000) { run.session.stall.first { it != null } }
      assertEquals(TorrentStallReason.NO_PEERS, stall?.reason)
    }
  }

  @Test
  fun webSeeds_sayTheyDownloadOnlyOnceARunIsHandedOut() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val fixture = TorrentV2Fixture.build(listOf("data.iso" to 40_000),
          webSeeds = listOf("http://mirror.test/data.iso"))
        val gate = Gate(MirrorEngine(mapOf("http://mirror.test/data.iso" to fixture.payloads[0])))
        val buffers = TorrentBufferBudget(4 * 1024 * 1024)
        val store = TorrentV2PieceStore(fixture.document, FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-v2-webseed-log", emptySet(), "webseed", buffers, Semaphore(4))
        val seeds = assertNotNull(TorrentV2WebSeeds.of(fixture.document, fixture.layout, store,
          TorrentHttp(gate), ConnectionReporter.None, "test", retryMs = 5) {})
        withInfoLog { lines ->
          coroutineScope {
            val running = launch { seeds.run() }
            // Idle, as in a transfer that only seeds: nothing to say yet.
            assertIs<TorrentV2WebSeeds.Event.Idle>(seeds.updates.receive())
            assertTrue(lines.drain().none { "web seed(s) too" in it })
            val lease = assertNotNull(buffers.reserve(32_768 + 512))
            assertTrue(seeds.assign(0,
              listOf(TorrentV2PieceScheduler.WebSeedClaim(0, ByteArray(32_768), lease))))
            assertEquals(1, lines.drain().count { "web seed(s) too" in it })
            gate.asked.await()
            running.cancel()
          }
        }
        seeds.close()
        assertEquals(0, buffers.allocated)
      }
    }
  }

  /** Waits until [outage] has refused requests and stopped getting more: its seed was dropped. */
  private suspend fun awaitDropped(outage: Outage) {
    while (outage.refused == 0) delay(10)
    var seen = -1
    while (seen != outage.refused) {
      seen = outage.refused
      delay(1_000)
    }
  }

  @Test
  fun relaunchedSeed_asksOnceMoreForPiecesItSentCorrupt() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("bad.bin" to 20_000, "good.bin" to 40_000),
      webSeeds = listOf("http://mirror.test/pub/"))
    val bad = "http://mirror.test/pub/pack/bad.bin"
    // The mirror has another bad.bin, and is down from when it sent it twice until restored.
    val mirror = MirrorEngine(mapOf(bad to fixture.payloads[0].reversedArray(),
      "http://mirror.test/pub/pack/good.bin" to fixture.payloads[1]))
    val restored = AtomicBoolean(false)
    val outage = Outage(mirror) {
      !restored.load() && mirror.requests.count { it.first == bad } >= 2
    }
    stalling(fixture, outage) { run ->
      awaitDropped(outage)
      restored.store(true)
      // Two minutes of stall start the seed again: it fetches good.bin and asks once more for
      // bad.bin, giving it up again at the next stale copy.
      run.skip(130_000)
      run.session.downloadedBytes.first { it == 40_000L }
      while (mirror.requests.count { it.first == bad } < 3) delay(10)
      delay(500)
      assertEquals(3, mirror.requests.count { it.first == bad })
    }
  }

  @Test
  fun mirrorFixedAfterItsSeedWasDropped_isAskedAgainOnceTheStallLasts() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("bad.bin" to 20_000, "good.bin" to 40_000),
      webSeeds = listOf("http://mirror.test/pub/"))
    val bad = "http://mirror.test/pub/pack/bad.bin"
    val good = "http://mirror.test/pub/pack/good.bin"
    // The only source has another bad.bin until it is fixed, and no peer connects.
    val before = MirrorEngine(mapOf(bad to fixture.payloads[0].reversedArray(),
      good to fixture.payloads[1]))
    val after = MirrorEngine(mapOf(bad to fixture.payloads[0], good to fixture.payloads[1]))
    val fixed = AtomicBoolean(false)
    val mirror = object : HttpEngine by before {
      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) = (if (fixed.load()) after else before).download(url, range, headers, onData)
    }
    val wanted = fixture.layout.files.single { it.v2Index == 0 }.id
    stalling(fixture, mirror, selected = setOf(wanted)) { run ->
      // Sent corrupt twice, the seed has nothing left to fetch and is dropped.
      while (before.requests.count { it.first == bad } < 2) delay(10)
      delay(2_000)
      run.skip(40_000)
      assertEquals(TorrentStallReason.MISSING_PIECES,
        run.session.stall.first { it != null }?.reason)
      // The mirror is fixed: two minutes into the stall the seed starts again, asks once more
      // and finishes.
      fixed.store(true)
      run.skip(90_000)
      run.session.downloadedBytes.first { it == 20_000L }
      assertEquals(1, after.requests.count { it.first == bad })
    }
  }
}
