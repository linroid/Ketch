package com.linroid.ketch.torrent

import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.core.engine.HttpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

  /** Runs a swarm without peers: discovery is closed from the start. */
  private suspend fun runSwarm(
    store: TorrentPieceStore,
    vararg engines: HttpEngine,
    connections: RecordingConnections = RecordingConnections(),
    buffers: Int = 4 * 1024 * 1024,
  ) {
    val network = createTorrentNetwork()
    val peers = Channel<PeerEndpoint>().also { it.close() }
    val budget = TorrentBufferBudget(buffers)
    try {
      // One engine per seed: each worker gets the one its URL's host names.
      TorrentSwarm(store, network, budget, reporter = connections,
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
        // Without a trailing slash, the URL still names the folder.
        withStore(multiFile("http://mirror.test/pub")) { store, output ->
          runSwarm(store, mirror)
          assertTrue(store.completed())
          for ((path, bytes) in parts) {
            assertContentEquals(bytes, torrentFileSystem.read(output / path) { readByteArray() })
          }
          // The empty file is never asked for.
          assertTrue(mirror.requests.none { it.first.endsWith("/empty") })
          // One run over every piece: a range request per file, each file whole.
          assertEquals(listOf(
            "http://mirror.test/pub/my%20set/a%20b.bin" to 0L..19_999L,
            "http://mirror.test/pub/my%20set/dir/%C3%BCn%C3%AF.bin" to 0L..29_999L,
            "http://mirror.test/pub/my%20set/c.bin" to 0L..39_999L,
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
          runSwarm(store, mirror)
          assertTrue(store.completed())
          assertContentEquals(payload, torrentFileSystem.read(output) { readByteArray() })
          // Pieces 0 to 4 were kept; the next run starts at the corrupt piece.
          assertEquals(listOf(0L..<16L * PIECE, 5L * PIECE..<16L * PIECE),
            mirror.requests.map { it.second })
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
          runSwarm(store, mirror)
          assertTrue(store.completed())
          assertContentEquals(payload, torrentFileSystem.read(output) { readByteArray() })
          assertEquals(listOf(0L..<8L * PIECE, 3L * PIECE..<8L * PIECE),
            mirror.requests.map { it.second })
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
  fun missingFile_404_dropsTheSeedAndTheSwarmFails() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val payload = ByteArray(2 * PIECE) { it.toByte() }
        val metadata = singleFile(payload, "http://mirror.test/data")
        val mirror = MirrorEngine(emptyMap(), missing = setOf("http://mirror.test/data"))
        withStore(metadata) { store, _ ->
          assertFailsWith<IllegalStateException> { runSwarm(store, mirror) }
          assertEquals(WebSeedWorker.MAX_FAILURES, mirror.requests.size)
          assertFalse(store.completed())
        }
      }
    }
  }

  private companion object {
    const val PIECE = 16_384
  }
}
