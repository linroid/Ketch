package com.linroid.ketch.torrent.webseed

import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.torrent.RecordingConnections
import com.linroid.ketch.torrent.TorrentUploadPolicy
import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.api.TorrentStallReason
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.magnet.MagnetUri
import com.linroid.ketch.torrent.magnet.metainfoFromInfo
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.TorrentListener
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.peer.SWARM_SUMMARY_INTERVAL_MS
import com.linroid.ketch.torrent.peer.pieceBitfield
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import com.linroid.ketch.torrent.v1.TorrentPieceScheduler
import com.linroid.ketch.torrent.v1.TorrentSwarm
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.IOException
import okio.Path

@OptIn(ExperimentalAtomicApi::class)
class TorrentWebSeedTest {
  private fun hashes(payload: ByteArray, pieceLength: Int): ByteArray =
    payload.asList().chunked(pieceLength).fold(ByteArray(0)) { result, chunk ->
      result + sha1Digest(chunk.toByteArray())
    }

  private fun singleFile(payload: ByteArray, seeds: Any?): TorrentMetadata {
    val root = mutableMapOf<String, Any>("info" to mapOf("name" to "data",
      "length" to payload.size.toLong(), "piece length" to PIECE.toLong(),
      "pieces" to hashes(payload, PIECE)))
    if (seeds != null) root["url-list"] = seeds
    return TorrentMetadata.fromBencode(Bencode.encode(root))
  }

  /** Three files whose pieces span file boundaries, the middle one empty. */
  private val parts = listOf(
    "a b.bin" to ByteArray(20_000) { (it * 7 + 1).toByte() },
    "empty" to ByteArray(0),
    "dir/ünï.bin" to ByteArray(30_000) { (it * 13 + 5).toByte() },
    "c.bin" to ByteArray(40_000) { (it * 3 + 11).toByte() },
  )

  private fun multiFile(seeds: Any): TorrentMetadata {
    val payload = parts.fold(ByteArray(0)) { all, part -> all + part.second }
    return TorrentMetadata.fromBencode(Bencode.encode(mapOf(
      "info" to mapOf("name" to "my set", "piece length" to PIECE.toLong(),
        "pieces" to hashes(payload, PIECE),
        "files" to parts.map { (path, bytes) ->
          mapOf("length" to bytes.size.toLong(), "path" to path.split('/'))
        }),
      "url-list" to seeds,
    )))
  }

  /** Where the mirror serves [parts] under `http://mirror.test/pub/`. */
  private val mirrorFiles = mapOf(
    "http://mirror.test/pub/my%20set/a%20b.bin" to parts[0].second,
    "http://mirror.test/pub/my%20set/empty" to parts[1].second,
    "http://mirror.test/pub/my%20set/dir/%C3%BCn%C3%AF.bin" to parts[2].second,
    "http://mirror.test/pub/my%20set/c.bin" to parts[3].second,
  )

