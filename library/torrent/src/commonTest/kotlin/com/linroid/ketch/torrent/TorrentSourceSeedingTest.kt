package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The source keeps a finished torrent seeding on a lent slot, takes the slot back once seeding
 * stops by itself, and applies upload settings without starting an engine.
 */
class TorrentSourceSeedingTest {
  private object UnusedFileAccessor : FileAccessor {
    override suspend fun writeAt(offset: Long, data: ByteArray): Unit = error("Source owns I/O")
    override suspend fun flush(): Unit = error("Source owns I/O")
    override fun close() = Unit
    override suspend fun delete(): Unit = error("Source owns I/O")
    override suspend fun size(): Long = error("Source owns I/O")
    override suspend fun preallocate(size: Long): Unit = error("Source owns I/O")
  }

  private fun context(taskId: String, resolved: ResolvedSource, output: String) = DownloadContext(
    taskId = taskId, url = resolved.url, request = DownloadRequest(resolved.url),
    fileAccessor = UnusedFileAccessor, segments = MutableStateFlow(emptyList()),
    onProgress = { _, _ -> }, throttle = {}, headers = emptyMap(), preResolved = resolved,
    outputPath = output,
  )

  private fun v1Metainfo(name: String) = Bencode.encode(mapOf("info" to mapOf(
    "name" to name, "length" to 4L, "piece length" to 4L,
    "pieces" to sha1Digest(byteArrayOf(1, 2, 3, 4)))))

  /** Waits in real time, as the source's own work runs on its own dispatcher. */
  private suspend fun eventually(condition: suspend () -> Boolean) {
    while (!condition()) delay(10)
  }

