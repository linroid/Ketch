package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Kotlin engines uploading v2 and hybrid torrents to each other and to a v1-only peer, and
 * resolving magnets from one another: the info dictionary over `ut_metadata` and the piece layers
 * as hash proofs. Every engine that serves metadata uploads, as `ut_metadata` needs.
 */
class KotlinTorrentV2SwarmTest {
  // Pieces of 32 KiB: a spans pieces 0 and 1, b is piece 2, c is piece 3.
  private val files = listOf("a" to 40_000, "b" to 5, "c" to 20_000)
  private val payload = files.sumOf { it.second }.toLong()

  // For magnets: a's piece layer has five hashes in a proof of eight, so padding positions are
  // proved too; b's has three in four; c has none.
  private val magnetFiles = listOf("a" to 150_000, "b" to 70_000, "c" to 5)

  private object UnusedFileAccessor : FileAccessor {
    override suspend fun writeAt(offset: Long, data: ByteArray): Unit = error("Source owns I/O")
    override suspend fun flush(): Unit = error("Source owns I/O")
    override fun close() = Unit
    override suspend fun delete(): Unit = error("Source owns I/O")
    override suspend fun size(): Long = error("Source owns I/O")
    override suspend fun preallocate(size: Long): Unit = error("Source owns I/O")
  }

