package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.torrent.TorrentCapability
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SeedingOutcome
import com.linroid.ketch.core.engine.SeedingTask
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.task.TaskControl
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Completed torrents seed as the live upload setting allows: Ketch shows it on the task, keeps
 * the intent to seed, and restores seeding after a restart into free slots, always after the
 * files pass a recheck, for v1 and v2.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentSeedingControlTest {
  private inner class Harness(val seeder: GatedSeeder, val root: Path) {
    val torrent: MultiFileTorrent get() = seeder.torrent
    val engines = mutableListOf<KotlinTorrentEngine>()
    val networks = mutableListOf<CountingTorrentNetwork>()
    private val instances = mutableListOf<Ketch>()
    val sources = mutableListOf<TorrentDownloadSource>()

    /** Tracker announces sent through [http]. */
    val announces = AtomicInt(0)

    /** The seeder's engine, counting announces. */
    val http: HttpEngine = object : HttpEngine by seeder.http {
      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) {
        if (url.startsWith(GatedSeeder.ANNOUNCE)) announces.incrementAndFetch()
        seeder.http.download(url, range, headers, onData)
      }
    }

    /** Engines the sources created; restore must not create one unless it seeds. */
    val created = AtomicInt(0)

    fun source(
      policy: TorrentUploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION,
      maxActiveTorrents: Int = 5,
      restoreSeeding: Boolean = true,
    ): TorrentDownloadSource {
      val network = CountingTorrentNetwork().also { networks += it }
      return TorrentDownloadSource(TorrentConfig(dhtEnabled = false, uploadPolicy = policy,
        maxActiveTorrents = maxActiveTorrents), http, restoreSeeding).also { source ->
        source.engineFactory = { config ->
          created.incrementAndFetch()
          KotlinTorrentEngine(config, network, TorrentHttp(http), listenHost = "127.0.0.1")
            .also { engines += it }
        }
        sources += source
      }
    }

    fun ketch(source: TorrentDownloadSource, store: TaskStore = MemoryTaskStore()): Ketch =
      Ketch(http, taskStore = store, additionalSources = listOf(source)).also { instances += it }

    val output: Path get() = root / "out"

    fun request(vararg files: Int) = DownloadRequest(GatedSeeder.URL,
      destination = Destination(output.toString()),
      selectedFileIds = files.map { it.toString() }.toSet())

    fun close() = instances.forEach { it.close() }
  }

  private fun harness(block: suspend Harness.() -> Unit) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(45_000) {
        coroutineScope {
          val seeder = GatedSeeder()
          seeder.start(this)
          seeder.openAll()
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-seeding-control-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          torrentFileSystem.createDirectories(root)
          val harness = Harness(seeder, root)
          try {
            harness.block()
          } finally {
            harness.close()
            harness.engines.forEach { it.stop() }
            seeder.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
          // Every test stops its seeders before Ketch closes: nothing stays reserved.
          harness.sources.forEach {
            assertEquals(0, it.slotsInUse())
            assertEquals(0, it.reservedTasks())
          }
          harness.engines.forEach { it.assertNoLeaks() }
        }
      }
    }
  }

  private suspend fun DownloadTask.awaitCompleted(): DownloadState.Completed {
    awaitStage("completion", this) { await().getOrThrow() }
    return assertIs<DownloadState.Completed>(state.value)
  }

  private suspend fun DownloadTask.awaitSeeding(seeding: Boolean) =
    awaitStage("seeding=$seeding", this) {
      state.first { it is DownloadState.Completed && it.seeding == seeding }
    }

  private suspend fun TaskStore.intent(taskId: String): Boolean? =
    load(taskId)?.control?.seeding

  /** A seeding task of [record] to start through the source. */
  private fun seedingTask(record: TaskRecord) = SeedingTask(record.taskId, record.request.url,
    checkNotNull(record.sourceResumeState), checkNotNull(record.outputPath),
    record.request.selectedFileIds)

  /** A completed v1 task of [torrent], already on disk at [output], that seeded. */
  private fun v1Record(
    taskId: String,
    torrent: MultiFileTorrent,
    output: Path,
    completedAtMs: Long = 1_000,
  ): TaskRecord {
    torrent.writeTo(output)
    val ids = torrent.sizes.indices.mapTo(LinkedHashSet()) { it.toString() }
    val total = torrent.sizes.sum().toLong()
    val state = TorrentResumeState(torrent.metadata.infoHash.hex, total, "", ids,
      output.toString(), encodeBase64(torrent.metainfo), version = 2,
      privacy = TorrentDiscoveryPrivacy.PUBLIC)
    return completedRecord(taskId, "http://fixture/$taskId.torrent", output, total, ids,
      Json.encodeToString(state), completedAtMs)
  }

  /** A completed v2 task of [fixture], already on disk at [output], that seeded. */
  private suspend fun v2Record(
    taskId: String,
    fixture: TorrentV2Fixture,
    output: Path,
  ): TaskRecord {
    val checkpoint = fixture.preseed(output, taskId)
    val ids = TorrentFileTable.parse(fixture.metainfo, TorrentConfig()).ids
    val total = fixture.payloads.sumOf { it.size.toLong() }
    val state = TorrentResumeState(fixture.document.info.hash.hex, total,
      encodeBase64(checkpoint.encode()), ids, output.toString(), encodeBase64(fixture.metainfo),
      version = 3, privacy = TorrentDiscoveryPrivacy.PUBLIC)
    return completedRecord(taskId, "http://fixture/$taskId.torrent", output, total, ids,
      Json.encodeToString(state), 1_000)
  }

  private fun completedRecord(
    taskId: String,
    url: String,
    output: Path,
    total: Long,
    ids: Set<String>,
    state: String,
    completedAtMs: Long,
  ): TaskRecord {
    val at = Instant.fromEpochMilliseconds(completedAtMs)
    return TaskRecord(taskId = taskId,
      request = DownloadRequest(url, destination = Destination(output.toString()),
        selectedFileIds = ids),
      outputPath = output.toString(), state = TaskState.COMPLETED, totalBytes = total,
      sourceType = TorrentDownloadSource.TYPE,
      sourceResumeState = SourceResumeState(TorrentDownloadSource.TYPE, state),
      createdAt = at, updatedAt = at, completedAt = at, control = TaskControl(seeding = true))
  }

  /** Flips one byte in the middle of [path]. */
  private fun corrupt(path: Path) {
    val bytes = torrentFileSystem.read(path) { readByteArray() }
    bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
    torrentFileSystem.write(path) { write(bytes) }
  }

  @Test
  fun completion_seedPolicy_publishesCompletedSeeding() = harness {
    val source = source()
    val store = MemoryTaskStore()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(0, 1))
    task.awaitCompleted()
    task.awaitSeeding(true)
    assertEquals(setOf(task.taskId), source.seedingTaskIds.value)
    eventually("the seeding intent to be saved") { store.intent(task.taskId) == true }
    assertTrue(TorrentCapability.SEEDING.wireName in source.torrentCapabilities)
    source.stopSeeding(task.taskId)
    source.assertNoLeaks()
  }

  @Test
  fun stopSeeding_stopsTheSessionAndPublishesNotSeeding() = harness {
    val source = source()
    val store = MemoryTaskStore()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(0))
    task.awaitCompleted()
    task.awaitSeeding(true)
    source.stopSeeding(task.taskId)
    task.awaitSeeding(false)
    assertNull(source.sessionOf(task.taskId))
    assertEquals(emptySet(), source.seedingTaskIds.value)
    // Stopping at the source keeps the intent; only a Stop command clears it.
    eventually("the seeding intent to be saved") { store.intent(task.taskId) == true }
    source.assertNoLeaks()
  }

  @Test
  fun startSeeding_completedTask_seedsAfterRecheck() = harness {
    val source = source()
    val store = MemoryTaskStore()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(0, 2))
    task.awaitCompleted()
    task.awaitSeeding(true)
    source.stopSeeding(task.taskId)
    task.awaitSeeding(false)
    val record = assertNotNull(store.load(task.taskId))
    assertNull(source.seedingAvailability(task.taskId))
    assertEquals(SeedingOutcome.SEEDING, source.startSeeding(seedingTask(record)))
    task.awaitSeeding(true)
    assertEquals(TorrentSessionState.SEEDING, source.sessionOf(task.taskId)?.state?.value)
    assertEquals(SeedingOutcome.ALREADY_ACTIVE, source.seedingAvailability(task.taskId))
    assertEquals(SeedingOutcome.ALREADY_ACTIVE, source.startSeeding(seedingTask(record)))
    source.stopSeeding(task.taskId)
    source.assertNoLeaks()
  }

  @Test
  fun startSeeding_policyOff_returnsPolicyOff() = harness {
    val source = source(TorrentUploadPolicy.DISABLED)
    val store = MemoryTaskStore()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(1))
    assertFalse(task.awaitCompleted().seeding)
    assertFalse(TorrentCapability.SEEDING.wireName in source.torrentCapabilities)
    assertFalse(source.restoresSeeding)
    assertEquals(SeedingOutcome.POLICY_OFF, source.seedingAvailability(task.taskId))
    val record = assertNotNull(store.load(task.taskId))
    assertEquals(SeedingOutcome.POLICY_OFF, source.startSeeding(seedingTask(record)))
    assertNull(source.sessionOf(task.taskId))
    assertNull(store.intent(task.taskId))
    source.assertNoLeaks()
  }

  @Test
  fun startSeeding_liveSwitchToSeed_isAvailableWithoutRestart() = harness {
    val source = source(TorrentUploadPolicy.WHILE_DOWNLOADING)
    val store = MemoryTaskStore()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(0))
    assertFalse(task.awaitCompleted().seeding)
    assertEquals(SeedingOutcome.POLICY_OFF, source.seedingAvailability(task.taskId))
    source.setUploadPolicy(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
    assertTrue(source.restoresSeeding)
    assertTrue(TorrentCapability.SEEDING.wireName in source.torrentCapabilities)
    assertNull(source.seedingAvailability(task.taskId))
    val record = assertNotNull(store.load(task.taskId))
    assertEquals(SeedingOutcome.SEEDING, source.startSeeding(seedingTask(record)))
    task.awaitSeeding(true)
    eventually("the seeding intent to be saved") { store.intent(task.taskId) == true }
    source.stopSeeding(task.taskId)
    source.assertNoLeaks()
  }

  @Test
  fun restore_seedingTask_seedsAfterKetchRestart() = harness {
    // One store for both instances and the test, so reads never see a write half done.
    val store = FileTaskStore(root / "tasks.json")
    val firstSource = source()
    val first = ketch(firstSource, store)
    first.start()
    val task = first.download(request(0, 1, 2))
    task.awaitCompleted()
    task.awaitSeeding(true)
    eventually("the seeding intent to be saved") { store.intent(task.taskId) == true }
    firstSource.stopSeeding(task.taskId)
    first.close()
    firstSource.assertNoLeaks()
    assertEquals(true, store.intent(task.taskId))

    val secondSource = source()
    val second = ketch(secondSource, store)
    second.start()
    val restored = second.tasks.value.single()
    restored.awaitSeeding(true)
    assertEquals(TorrentSessionState.SEEDING,
      secondSource.sessionOf(restored.taskId)?.state?.value)
    assertEquals(true, store.intent(task.taskId))
    secondSource.stopSeeding(restored.taskId)
    secondSource.assertNoLeaks()
  }

  @Test
  fun restore_changedPayload_stopsAndClearsIntent() = harness {
    // One store for both instances and the test, so reads never see a write half done.
    val store = FileTaskStore(root / "tasks.json")
    val firstSource = source()
    val first = ketch(firstSource, store)
    first.start()
    val task = first.download(request(0))
    task.awaitCompleted()
    task.awaitSeeding(true)
    eventually("the seeding intent to be saved") { store.intent(task.taskId) == true }
    firstSource.stopSeeding(task.taskId)
    first.close()
    corrupt(output / "f0")
    val announced = announces.load()

    val secondSource = source()
    val second = ketch(secondSource, store)
    second.start()
    eventually("the intent to be cleared") { store.intent(task.taskId) == false }
    val restored = second.tasks.value.single()
    assertFalse(assertIs<DownloadState.Completed>(restored.state.value).seeding)
    assertNull(secondSource.sessionOf(restored.taskId))
    // The recheck found the change before any tracker or peer was contacted.
    assertEquals(announced, announces.load())
    assertEquals(0, networks.last().connects.load())
    secondSource.assertNoLeaks()
  }

  // Pieces of 32 KiB: a spans pieces 0 and 1, b is piece 2.
  private val v2Fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 20_000))

  @Test
  fun restore_v2SeedingTask_seedsAfterKetchRestart() = harness {
    val store = MemoryTaskStore()
    val record = v2Record("v2-seed", v2Fixture, root / "v2")
    store.save(record)
    val source = source()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.tasks.value.single()
    task.awaitSeeding(true)
    assertIs<TorrentV2DownloadSession>(source.sessionOf(task.taskId))
    assertEquals(TorrentSessionState.SEEDING, source.sessionOf(task.taskId)?.state?.value)
    assertEquals(true, store.intent(task.taskId))
    source.stopSeeding(task.taskId)
    task.awaitSeeding(false)
    source.assertNoLeaks()
  }

  @Test
  fun restore_v2ChangedPayload_stopsAndClearsIntent() = harness {
    val store = MemoryTaskStore()
    val record = v2Record("v2-changed", v2Fixture, root / "v2")
    store.save(record)
    corrupt(root / "v2" / "a")
    val source = source()
    val ketch = ketch(source, store)
    ketch.start()
    eventually("the intent to be cleared") { store.intent(record.taskId) == false }
    val task = ketch.tasks.value.single()
    assertFalse(assertIs<DownloadState.Completed>(task.state.value).seeding)
    assertNull(source.sessionOf(task.taskId))
    assertEquals(0, networks.single().connects.load())
    source.assertNoLeaks()
  }

  @Test
  fun restore_noFreeSlot_skipsAndKeepsIntent() = harness {
    val store = MemoryTaskStore()
    val older = v1Record("older", MultiFileTorrent(listOf(40_000, 30_000)), root / "older", 1_000)
    val newer = v1Record("newer", MultiFileTorrent(listOf(50_000, 20_000)), root / "newer", 2_000)
    store.save(newer)
    store.save(older)
    val source = source(maxActiveTorrents = 1)
    val ketch = ketch(source, store)
    ketch.start()
    val tasks = ketch.tasks.value.associateBy { it.taskId }
    // The older completion seeds on the only slot; the newer finds none and keeps its intent.
    checkNotNull(tasks["older"]).awaitSeeding(true)
    eventually("the walk to end") { source.seedingAvailability("newer") == SeedingOutcome.NO_SLOT }
    assertNull(source.sessionOf("newer"))
    assertFalse(assertIs<DownloadState.Completed>(checkNotNull(tasks["newer"]).state.value).seeding)
    assertEquals(true, store.intent("newer"))
    assertEquals(true, store.intent("older"))
    source.stopSeeding("older")
    source.assertNoLeaks()
  }

  @Test
  fun restore_disabled_neverStartsSessions() = harness {
    val store = MemoryTaskStore()
    store.save(v1Record("kept", MultiFileTorrent(listOf(40_000, 30_000)), root / "kept"))
    val source = source(restoreSeeding = false)
    assertFalse(source.restoresSeeding)
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.tasks.value.single()
    delay(500)
    assertFalse(assertIs<DownloadState.Completed>(task.state.value).seeding)
    assertEquals(0, created.load())
    assertEquals(true, store.intent("kept"))
    source.assertNoLeaks()
  }

  @Test
  fun eviction_downloadNeedsSlot_keepsIntent() = harness {
    val store = MemoryTaskStore()
    store.save(v1Record("seeder", MultiFileTorrent(listOf(40_000, 30_000)), root / "seeder"))
    val source = source(maxActiveTorrents = 1)
    val ketch = ketch(source, store)
    ketch.start()
    val seeder = ketch.tasks.value.single()
    seeder.awaitSeeding(true)
    // A download needs the only slot: the restored seeder gives it up.
    val download = ketch.download(request(0))
    download.awaitCompleted()
    seeder.awaitSeeding(false)
    assertNull(source.sessionOf("seeder"))
    assertEquals(true, store.intent("seeder"))
    download.awaitSeeding(true)
    source.stopSeeding(download.taskId)
    source.assertNoLeaks()
  }
}
