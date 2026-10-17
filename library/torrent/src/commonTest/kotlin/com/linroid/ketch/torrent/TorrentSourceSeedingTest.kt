package com.linroid.ketch.torrent

import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.ConnectionReporter
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.decodeState
import com.linroid.ketch.core.engine.resolve
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.torrent.api.TorrentCapability
import com.linroid.ketch.torrent.control.SeedingOutcome
import com.linroid.ketch.torrent.control.SeedingTask
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.engine.TorrentRouteTable
import com.linroid.ketch.torrent.magnet.MagnetUri
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerHandshake
import com.linroid.ketch.torrent.peer.PeerIdentityHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerTraffic
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import kotlin.test.assertNull
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

  private fun context(
    taskId: String,
    resolved: ResolvedSource,
    output: String,
    connections: ConnectionReporter = ConnectionReporter.None,
  ) = DownloadContext(
    taskId = taskId, url = resolved.url, request = DownloadRequest(resolved.url),
    fileAccessor = UnusedFileAccessor, segments = MutableStateFlow(emptyList()),
    onProgress = { _, _ -> }, throttle = {}, headers = emptyMap(), preResolved = resolved,
    outputPath = output, connections = connections,
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
      source.setUploadsPaused(true)
      source.setUploadsPaused(false)
      assertEquals(0, created)
      assertEquals(TorrentUploadPolicy.SEED_AFTER_COMPLETION, source.currentUploadPolicy)
    } finally { source.close() }
  }

  /** Starts [source]'s engine, [engine], through a magnet lookup, the first thing that needs it. */
  private suspend fun startEngine(source: TorrentDownloadSource, engine: FakeTorrentEngine) {
    val metadata = TorrentMetadata.fromBencode(v1Metainfo("payload"))
    engine.fetchMetadataResult = metadata
    source.resolve(MagnetUri(metadata.infoHash).toUri(), emptyMap())
  }

  @Test
  fun setUploadsPaused_runningEngine_runsDisabledUntilResumed() = runTest {
    val engine = FakeTorrentEngine()
    val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
      uploadPolicy = TorrentUploadPolicy.WHILE_DOWNLOADING)).also { it.engineFactory = { engine } }
    try {
      startEngine(source, engine)
      source.setUploadsPaused(true)
      assertEquals(TorrentUploadPolicy.DISABLED, engine.uploadPolicy)
      // The configured policy stays, for when uploads resume.
      assertEquals(TorrentUploadPolicy.WHILE_DOWNLOADING, source.currentUploadPolicy)
      source.setUploadsPaused(false)
      assertEquals(TorrentUploadPolicy.WHILE_DOWNLOADING, engine.uploadPolicy)
    } finally { source.close() }
  }

  @Test
  fun setUploadPolicy_whilePaused_keepsTheEngineDisabled() = runTest {
    val engine = FakeTorrentEngine()
    val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
      .also { it.engineFactory = { engine } }
    try {
      startEngine(source, engine)
      source.setUploadsPaused(true)
      source.setUploadPolicy(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
      assertEquals(TorrentUploadPolicy.DISABLED, engine.uploadPolicy)
      assertEquals(TorrentUploadPolicy.SEED_AFTER_COMPLETION, source.currentUploadPolicy)
      // Seeding is still offered: tasks can be meant to seed, and seed once uploads resume.
      assertTrue(TorrentCapability.SEEDING.wireName in source.torrentCapabilities)
      assertFalse(source.restoresSeeding)
      assertEquals(SeedingOutcome.UPLOADS_PAUSED, source.seedingAvailability("task"))
      source.setUploadsPaused(false)
      assertEquals(TorrentUploadPolicy.SEED_AFTER_COMPLETION, engine.uploadPolicy)
      assertTrue(source.restoresSeeding)
      assertNull(source.seedingAvailability("task"))
    } finally { source.close() }
  }

  @Test
  fun constructedPaused_firstEngine_runsDisabled() = runTest {
    val engine = FakeTorrentEngine()
    var config: TorrentConfig? = null
    val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
      uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION), uploadsPaused = true).also {
      it.engineFactory = { created -> config = created; engine }
    }
    try {
      assertFalse(source.restoresSeeding)
      startEngine(source, engine)
      assertEquals(TorrentUploadPolicy.DISABLED, assertNotNull(config).uploadPolicy)
      source.setUploadsPaused(false)
      assertEquals(TorrentUploadPolicy.SEED_AFTER_COMPLETION, engine.uploadPolicy)
    } finally { source.close() }
  }

  @Test
  fun sharingResumes_sameValueSetters_bumpOnlyWhenSeedingComesBack() = runTest {
    val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
      uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
      .also { it.engineFactory = { FakeTorrentEngine() } }
    try {
      source.setUploadsPaused(false)
      source.setUploadPolicy(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
      assertEquals(0L, source.sharingResumes.value)
      source.setUploadsPaused(true)
      source.setUploadsPaused(true)
      source.setUploadPolicy(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
      assertEquals(0L, source.sharingResumes.value)
      // Resuming under Seed brings seeding back once, however often it is repeated.
      source.setUploadsPaused(false)
      source.setUploadsPaused(false)
      assertEquals(1L, source.sharingResumes.value)
      // So does switching to Seed, but not to another policy.
      source.setUploadPolicy(TorrentUploadPolicy.WHILE_DOWNLOADING)
      source.setUploadsPaused(true)
      source.setUploadsPaused(false)
      assertEquals(1L, source.sharingResumes.value)
      source.setUploadPolicy(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
      source.setUploadPolicy(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
      assertEquals(2L, source.sharingResumes.value)
    } finally { source.close() }
  }

  /**
   * Downloads a one-piece torrent as [taskId] on [source], whose fake engine finishes the
   * session as soon as it runs.
   */
  private suspend fun CoroutineScope.downloadFinished(
    source: TorrentDownloadSource,
    engine: FakeTorrentEngine,
    taskId: String,
  ) {
    val resolved = source.resolveMetainfo(v1Metainfo(taskId))
    val session = FakeTorrentSession(resolved.metadata.getValue("infoHash"))
    engine.addTorrentResult = session
    val finishes = launch {
      eventually { session.resumed }
      session.finish()
    }
    source.download(context(taskId, resolved, "unused-$taskId"), null)
    finishes.join()
  }

  @Test
  fun download_completesWhilePausedUnderSeed_defersSharingOnce() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION), uploadsPaused = true)
          .also { it.engineFactory = { engine } }
        try {
          downloadFinished(source, engine, "paused")
          assertTrue(source.takeDeferredSharing("paused"))
          assertFalse(source.takeDeferredSharing("paused"))
          // Under another policy a finished session is all the task asked for.
          source.setUploadPolicy(TorrentUploadPolicy.WHILE_DOWNLOADING)
          downloadFinished(source, engine, "other")
          assertFalse(source.takeDeferredSharing("other"))
          source.assertNoLeaks()
        } finally { source.close() }
      }
    }
  }

  @Test
  fun download_lentToSeed_defersSharingSoAPauseCannotDropIt() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
          .also { it.engineFactory = { engine } }
        try {
          val resolved = source.resolveMetainfo(v1Metainfo("seeder"))
          val session = FakeTorrentSession(resolved.metadata.getValue("infoHash"))
          engine.addTorrentResult = session
          val seeds = launch {
            eventually { session.resumed }
            session.seed()
          }
          source.download(context("seeder", resolved, "unused-seeder"), null)
          seeds.join()
          assertEquals(setOf("seeder"), source.seedingTaskIds.value)
          // Uploads pause before Ketch publishes the completion: the seeder stops, and the
          // completion still carries the intent to seed.
          source.setUploadsPaused(true)
          session.finish()
          eventually { source.seedingTaskIds.value.isEmpty() }
          assertTrue(source.takeDeferredSharing("seeder"))
          assertFalse(source.takeDeferredSharing("seeder"))
          source.assertNoLeaks()
        } finally { source.close() }
      }
    }
  }

  /** Each state [source]'s seeding sessions save, decoded, with the task's ID. */
  private fun savedStates(
    source: TorrentDownloadSource,
  ): Channel<Pair<String, TorrentSourceState>> {
    val saved = Channel<Pair<String, TorrentSourceState>>(Channel.UNLIMITED)
    source.saveSessionStates { taskId, state -> saved.send(taskId to source.decodeState(state)) }
    return saved
  }

  @Test
  fun lentSeeder_savesItsCheckpointWhileItSeedsAndAsItStops() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION)).also {
          it.engineFactory = { engine }
          it.seedingSaveIntervalMs = 20
        }
        val saved = savedStates(source)
        try {
          val resolved = source.resolveMetainfo(v1Metainfo("seeder"))
          val hash = resolved.metadata.getValue("infoHash")
          val session = FakeTorrentSession(hash).apply { savedResumeData = byteArrayOf(1) }
          engine.addTorrentResult = session
          val seeds = launch {
            eventually { session.resumed }
            session.seed()
          }
          source.download(context("seeder", resolved, "unused-seeder"), null)
          seeds.join()
          // The run saved its own state; from here on only the seeder saves what it uploads.
          session.savedResumeData = byteArrayOf(2)
          while (true) {
            val (taskId, state) = saved.receive()
            assertEquals("seeder", taskId)
            assertEquals(hash, state.infoHash)
            if (state.resumeData == encodeBase64(byteArrayOf(2))) break
          }
          source.stopSeeding("seeder")
          // The last one carries what it saved as it stopped.
          var last: TorrentSourceState? = null
          while (true) last = saved.tryReceive().getOrNull()?.second ?: break
          assertEquals(encodeBase64(byteArrayOf(2)), assertNotNull(last).resumeData)
          assertTrue(session.paused)
          // Stopped, it saves nothing more.
          session.savedResumeData = byteArrayOf(3)
          delay(100)
          val later = generateSequence { saved.tryReceive().getOrNull() }.toList()
          assertTrue(later.none { it.second.resumeData == encodeBase64(byteArrayOf(3)) })
          source.assertNoLeaks()
        } finally { source.close() }
      }
    }
  }

  @Test
  fun stop_savesTheSeedersCheckpointBeforeTheEngineStops() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
          .also { it.engineFactory = { engine } }
        // Whether the engine had stopped by each save.
        val saves = Channel<Pair<TorrentSourceState, Boolean>>(Channel.UNLIMITED)
        source.saveSessionStates { _, state ->
          saves.send(source.decodeState(state) to engine.stopped)
        }
        val metainfo = v1Metainfo("data")
        val hash = TorrentMetadata.fromBencode(metainfo).infoHash.hex
        val ids = TorrentFileTable.parse(metainfo, TorrentConfig()).ids
        val state = TorrentSourceState(hash, 4, "", ids, "unused-data", encodeBase64(metainfo),
          version = 2, privacy = TorrentDiscoveryPrivacy.PUBLIC)
        val session = FakeTorrentSession(hash).apply { savedResumeData = byteArrayOf(7) }
        engine.addTorrentResult = session
        val seeds = launch {
          eventually { session.resumed }
          session.seed()
        }
        try {
          assertEquals(SeedingOutcome.SEEDING, source.startSeeding(SeedingTask("seeder",
            "http://fixture/data.torrent", state.saved(), null, "unused-data", ids)))
          seeds.join()
          source.stop()
          val (last, stopped) = assertNotNull(saves.tryReceive().getOrNull())
          // The state it was restored from, with the checkpoint it saved last.
          assertEquals(state.copy(resumeData = encodeBase64(byteArrayOf(7))), last)
          assertFalse(stopped)
          assertTrue(engine.stopped)
        } finally { source.close() }
      }
    }
  }

  @Test
  fun startSeeding_pausedDuringRecheck_returnsUploadsPaused() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
          .also { it.engineFactory = { engine } }
        try {
          val metainfo = v1Metainfo("data")
          val hash = TorrentMetadata.fromBencode(metainfo).infoHash.hex
          val ids = TorrentFileTable.parse(metainfo, TorrentConfig()).ids
          val state = TorrentSourceState(hash, 4, "", ids, "unused-data", encodeBase64(metainfo),
            version = 2, privacy = TorrentDiscoveryPrivacy.PUBLIC)
          val session = FakeTorrentSession(hash)
          engine.addTorrentResult = session
          // Uploads pause while the seed-only session checks its files, which finishes it.
          val pauses = launch {
            eventually { session.resumed }
            source.setUploadsPaused(true)
            session.finish()
          }
          val outcome = source.startSeeding(SeedingTask("seeder", "http://fixture/data.torrent",
            state.saved(), null, "unused-data", ids))
          pauses.join()
          assertEquals(SeedingOutcome.UPLOADS_PAUSED, outcome)
          assertEquals(listOf(hash to false), engine.removedTorrents)
          assertEquals(emptySet(), source.seedingTaskIds.value)
          source.assertNoLeaks()
        } finally { source.close() }
      }
    }
  }

  @Test
  fun restoreSeedingOff_neverResumesNorDefers() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION), restoreSeeding = false,
          uploadsPaused = true).also { it.engineFactory = { engine } }
        try {
          downloadFinished(source, engine, "paused")
          assertFalse(source.takeDeferredSharing("paused"))
          source.setUploadsPaused(false)
          source.setUploadPolicy(TorrentUploadPolicy.DISABLED)
          source.setUploadPolicy(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
          assertEquals(0L, source.sharingResumes.value)
          assertEquals(TorrentUploadPolicy.SEED_AFTER_COMPLETION, engine.uploadPolicy)
          source.assertNoLeaks()
        } finally { source.close() }
      }
    }
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
          source.download(context("seeding", seeding, "unused-seeding"), null)
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
          source.download(context("next", next, "unused-next"), null)
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
          source.download(context("first", resolved, "unused-first"), null)
          seeds.join()
          assertTrue(engine.removedTorrents.isEmpty())
          // The same torrent again, as another task: the seeder gives way instead of failing it.
          val again = FakeTorrentSession(hash)
          engine.addTorrentResult = again
          val finishes = launch {
            eventually { again.resumed }
            again.finish()
          }
          source.download(context("second", resolved, "unused-second"), null)
          finishes.join()
          assertEquals(listOf(hash to false, hash to false), engine.removedTorrents)
          // The first task finds nothing left to stop.
          source.release("first")
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
    connections: ConnectionReporter = ConnectionReporter.None,
  ) {
    val checkpoint = fixture.preseed(output, taskId)
    val resolved = resolveMetainfo(fixture.metainfo)
    val state = TorrentSourceState(fixture.document.info.hash.hex, resolved.totalBytes,
      encodeBase64(checkpoint.encode()), resolved.files.map { it.id }.toSet(), output.toString(),
      encodeBase64(fixture.metainfo), version = 3, privacy = TorrentDiscoveryPrivacy.PUBLIC)
    download(context(taskId, resolved, output.toString(), connections), state)
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
      source.release("seeder")
      assertFalse(engine().hasFreeSlot())
      // The new task seeds on the slot until it is removed too.
      source.release("other")
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
      source.release("seeder")
      assertTrue(engine().hasFreeSlot())
      source.setTaskUploadRateLimit("again", 0)
      assertFailsWith<IllegalStateException> { source.setTaskUploadRateLimit("seeder", 0) }
      source.release("again")
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
    source.release("seeder")
    assertTrue(engine().hasFreeSlot())
    assertEquals(0, engine().admittedSessionBytes)
    assertFailsWith<IllegalStateException> { source.setTaskUploadRateLimit("seeder", 0) }
  }

  @Test
  fun lentV2SeederKeepsReportingItsPeers() = v2Seeding { source, engine, root ->
    val recorder = RecordingConnections()
    // The download returns at once and its session keeps seeding on the lent slot.
    source.resumeSeeded("seeder", fixture, root / "seeding", recorder)
    val network = createTorrentNetwork()
    try {
      val connection = network.connect(PeerEndpoint("127.0.0.1", engine().listenPort))
      PeerIdentityHandshake(fixture.document.identity).initiate(connection,
        PeerIdentityHandshake.Mode.V2, torrentRandomBytes(20).toByteString(),
        TorrentBufferBudget(65_536))
      val wire = PeerWire(connection, pieceCount = fixture.layout.pieceCount.toInt())
      takeBlock(wire, 16_384)
      eventually("the peer's upload") { recorder.opened.singleOrNull()?.sent == 16_384L }
      val peer = recorder.opened.single()
      assertEquals(ConnectionDirection.INCOMING, peer.spec.direction)
      assertEquals("127.0.0.1", peer.spec.host)
      assertEquals(PeerTraffic.PROTOCOL, peer.spec.protocol)
      assertEquals("v2", peer.spec.peer?.wire)
      assertEquals(0, peer.received)
      eventually("the peer's upload slot") {
        peer.spec.peer?.let { it.uploadSlot && it.peerInterested } ?: false
      }
      // The peer leaves: its connection closes at once.
      connection.close()
      eventually("the peer to close") { peer.closed }
    } finally { network.close() }
    source.release("seeder")
    assertEquals(emptyList(), recorder.open)
  }

  @Test
  fun startedV1SeederReportsItsPeers() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-source-seed-v1-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}")
          .also { torrentFileSystem.createDirectories(it) }
        val metainfo = v1Metainfo("data")
        val metadata = TorrentMetadata.fromBencode(metainfo)
        val output = root / "data"
        torrentFileSystem.write(output) { write(byteArrayOf(1, 2, 3, 4)) }
        var engine: KotlinTorrentEngine? = null
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION)).also {
          it.engineFactory = { config ->
            KotlinTorrentEngine(config, listenHost = "127.0.0.1").also { created ->
              engine = created
            }
          }
        }
        val recorder = RecordingConnections()
        val network = createTorrentNetwork()
        try {
          val ids = TorrentFileTable.parse(metainfo, TorrentConfig()).ids
          val state = TorrentSourceState(metadata.infoHash.hex, 4, "", ids, output.toString(),
            encodeBase64(metainfo), version = 2, privacy = TorrentDiscoveryPrivacy.PUBLIC)
          assertEquals(SeedingOutcome.SEEDING, source.startSeeding(SeedingTask("seeder",
            "http://fixture/seeder.torrent",
            state.saved(), null,
            output.toString(), ids, recorder)))
          val connection = network.connect(PeerEndpoint("127.0.0.1",
            checkNotNull(engine).listenPort))
          val wire = PeerWire(connection, metadata)
          wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
          takeBlock(wire, 4)
          eventually("the peer's upload") { recorder.opened.singleOrNull()?.sent == 4L }
          val peer = recorder.opened.single()
          assertEquals(ConnectionDirection.INCOMING, peer.spec.direction)
          assertEquals("v1", peer.spec.peer?.wire)
          connection.close()
          eventually("the peer to close") { peer.closed }
          source.stopSeeding("seeder")
          source.assertNoLeaks()
        } finally {
          network.close()
          source.close()
          engine?.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
        assertEquals(emptyList(), recorder.open)
        checkNotNull(engine).assertNoLeaks()
      }
    }
  }

  /** Asks for the first [length] bytes of piece 0 once unchoked, and waits for them. */
  private suspend fun takeBlock(wire: PeerWire, length: Int) {
    suspend fun next(match: (PeerMessage) -> Boolean): PeerMessage {
      while (true) {
        val message = wire.read()
        if (match(message)) return message
      }
    }
    wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
    next { it == PeerMessage.Control(PeerMessage.Signal.UNCHOKE) }
    wire.send(PeerMessage.Request(0, 0, length))
    assertEquals(length, assertIs<PeerMessage.Piece>(next { it is PeerMessage.Piece }).bytes.size)
  }
}