  private fun root(): Path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-v2-swarm-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}")
    .also { torrentFileSystem.createDirectories(it) }

  /**
   * Runs [body] against a seeding engine owning [fixture]'s payload, then removes the seed and
   * checks both its admission and its exchange budgets.
   */
  private fun seeding(
    fixture: TorrentV2Fixture,
    body: suspend (KotlinTorrentEngine, TorrentV2DownloadSession, Path) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = root()
        val seeder = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
        try {
          seeder.start()
          val output = root / "seed"
          val seed = seeder.addV2Task(TorrentV2TaskSpec("seed", fixture.document,
            output.toString(), checkpoint = fixture.preseed(output, "seed"),
            privacy = TorrentDiscoveryPrivacy.PUBLIC))
          seed.resume()
          assertEquals(TorrentSessionState.SEEDING, seed.state.first {
            it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
          }, seed.failure.value?.stackTraceToString())
          body(seeder, seed, root)
          seeder.removeTorrent(fixture.document.info.hash.hex)
          assertEquals(0, seeder.admittedSessionBytes)
        } finally {
          seeder.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
        assertEquals(0, seeder.admittedSessionBytes)
        assertEquals(0, seeder.allocatedExchangeBytes)
      }
    }
  }

  /** A second engine downloads [fixture] from [seeder], dialing it in [mode]. */
  private suspend fun leech(
    fixture: TorrentV2Fixture,
    seeder: KotlinTorrentEngine,
    root: Path,
    mode: PeerIdentityHandshake.Mode,
  ) {
    val leecher = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
    try {
      leecher.start()
      val output = root / "leech"
      val session = leecher.addV2Task(TorrentV2TaskSpec("leech", fixture.document,
        output.toString(), privacy = TorrentDiscoveryPrivacy.PUBLIC, discover = { peers ->
          peers.send(PeerEndpoint("127.0.0.1", seeder.listenPort))
          awaitCancellation()
        }, discoverMode = mode))
      session.resume()
      assertEquals(TorrentSessionState.FINISHED, session.state.first {
        it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
      }, session.failure.value?.stackTraceToString())
      for ((index, name) in fixture.names.withIndex()) {
        assertContentEquals(fixture.payloads[index],
          torrentFileSystem.read(output / name) { readByteArray() })
      }
      leecher.removeTorrent(fixture.document.info.hash.hex)
      assertEquals(0, leecher.admittedSessionBytes)
    } finally {
      leecher.stop()
    }
    assertEquals(0, leecher.allocatedExchangeBytes)
  }

  @Test
  fun v2SeederUploadsToSecondEngineThroughIncomingRoute() {
    val fixture = TorrentV2Fixture.build(files)
    seeding(fixture) { seeder, seed, root ->
      // The leecher dials the seed, whose engine routes the v2 tag to the seeding owner.
      leech(fixture, seeder, root, PeerIdentityHandshake.Mode.V2)
      assertTrue(seed.uploadedBytes() >= payload, "${seed.uploadedBytes()}")
      assertEquals(TorrentSessionState.SEEDING, seed.state.value)
    }
  }

  @Test
  fun hybridSeederUploadsOverUpgradedV1Tag() {
    val fixture = TorrentV2Fixture.build(files, hybrid = true)
    seeding(fixture) { seeder, seed, root ->
      // The leecher knows the seed from the v1 swarm, dials its v1 tag and offers the upgrade.
      leech(fixture, seeder, root, PeerIdentityHandshake.Mode.V1)
      assertTrue(seed.uploadedBytes() >= payload, "${seed.uploadedBytes()}")
      // The seed took the upgrade: the leecher was its only peer, and nothing went as v1 blocks.
      assertEquals(0L, seed.v1UploadedBytes(), "${seed.v1UploadedBytes()}")
    }
  }

  @Test
  fun hybridSeederUploadsToScriptedV1OnlyLeecher() {
    val fixture = TorrentV2Fixture.build(files, hybrid = true)
    val legacy = TorrentMetadata.fromBencode(fixture.metainfo, allowHybrid = true)
    val pieceCount = legacy.pieceHashes.size / 20
    seeding(fixture) { seeder, seed, _ ->
      val network = createTorrentNetwork()
      try {
        val connection = network.connect(PeerEndpoint("127.0.0.1", seeder.listenPort))
        try {
          // A peer of the v1 swarm only: the v1 tag without the upgrade bit.
          connection.write(PeerWire.encodeHandshake(PeerHandshake(legacy.infoHash,
            torrentRandomBytes(20), extensions = false, dht = false)))
          PeerWire.decodeHandshake(connection.readExactly(68), legacy.infoHash)
          val wire = PeerWire(connection, legacy)
          assertContentEquals(pieceBitfield(BooleanArray(pieceCount) { true }),
            assertIs<PeerMessage.Bitfield>(wire.read()).bytes)
          wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
          while (wire.read() != PeerMessage.Control(PeerMessage.Signal.UNCHOKE)) Unit
          // Canonical v1 blocks of every piece, padding included, hashed as v1 peers do.
          for (index in 0 until pieceCount) {
            val size = minOf(legacy.pieceLength, legacy.totalBytes - index * legacy.pieceLength)
              .toInt()
            val piece = ByteArray(size)
            for (begin in 0 until size step 16_384) {
              wire.send(PeerMessage.Request(index, begin, minOf(16_384, size - begin)))
              val block = assertIs<PeerMessage.Piece>(nextPiece(wire))
              assertEquals(index to begin, block.index to block.begin)
              block.bytes.copyInto(piece, begin)
            }
            assertContentEquals(legacy.pieceHashes.copyOfRange(index * 20, index * 20 + 20),
              sha1Digest(piece), "v1 piece $index")
            assertContentEquals(fixture.v1Piece(index), piece)
          }
          while (seed.uploadedBytes() < legacy.totalBytes) delay(10)
          assertEquals(legacy.totalBytes, seed.uploadedBytes())
          // All of it went over the v1 route.
          assertEquals(legacy.totalBytes, seed.v1UploadedBytes())
        } finally { connection.close() }
      } finally { network.close() }
    }
  }

  @Test
  fun btmhMagnetDownloadFetchesMetadataAndProofsFromKetchSeeder() {
    val fixture = TorrentV2Fixture.build(magnetFiles)
    seeding(fixture) { seeder, seed, root ->
      // Only the v2 exact topic and the seed: metadata and piece layers both come from it.
      val uri = MagnetUri(fixture.document.identity,
        explicitPeers = listOf("127.0.0.1:${seeder.listenPort}")).toUri()
      magnet(uri, fixture, root)
      assertTrue(seed.uploadedBytes() >= magnetFiles.sumOf { it.second },
        "${seed.uploadedBytes()}")
    }
  }

  @Test
  fun btihOnlyMagnetResolvesHybridFromKetchSeeder() {
    val fixture = TorrentV2Fixture.build(magnetFiles, hybrid = true)
    seeding(fixture) { seeder, seed, root ->
      // Only the v1 exact topic: the seed's v1 swarm hands over the hybrid info dictionary,
      // which names the v2 identity the piece layers and the download use.
      val uri = MagnetUri(checkNotNull(fixture.document.identity.v1),
        explicitPeers = listOf("127.0.0.1:${seeder.listenPort}")).toUri()
      magnet(uri, fixture, root)
      assertTrue(seed.uploadedBytes() >= magnetFiles.sumOf { it.second },
        "${seed.uploadedBytes()}")
    }
  }

  /** A source with an engine of its own resolves [uri] and downloads it in full. */
  private suspend fun magnet(uri: String, fixture: TorrentV2Fixture, root: Path) {
    var engine: KotlinTorrentEngine? = null
    val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false)).also {
      it.engineFactory = { config ->
        KotlinTorrentEngine(config).also { created -> engine = created }
      }
    }
    try {
      val resolved = source.resolve(uri, emptyMap())
      assertEquals(fixture.document.info.hash.hex, resolved.metadata["infoHash"])
      assertEquals("v2", resolved.metadata["format"])
      assertEquals(fixture.names.size, resolved.files.size)
      val output = root / "magnet"
      source.download(context("magnet", resolved, output.toString()))
      for ((index, name) in fixture.names.withIndex()) {
        assertContentEquals(fixture.payloads[index],
          torrentFileSystem.read(output / name) { readByteArray() })
      }
    } finally {
      source.close()
      engine?.stop()
    }
    val leecher = checkNotNull(engine)
    assertEquals(0, leecher.admittedSessionBytes)
    assertEquals(0, leecher.allocatedExchangeBytes)
  }

  private fun context(taskId: String, resolved: ResolvedSource, output: String) = DownloadContext(
    taskId = taskId, url = resolved.url, request = DownloadRequest(resolved.url),
    fileAccessor = UnusedFileAccessor, segments = MutableStateFlow(emptyList()),
    onProgress = { _, _ -> }, throttle = {}, headers = emptyMap(), preResolved = resolved,
    outputPath = output,
  )

  private suspend fun nextPiece(wire: PeerWire): PeerMessage {
    while (true) {
      val message = wire.read()
      if (message is PeerMessage.Piece) return message
    }
  }
}
