package com.linroid.ketch.torrent

import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.ConnectionReporter
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.SourceState
import com.linroid.ketch.core.engine.decodeState
import com.linroid.ketch.core.engine.resolve
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.core.task.TaskControl
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.torrent.api.TorrentCapability
import com.linroid.ketch.torrent.control.SeedingOutcome
import com.linroid.ketch.torrent.control.SeedingTask
import com.linroid.ketch.torrent.control.UnusedHttpEngine
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.engine.KotlinTorrentSession
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.engine.TorrentRouteTable
import com.linroid.ketch.torrent.engine.TorrentSessionState
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
import com.linroid.ketch.torrent.peer.pieceBitfield
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
import kotlin.time.Instant
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
  fun restoredSeeder_shrunkWhileItChecks_checksAndSeedsTheSmallerSelection() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
          .also { it.engineFactory = { engine } }
        val torrent = MultiFileTorrent(listOf(40_000, 30_000))
        val hash = torrent.metadata.infoHash.hex
        val ids = TorrentFileTable.parse(torrent.metainfo, TorrentConfig()).ids
        val total = torrent.sizes.sum().toLong()
        val state = TorrentSourceState(hash, total, "", ids, "unused-pack",
          encodeBase64(torrent.metainfo), version = 2, privacy = TorrentDiscoveryPrivacy.PUBLIC)
        val session = FakeTorrentSession(hash, torrent.sizes[1].toLong()).apply {
          stateOnResume = TorrentSessionState.CHECKING_FILES
          savedResumeData = byteArrayOf(1)
        }
        engine.addTorrentResult = session
        val store = MemoryTaskStore()
        val at = Instant.fromEpochMilliseconds(1_000)
        store.save(TaskRecord("seeder",
          DownloadRequest("http://fixture/pack.torrent", selectedFileIds = ids),
          outputPath = "unused-pack", state = TaskState.COMPLETED, totalBytes = total,
          sourceType = TorrentDownloadSource.TYPE, sourceState = state.saved(), createdAt = at,
          updatedAt = at, completedAt = at, control = TaskControl(sharing = true)))
        val ketch = Ketch(UnusedHttpEngine(), taskStore = store, additionalSources = listOf(source))
        try {
          ketch.start()
          // The restored seeder checks its files, and a file, deleted maybe, is dropped meanwhile.
          eventually { session.resumed }
          ketch.tasks.value.single().selectFiles(setOf("1"))
          // The check takes the smaller selection, which no longer needs that file.
          assertEquals(listOf(setOf("1")), session.selectionChanges)

          session.seed()
          eventually { "seeder" in source.seedingTaskIds.value }

          assertEquals(listOf(setOf("1")), session.selectionChanges)
          source.stopSeeding("seeder")
          // The checkpoint it saved as it stopped describes the smaller selection too.
          val saved = source.decodeState(assertNotNull(store.load("seeder")?.sourceState))
          assertEquals(setOf("1"), saved.selectedFileIds)
          assertEquals(torrent.sizes[1].toLong(), saved.totalBytes)
          source.assertNoLeaks()
        } finally { ketch.close() }
      }
    }
  }

  @Test
  fun startSeeding_newerSelectionAppliedFirst_seedsTheNewerOne() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
          .also { it.engineFactory = { engine } }
        try {
          val torrent = MultiFileTorrent()
          val hash = torrent.metadata.infoHash.hex
          val ids = TorrentFileTable.parse(torrent.metainfo, TorrentConfig()).ids
          val state = TorrentSourceState(hash, torrent.sizes.sum().toLong(), "", ids,
            "unused-pack", encodeBase64(torrent.metainfo), version = 2,
            privacy = TorrentDiscoveryPrivacy.PUBLIC)
          val session = FakeTorrentSession(hash).apply {
            stateOnResume = TorrentSessionState.CHECKING_FILES
            savedResumeData = byteArrayOf(1)
          }
          engine.addTorrentResult = session
          val saved = MutableStateFlow<SourceState?>(null)
          source.saveSessionStates { _, state -> saved.value = state }
          val selection = MutableStateFlow(ids)
          val held = CompletableDeferred<Unit>()
          val release = CompletableDeferred<Unit>()
          session.beforeChange = {
            if (it == setOf("0", "1")) {
              held.complete(Unit)
              release.await()
            }
          }
          val seeding = async {
            source.startSeeding(SeedingTask("seeder", "http://fixture/pack.torrent", state.saved(),
              null, "unused-pack", ids, selection = { selection.value }))
          }
          eventually { session.resumed }
          // The task drops a file as the seeder starts seeding, which it reads then...
          selection.value = setOf("0", "1")
          session.seed()
          held.await()
          // ...and another before it applies that one, which Ketch applies first.
          selection.value = setOf("0")
          source.changeSeedingSelection("seeder", setOf("0"))
          release.complete(Unit)

          assertEquals(SeedingOutcome.SEEDING, seeding.await())
          assertEquals(listOf(setOf("0"), setOf("0", "1"), setOf("0")), session.selectionChanges)
          source.stopSeeding("seeder")
          assertEquals(setOf("0"),
            source.decodeState(assertNotNull(saved.value)).selectedFileIds)
          source.assertNoLeaks()
        } finally { source.close() }
      }
    }
  }

  @Test
  fun startSeeding_selectionGrewWhileItChecked_leavesTheSeederAlone() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
          .also { it.engineFactory = { engine } }
        try {
          val torrent = MultiFileTorrent(listOf(40_000, 30_000))
          val hash = torrent.metadata.infoHash.hex
          val ids = TorrentFileTable.parse(torrent.metainfo, TorrentConfig()).ids
          val state = TorrentSourceState(hash, 40_000, "", setOf("0"), "unused-pack",
            encodeBase64(torrent.metainfo), version = 2, privacy = TorrentDiscoveryPrivacy.PUBLIC)
          val session = FakeTorrentSession(hash).apply {
            stateOnResume = TorrentSessionState.SEEDING
          }
          engine.addTorrentResult = session
          // The task reopens for the added file, and its run takes the seeder over.
          val outcome = source.startSeeding(SeedingTask("seeder", "http://fixture/pack.torrent",
            state.saved(), null, "unused-pack", setOf("0"), selection = { ids }))

          assertEquals(SeedingOutcome.SEEDING, outcome)
          assertEquals(emptyList(), session.selectionChanges)
          source.stopSeeding("seeder")
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
  fun startSeeding_deletedFileDroppedSinceTheTaskWasRead_seeds() = v2Seeding { source, _, root ->
    val output = root / "seeding"
    val checkpoint = fixture.preseed(output, "seeder")
    val resolved = source.resolveMetainfo(fixture.metainfo)
    val ids = resolved.files.map { it.id }.toSet()
    val dropped = resolved.files.single { it.name.substringAfterLast('/') == "c" }.id
    // The owner deleted a file and then dropped it, after the restore read the task.
    torrentFileSystem.delete(output / "c")
    val state = TorrentSourceState(fixture.document.info.hash.hex, resolved.totalBytes,
      encodeBase64(checkpoint.encode()), ids, output.toString(), encodeBase64(fixture.metainfo),
      version = 3, privacy = TorrentDiscoveryPrivacy.PUBLIC)

    val outcome = source.startSeeding(SeedingTask("seeder", "http://fixture/pack.torrent",
      state.saved(), null, output.toString(), ids, selection = { ids - dropped }))

    assertEquals(SeedingOutcome.SEEDING, outcome)
    assertEquals(ids - dropped, source.sessionOf("seeder")?.selectedFileIds)
    source.stopSeeding("seeder")
    source.assertNoLeaks()
  }

  /**
   * The payload file system, which holds the next read of the file [hold] names until the hold is
   * released: a check of the files waits there, in the middle of the check.
   */
  @OptIn(ExperimentalAtomicApi::class)
  private class HoldingFileSystem : ForwardingFileSystem(torrentFileSystem) {
    class Hold(val name: String) {
      val reached = CompletableDeferred<Unit>()
      val released = CompletableDeferred<Unit>()
    }

    private val next = AtomicReference<Hold?>(null)

    fun hold(name: String): Hold = Hold(name).also { next.store(it) }

    override fun openReadOnly(file: Path): FileHandle {
      val hold = next.load()
      if (hold != null && file.name == hold.name && next.compareAndSet(hold, null)) {
        hold.reached.complete(Unit)
        // A storage thread blocked in its read, as one reading a slow disk is.
        runBlocking { hold.released.await() }
      }
      return super.openReadOnly(file)
    }
  }

  /**
   * Runs [body] with a source whose engine seeds v1 tasks of [torrent], every file written into
   * the output folder it is given, and checks that the source and the engine return everything.
   * No file ends on a piece boundary: f1 shares its first piece with f0 and its last with f2.
   * The engine's stores keep their payload in [payload].
   */
  private fun v1Seeding(
    torrent: MultiFileTorrent,
    payload: FileSystem = torrentFileSystem,
    body: suspend CoroutineScope.(TorrentDownloadSource, () -> KotlinTorrentEngine, Path) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val root = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-source-seed-v1-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}")
          .also { torrentFileSystem.createDirectories(it) }
        val output = root / "pack"
        torrent.writeTo(output)
        var engine: KotlinTorrentEngine? = null
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION)).also {
          it.engineFactory = { config ->
            KotlinTorrentEngine(config, listenHost = "127.0.0.1", payloadFileSystem = payload)
              .also { created -> engine = created }
          }
        }
        try {
          body(source, { checkNotNull(engine) }, output)
          source.assertNoLeaks()
        } finally {
          source.close()
          engine?.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
        checkNotNull(engine).assertNoLeaks()
      }
    }
  }

  /**
   * The completed task `seeder` of [torrent] at [output], restored with [selected] and the
   * [state] its record holds: by default, what Ketch saved as it completed without a checkpoint.
   */
  private fun v1SeedingTask(
    torrent: MultiFileTorrent,
    output: Path,
    selected: Set<String>,
    state: SourceState? = null,
  ) = SeedingTask("seeder", "http://fixture/pack.torrent",
    state ?: TorrentSourceState(torrent.metadata.infoHash.hex, torrent.sizes.sum().toLong(), "",
      selected, output.toString(), encodeBase64(torrent.metainfo), version = 2,
      privacy = TorrentDiscoveryPrivacy.PUBLIC).saved(),
    null, output.toString(), selected)

  /** The latest state the source saves for a seeding task, as the task's record keeps it. */
  private fun TorrentDownloadSource.savedStates(): MutableStateFlow<SourceState?> =
    MutableStateFlow<SourceState?>(null).also { saved ->
      saveSessionStates { _, state -> saved.value = state }
    }

  /**
   * Rewrites the bytes [at] of [path] in place, as an editor would, late enough that the file's
   * modification time tells the edit apart from the last write. Returns the edited bytes.
   */
  private suspend fun editInPlace(path: Path, vararg at: Int): ByteArray {
    val bytes = torrentFileSystem.read(path) { readByteArray() }
    for (index in at) bytes[index] = (bytes[index] + 1).toByte()
    val before = torrentFileSystem.metadata(path).lastModifiedAtMillis
    while (true) {
      torrentFileSystem.write(path) { write(bytes) }
      if (torrentFileSystem.metadata(path).lastModifiedAtMillis != before) return bytes
      delay(5)
    }
  }

  /**
   * Connects to [engine] as a peer of [torrent] and asks for the first block of [piece], until
   * [condition] holds.
   */
  private suspend fun requestPiece(
    torrent: MultiFileTorrent,
    engine: KotlinTorrentEngine,
    piece: Int,
    condition: suspend () -> Boolean,
  ) {
    val network = createTorrentNetwork()
    try {
      val connection = network.connect(PeerEndpoint("127.0.0.1", engine.listenPort))
      try {
        val wire = PeerWire(connection, torrent.metadata)
        wire.handshake(PeerHandshake(torrent.metadata.infoHash, torrentRandomBytes(20), false,
          false))
        wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
        while (wire.read() != PeerMessage.Control(PeerMessage.Signal.UNCHOKE)) Unit
        wire.send(PeerMessage.Request(piece, 0, 16_384))
        eventually(condition)
      } finally {
        connection.close()
      }
    } finally {
      network.close()
    }
  }

  /** The bitfield the engine sends a peer that connects for [torrent]. */
  private suspend fun advertised(
    torrent: MultiFileTorrent,
    engine: KotlinTorrentEngine,
  ): ByteArray {
    val network = createTorrentNetwork()
    try {
      val connection = network.connect(PeerEndpoint("127.0.0.1", engine.listenPort))
      try {
        val wire = PeerWire(connection, torrent.metadata)
        wire.handshake(PeerHandshake(torrent.metadata.infoHash, torrentRandomBytes(20), false,
          false))
        return assertIs<PeerMessage.Bitfield>(wire.read()).bytes
      } finally {
        connection.close()
      }
    } finally {
      network.close()
    }
  }

  @Test
  fun startSeeding_v1FileDeletedThenDropped_seedsWithoutTheSharedPieces() {
    val torrent = MultiFileTorrent()
    v1Seeding(torrent) { source, engine, output ->
      val ids = TorrentFileTable.parse(torrent.metainfo, TorrentConfig()).ids
      // Deleted before anything kept a copy of the pieces it shares with f0 and f2.
      torrentFileSystem.delete(output / "f1")

      val outcome = source.startSeeding(v1SeedingTask(torrent, output, ids - "1"))

      assertEquals(SeedingOutcome.SEEDING, outcome)
      val shared = setOf(torrent.piecesOf(1).first, torrent.piecesOf(1).last)
      val kept = (torrent.piecesOf(0) + torrent.piecesOf(2)).toSet() - shared
      val pieces = BooleanArray(torrent.pieceCount) { it in kept }
      assertContentEquals(pieceBitfield(pieces), advertised(torrent, engine()))
      assertEquals(TorrentSessionState.SEEDING, source.sessionOf("seeder")?.state?.value)
      source.stopSeeding("seeder")
    }
  }

  /**
   * Has the engine create f1 for the task, as a download would, then writes its bytes in place:
   * the task owns f1 from then on, and seeds every file of [torrent] at [output].
   */
  private suspend fun TorrentDownloadSource.seedWithF1Owned(
    torrent: MultiFileTorrent,
    output: Path,
    ids: Set<String>,
  ) {
    torrentFileSystem.delete(output / "f1")
    assertEquals(SeedingOutcome.CHANGED_ON_DISK,
      startSeeding(v1SeedingTask(torrent, output, ids)))
    torrentFileSystem.write(output / "f1") { write(torrent.payloads[1]) }
    assertEquals(SeedingOutcome.SEEDING, startSeeding(v1SeedingTask(torrent, output, ids)))
  }

  @Test
  fun startSeeding_v1DroppedFileEdited_seedsWithoutTheSharedPiecesAndLeavesIt() {
    val torrent = MultiFileTorrent()
    v1Seeding(torrent) { source, engine, output ->
      val ids = TorrentFileTable.parse(torrent.metainfo, TorrentConfig()).ids
      val saved = source.savedStates()
      source.seedWithF1Owned(torrent, output, ids)
      assertTrue(source.changeSeedingSelection("seeder", ids - "1"))
      source.stopSeeding("seeder")
      // The owner edits the file it dropped in place, as a tag editor would, at both ends.
      val edited = editInPlace(output / "f1", 0, torrent.sizes[1] - 1)

      val outcome = source.startSeeding(v1SeedingTask(torrent, output, ids - "1",
        assertNotNull(saved.value)))

      assertEquals(SeedingOutcome.SEEDING, outcome)
      val shared = setOf(torrent.piecesOf(1).first, torrent.piecesOf(1).last)
      val kept = (torrent.piecesOf(0) + torrent.piecesOf(2)).toSet() - shared
      val pieces = BooleanArray(torrent.pieceCount) { it in kept }
      assertContentEquals(pieceBitfield(pieces), advertised(torrent, engine()))
      source.stopSeeding("seeder")
      assertContentEquals(edited, torrentFileSystem.read(output / "f1") { readByteArray() })
    }
  }

  @Test
  fun startSeeding_v1ChosenFileEditedInASharedPiece_changedOnDisk() {
    val torrent = MultiFileTorrent()
    v1Seeding(torrent) { source, _, output ->
      val ids = TorrentFileTable.parse(torrent.metainfo, TorrentConfig()).ids
      val saved = source.savedStates()
      source.seedWithF1Owned(torrent, output, ids)
      assertTrue(source.changeSeedingSelection("seeder", ids - "1"))
      source.stopSeeding("seeder")
      // The owner edits f0 where it shares a piece with f1, which is as the seeder left it.
      val edited = editInPlace(output / "f0", torrent.sizes[0] - 1)

      val outcome = source.startSeeding(v1SeedingTask(torrent, output, ids - "1",
        assertNotNull(saved.value)))

      assertEquals(SeedingOutcome.CHANGED_ON_DISK, outcome)
      assertContentEquals(edited, torrentFileSystem.read(output / "f0") { readByteArray() })
      assertContentEquals(torrent.payloads[1],
        torrentFileSystem.read(output / "f1") { readByteArray() })
    }
  }

  @Test
  fun startSeeding_v1FileDroppedWhileItChecks_seedsWithoutTheSharedPieces() {
    val torrent = MultiFileTorrent()
    val payload = HoldingFileSystem()
    v1Seeding(torrent, payload) { source, engine, output ->
      val ids = TorrentFileTable.parse(torrent.metainfo, TorrentConfig()).ids
      // The owner deleted f1; a check of every file created it again, empty, and stopped.
      torrentFileSystem.delete(output / "f1")
      assertEquals(SeedingOutcome.CHANGED_ON_DISK,
        source.startSeeding(v1SeedingTask(torrent, output, ids)))
      assertEquals(0L, torrentFileSystem.metadata(output / "f1").size)
      // The next check holds as it reads f2, having checked f0's pieces, when the owner drops f1.
      val hold = payload.hold("f2")
      val seeding = async { source.startSeeding(v1SeedingTask(torrent, output, ids)) }
      hold.reached.await()
      val session = assertNotNull(source.sessionOf("seeder"))
      assertEquals(TorrentSessionState.CHECKING_FILES, session.state.value)
      // The drop stops the check where it is and checks again with the smaller selection.
      val drop = async { source.changeSeedingSelection("seeder", ids - "1") }
      delay(200)
      hold.released.complete(Unit)
      assertTrue(drop.await())

      assertEquals(SeedingOutcome.SEEDING, seeding.await())
      assertEquals(TorrentSessionState.SEEDING, session.state.value)
      val shared = setOf(torrent.piecesOf(1).first, torrent.piecesOf(1).last)
      val kept = (torrent.piecesOf(0) + torrent.piecesOf(2)).toSet() - shared
      val pieces = BooleanArray(torrent.pieceCount) { it in kept }
      assertContentEquals(pieceBitfield(pieces), advertised(torrent, engine()))
      assertEquals(0L, torrentFileSystem.metadata(output / "f1").size)
      source.stopSeeding("seeder")
    }
  }

  @Test
  fun startSeeding_v1OwnedFileDroppedWhileNotSeedingThenDeleted_seedsWithoutTheSharedPieces() {
    val torrent = MultiFileTorrent()
    v1Seeding(torrent) { source, engine, output ->
      val ids = TorrentFileTable.parse(torrent.metainfo, TorrentConfig()).ids
      source.seedWithF1Owned(torrent, output, ids)
      source.stopSeeding("seeder")
      // Dropped while the task does not seed, so no store sees it, then deleted.
      torrentFileSystem.delete(output / "f1")

      val outcome = source.startSeeding(v1SeedingTask(torrent, output, ids - "1"))

      assertEquals(SeedingOutcome.SEEDING, outcome)
      val shared = setOf(torrent.piecesOf(1).first, torrent.piecesOf(1).last)
      val kept = (torrent.piecesOf(0) + torrent.piecesOf(2)).toSet() - shared
      val pieces = BooleanArray(torrent.pieceCount) { it in kept }
      assertContentEquals(pieceBitfield(pieces), advertised(torrent, engine()))
      assertFalse(torrentFileSystem.exists(output / "f1"))
      source.stopSeeding("seeder")
    }
  }

  @Test
  fun seeder_v1DroppedFileDeletedWhileSeeding_keepsSeedingWithoutTheSharedPiece() {
    val torrent = MultiFileTorrent()
    v1Seeding(torrent) { source, engine, output ->
      val ids = TorrentFileTable.parse(torrent.metainfo, TorrentConfig()).ids
      assertEquals(SeedingOutcome.SEEDING,
        source.startSeeding(v1SeedingTask(torrent, output, ids)))
      val session = assertIs<KotlinTorrentSession>(source.sessionOf("seeder"))
      assertTrue(source.changeSeedingSelection("seeder", ids - "1"))
      // The owner deletes the file it dropped; a peer asks for a piece f0 shares with it.
      torrentFileSystem.delete(output / "f1")
      val shared = torrent.piecesOf(1).first
      requestPiece(torrent, engine(), shared) { !session.verifiedPieces()[shared] }

      // It seeds on without the piece, which it never offers nor downloads again.
      assertNull(withTimeoutOrNull(1_000) {
        session.state.first { it != TorrentSessionState.SEEDING }
      })
      assertEquals(setOf("seeder"), source.seedingTaskIds.value)
      val pieces = BooleanArray(torrent.pieceCount) { it != shared }
      assertContentEquals(pieceBitfield(pieces), advertised(torrent, engine()))
      source.stopSeeding("seeder")
    }
  }

  @Test
  fun seeder_v1DroppedFileEditedWhileSeeding_keepsSeedingWithoutTheSharedPiece() {
    val torrent = MultiFileTorrent()
    v1Seeding(torrent) { source, engine, output ->
      val ids = TorrentFileTable.parse(torrent.metainfo, TorrentConfig()).ids
      assertEquals(SeedingOutcome.SEEDING,
        source.startSeeding(v1SeedingTask(torrent, output, ids)))
      val session = assertIs<KotlinTorrentSession>(source.sessionOf("seeder"))
      assertTrue(source.changeSeedingSelection("seeder", ids - "1"))
      // The owner edits the file it dropped where it shares a piece with f0.
      val edited = editInPlace(output / "f1", 0)
      val shared = torrent.piecesOf(1).first
      requestPiece(torrent, engine(), shared) { !session.verifiedPieces()[shared] }

      assertNull(withTimeoutOrNull(1_000) {
        session.state.first { it != TorrentSessionState.SEEDING }
      })
      val pieces = BooleanArray(torrent.pieceCount) { it != shared }
      assertContentEquals(pieceBitfield(pieces), advertised(torrent, engine()))
      source.stopSeeding("seeder")
      assertContentEquals(edited, torrentFileSystem.read(output / "f1") { readByteArray() })
    }
  }

  @Test
  fun seeder_v1ChosenFileEditedInASharedPiece_downloadsThePieceAgain() {
    val torrent = MultiFileTorrent()
    v1Seeding(torrent) { source, engine, output ->
      val ids = TorrentFileTable.parse(torrent.metainfo, TorrentConfig()).ids
      assertEquals(SeedingOutcome.SEEDING,
        source.startSeeding(v1SeedingTask(torrent, output, ids)))
      val session = assertIs<KotlinTorrentSession>(source.sessionOf("seeder"))
      assertTrue(source.changeSeedingSelection("seeder", ids - "1"))
      // The owner edits f0 where it shares a piece with f1, which is as the check found it.
      editInPlace(output / "f0", torrent.sizes[0] - 1)
      val shared = torrent.piecesOf(1).first
      requestPiece(torrent, engine(), shared) { !session.verifiedPieces()[shared] }

      // A chosen file changed: seeding cannot serve around it, and the piece is wanted again.
      assertEquals(TorrentSessionState.DOWNLOADING, withTimeout(5_000) {
        session.state.first { it != TorrentSessionState.SEEDING }
      })
      source.stopSeeding("seeder")
    }
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