  @Test
  fun uploadSettersDoNotStartTheEngine() = runTest {
    var created = 0
    val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false)).also {
      it.engineFactory = { created++; FakeTorrentEngine() }
    }
    try {
      assertEquals(TorrentUploadPolicy.DISABLED, source.currentUploadPolicy)
      source.setUploadPolicy(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
      source.setUploadRateLimit(1_000)
      assertEquals(0, created)
      assertEquals(TorrentUploadPolicy.SEED_AFTER_COMPLETION, source.currentUploadPolicy)
    } finally { source.close() }
  }

  @Test
  fun uploadPolicyReachesTheNextEngine() = runTest {
    val engine = FakeTorrentEngine()
    var config: TorrentConfig? = null
    val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
      uploadPolicy = TorrentUploadPolicy.DISABLED, uploadRateLimit = 10)).also {
      it.engineFactory = { created -> config = created; engine }
    }
    try {
      source.setUploadPolicy(TorrentUploadPolicy.WHILE_DOWNLOADING)
      source.setUploadRateLimit(2_000)
      // A magnet lookup is the first thing that needs the engine.
      val metadata = TorrentMetadata.fromBencode(v1Metainfo("payload"))
      engine.fetchMetadataResult = metadata
      source.resolve(MagnetUri(metadata.infoHash).toUri(), emptyMap())
      assertEquals(TorrentUploadPolicy.WHILE_DOWNLOADING, assertNotNull(config).uploadPolicy)
      assertEquals(2_000L, assertNotNull(config).uploadRateLimit)
      // The running engine hears later changes at once.
      source.setUploadPolicy(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
      source.setUploadRateLimit(0)
      assertEquals(TorrentUploadPolicy.SEED_AFTER_COMPLETION, engine.uploadPolicy)
      assertEquals(0L, engine.uploadRateLimit)
    } finally { source.close() }
  }

  @Test
  fun seedWatcherReleasesSlotWhenSeederStops() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          maxActiveTorrents = 1)).also { it.engineFactory = { engine } }
        try {
          val seeding = source.resolveMetainfo(v1Metainfo("seeding"))
          val next = source.resolveMetainfo(v1Metainfo("next"))
          val seedingHash = seeding.metadata.getValue("infoHash")
          val nextHash = next.metadata.getValue("infoHash")
          val seeder = FakeTorrentSession(seedingHash)
          engine.addTorrentResult = seeder
          val seeds = launch {
            eventually { seeder.resumed }
            seeder.seed()
          }
          // The download returns while its session keeps seeding on the lent slot.
          source.download(context("seeding", seeding, "unused-seeding"))
          seeds.join()
          assertTrue(engine.removedTorrents.isEmpty())
          // Seeding ends by itself, such as when uploads are switched off.
          seeder.finish()
          eventually { engine.removedTorrents.isNotEmpty() }
          assertEquals(listOf(seedingHash to false), engine.removedTorrents)
          // The slot is free: the next torrent starts without stopping anything else.
          val downloader = FakeTorrentSession(nextHash)
          engine.addTorrentResult = downloader
          val finishes = launch {
            eventually { downloader.resumed }
            downloader.finish()
          }
          source.download(context("next", next, "unused-next"))
          finishes.join()
          assertEquals(listOf(seedingHash to false, nextHash to false), engine.removedTorrents)
        } finally { source.close() }
      }
    }
  }

  @Test
  fun sameV1TorrentTakesOverItsSeeder() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        // A free slot: nothing has to be evicted for the second task to start.
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          maxActiveTorrents = 2)).also { it.engineFactory = { engine } }
        try {
          val resolved = source.resolveMetainfo(v1Metainfo("again"))
          val hash = resolved.metadata.getValue("infoHash")
          val seeder = FakeTorrentSession(hash)
          engine.addTorrentResult = seeder
          val seeds = launch {
            eventually { seeder.resumed }
            seeder.seed()
          }
          source.download(context("first", resolved, "unused-first"))
          seeds.join()
          assertTrue(engine.removedTorrents.isEmpty())
          // The same torrent again, as another task: the seeder gives way instead of failing it.
          val again = FakeTorrentSession(hash)
          engine.addTorrentResult = again
          val finishes = launch {
            eventually { again.resumed }
            again.finish()
          }
          source.download(context("second", resolved, "unused-second"))
          finishes.join()
          assertEquals(listOf(hash to false, hash to false), engine.removedTorrents)
          // The first task finds nothing left to stop.
          source.release("first", null)
          assertEquals(2, engine.removedTorrents.size)
        } finally { source.close() }
      }
    }
  }

  // Pieces of 32 KiB: a spans pieces 0 and 1, b is piece 2, c is piece 3.
  private val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5, "c" to 20_000))

  /**
   * Runs [body] with a source whose engine seeds a preseeded v2 task, and checks that the
   * engine returns everything once the source closes.
   */
  private fun v2Seeding(
    maxActiveTorrents: Int = 5,
    body: suspend CoroutineScope.(TorrentDownloadSource, () -> KotlinTorrentEngine, Path) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-source-seed-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}")
          .also { torrentFileSystem.createDirectories(it) }
        var engine: KotlinTorrentEngine? = null
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          maxActiveTorrents = maxActiveTorrents,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION)).also {
          it.engineFactory = { config ->
            KotlinTorrentEngine(config, listenHost = "127.0.0.1").also { created ->
              engine = created
            }
          }
        }
        try {
          body(source, { checkNotNull(engine) }, root)
        } finally {
          source.close()
          engine?.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
        engine?.let {
          assertEquals(0, it.admittedSessionBytes)
          assertEquals(0, it.allocatedExchangeBytes)
        }
      }
    }
  }

  /** Resumes [taskId] from a checkpoint of [fixture]'s payload already on disk at [output]. */
  private suspend fun TorrentDownloadSource.resumeSeeded(
    taskId: String,
    fixture: TorrentV2Fixture,
    output: Path,
  ) {
    val checkpoint = fixture.preseed(output, taskId)
    val resolved = resolveMetainfo(fixture.metainfo)
    val state = TorrentResumeState(fixture.document.info.hash.hex, resolved.totalBytes,
      encodeBase64(checkpoint.encode()), resolved.files.map { it.id }.toSet(), output.toString(),
      encodeBase64(fixture.metainfo), version = 3, privacy = TorrentDiscoveryPrivacy.PUBLIC)
    resume(context(taskId, resolved, output.toString()),
      SourceResumeState(TorrentDownloadSource.TYPE, Json.encodeToString(state)))
  }

  @Test
  fun policyOffFinishesV2SeederAndSourceReleasesSlot() =
    v2Seeding(maxActiveTorrents = 1) { source, engine, root ->
      // The task completes at once and keeps seeding on the only slot.
      source.resumeSeeded("seeder", fixture, root / "seeding")
      assertFalse(engine().hasFreeSlot())
      source.setUploadPolicy(TorrentUploadPolicy.DISABLED)
      // The owner finishes and the source removes it on its own, freeing the engine slot.
      eventually { engine().hasFreeSlot() && engine().admittedSessionBytes == 0 }
      assertEquals(emptySet(), engine().incomingTags())
      // And its own slot: another torrent starts and finishes, nothing waits.
      val other = TorrentV2Fixture.build(listOf("d" to 30_000), seed = 2)
      source.resumeSeeded("other", other, root / "other")
      eventually { engine().hasFreeSlot() }
      assertContentEquals(fixture.payloads[0],
        torrentFileSystem.read(root / "seeding" / "a") { readByteArray() })
    }

  @Test
  fun secondDownloadTakesV2SeederSlot() =
    v2Seeding(maxActiveTorrents = 1) { source, engine, root ->
      // The task completes at once and keeps seeding on the only slot.
      source.resumeSeeded("seeder", fixture, root / "seeding")
      assertFalse(engine().hasFreeSlot())
      assertEquals(TorrentRouteTable.tagsOf(fixture.document.identity).toSet(),
        engine().incomingTags())
      // Another torrent needs that slot: it takes it from the seeder instead of waiting.
      val other = TorrentV2Fixture.build(listOf("d" to 30_000), seed = 2)
      source.resumeSeeded("other", other, root / "other")
      // The seeding owner left the engine before the new one joined; its files stay.
      assertEquals(TorrentRouteTable.tagsOf(other.document.identity).toSet(),
        engine().incomingTags())
      assertContentEquals(fixture.payloads[0],
        torrentFileSystem.read(root / "seeding" / "a") { readByteArray() })
      // Removing the stopped task finds nothing left to stop and frees no slot twice.
      source.release("seeder", null)
      assertFalse(engine().hasFreeSlot())
      // The new task seeds on the slot until it is removed too.
      source.release("other", null)
      assertTrue(engine().hasFreeSlot())
      assertEquals(emptySet(), engine().incomingTags())
      assertEquals(0, engine().admittedSessionBytes)
    }

  @Test
  fun sameV2TorrentTakesOverItsSeeder() =
    v2Seeding(maxActiveTorrents = 2) { source, engine, root ->
      source.resumeSeeded("seeder", fixture, root / "seeding")
      assertTrue(engine().hasFreeSlot())
      // The same torrent again, into another folder: the seeder leaves the swarm to it.
      source.resumeSeeded("again", fixture, root / "again")
      assertEquals(TorrentRouteTable.tagsOf(fixture.document.identity).toSet(),
        engine().incomingTags())
      assertContentEquals(fixture.payloads[0],
        torrentFileSystem.read(root / "again" / "a") { readByteArray() })
      // The first task's seeder is gone, and the slot it held went to the new task.
      source.release("seeder", null)
      assertTrue(engine().hasFreeSlot())
      source.setTaskUploadRateLimit("again", 0)
      assertFailsWith<IllegalStateException> { source.setTaskUploadRateLimit("seeder", 0) }
      source.release("again", null)
      assertEquals(emptySet(), engine().incomingTags())
      assertEquals(0, engine().admittedSessionBytes)
    }

  @Test
  fun taskUploadLimitAppliesToV2Seeder() = v2Seeding { source, engine, root ->
    source.resumeSeeded("seeder", fixture, root / "seeding")
    // One byte a second: the bucket's first block is all a peer gets for now.
    source.setTaskUploadRateLimit("seeder", 1)
    val network = createTorrentNetwork()
    try {
      val connection = network.connect(PeerEndpoint("127.0.0.1", engine().listenPort))
      PeerIdentityHandshake(fixture.document.identity).initiate(connection,
        PeerIdentityHandshake.Mode.V2, torrentRandomBytes(20).toByteString(),
        TorrentBufferBudget(65_536))
      val wire = PeerWire(connection, pieceCount = fixture.layout.pieceCount.toInt())
      suspend fun next(match: (PeerMessage) -> Boolean): PeerMessage {
        while (true) {
          val message = wire.read()
          if (match(message)) return message
        }
      }
      assertIs<PeerMessage.Bitfield>(wire.read())
      wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
      next { it == PeerMessage.Control(PeerMessage.Signal.UNCHOKE) }
      wire.send(PeerMessage.Request(0, 0, 16_384))
      assertEquals(0, assertIs<PeerMessage.Piece>(next { it is PeerMessage.Piece }).begin)
      wire.send(PeerMessage.Request(0, 16_384, 16_384))
      val asked = TimeSource.Monotonic.markNow()
      val arrived = async {
        next { it is PeerMessage.Piece }
        asked.elapsedNow()
      }
      delay(500)
      assertFalse(arrived.isCompleted)
      // Lifting the task's limit lets the held block go.
      source.setTaskUploadRateLimit("seeder", 0)
      assertTrue(arrived.await() >= 500.milliseconds)
      connection.close()
    } finally { network.close() }
    // The download already returned; removing the task stops the seeder.
    source.release("seeder", null)
    assertTrue(engine().hasFreeSlot())
    assertEquals(0, engine().admittedSessionBytes)
    assertFailsWith<IllegalStateException> { source.setTaskUploadRateLimit("seeder", 0) }
  }
}