  private suspend fun withStore(
    metadata: TorrentMetadata,
    selected: Set<Int> = emptySet(),
    block: suspend (TorrentPieceStore, Path) -> Unit,
  ) {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-webseed-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val output = root / metadata.name
    try {
      block(TorrentPieceStore(metadata, output, selected, "webseed"), output)
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  /** What a swarm's web seeds received, and of it threw away. */
  private class Payload {
    val received = AtomicLong(0)
    val discarded = AtomicLong(0)
  }

  /** Runs a swarm without peers: discovery is closed from the start. */
  private suspend fun runSwarm(
    store: TorrentPieceStore,
    vararg engines: HttpEngine,
    connections: RecordingConnections = RecordingConnections(),
    buffers: Int = 4 * 1024 * 1024,
    payload: Payload = Payload(),
  ) {
    val network = createTorrentNetwork()
    val peers = Channel<PeerEndpoint>().also { it.close() }
    val budget = TorrentBufferBudget(buffers)
    try {
      // One engine per seed: each worker gets the one its URL's host names.
      TorrentSwarm(store, network, budget, reporter = connections,
        downloadPayload = { payload.received.addAndFetch(it.toLong()) },
        onDiscarded = { payload.discarded.addAndFetch(it) },
        webSeedHttp = TorrentHttp(mirrorHosts(*engines)), webSeedRetryMs = 5).run(peers)
      assertEquals(0, budget.allocated)
    } finally {
      network.close()
    }
  }

  @Test
  fun metadata_urlListStringOrList_keepsHttpAndHttpsOnly() {
    val payload = ByteArray(10)
    assertEquals(listOf("https://a.test/data"),
      singleFile(payload, "https://a.test/data").webSeeds)
    val listed = singleFile(payload, listOf("http://a.test/", "ftp://b.test/data", "",
      "https://a.test/x", "http://a.test/", 7L, "mailto:x"))
    assertEquals(listOf("http://a.test/", "https://a.test/x"), listed.webSeeds)
    assertEquals(emptyList(), singleFile(payload, null).webSeeds)
  }

  @Test
  fun magnet_wsParameters_reachTheRebuiltMetainfo() {
    val magnet = MagnetUri.parse("magnet:?xt=urn:btih:${"ab".repeat(20)}" +
      "&ws=https%3A%2F%2Fm.test%2Fdata&ws=ftp%3A%2F%2Fm.test%2Fdata")
    assertEquals(listOf("https://m.test/data"), magnet.webSeeds)
    assertEquals(magnet, MagnetUri.parse(magnet.toUri()))
    val info = singleFile(ByteArray(10), null).infoBytes
    val rebuilt = TorrentMetadata.fromBencode(metainfoFromInfo(info,
      listOf(listOf("udp://t.test:1")), magnet.webSeeds))
    assertEquals(magnet.webSeeds, rebuilt.webSeeds)
    assertEquals(singleFile(ByteArray(10), null).infoHash, rebuilt.infoHash)
  }

  @Test
  fun layout_pieceSpanningFiles_mapsEachFileRangeWithEncodedPath() {
    val layout = WebSeedLayout(multiFile("http://mirror.test/pub"), "http://mirror.test/pub")
    // Piece 1 is bytes 16384..32767: the end of "a b.bin", nothing of "empty", then "ünï.bin".
    val spans = layout.spans(1, PIECE)
    assertEquals(listOf("http://mirror.test/pub/my%20set/a%20b.bin",
      "http://mirror.test/pub/my%20set/dir/%C3%BCn%C3%AF.bin"), spans.map { it.url })
    assertEquals(listOf(16_384L..19_999L, 0L..12_767L), spans.map { it.range })
    assertEquals(listOf(0, 3_616), spans.map { it.offset })
  }

  @Test
  fun layout_piecesOfAFile_includeThoseItShares() {
    val layout = WebSeedLayout(multiFile("http://mirror.test/pub"), "http://mirror.test/pub")
    val (first, empty, middle, last) = mirrorFiles.keys.toList()
    // a b.bin is bytes 0..19999, ünï.bin 20000..49999 and c.bin 50000..89999.
    assertEquals(setOf(0, 1), layout.piecesOf(first))
    assertEquals(emptySet(), layout.piecesOf(empty))
    assertEquals(setOf(1, 2, 3), layout.piecesOf(middle))
    assertEquals(setOf(3, 4, 5), layout.piecesOf(last))
  }

  @Test
  fun layout_singleFileFolderUrl_appendsTheName() {
    val metadata = singleFile(ByteArray(PIECE + 1), null)
    assertEquals("http://m.test/d/data", WebSeedLayout(metadata, "http://m.test/d/")
      .spans(1, 1).single().url)
    assertEquals(PIECE.toLong()..PIECE.toLong(),
      WebSeedLayout(metadata, "http://m.test/file.iso").spans(1, 1).single().range)
  }

  @Test
  fun singleFile_webSeedOnly_completesWithZeroPeers() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val payload = ByteArray(3 * PIECE + 123) { (it * 31 + 7).toByte() }
        val metadata = singleFile(payload, "https://mirror.test/data.bin")
        val mirror = MirrorEngine(mapOf("https://mirror.test/data.bin" to payload))
        val connections = RecordingConnections()
        withStore(metadata) { store, output ->
          runSwarm(store, mirror, connections = connections)
          assertTrue(store.completed())
          assertContentEquals(payload, torrentFileSystem.read(output) { readByteArray() })
          // The four pieces in one run, one range request.
          assertEquals(listOf(0L..<payload.size.toLong()), mirror.requests.map { it.second })
        }
        val seed = connections.opened.first()
        assertEquals("mirror.test", seed.spec.host)
        assertEquals(443, seed.spec.port)
        assertEquals(true, seed.spec.secure)
        assertEquals(ConnectionDirection.OUTGOING, seed.spec.direction)
        assertEquals(payload.size.toLong(), connections.opened.sumOf { it.received })
        assertTrue(connections.opened.all { it.closed })
      }
    }
  }

  @Test
  fun multiFile_webSeed_fetchesPiecesSpanningFiles() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val mirror = MirrorEngine(mirrorFiles)
        val overlap = OverlapEngine(mirror)
        // Without a trailing slash, the URL still names the folder.
        withStore(multiFile("http://mirror.test/pub")) { store, output ->
          runSwarm(store, overlap)
          assertTrue(store.completed())
          for ((path, bytes) in parts) {
            assertContentEquals(bytes, torrentFileSystem.read(output / path) { readByteArray() })
          }
          // The empty file is never asked for.
          assertTrue(mirror.requests.none { it.first.endsWith("/empty") })
          // One run over every piece: a range request per file, each file whole. The first goes
          // alone; once the mirror answers, the others go out together.
          assertEquals("http://mirror.test/pub/my%20set/a%20b.bin" to 0L..19_999L,
            mirror.requests.first())
          assertEquals(setOf(
            "http://mirror.test/pub/my%20set/dir/%C3%BCn%C3%AF.bin" to 0L..29_999L,
            "http://mirror.test/pub/my%20set/c.bin" to 0L..39_999L,
          ), mirror.requests.drop(1).toSet())
          assertEquals(3, mirror.requests.size)
          assertEquals(2, overlap.most)
        }
      }
    }
  }

  @Test
  fun multiFile_paddingNeverRequestedAndFilesAskedByTheirTorrentNames() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        // BEP 47 padding after "a.bin" starts "Who?.bin" at piece 2.
        val a = ByteArray(20_000) { (it * 7 + 1).toByte() }
        val b = ByteArray(10_000) { (it * 13 + 5).toByte() }
        val pad = 2 * PIECE - a.size
        val payload = a + ByteArray(pad) + b
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
          "info" to mapOf("name" to "set", "piece length" to PIECE.toLong(),
            "pieces" to hashes(payload, PIECE),
            "files" to listOf(
              mapOf("length" to a.size.toLong(), "path" to listOf("a.bin")),
              mapOf("attr" to "p", "length" to pad.toLong(), "path" to listOf(".pad", "$pad")),
              mapOf("length" to b.size.toLong(), "path" to listOf("Who?.bin"))
            )),
          "url-list" to "http://mirror.test/pub/"
        )))
        val mirror = MirrorEngine(mapOf(
          "http://mirror.test/pub/set/a.bin" to a,
          "http://mirror.test/pub/set/Who%3F.bin" to b
        ))
        withStore(metadata) { store, output ->
          runSwarm(store, mirror)
          assertTrue(store.completed())
          assertContentEquals(a, torrentFileSystem.read(output / "a.bin") { readByteArray() })
          assertContentEquals(b, torrentFileSystem.read(output / "Who_.bin") { readByteArray() })
          assertFalse(torrentFileSystem.exists(output / ".pad"))
          assertEquals(listOf(
            "http://mirror.test/pub/set/a.bin" to 0L..19_999L,
            "http://mirror.test/pub/set/Who%3F.bin" to 0L..9_999L
          ), mirror.requests)
        }
      }
    }
  }

  @Test
  fun singleFile_sixteenPieces_fetchesTheRunInOneRequest() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val payload = ByteArray(16 * PIECE) { (it * 17 + 3).toByte() }
        val mirror = MirrorEngine(mapOf("http://mirror.test/data" to payload))
        withStore(singleFile(payload, "http://mirror.test/data")) { store, output ->
          runSwarm(store, mirror)
          assertTrue(store.completed())
          assertContentEquals(payload, torrentFileSystem.read(output) { readByteArray() })
          assertEquals(listOf(0L..<payload.size.toLong()), mirror.requests.map { it.second })
        }
      }
    }
  }

  @Test
  fun corruptPieceInARun_failsOnlyThatPieceAndIsFetchedAgain() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val payload = ByteArray(16 * PIECE) { (it * 11 + 1).toByte() }
        val url = "http://mirror.test/data"
        // A byte of piece 5 is wrong in the first response only.
        val mirror = MirrorEngine(mapOf(url to payload), damage = mapOf(url to 5L * PIECE + 100))
        withStore(singleFile(payload, url)) { store, output ->
          val counted = Payload()
          runSwarm(store, mirror, payload = counted)
          assertTrue(store.completed())
          assertContentEquals(payload, torrentFileSystem.read(output) { readByteArray() })
          // Pieces 0 to 4 were kept; the next run starts at the corrupt piece.
          assertEquals(listOf(0L..<16L * PIECE, 5L * PIECE..<16L * PIECE),
            mirror.requests.map { it.second })
          // The corrupt piece, and what came of the run after it, were thrown away.
          assertTrue(counted.discarded.load() >= PIECE)
          assertEquals(payload.size.toLong(), counted.received.load() - counted.discarded.load())
        }
      }
    }
  }

  @Test
  fun stoppedWhileThrottled_countsTheWaitingChunkAsDiscarded() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val payload = ByteArray(4 * PIECE) { (it * 5 + 3).toByte() }
        val url = "http://mirror.test/data"
        withStore(singleFile(payload, url)) { store, _ ->
          val counted = Payload()
          val waiting = CompletableDeferred<Unit>()
          val network = createTorrentNetwork()
          val peers = Channel<PeerEndpoint>().also { it.close() }
          try {
            val swarm = launch {
              TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                downloadPayload = { size ->
                  // Counted, then held by the limits before the first piece is in.
                  if (counted.received.addAndFetch(size.toLong()) > PIECE) {
                    waiting.complete(Unit)
                    awaitCancellation()
                  }
                },
                onDiscarded = { counted.discarded.addAndFetch(it) },
                webSeedHttp = TorrentHttp(MirrorEngine(mapOf(url to payload))),
              ).run(peers)
            }
            waiting.await()
            swarm.cancelAndJoin()
          } finally {
            network.close()
          }
          assertEquals(0, store.verifiedPieces().count { it })
          assertTrue(counted.received.load() > PIECE)
          assertEquals(counted.received.load(), counted.discarded.load())
        }
      }
    }
  }

  @Test
  fun connectionDroppedMidRun_keepsTheVerifiedPieces() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val payload = ByteArray(8 * PIECE) { (it * 19 + 2).toByte() }
        val url = "http://mirror.test/data"
        // The first response ends halfway through piece 3.
        val mirror = MirrorEngine(mapOf(url to payload), drop = mapOf(url to 3 * PIECE + 500))
        withStore(singleFile(payload, url)) { store, output ->
          val counted = Payload()
          runSwarm(store, mirror, payload = counted)
          assertTrue(store.completed())
          assertContentEquals(payload, torrentFileSystem.read(output) { readByteArray() })
          assertEquals(listOf(0L..<8L * PIECE, 3L * PIECE..<8L * PIECE),
            mirror.requests.map { it.second })
          // The part of piece 3 the dropped run brought is fetched again.
          assertEquals(500L, counted.discarded.load())
          assertEquals(8L * PIECE + 500, counted.received.load())
        }
      }
    }
  }

  /** A worker for the one seed at [url] of [store], counting into [stats]: a failure backs it off. */
  private suspend fun soleWorker(
    store: TorrentPieceStore,
    url: String,
    engine: HttpEngine,
    stats: WebSeedStats,
  ): WebSeedWorker<TorrentPieceScheduler.Claim> {
    val scheduler = TorrentPieceScheduler(BooleanArray(store.pieceCount) { store.needed(it) },
      store.verifiedPieces(), store::pieceSize, TorrentBufferBudget(4 * 1024 * 1024))
    return WebSeedWorker(url, SwarmWebSeedPieces(-1, url, store, scheduler), TorrentHttp(engine),
      stats, "test", retryMs = 60_000)
  }

  @Test
  fun corruptPiece_isThrownAwayByTheStallCheckEachPartArrivedAfter() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val payload = ByteArray(PIECE) { (it * 3 + 7).toByte() }
        val url = "http://mirror.test/data"
        // A corrupt copy, sent a part at a time: 7,001 bytes, 7,001, then 2,382.
        val mirror = Stepped(MirrorEngine(mapOf(url to payload), corrupt = true))
        withStore(singleFile(payload, url)) { store, _ ->
          store.initialize()
          val stats = WebSeedStats()
          coroutineScope {
            val running = launch { soleWorker(store, url, mirror, stats).run() }
            // The first part arrives before a stall check, the rest after it.
            mirror.steps.send(Unit)
            while (mirror.delivered == 0) delay(10)
            assertEquals(WebSeedLedger(checks = 0, arrived = 7_001), stats.checked())
            mirror.steps.send(Unit)
            mirror.steps.send(Unit)
            while (stats.wasted == 0L) delay(10)
            // The check counted the first part, the next counts the rest: each takes its own back.
            assertEquals(WebSeedLedger(checks = 1, arrived = PIECE - 7_001L,
              wasted = mapOf(0L to 7_001L, 1L to PIECE - 7_001L)), stats.checked())
            running.cancel()
          }
        }
      }
    }
  }

  @Test
  fun corruptPieceAfterAVerifiedOne_throwsAwayOnlyItsOwnBytes() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val payload = ByteArray(2 * PIECE) { (it * 17 + 3).toByte() }
        val url = "http://mirror.test/data"
        // Both pieces in one response, the second corrupt.
        val mirror = MirrorEngine(mapOf(url to payload), damage = mapOf(url to PIECE + 100L))
        withStore(singleFile(payload, url)) { store, _ ->
          store.initialize()
          val stats = WebSeedStats()
          coroutineScope {
            val running = launch { soleWorker(store, url, mirror, stats).run() }
            while (stats.wasted == 0L) delay(10)
            // No check came meanwhile: of what arrived, the first piece is kept.
            assertTrue(store.verifiedPieces()[0])
            assertEquals(WebSeedLedger(checks = 0, arrived = 2L * PIECE,
              kept = mapOf(0L to PIECE.toLong()), wasted = mapOf(0L to PIECE.toLong())),
              stats.checked())
            running.cancel()
          }
        }
      }
    }
  }

  @Test
  fun selectionHole_splitsTheRun() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val mirror = MirrorEngine(mirrorFiles)
        // "a b.bin" is pieces 0 and 1, "c.bin" pieces 3 to 5; piece 2 lies in "ünï.bin" only.
        withStore(multiFile("http://mirror.test/pub/"), selected = setOf(0, 3)) { store, output ->
          runSwarm(store, mirror)
          assertTrue(store.completed())
          assertContentEquals(parts[0].second,
            torrentFileSystem.read(output / "a b.bin") { readByteArray() })
          assertContentEquals(parts[3].second,
            torrentFileSystem.read(output / "c.bin") { readByteArray() })
          // The two runs ask for the ends of "ünï.bin" apart, never its unwanted middle.
          assertEquals(listOf(0L..12_767L, 29_152L..29_999L), mirror.requests
            .filter { it.first.endsWith("%C3%AF.bin") }.map { it.second })
          assertEquals(4, mirror.requests.size)
        }
      }
    }
  }

  @Test
  fun bufferBudget_limitsTheRunLength() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val payload = ByteArray(16 * PIECE) { (it * 23 + 9).toByte() }
        val mirror = MirrorEngine(mapOf("http://mirror.test/data" to payload))
        withStore(singleFile(payload, "http://mirror.test/data")) { store, output ->
          // A run's later pieces leave half of a twelve pieces' budget free: six in a run.
          runSwarm(store, mirror, buffers = 12 * PIECE)
          assertTrue(store.completed())
          assertContentEquals(payload, torrentFileSystem.read(output) { readByteArray() })
          assertEquals(listOf(0L..<6L * PIECE, 6L * PIECE..<12L * PIECE,
            12L * PIECE..<16L * PIECE), mirror.requests.map { it.second })
        }
      }
    }
  }

  @Test
  fun selection_onlyWantedPiecesAreFetched() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val mirror = MirrorEngine(mirrorFiles)
        withStore(multiFile("http://mirror.test/pub/"), selected = setOf(3)) { store, output ->
          runSwarm(store, mirror)
          assertTrue(store.completed())
          assertContentEquals(parts[3].second,
            torrentFileSystem.read(output / "c.bin") { readByteArray() })
          // "c.bin" starts at 50000, inside piece 3: only that piece reaches into "ünï.bin".
          val urls = mirror.requests.map { it.first }
          assertTrue(urls.none { it.endsWith("a%20b.bin") })
          assertEquals(listOf(29_152L..29_999L), mirror.requests
            .filter { it.first.endsWith("%C3%AF.bin") }.map { it.second })
        }
      }
    }
  }

  @Test
  fun corruptSeed_backsOffWhileAnotherSeedFinishes() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val payload = ByteArray(6 * PIECE) { (it * 5 + 3).toByte() }
        val metadata = singleFile(payload, listOf("http://mirror0.test/data",
          "http://mirror1.test/data"))
        val bad = MirrorEngine(mapOf("http://mirror.test/data" to payload), corrupt = true)
        val good = MirrorEngine(mapOf("http://mirror.test/data" to payload))
        withStore(metadata) { store, output ->
          runSwarm(store, bad, good)
          assertTrue(store.completed())
          assertContentEquals(payload, torrentFileSystem.read(output) { readByteArray() })
          assertTrue(bad.requests.isNotEmpty())
          // Dropped after its failures in a row, never past them.
          assertTrue(bad.requests.size <= WebSeedWorker.MAX_FAILURES)
        }
      }
    }
  }

  @Test
  fun mirrorRefusingParallelRequests_isAskedOneFileAtATime() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        // A proxy in front of the mirror refuses a second connection while one is open.
        val proxy = OneAtATime(MirrorEngine(mirrorFiles))
        withStore(multiFile("http://mirror.test/pub")) { store, output ->
          runSwarm(store, proxy)
          assertTrue(store.completed())
          for ((path, bytes) in parts) {
            assertContentEquals(bytes, torrentFileSystem.read(output / path) { readByteArray() })
          }
          // Four requests at once, then two: each refusal halved them, without dropping the seed.
          assertEquals(2, proxy.refused)
        }
      }
    }
  }

  @Test
  fun missingFile_404_dropsTheSeedAndTheSwarmFails() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val payload = ByteArray(2 * PIECE) { it.toByte() }
        val metadata = singleFile(payload, "http://mirror.test/data")
        val mirror = MirrorEngine(emptyMap(), missing = setOf("http://mirror.test/data"))
        withStore(metadata) { store, _ ->
          assertFailsWith<IllegalStateException> { runSwarm(store, mirror) }
          // The file is missing: asked once, the seed has nothing left it can serve.
          assertEquals(1, mirror.requests.size)
          assertFalse(store.completed())
        }
      }
    }
  }

  @Test
  fun staleFile_isLeftToTheOtherSeed() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        // One mirror has since lost c.bin, as archive.org items lose regenerated files.
        val stale = "http://mirror.test/pub/my%20set/c.bin"
        val partial = MirrorEngine(mirrorFiles, missing = setOf(stale))
        val full = MirrorEngine(mirrorFiles)
        val seeds = listOf("http://mirror0.test/pub", "http://mirror1.test/pub")
        withStore(multiFile(seeds)) { store, output ->
          runSwarm(store, partial, full)
          assertTrue(store.completed())
          for ((path, bytes) in parts) {
            assertContentEquals(bytes, torrentFileSystem.read(output / path) { readByteArray() })
          }
          // Never asked for the lost file again.
          assertTrue(partial.requests.count { it.first == stale } <= 1)
        }
      }
    }
  }

  /** Two files: "stale.bin" is piece 0, "good.bin" pieces 1 to 3. */
  private val staleSet = ByteArray(PIECE) { (it * 7 + 1).toByte() } to
    ByteArray(3 * PIECE) { (it * 13 + 5).toByte() }

  private fun staleSetMetadata(seeds: Any = "http://mirror.test/pub/"): TorrentMetadata {
    val (stale, good) = staleSet
    return TorrentMetadata.fromBencode(Bencode.encode(mapOf(
      "info" to mapOf("name" to "set", "piece length" to PIECE.toLong(),
        "pieces" to hashes(stale + good, PIECE),
        "files" to listOf(
          mapOf("length" to stale.size.toLong(), "path" to listOf("stale.bin")),
          mapOf("length" to good.size.toLong(), "path" to listOf("good.bin"))
        )),
      "url-list" to seeds,
    )))
  }

  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun relaunchedSeed_asksOnceMoreForPiecesItSentCorrupt() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val (stale, good) = staleSet
        val staleUrl = "http://mirror.test/pub/set/stale.bin"
        // The mirror has another stale.bin, and is down from when it sent it twice until it is
        // restored.
        val mirror = MirrorEngine(mapOf(staleUrl to stale.reversedArray(),
          "http://mirror.test/pub/set/good.bin" to good))
        val restored = AtomicBoolean(false)
        val outage = Outage(mirror) {
          !restored.load() && mirror.requests.count { it.first == staleUrl } >= 2
        }
        withStore(staleSetMetadata()) { store, _ ->
          store.initialize()
          val scheduler = TorrentPieceScheduler(BooleanArray(store.pieceCount) { store.needed(it) },
            store.verifiedPieces(), store::pieceSize, TorrentBufferBudget(4 * 1024 * 1024))
          val pieces = SwarmWebSeedPieces(-1, "http://mirror.test/pub/", store, scheduler)
          val worker = WebSeedWorker("http://mirror.test/pub/", pieces, TorrentHttp(outage),
            WebSeedStats(), "test", retryMs = 5)
          pieces.join()
          // Dropped while the mirror is down, knowing piece 0 is not the torrent's there.
          worker.run()
          assertEquals(setOf(0), worker.refusals)
          restored.store(true)
          coroutineScope {
            val started = launch {
              pieces.join()
              worker.run()
            }
            while (!(1..3).all { store.verifiedPieces()[it] }) delay(10)
            started.cancel()
          }
          // Started again, it fetched the rest and asked once more for stale.bin: one more stale
          // copy was enough to give it up again.
          assertEquals(3, mirror.requests.count { it.first == staleUrl })
          assertEquals(setOf(0), worker.refusals)
        }
      }
    }
  }

  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun mirrorFixedAfterItsSeedWasDropped_isAskedAgainOnceTheStallLasts() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        var clock = 0L
        val stall = MutableStateFlow<TorrentStall?>(null)
        val (stale, good) = staleSet
        val staleUrl = "http://mirror.test/pub/set/stale.bin"
        val goodUrl = "http://mirror.test/pub/set/good.bin"
        // The only source has another stale.bin until it is fixed, and no peer connects.
        val before = MirrorEngine(mapOf(staleUrl to stale.reversedArray(), goodUrl to good))
        val after = MirrorEngine(mapOf(staleUrl to stale, goodUrl to good))
        val fixed = AtomicBoolean(false)
        val mirror = object : HttpEngine by before {
          override suspend fun download(
            url: String,
            range: LongRange?,
            headers: Map<String, String>,
            onData: suspend (ByteArray) -> Unit,
          ) = (if (fixed.load()) after else before).download(url, range, headers, onData)
        }
        withStore(staleSetMetadata(), selected = setOf(0)) { store, output ->
          val network = createTorrentNetwork()
          val peers = Channel<PeerEndpoint>()
          try {
            coroutineScope {
              val swarm = launch {
                TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                  webSeedHttp = TorrentHttp(mirror), webSeedRetryMs = 5, nowMs = { clock },
                  stall = stall).run(peers)
              }
              // Sent corrupt twice, the seed has nothing left to fetch and is dropped.
              while (before.requests.count { it.first == staleUrl } < 2) delay(20)
              delay(500)
              clock = 40_000
              assertEquals(TorrentStallReason.MISSING_PIECES,
                withTimeout(5_000) { stall.filterNotNull().first() }.reason)
              // The mirror is fixed: two minutes into the stall the seed starts again, asks once
              // more and finishes.
              fixed.store(true)
              clock = 130_000
              while (!store.completed()) delay(20)
              assertEquals(1, after.requests.count { it.first == staleUrl })
              swarm.cancel()
            }
          } finally {
            network.close()
          }
          assertContentEquals(stale,
            torrentFileSystem.read(output / "stale.bin") { readByteArray() })
        }
      }
    }
  }

  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun relaunchedSeed_keepsWhatWasMissingUntilItsBytesArrive() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        var clock = 0L
        val stall = MutableStateFlow<TorrentStall?>(null)
        val (stale, good) = staleSet
        val staleUrl = "http://mirror.test/pub/set/stale.bin"
        val goodUrl = "http://mirror.test/pub/set/good.bin"
        // The only source lacks stale.bin, the one file wanted, until it is fixed; started again,
        // it sends it slowly, a part when the test lets it, and no peer connects.
        val lacking = MirrorEngine(mapOf(goodUrl to good))
        val fixed = Stepped(MirrorEngine(mapOf(staleUrl to stale, goodUrl to good)))
        val holding = AtomicBoolean(false)
        val mirror = object : HttpEngine by lacking {
          override suspend fun download(
            url: String,
            range: LongRange?,
            headers: Map<String, String>,
            onData: suspend (ByteArray) -> Unit,
          ) = (if (holding.load()) fixed else lacking).download(url, range, headers, onData)
        }
        withStore(staleSetMetadata(), selected = setOf(0)) { store, _ ->
          val network = createTorrentNetwork()
          val peers = Channel<PeerEndpoint>()
          try {
            coroutineScope {
              val swarm = launch {
                TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                  webSeedHttp = TorrentHttp(mirror), webSeedRetryMs = 5, nowMs = { clock },
                  stall = stall).run(peers)
              }
              while (lacking.requests.none { it.first == staleUrl }) delay(20)
              delay(500)
              clock = 40_000
              val reported = withTimeout(5_000) { stall.filterNotNull().first() }
              assertEquals(TorrentStallReason.MISSING_PIECES, reported.reason)
              // Two minutes into the stall the seed starts again and asks once more: it offers
              // every piece, but has yet to answer, so stale.bin stays missing as long as the
              // answer takes.
              holding.store(true)
              clock = 130_000
              fixed.asked.await()
              for (time in listOf(145_000L, 400_000L)) {
                clock = time
                delay(500)
                val waiting = assertNotNull(stall.value)
                assertEquals(TorrentStallReason.MISSING_PIECES, waiting.reason)
                assertEquals(1, waiting.missingPieces)
                assertEquals(listOf("0"), waiting.missingFileIds)
              }
              // The first bytes of stale.bin arrive: the mirror serves it, so it is not missing
              // while the rest of its piece comes.
              fixed.steps.send(Unit)
              while (fixed.delivered == 0) delay(20)
              clock = 401_000
              delay(500)
              val streaming = assertNotNull(stall.value)
              assertEquals(TorrentStallReason.NO_PEERS, streaming.reason)
              assertEquals(0, streaming.missingPieces)
              // The rest arrives: the download finishes.
              while (!store.completed()) {
                fixed.steps.trySend(Unit)
                delay(20)
              }
              swarm.cancel()
            }
          } finally {
            network.close()
          }
        }
      }
    }
  }

  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun peerReconnectingAfterARelaunch_clearsWhatWasMissingOnlyWithThosePieces() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        var clock = 0L
        val stall = MutableStateFlow<TorrentStall?>(null)
        val goodUrl = "http://mirror.test/pub/set/good.bin"
        // The only web seed lacks stale.bin, the one file wanted, and once started again its
        // request waits.
        val lacking = MirrorEngine(mapOf(goodUrl to staleSet.second))
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
        withStore(staleSetMetadata(), selected = setOf(0)) { store, _ ->
          val network = createTorrentNetwork()
          val first = network.listen(PeerEndpoint("127.0.0.1", 0))
          val again = network.listen(PeerEndpoint("127.0.0.1", 0))
          val holder = network.listen(PeerEndpoint("127.0.0.1", 0))
          val peers = Channel<PeerEndpoint>(1)
          // Steps the clock a second at a time until the stall says [done].
          suspend fun awaitStall(done: (TorrentStall) -> Boolean): TorrentStall {
            while (true) {
              stall.value?.takeIf(done)?.let { return it }
              clock += 1_000
              delay(200)
            }
          }
          // Connects as a peer holding [held] that never unchokes.
          suspend fun connect(
            listener: TorrentListener,
            held: BooleanArray,
          ): TorrentConnection {
            peers.send(listener.local)
            val connection = listener.accept()
            val wire = PeerWire(connection, store.metadata)
            wire.handshake(PeerHandshake(store.metadata.infoHash, torrentRandomBytes(20),
              extensions = false, dht = false))
            wire.send(PeerMessage.Bitfield(pieceBitfield(held)))
            return connection
          }
          val lackingStale = booleanArrayOf(false, true, true, true)
          val connections = mutableListOf<TorrentConnection>()
          try {
            coroutineScope {
              val swarm = launch {
                TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                  webSeedHttp = TorrentHttp(mirror), webSeedRetryMs = 5, nowMs = { clock },
                  stall = stall).run(peers)
              }
              while (lacking.requests.isEmpty()) delay(20)
              delay(500)
              // Late into the stall a peer with good.bin alone connects: stale.bin is missing.
              clock = 100_000
              val leaving = connect(first, lackingStale)
              val missing = awaitStall { it.peers == 1 }
              assertEquals(TorrentStallReason.MISSING_PIECES, missing.reason)
              assertEquals(1, missing.missingPieces)
              // Two minutes into the stall the seed starts again, and asks once more.
              holding.store(true)
              clock = 130_000
              asked.await()
              // The peer leaves and comes back, as the swarm drops a peer that brings nothing for
              // 90 s and dials it again: still without stale.bin, it changes nothing.
              leaving.close()
              first.close()
              awaitStall { it.peers == 0 }
              connections += connect(again, lackingStale)
              val back = awaitStall { it.peers == 1 }
              assertEquals(TorrentStallReason.MISSING_PIECES, back.reason)
              assertEquals(1, back.missingPieces)
              assertEquals(listOf("0"), back.missingFileIds)
              // A peer with stale.bin connects: nothing is missing now.
              connections += connect(holder, booleanArrayOf(true, true, true, true))
              val heard = awaitStall { it.peers == 2 }
              assertEquals(TorrentStallReason.CHOKED, heard.reason)
              assertEquals(0, heard.missingPieces)
              swarm.cancel()
            }
          } finally {
            connections.forEach { it.close() }
            first.close()
            again.close()
            holder.close()
            network.close()
          }
        }
      }
    }
  }

  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun deadRelaunchedSeed_keepsWhatWasMissingThroughItsRetriesAndOnceDropped() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        var clock = 0L
        val stall = MutableStateFlow<TorrentStall?>(null)
        val goodUrl = "http://mirror.test/pub/set/good.bin"
        // The only web seed lacks stale.bin, the one file wanted, and once started again it is
        // down: each request fails when the test lets it.
        val lacking = MirrorEngine(mapOf(goodUrl to staleSet.second))
        val holding = AtomicBoolean(false)
        val asked = AtomicInt(0)
        val failures = Channel<Unit>(Channel.UNLIMITED)
        val mirror = object : HttpEngine by lacking {
          override suspend fun download(
            url: String,
            range: LongRange?,
            headers: Map<String, String>,
            onData: suspend (ByteArray) -> Unit,
          ) {
            if (!holding.load()) return lacking.download(url, range, headers, onData)
            asked.incrementAndFetch()
            failures.receive()
            throw KetchError.Network()
          }
        }
        withStore(staleSetMetadata(), selected = setOf(0)) { store, _ ->
          val network = createTorrentNetwork()
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          val peers = Channel<PeerEndpoint>(1)
          // Steps the clock a second and returns the stall a check then publishes.
          suspend fun checked(): TorrentStall {
            clock += 1_000
            delay(300)
            return assertNotNull(stall.value)
          }
          try {
            coroutineScope {
              val swarm = launch {
                TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                  webSeedHttp = TorrentHttp(mirror), webSeedRetryMs = 5, nowMs = { clock },
                  stall = stall).run(peers)
              }
              while (lacking.requests.isEmpty()) delay(20)
              delay(500)
              // Late into the stall a peer with good.bin alone connects: stale.bin is missing.
              clock = 100_000
              peers.send(listener.local)
              val connection = listener.accept()
              try {
                val wire = PeerWire(connection, store.metadata)
                wire.handshake(PeerHandshake(store.metadata.infoHash, torrentRandomBytes(20),
                  extensions = false, dht = false))
                wire.send(PeerMessage.Bitfield(pieceBitfield(booleanArrayOf(false, true, true,
                  true))))
                var connected = checked()
                while (connected.peers != 1) connected = checked()
                assertEquals(TorrentStallReason.MISSING_PIECES, connected.reason)
                // Two minutes into the stall the seed starts again, and every request it sends
                // fails: stale.bin stays missing while it tries, and once it is dropped again.
                holding.store(true)
                clock = 130_000
                var failed = 0
                while (true) {
                  withTimeoutOrNull(1_000) { while (asked.load() == failed) delay(10) } ?: break
                  val trying = checked()
                  assertEquals(TorrentStallReason.MISSING_PIECES, trying.reason)
                  assertEquals(listOf("0"), trying.missingFileIds)
                  failures.send(Unit)
                  failed++
                }
                assertEquals(2 + WebSeedWorker.MAX_FAILURES, failed)
                val dropped = checked()
                assertEquals(TorrentStallReason.MISSING_PIECES, dropped.reason)
                assertEquals(1, dropped.missingPieces)
                assertEquals(listOf("0"), dropped.missingFileIds)
                assertEquals(1, dropped.peers)
                swarm.cancel()
              } finally {
                connection.close()
              }
            }
          } finally {
            listener.close()
            network.close()
          }
        }
      }
    }
  }

  @Test
  fun droppedSeedWithoutPeers_reportsTheStaleFileAsMissing() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        var clock = 0L
        val stall = MutableStateFlow<TorrentStall?>(null)
        val (stale, good) = staleSet
        val staleUrl = "http://mirror.test/pub/set/stale.bin"
        // The one file wanted is another stale.bin at the only source, and no peer connects.
        val mirror = MirrorEngine(mapOf(staleUrl to stale.reversedArray(),
          "http://mirror.test/pub/set/good.bin" to good))
        withStore(staleSetMetadata(), selected = setOf(0)) { store, _ ->
          val network = createTorrentNetwork()
          // Discovery stays open, finding nobody.
          val peers = Channel<PeerEndpoint>()
          try {
            coroutineScope {
              val swarm = launch {
                TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                  webSeedHttp = TorrentHttp(mirror), webSeedRetryMs = 5, nowMs = { clock },
                  stall = stall).run(peers)
              }
              // Sent corrupt twice, the seed has nothing left to fetch and is dropped.
              while (mirror.requests.count { it.first == staleUrl } < 2) delay(20)
              delay(500)
              // Nothing arrives for 40 s: a stall, and why.
              clock = 40_000
              delay(500)
              clock = 80_000
              val reported = withTimeout(5_000) { stall.filterNotNull().first() }
              assertEquals(TorrentStallReason.MISSING_PIECES, reported.reason)
              assertEquals(1, reported.missingPieces)
              assertEquals(listOf("0"), reported.missingFileIds)
              assertEquals(0, reported.peers)
              swarm.cancel()
            }
          } finally {
            network.close()
          }
        }
      }
    }
  }

  @Test
  fun seedBackingOffWithoutPeers_isNoPeers() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        var clock = 0L
        val stall = MutableStateFlow<TorrentStall?>(null)
        val payload = ByteArray(2 * PIECE) { (it * 3 + 1).toByte() }
        // The only source is down, and no peer connects.
        val down = Outage(MirrorEngine(mapOf("http://mirror.test/data" to payload))) { true }
        withStore(singleFile(payload, "http://mirror.test/data")) { store, _ ->
          val network = createTorrentNetwork()
          val peers = Channel<PeerEndpoint>()
          try {
            coroutineScope {
              val swarm = launch {
                // The seed backs off for a minute after its first failure, still running.
                TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                  webSeedHttp = TorrentHttp(down), webSeedRetryMs = 60_000, nowMs = { clock },
                  stall = stall).run(peers)
              }
              while (down.refused == 0) delay(20)
              delay(500)
              // Nothing arrives for 40 s: the seed stores nothing, and the download waits for
              // peers.
              clock = 40_000
              val reported = withTimeout(5_000) { stall.filterNotNull().first() }
              assertEquals(TorrentStallReason.NO_PEERS, reported.reason)
              assertEquals(0, reported.missingPieces)
              assertEquals(1, down.refused)
              swarm.cancel()
            }
          } finally {
            network.close()
          }
        }
      }
    }
  }

  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun pieceOneSeedLacked_isMissingOnlyOnceEverySeedLacksIt() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        var clock = 0L
        val stall = MutableStateFlow<TorrentStall?>(null)
        val (stale, good) = staleSet
        val staleUrl = "http://mirror.test/pub/set/stale.bin"
        val files = mapOf(staleUrl to stale, "http://mirror.test/pub/set/good.bin" to good)
        // The first seed lacks stale.bin, the one file wanted; the second is down, and lacks it
        // too once back. Both are dropped, and no peer connects.
        val lacking = MirrorEngine(files - staleUrl)
        val second = MirrorEngine(files - staleUrl)
        val back = AtomicBoolean(false)
        val down = Outage(second) { !back.load() }
        val seeds = listOf("http://mirror0.test/pub/", "http://mirror1.test/pub/")
        withStore(staleSetMetadata(seeds), selected = setOf(0)) { store, _ ->
          val network = createTorrentNetwork()
          val peers = Channel<PeerEndpoint>()
          try {
            coroutineScope {
              val swarm = launch {
                TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                  webSeedHttp = TorrentHttp(mirrorHosts(lacking, down)), webSeedRetryMs = 5,
                  nowMs = { clock }, stall = stall).run(peers)
              }
              while (lacking.requests.isEmpty() || down.refused < 2 + WebSeedWorker.MAX_FAILURES) {
                delay(20)
              }
              delay(500)
              // Nothing arrives for 40 s: the second seed may still serve stale.bin once started
              // again, so it is not missing, and the download waits for a source.
              clock = 40_000
              val reported = withTimeout(5_000) { stall.filterNotNull().first() }
              assertEquals(TorrentStallReason.NO_PEERS, reported.reason)
              assertEquals(0, reported.missingPieces)
              // Two minutes into the stall both start again, and neither has stale.bin: missing.
              back.store(true)
              clock = 130_000
              while (lacking.requests.count { it.first == staleUrl } < 2 ||
                second.requests.none { it.first == staleUrl }) {
                delay(20)
              }
              delay(500)
              clock = 131_000
              val missing = withTimeout(5_000) {
                stall.filterNotNull().first { it.reason == TorrentStallReason.MISSING_PIECES }
              }
              assertEquals(1, missing.missingPieces)
              assertEquals(listOf("0"), missing.missingFileIds)
              swarm.cancel()
            }
          } finally {
            network.close()
          }
        }
      }
    }
  }

  @Test
  fun pieceOneSeedLacked_isMissingOnceTheOtherIsDownAcrossARelaunch() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        var clock = 0L
        val stall = MutableStateFlow<TorrentStall?>(null)
        val (stale, good) = staleSet
        val staleUrl = "http://mirror.test/pub/set/stale.bin"
        val files = mapOf(staleUrl to stale, "http://mirror.test/pub/set/good.bin" to good)
        // The first seed lacks stale.bin, the one file wanted; the second stays down. Both are
        // dropped, and no peer connects.
        val lacking = MirrorEngine(files - staleUrl)
        val down = Outage(MirrorEngine(files)) { true }
        val seeds = listOf("http://mirror0.test/pub/", "http://mirror1.test/pub/")
        withStore(staleSetMetadata(seeds), selected = setOf(0)) { store, _ ->
          val network = createTorrentNetwork()
          val peers = Channel<PeerEndpoint>()
          try {
            coroutineScope {
              val swarm = launch {
                TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                  webSeedHttp = TorrentHttp(mirrorHosts(lacking, down)), webSeedRetryMs = 5,
                  nowMs = { clock }, stall = stall).run(peers)
              }
              while (lacking.requests.isEmpty() || down.refused < 2 + WebSeedWorker.MAX_FAILURES) {
                delay(20)
              }
              delay(500)
              // Nothing arrives for 40 s: the second seed may only be down for a while, and serve
              // stale.bin once started again, so it is not missing yet.
              clock = 40_000
              val reported = withTimeout(5_000) { stall.filterNotNull().first() }
              assertEquals(TorrentStallReason.NO_PEERS, reported.reason)
              assertEquals(0, reported.missingPieces)
              // Two minutes into the stall both start again: the first lacks stale.bin again, and
              // the second, still down, is dropped again without an answer, which says nothing of
              // stale.bin any more: missing.
              clock = 130_000
              while (lacking.requests.count { it.first == staleUrl } < 2 ||
                down.refused < 2 + 2 * WebSeedWorker.MAX_FAILURES) {
                delay(20)
              }
              delay(500)
              clock = 131_000
              val missing = withTimeout(5_000) {
                stall.filterNotNull().first { it.reason == TorrentStallReason.MISSING_PIECES }
              }
              assertEquals(1, missing.missingPieces)
              assertEquals(listOf("0"), missing.missingFileIds)
              swarm.cancel()
            }
          } finally {
            network.close()
          }
        }
      }
    }
  }

  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun pieceTheRunningSeedLacks_isMissingOnlyOnceTheStoppedSeedLacksItToo() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        var clock = 0L
        val stall = MutableStateFlow<TorrentStall?>(null)
        val (stale, good) = staleSet
        val staleUrl = "http://mirror.test/pub/set/stale.bin"
        val files = mapOf(staleUrl to stale, "http://mirror.test/pub/set/good.bin" to good)
        // The first seed lacks stale.bin and serves good.bin, so it keeps running; the second is
        // down, so it is dropped, and lacks stale.bin too once back. No peer connects.
        val lacking = MirrorEngine(files - staleUrl)
        val second = MirrorEngine(files - staleUrl)
        val back = AtomicBoolean(false)
        val down = Outage(second) { !back.load() }
        val seeds = listOf("http://mirror0.test/pub/", "http://mirror1.test/pub/")
        withStore(staleSetMetadata(seeds)) { store, _ ->
          val network = createTorrentNetwork()
          val peers = Channel<PeerEndpoint>()
          try {
            coroutineScope {
              val swarm = launch {
                TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                  webSeedHttp = TorrentHttp(mirrorHosts(lacking, down)), webSeedRetryMs = 5,
                  nowMs = { clock }, stall = stall).run(peers)
              }
              while (lacking.requests.none { it.first == staleUrl } ||
                !(1..3).all { store.verifiedPieces()[it] } ||
                down.refused < 2 + WebSeedWorker.MAX_FAILURES) {
                delay(20)
              }
              delay(500)
              // Payload stopped 40 s ago: the dropped seed may still serve stale.bin once started
              // again, so it is not missing while the other seed runs, and the download waits for
              // a source.
              clock = 40_000
              delay(500)
              clock = 80_000
              val reported = withTimeout(5_000) { stall.filterNotNull().first() }
              assertEquals(TorrentStallReason.NO_PEERS, reported.reason)
              assertEquals(0, reported.missingPieces)
              // Two minutes into the stall the dropped seed starts again, without stale.bin
              // either: missing.
              back.store(true)
              clock = 130_000
              while (second.requests.none { it.first == staleUrl }) delay(20)
              delay(500)
              clock = 131_000
              val missing = withTimeout(5_000) {
                stall.filterNotNull().first { it.reason == TorrentStallReason.MISSING_PIECES }
              }
              assertEquals(1, missing.missingPieces)
              assertEquals(listOf("0"), missing.missingFileIds)
              swarm.cancel()
            }
          } finally {
            network.close()
          }
        }
      }
    }
  }

  @Test
  fun peerLackingAPiece_isMissingOnceTheDownSeedFailsAcrossARelaunch() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        var clock = 0L
        val stall = MutableStateFlow<TorrentStall?>(null)
        // The only web seed stays down, and the one peer has good.bin but not stale.bin.
        val down = Outage(MirrorEngine(emptyMap())) { true }
        withStore(staleSetMetadata()) { store, _ ->
          val network = createTorrentNetwork()
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          val peers = Channel<PeerEndpoint>(1)
          try {
            coroutineScope {
              val swarm = launch {
                TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                  webSeedHttp = TorrentHttp(down), webSeedRetryMs = 5, nowMs = { clock },
                  stall = stall).run(peers)
              }
              while (down.refused < 2 + WebSeedWorker.MAX_FAILURES) delay(20)
              delay(500)
              // The peer connects late into the stall, so it is not given up on for bringing
              // nothing before the seed starts again.
              clock = 100_000
              peers.send(listener.local)
              val connection = listener.accept()
              try {
                val wire = PeerWire(connection, store.metadata)
                wire.handshake(PeerHandshake(store.metadata.infoHash, torrentRandomBytes(20),
                  extensions = false, dht = false))
                wire.send(PeerMessage.Bitfield(pieceBitfield(booleanArrayOf(false, true, true,
                  true))))
                // Interested, the swarm took the peer's pieces; the peer never unchokes it.
                do {
                  val message = wire.read()
                } while (message != PeerMessage.Control(PeerMessage.Signal.INTERESTED))
                // The seed may only be down for a while, and serve stale.bin once started again:
                // not missing yet.
                clock = 101_000
                val choked = withTimeout(5_000) {
                  stall.filterNotNull().first { it.reason == TorrentStallReason.CHOKED }
                }
                assertEquals(0, choked.missingPieces)
                assertEquals(1, choked.peers)
                // Two minutes into the stall it starts again, and is dropped again without an
                // answer: it says nothing of stale.bin any more, and the peer lacks it: missing.
                clock = 130_000
                while (down.refused < 2 + 2 * WebSeedWorker.MAX_FAILURES) delay(20)
                delay(500)
                clock = 131_000
                val missing = withTimeout(5_000) {
                  stall.filterNotNull().first { it.reason == TorrentStallReason.MISSING_PIECES }
                }
                assertEquals(1, missing.missingPieces)
                assertEquals(listOf("0"), missing.missingFileIds)
                assertEquals(1, missing.peers)
                swarm.cancel()
              } finally {
                connection.close()
              }
            }
          } finally {
            listener.close()
            network.close()
          }
        }
      }
    }
  }

  @Test
  fun corruptPieceAfterAStall_isNoProgress() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        var clock = 0L
        val stall = MutableStateFlow<TorrentStall?>(null)
        val (stale, good) = staleSet
        val staleUrl = "http://mirror.test/pub/set/stale.bin"
        // The one file wanted is another stale.bin at the only source, which sends it slowly.
        val mirror = Stepped(MirrorEngine(mapOf(staleUrl to stale.reversedArray(),
          "http://mirror.test/pub/set/good.bin" to good)))
        withInfoLog { lines ->
          withStore(staleSetMetadata(), selected = setOf(0)) { store, _ ->
            val network = createTorrentNetwork()
            val peers = Channel<PeerEndpoint>()
            try {
              coroutineScope {
                val swarm = launch {
                  TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                    webSeedHttp = TorrentHttp(mirror), webSeedRetryMs = 5, nowMs = { clock },
                    stall = stall).run(peers)
                }
                // Nothing for longer than a piece may take: a stall, and no peer to help.
                mirror.asked.await()
                clock = 70_000
                assertEquals(TorrentStallReason.NO_PEERS,
                  withTimeout(5_000) { stall.filterNotNull().first() }.reason)
                // The piece arrives, corrupt, twice, a part a second: what may still be thrown
                // away is no progress, and once thrown away it never was.
                val seen = mutableListOf<TorrentStall?>()
                while (stall.value?.reason != TorrentStallReason.MISSING_PIECES) {
                  mirror.steps.trySend(Unit)
                  delay(100)
                  clock += 1_000
                  delay(300)
                  seen += stall.value
                }
                assertTrue(seen.all { it != null }, seen.toString())
                swarm.cancel()
              }
            } finally {
              network.close()
            }
          }
          assertTrue(lines.drain().none { "makes progress again" in it })
        }
      }
    }
  }

  @Test
  fun corruptPieceWithinOneCheck_stallsHalfAMinuteAfterTheLastKeptPayload() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        var clock = 0L
        val stall = MutableStateFlow<TorrentStall?>(null)
        val payload = ByteArray(2 * PIECE) { (it * 11 + 5).toByte() }
        val url = "http://mirror.test/data"
        // The only source sends a part of 7,001 bytes when the test lets it, and its first copy
        // of piece 1 is corrupt.
        val mirror = MirrorEngine(mapOf(url to payload), damage = mapOf(url to PIECE + 100L))
        val stepped = Stepped(mirror)
        withStore(singleFile(payload, url)) { store, _ ->
          val network = createTorrentNetwork()
          val peers = Channel<PeerEndpoint>()
          // Moves the clock on and lets the swarm check itself; returns the stall it published.
          suspend fun at(time: Long): TorrentStall? {
            clock = time
            delay(300)
            return stall.value
          }
          suspend fun deliver(parts: Int) {
            val delivered = stepped.delivered + parts
            repeat(parts) { stepped.steps.send(Unit) }
            while (stepped.delivered < delivered) delay(10)
          }
          try {
            coroutineScope {
              val swarm = launch {
                TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                  webSeedHttp = TorrentHttp(stepped), webSeedRetryMs = 5, nowMs = { clock },
                  stall = stall).run(peers)
              }
              stepped.asked.await()
              // Payload that may still be kept is payload: parts at 0 s and 25 s, though nothing
              // verified yet, keep the swarm from stalling at 40 s.
              deliver(1)
              at(1_000)
              at(25_000)
              deliver(1)
              at(26_000)
              assertNull(at(40_000))
              // The next part verifies piece 0 and brings the first bytes of piece 1, counted at
              // 41 s: the last payload kept.
              deliver(1)
              assertTrue(store.verifiedPieces()[0])
              delay(300)
              at(41_000)
              at(60_000)
              // The rest of piece 1 comes within one check, corrupt: the last part fails the run,
              // and the seed asks for the piece again.
              deliver(1)
              stepped.steps.send(Unit)
              while (mirror.requests.size < 2) delay(10)
              at(61_000)
              // Thrown away, piece 1 never was payload: only the no-payload clock can stall the
              // swarm before 100 s, half a minute after the last payload kept.
              assertNull(at(70_000))
              clock = 71_000
              assertEquals(TorrentStallReason.NO_PEERS,
                withTimeout(5_000) { stall.filterNotNull().first() }.reason)
              swarm.cancel()
            }
          } finally {
            network.close()
          }
        }
      }
    }
  }

  @Test
  fun completingAfterAStall_isNotLoggedAsProgress() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        var clock = 0L
        val stall = MutableStateFlow<TorrentStall?>(null)
        val payload = ByteArray(2 * PIECE) { (it * 3 + 1).toByte() }
        val gate = Gate(MirrorEngine(mapOf("http://mirror.test/data" to payload)))
        withInfoLog { lines ->
          withStore(singleFile(payload, "http://mirror.test/data")) { store, _ ->
            val network = createTorrentNetwork()
            val peers = Channel<PeerEndpoint>()
            try {
              coroutineScope {
                val swarm = launch {
                  TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                    uploadPolicy = { TorrentUploadPolicy.SEED_AFTER_COMPLETION },
                    webSeedHttp = TorrentHttp(gate), webSeedRetryMs = 5, nowMs = { clock },
                    stall = stall).run(peers)
                }
                // The mirror answers late: a stall, then the whole torrent at once.
                gate.asked.await()
                clock = 40_000
                withTimeout(5_000) { stall.filterNotNull().first() }
                gate.open.complete(Unit)
                while (!store.completed()) delay(10)
                // Seeding, the swarm checks itself again: no stall, and no progress either.
                clock = 41_000
                withTimeout(5_000) { stall.first { it == null } }
                swarm.cancel()
              }
            } finally {
              network.close()
            }
          }
          val logged = lines.drain()
          assertTrue(logged.any { "stalled: no-peers" in it }, logged.toString())
          assertTrue(logged.none { "makes progress again" in it }, logged.toString())
        }
      }
    }
  }

  @Test
  fun seedingSwarm_startsItsWebSeedsOnlyOnceTheSelectionGrows() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val mirror = MirrorEngine(mirrorFiles)
        withStore(multiFile("http://mirror.test/pub"), selected = setOf(0)) { store, output ->
          runSwarm(store, mirror)
          assertTrue(store.completed())
          withInfoLog { lines ->
            val network = createTorrentNetwork()
            val selections = Channel<CompletableDeferred<Unit>>()
            try {
              coroutineScope {
                val swarm = launch {
                  TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                    uploadPolicy = { TorrentUploadPolicy.SEED_AFTER_COMPLETION },
                    webSeedHttp = TorrentHttp(mirror), webSeedRetryMs = 5)
                    .run(Channel(), selections = selections)
                }
                // Seeding, it has nothing to fetch from its seed.
                delay(300)
                assertTrue(lines.drain().none { "web seed(s) too" in it })
                // c.bin is wanted too: the seed starts and fetches it.
                store.changeSelection(setOf(0, 3))
                CompletableDeferred<Unit>().also { selections.send(it) }.await()
                while (!store.completed()) delay(10)
                assertEquals(1, lines.drain().count { "web seed(s) too" in it })
                swarm.cancel()
              }
            } finally {
              network.close()
            }
          }
          assertContentEquals(parts[3].second,
            torrentFileSystem.read(output / "c.bin") { readByteArray() })
        }
      }
    }
  }

  @Test
  fun completedSwarm_stopsItsWebSeedsUntilTheSelectionGrows() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        var clock = 0L
        val mirror = MirrorEngine(mirrorFiles)
        val completions = Channel<Unit>(Channel.UNLIMITED)
        withStore(multiFile("http://mirror.test/pub"), selected = setOf(0)) { store, output ->
          withLog(LogLevel.DEBUG) { lines ->
            val network = createTorrentNetwork()
            val selections = Channel<CompletableDeferred<Unit>>()
            try {
              coroutineScope {
                val swarm = launch {
                  TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                    uploadPolicy = { TorrentUploadPolicy.SEED_AFTER_COMPLETION },
                    onCompleted = { completions.send(Unit) }, nowMs = { clock },
                    webSeedHttp = TorrentHttp(mirror), webSeedRetryMs = 5)
                    .run(Channel(), selections = selections)
                }
                completions.receive()
                // Seeding, the swarm has nothing left to fetch: its summary shows no web seed
                // running, and the two pieces the seed delivered.
                clock = SWARM_SUMMARY_INTERVAL_MS
                var summary: String
                do {
                  summary = lines.receive()
                } while ("peers connected=" !in summary)
                assertTrue("webSeeds=0/1 (dropped=0), webSeedPieces=2," in summary, summary)
                // c.bin is wanted too: the seed starts again and fetches it.
                store.changeSelection(setOf(0, 3))
                CompletableDeferred<Unit>().also { selections.send(it) }.await()
                completions.receive()
                val logged = lines.drain()
                assertEquals(1, logged.count { "web seed(s) too" in it }, logged.toString())
                swarm.cancel()
              }
            } finally {
              network.close()
            }
          }
          assertContentEquals(parts[3].second,
            torrentFileSystem.read(output / "c.bin") { readByteArray() })
        }
      }
    }
  }

  @Test
  fun lostFile_isReportedAsAStallNamingIt() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        var clock = 0L
        val stall = MutableStateFlow<TorrentStall?>(null)
        // The only source lost c.bin, as archive.org items lose regenerated files.
        val lost = "http://mirror.test/pub/my%20set/c.bin"
        val mirror = MirrorEngine(mirrorFiles, missing = setOf(lost))
        withStore(multiFile("http://mirror.test/pub")) { store, _ ->
          val network = createTorrentNetwork()
          val peers = Channel<PeerEndpoint>().also { it.close() }
          try {
            coroutineScope {
              val swarm = launch {
                TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                  webSeedHttp = TorrentHttp(mirror), webSeedRetryMs = 5, nowMs = { clock },
                  stall = stall).run(peers)
              }
              while (mirror.requests.none { it.first == lost }) delay(20)
              delay(500)
              // Payload stopped 40 s ago: a stall, and why.
              clock = 40_000
              delay(500)
              clock = 80_000
              val reported = withTimeout(5_000) { stall.filterNotNull().first() }
              assertEquals(TorrentStallReason.MISSING_PIECES, reported.reason)
              assertTrue(reported.missingPieces > 0)
              assertTrue("3" in reported.missingFileIds)
              swarm.cancel()
            }
          } finally {
            network.close()
          }
        }
      }
    }
  }

  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun refusals_standUntilASeedStartedAgainAnswers() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val (stale, good) = staleSet
        val staleUrl = "http://mirror.test/pub/set/stale.bin"
        val goodUrl = "http://mirror.test/pub/set/good.bin"
        // The mirror lacks stale.bin, the one file wanted, until it has it; then it sends it a part
        // at a time.
        val lacking = MirrorEngine(mapOf(goodUrl to good))
        val fixed = Stepped(MirrorEngine(mapOf(staleUrl to stale, goodUrl to good)))
        val back = AtomicBoolean(false)
        val mirror = object : HttpEngine by lacking {
          override suspend fun download(
            url: String,
            range: LongRange?,
            headers: Map<String, String>,
            onData: suspend (ByteArray) -> Unit,
          ) = (if (back.load()) fixed else lacking).download(url, range, headers, onData)
        }
        withStore(staleSetMetadata(), selected = setOf(0)) { store, _ ->
          store.initialize()
          val scheduler = TorrentPieceScheduler(BooleanArray(store.pieceCount) { store.needed(it) },
            store.verifiedPieces(), store::pieceSize, TorrentBufferBudget(4 * 1024 * 1024))
          val pieces = SwarmWebSeedPieces(-1, "http://mirror.test/pub/", store, scheduler)
          val worker = WebSeedWorker("http://mirror.test/pub/", pieces, TorrentHttp(mirror),
            WebSeedStats(), "test", retryMs = 5)
          pieces.join()
          // Dropped once it found it cannot serve stale.bin, which it still says once stopped.
          worker.run()
          assertEquals(setOf(0), worker.refusals)
          assertTrue(worker.unproven)
          back.store(true)
          coroutineScope {
            val started = launch {
              pieces.join()
              worker.run()
            }
            // Started again, it asks for stale.bin and has yet to answer: what it said stands.
            fixed.asked.await()
            assertTrue(worker.unproven)
            assertEquals(setOf(0), worker.refusals)
            // Its first bytes answer: it serves stale.bin now.
            fixed.steps.send(Unit)
            while (fixed.delivered == 0) delay(10)
            assertFalse(worker.unproven)
            assertEquals(emptySet(), worker.refusals)
            while (!store.verifiedPieces()[0]) {
              fixed.steps.trySend(Unit)
              delay(10)
            }
            started.cancel()
          }
        }
      }
    }
  }

  @Test
  fun unservable_onlyLastingAnswersAndCorruptPieces() {
    val pieces = setOf(3, 4)
    assertEquals(pieces, unservable(WebSeedRequestFailure(pieces, FILE, KetchError.Http(404))))
    assertEquals(pieces, unservable(WebSeedRequestFailure(pieces, FILE, KetchError.Http(416))))
    assertEquals(pieces, unservable(WebSeedRequestFailure(pieces, FILE, KetchError.Unsupported())))
    assertEquals(setOf(7), unservable(CorruptWebSeedPiece(7)))
    // A busy server, a timeout or a dropped connection may pass.
    assertEquals(emptySet(), unservable(WebSeedRequestFailure(pieces, FILE, KetchError.Http(503))))
    assertEquals(emptySet(), unservable(WebSeedRequestFailure(pieces, FILE, KetchError.Network())))
    assertEquals(emptySet(), unservable(WebSeedRequestFailure(pieces, FILE,
      IOException("Web seed response ended early"))))
  }

  private companion object {
    const val PIECE = 16_384
    const val FILE = "http://mirror.test/pub/my%20set/c.bin"
  }
}

/** Holds each request of [engine] a moment, counting the most that were under way at once. */
@OptIn(ExperimentalAtomicApi::class)
private class OverlapEngine(private val engine: HttpEngine) : HttpEngine by engine {
  private val running = AtomicInt(0)
  private val peak = AtomicInt(0)
  val most: Int get() = peak.load()

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    val now = running.incrementAndFetch()
    while (true) {
      val seen = peak.load()
      if (now <= seen || peak.compareAndSet(seen, now)) break
    }
    try {
      delay(200)
      engine.download(url, range, headers, onData)
    } finally {
      running.decrementAndFetch()
    }
  }
}
