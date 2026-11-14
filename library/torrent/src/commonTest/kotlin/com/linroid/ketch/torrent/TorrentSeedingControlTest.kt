package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.decodeState
import com.linroid.ketch.core.engine.SourceState
import com.linroid.ketch.core.task.TaskControl
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import com.linroid.ketch.torrent.api.TorrentActivity
import com.linroid.ketch.torrent.api.TorrentCapability
import com.linroid.ketch.torrent.api.TorrentCommandContext
import com.linroid.ketch.torrent.api.TorrentCommandError
import com.linroid.ketch.torrent.api.TorrentCommandException
import com.linroid.ketch.torrent.api.TorrentController
import com.linroid.ketch.torrent.api.TorrentTrackerEdit
import com.linroid.ketch.torrent.control.SeedingOutcome
import com.linroid.ketch.torrent.control.SeedingTask
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.engine.TorrentSessionState
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.storage.torrentFileSystem
import com.linroid.ketch.torrent.v2.TorrentV2DownloadSession
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
import okio.Path.Companion.toPath
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /** Announces sent to [EXTRA_TRACKER], a tracker no torrent of the tests names. */
    val extraAnnounces = AtomicInt(0)

    /** The seeder's engine, counting announces. */
    val http: HttpEngine = object : HttpEngine by seeder.http {
      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) {
        if (url.startsWith(GatedSeeder.ANNOUNCE)) announces.incrementAndFetch()
        if (url.startsWith(EXTRA_TRACKER)) extraAnnounces.incrementAndFetch()
        seeder.http.download(url, range, headers, onData)
      }
    }

    /** Engines the sources created; restore must not create one unless it seeds. */
    val created = AtomicInt(0)

    fun source(
      policy: TorrentUploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION,
      maxActiveTorrents: Int = 5,
      restoreSeeding: Boolean = true,
      uploadsPaused: Boolean = false,
    ): TorrentDownloadSource {
      val network = CountingTorrentNetwork().also { networks += it }
      return TorrentDownloadSource(TorrentConfig(dhtEnabled = false, uploadPolicy = policy,
        maxActiveTorrents = maxActiveTorrents), http, restoreSeeding,
        uploadsPaused = uploadsPaused).also { source ->
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
      state.first { it is DownloadState.Completed && it.sharing == seeding }
    }

  private suspend fun TaskStore.intent(taskId: String): Boolean? =
    load(taskId)?.control?.sharing

  /** A seeding task of [record] to start through the source. */
  private fun seedingTask(record: TaskRecord) = SeedingTask(record.taskId, record.request.url,
    checkNotNull(record.sourceState), record.resolvedSource, checkNotNull(record.outputPath),
    record.request.selectedFileIds)

  /**
   * A completed v1 task of [torrent], already on disk at [output], that seeded, or with
   * [sharing] as its intent to seed.
   */
  private fun v1Record(
    taskId: String,
    torrent: MultiFileTorrent,
    output: Path,
    completedAtMs: Long = 1_000,
    sharing: Boolean? = true,
  ): TaskRecord {
    torrent.writeTo(output)
    val ids = torrent.sizes.indices.mapTo(LinkedHashSet()) { it.toString() }
    val total = torrent.sizes.sum().toLong()
    val state = TorrentSourceState(torrent.metadata.infoHash.hex, total, "", ids,
      output.toString(), encodeBase64(torrent.metainfo), version = 2,
      privacy = TorrentDiscoveryPrivacy.PUBLIC)
    return completedRecord(taskId, "http://fixture/$taskId.torrent", output, total, ids,
      Json.encodeToString(state), completedAtMs, sharing)
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
    val state = TorrentSourceState(fixture.document.info.hash.hex, total,
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
    sharing: Boolean? = true,
  ): TaskRecord {
    val at = Instant.fromEpochMilliseconds(completedAtMs)
    return TaskRecord(taskId = taskId,
      request = DownloadRequest(url, destination = Destination(output.toString()),
        selectedFileIds = ids),
      outputPath = output.toString(), state = TaskState.COMPLETED, totalBytes = total,
      sourceType = TorrentDownloadSource.TYPE,
      sourceState = SourceState(TorrentDownloadSource.TYPE, state),
      createdAt = at, updatedAt = at, completedAt = at,
      control = sharing?.let { TaskControl(sharing = it) })
  }

  /** Flips one byte in the middle of [path]. */
  private fun corrupt(path: Path) {
    val bytes = torrentFileSystem.read(path) { readByteArray() }
    bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
    torrentFileSystem.write(path) { write(bytes) }
  }

  @Test
  fun recheck_completedTaskWithAChangedFile_downloadsItAgain() = harness {
    val source = source(TorrentUploadPolicy.DISABLED)
    val store = MemoryTaskStore()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(0, 1))
    val first = task.awaitCompleted()
    val file = output / "f0"
    corrupt(file)
    torrentFileSystem.delete(output / "f1")

    assertNotNull(ketch.controller(TorrentController)).recheck(task.taskId)

    // The run that checks it replaces the completed state, then completes anew.
    awaitStage("the recheck to finish", task) {
      task.state.first { it is DownloadState.Completed && it.completedAt != first.completedAt }
    }
    assertContentEquals(torrent.payloads[0], torrentFileSystem.read(file) { readByteArray() })
    assertContentEquals(torrent.payloads[1],
      torrentFileSystem.read(output / "f1") { readByteArray() })
    assertEquals(TaskState.COMPLETED, store.load(task.taskId)?.state)
    source.assertNoLeaks()
  }

  @Test
  fun recheck_v2TaskWithAReplacedAndADeletedFile_takesThemAsTheyAre() = harness {
    val store = MemoryTaskStore()
    val record = v2Record("v2-check", v2Fixture, root / "v2").let {
      it.copy(control = TaskControl(sharing = false))
    }
    store.save(record)
    val source = source(TorrentUploadPolicy.DISABLED)
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.tasks.value.single()
    val first = assertIs<DownloadState.Completed>(task.state.value)
    // Restored from a backup: the same bytes in a new file, which is not the one Ketch wrote.
    torrentFileSystem.delete(root / "v2" / "a")
    torrentFileSystem.write(root / "v2" / "a") { write(v2Fixture.payloads[0]) }
    val controller = assertNotNull(ketch.controller(TorrentController))

    controller.recheck(task.taskId)

    awaitStage("the recheck to finish", task) {
      task.state.first { it is DownloadState.Completed && it.completedAt != first.completedAt }
    }

    // A deleted file is made again and waits for a peer, rather than failing the task.
    torrentFileSystem.delete(root / "v2" / "b")
    controller.recheck(task.taskId)
    awaitStage("the download to start again", task) {
      task.state.first { it is DownloadState.Downloading || it is DownloadState.Failed }
    }
    eventually("the missing file to be made again") {
      torrentFileSystem.metadataOrNull(root / "v2" / "b") != null
    }
    assertIs<DownloadState.Downloading>(task.state.value)
    task.pause()
    source.assertNoLeaks()
  }

  @Test
  fun relocate_completedV1Task_movesItsFilesChecksThemAndOwnsThemThere() = harness {
    val source = source(TorrentUploadPolicy.DISABLED)
    val store = MemoryTaskStore()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(0, 1))
    val first = task.awaitCompleted()
    val controller = assertNotNull(ketch.controller(TorrentController))
    val folder = root / "moved"

    controller.relocate(task.taskId, folder.toString())

    awaitStage("the check after the move", task) {
      task.state.first { it is DownloadState.Completed && it.completedAt != first.completedAt }
    }
    val moved = folder / output.name
    assertEquals(moved.toString(), task.outputPath)
    assertEquals(moved.toString(), assertIs<DownloadState.Completed>(task.state.value).outputPath)
    assertEquals(moved.toString(), store.load(task.taskId)?.outputPath)
    assertContentEquals(torrent.payloads[0],
      torrentFileSystem.read(moved / "f0") { readByteArray() })
    assertContentEquals(torrent.payloads[1],
      torrentFileSystem.read(moved / "f1") { readByteArray() })
    // Nothing stays where the files were, the task's hidden bookkeeping included.
    assertNull(torrentFileSystem.metadataOrNull(output))
    assertTrue(torrentFileSystem.list(root).none { it.name.startsWith(".ketch-") })

    // Where it is already, inside itself and onto something of its name are all refused.
    torrentFileSystem.createDirectories(root / "taken" / output.name)
    for (refused in listOf(folder, moved / "inner", root / "taken")) {
      val error = assertFailsWith<TorrentCommandException> {
        controller.relocate(task.taskId, refused.toString())
      }
      assertEquals(TorrentCommandError.INVALID_INPUT, error.error)
    }
    assertEquals(moved.toString(), task.outputPath)
    awaitStage("the task to be completed still", task) {
      task.state.first { it is DownloadState.Completed }
    }

    // The files are the task's own where they are now: removing it with its files deletes them.
    task.remove(deleteFiles = true)
    eventually("the moved files to be deleted") {
      torrentFileSystem.metadataOrNull(moved / "f0") == null
    }
    source.assertNoLeaks()
  }

  @Test
  fun relocate_completedV2Task_movesItsFilesAndOwnsThemThere() = harness {
    val store = MemoryTaskStore()
    val record = v2Record("v2-move", v2Fixture, root / "v2").let {
      it.copy(control = TaskControl(sharing = false))
    }
    store.save(record)
    val source = source(TorrentUploadPolicy.DISABLED)
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.tasks.value.single()
    val first = assertIs<DownloadState.Completed>(task.state.value)
    val controller = assertNotNull(ketch.controller(TorrentController))
    val folder = root / "elsewhere"

    controller.relocate(task.taskId, folder.toString())

    awaitStage("the check after the move", task) {
      task.state.first { it is DownloadState.Completed && it.completedAt != first.completedAt }
    }
    val moved = folder / "v2"
    assertEquals(moved.toString(), task.outputPath)
    assertContentEquals(v2Fixture.payloads[0],
      torrentFileSystem.read(moved / "a") { readByteArray() })
    assertNull(torrentFileSystem.metadataOrNull(root / "v2"))
    assertTrue(torrentFileSystem.list(root).none { it.name.startsWith(".ketch-") })
    assertTrue(source.decodeState(assertNotNull(store.load(task.taskId)?.sourceState)).relocated)

    task.remove(deleteFiles = true)
    eventually("the moved files to be deleted") {
      torrentFileSystem.metadataOrNull(moved / "a") == null
    }
    source.assertNoLeaks()
  }

  @Test
  fun relocate_taskRemovedWithItsFilesBeforeItRunsAgain_deletesThemAtTheNewPlace() = harness {
    val source = source(TorrentUploadPolicy.DISABLED)
    val store = MemoryTaskStore()
    // Moved while they were not running, as a paused task is: nothing has checked them since.
    val v1 = v1Record("v1-moved", MultiFileTorrent(listOf(40_000, 30_000)), root / "one",
      sharing = false)
    val v2 = v2Record("v2-moved", v2Fixture, root / "two")
      .let { it.copy(control = TaskControl(sharing = false)) }
    for (record in listOf(v1, v2)) {
      val from = checkNotNull(record.outputPath)
      val to = (root / "moved" / from.toPath().name).toString()
      val state = source.relocate(record.taskId, from, to, record.sourceState)
      store.save(record.copy(outputPath = to, sourceState = state))
    }
    val ketch = ketch(source, store)
    ketch.start()
    assertTrue(torrentFileSystem.exists(root / "moved" / "one" / "f0"))
    assertTrue(torrentFileSystem.exists(root / "moved" / "two" / "a"))

    for (task in ketch.tasks.value) task.remove(deleteFiles = true)

    eventually("the moved files to be deleted") {
      torrentFileSystem.metadataOrNull(root / "moved" / "one" / "f0") == null &&
        torrentFileSystem.metadataOrNull(root / "moved" / "two" / "a") == null
    }
    source.assertNoLeaks()
  }

  @Test
  fun relocate_downloadingTask_goesOnAtTheNewPlace() = harness {
    val source = source(TorrentUploadPolicy.DISABLED)
    val ketch = ketch(source)
    ketch.start()
    // Slow enough to still be downloading when it is moved.
    val task = ketch.download(request(0, 1).copy(speedLimit = SpeedLimit.of(2048)))
    eventually("the download to be under way") {
      task.outputPath != null && task.state.value is DownloadState.Downloading &&
        torrentFileSystem.metadataOrNull(output / "f0") != null
    }
    val folder = root / "later"

    assertNotNull(ketch.controller(TorrentController)).relocate(task.taskId, folder.toString())

    task.setSpeedLimit(SpeedLimit.Unlimited)
    task.awaitCompleted()
    val moved = folder / output.name
    assertEquals(moved.toString(), task.outputPath)
    assertContentEquals(torrent.payloads[0],
      torrentFileSystem.read(moved / "f0") { readByteArray() })
    assertContentEquals(torrent.payloads[1],
      torrentFileSystem.read(moved / "f1") { readByteArray() })
    assertNull(torrentFileSystem.metadataOrNull(output))
    source.assertNoLeaks()
  }

  @Test
  fun recheck_taskItsUserStoppedSeeding_staysStopped() = harness {
    val source = source()
    val store = MemoryTaskStore()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(0))
    val first = task.awaitCompleted()
    task.awaitSeeding(true)
    val controller = assertNotNull(ketch.controller(TorrentController))
    controller.setSeeding(task.taskId, false, ketch.context(task.taskId, "stop"))
    task.awaitSeeding(false)

    controller.recheck(task.taskId)

    awaitStage("the recheck to finish", task) {
      task.state.first { it is DownloadState.Completed && it.completedAt != first.completedAt }
    }
    // The upload setting still seeds finished torrents, but this one was told to stop.
    delay(300)
    assertFalse(assertIs<DownloadState.Completed>(task.state.value).sharing)
    assertNull(source.sessionOf(task.taskId))
    assertEquals(false, store.intent(task.taskId))
    source.assertNoLeaks()
  }

  @Test
  fun recheck_seedingTask_checksThenSeedsAgain() = harness {
    val source = source()
    val ketch = ketch(source)
    ketch.start()
    val task = ketch.download(request(0))
    val first = task.awaitCompleted()
    task.awaitSeeding(true)
    val before = assertNotNull(source.sessionOf(task.taskId))

    assertNotNull(ketch.controller(TorrentController)).recheck(task.taskId)

    awaitStage("the recheck to finish", task) {
      task.state.first {
        it is DownloadState.Completed && it.completedAt != first.completedAt && it.sharing
      }
    }
    // A session of its own checked the files; the seeder it replaced was not taken over.
    assertTrue(source.sessionOf(task.taskId) !== before)
    source.stopSeeding(task.taskId)
    source.assertNoLeaks()
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
  fun selectFiles_seedingShrink_appliesToTheSeedingSession() = harness {
    val source = source()
    val ketch = ketch(source)
    ketch.start()
    val task = ketch.download(request(0, 1))
    task.awaitCompleted()
    task.awaitSeeding(true)
    task.selectFiles(setOf("1"))
    val session = assertNotNull(source.sessionOf(task.taskId))
    assertEquals(setOf("1"), session.selectedFileIds)
    assertEquals(torrent.sizes[1].toLong(), session.totalBytes)
    val completed = assertIs<DownloadState.Completed>(task.state.value)
    assertTrue(completed.sharing)
    assertEquals(setOf(task.taskId), source.seedingTaskIds.value)
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
    assertFalse(task.awaitCompleted().sharing)
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
    assertFalse(task.awaitCompleted().sharing)
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
    assertFalse(assertIs<DownloadState.Completed>(restored.state.value).sharing)
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
    assertFalse(assertIs<DownloadState.Completed>(task.state.value).sharing)
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
    assertFalse(assertIs<DownloadState.Completed>(checkNotNull(tasks["newer"]).state.value).sharing)
    assertEquals(true, store.intent("newer"))
    assertEquals(true, store.intent("older"))
    source.stopSeeding("older")
    source.assertNoLeaks()
  }

  @Test
  fun queue_seederToldToStop_handsItsSlotToTheNextTaskMeantToSeed() = harness {
    val store = MemoryTaskStore()
    val older = v1Record("older", MultiFileTorrent(listOf(40_000, 30_000)), root / "older", 1_000)
    val newer = v1Record("newer", MultiFileTorrent(listOf(50_000, 20_000)), root / "newer", 2_000)
    store.save(newer)
    store.save(older)
    val source = source(maxActiveTorrents = 1)
    val ketch = ketch(source, store)
    ketch.start()
    val tasks = ketch.tasks.value.associateBy { it.taskId }
    checkNotNull(tasks["older"]).awaitSeeding(true)
    eventually("the walk to end") { source.seedingAvailability("newer") == SeedingOutcome.NO_SLOT }
    val controller = assertNotNull(ketch.controller(TorrentController))

    controller.setSeeding("older", false, ketch.context("older", "stop"))

    // The one that waited seeds without a restart; the one told to stop stays stopped.
    checkNotNull(tasks["newer"]).awaitSeeding(true)
    assertNull(source.sessionOf("older"))
    assertEquals(false, store.intent("older"))
    assertEquals(setOf("newer"), source.seedingTaskIds.value)
    source.stopSeeding("newer")
    source.assertNoLeaks()
  }

  @Test
  fun editTrackers_seedingTask_announcesToTheNewTrackers_andSavesThem() = harness {
    val store = MemoryTaskStore()
    val source = source()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(0))
    task.awaitCompleted()
    task.awaitSeeding(true)
    val controller = assertNotNull(ketch.controller(TorrentController))
    assertEquals(0, extraAnnounces.load())

    // The seeder opens again and announces to the tracker it was given in place of its own.
    val swapped = controller.editTrackers(task.taskId,
      TorrentTrackerEdit(add = listOf(EXTRA_TRACKER), remove = listOf("http://fixture:80")))
    assertEquals(listOf("http://extra.example:80"), swapped.map { it.endpoint })
    awaitStage("an announce to the added tracker", task) {
      while (extraAnnounces.load() == 0) delay(20)
    }
    task.awaitSeeding(true)

    // A tracker added later is a tier after it, and one it has already is not added again.
    val before = extraAnnounces.load()
    val left = controller.editTrackers(task.taskId,
      TorrentTrackerEdit(add = listOf(GatedSeeder.ANNOUNCE, EXTRA_TRACKER)))
    assertEquals(listOf("http://extra.example:80", "http://fixture:80"), left.map { it.endpoint })
    assertEquals(listOf(0, 1), left.map { it.tier })
    awaitStage("the seeder to announce again", task) {
      while (extraAnnounces.load() == before) delay(20)
    }
    task.awaitSeeding(true)
    val saved = source.decodeState(assertNotNull(store.load(task.taskId)?.sourceState))
    assertEquals(listOf(listOf(EXTRA_TRACKER), listOf(GatedSeeder.ANNOUNCE)), saved.trackerTiers)

    // A URL Ketch cannot announce to changes nothing.
    val refused = assertFailsWith<TorrentCommandException> {
      controller.editTrackers(task.taskId, TorrentTrackerEdit(add = listOf("wss://w.example/a")))
    }
    assertEquals(TorrentCommandError.INVALID_INPUT, refused.error)
    assertEquals(left.map { it.endpoint },
      controller.trackers(task.taskId)?.map { it.endpoint })

    source.stopSeeding(task.taskId)
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
    // Neither uploads resuming nor a switch to seeding walks the tasks again.
    source.setUploadsPaused(true)
    source.setUploadsPaused(false)
    source.setUploadPolicy(TorrentUploadPolicy.DISABLED)
    source.setUploadPolicy(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
    delay(500)
    assertFalse(assertIs<DownloadState.Completed>(task.state.value).sharing)
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

  @Test
  fun setUploadsPaused_seedingV1Task_stopsAndSeedsAgainOnResume() = harness {
    val source = source()
    val store = MemoryTaskStore()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(0, 1))
    task.awaitCompleted()
    task.awaitSeeding(true)
    eventually("the seeding intent to be saved") { store.intent(task.taskId) == true }

    source.setUploadsPaused(true)

    // The seeder finishes and frees its slot; the task stays meant to seed.
    task.awaitSeeding(false)
    source.assertNoLeaks()
    assertNull(source.sessionOf(task.taskId))
    assertEquals(true, store.intent(task.taskId))
    assertEquals(SeedingOutcome.UPLOADS_PAUSED, source.seedingAvailability(task.taskId))

    source.setUploadsPaused(false)

    task.awaitSeeding(true)
    assertEquals(TorrentSessionState.SEEDING, source.sessionOf(task.taskId)?.state?.value)
    source.stopSeeding(task.taskId)
    source.assertNoLeaks()
  }

  @Test
  fun setUploadsPaused_seedingV2Task_stopsAndSeedsAgainOnResume() = harness {
    val store = MemoryTaskStore()
    val record = v2Record("v2-seed", v2Fixture, root / "v2")
    store.save(record)
    val source = source()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.tasks.value.single()
    task.awaitSeeding(true)

    source.setUploadsPaused(true)

    task.awaitSeeding(false)
    source.assertNoLeaks()
    assertNull(source.sessionOf(task.taskId))
    assertEquals(true, store.intent(task.taskId))

    source.setUploadsPaused(false)

    task.awaitSeeding(true)
    assertIs<TorrentV2DownloadSession>(source.sessionOf(task.taskId))
    assertEquals(TorrentSessionState.SEEDING, source.sessionOf(task.taskId)?.state?.value)
    source.stopSeeding(task.taskId)
    task.awaitSeeding(false)
    source.assertNoLeaks()
  }

  @Test
  fun restore_startedPaused_waitsForUploadsToResume() = harness {
    val store = MemoryTaskStore()
    store.save(v1Record("kept", MultiFileTorrent(listOf(40_000, 30_000)), root / "kept"))
    val source = source(uploadsPaused = true)
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.tasks.value.single()
    delay(500)
    assertFalse(assertIs<DownloadState.Completed>(task.state.value).sharing)
    assertEquals(0, created.load())
    assertEquals(true, store.intent("kept"))

    source.setUploadsPaused(false)

    task.awaitSeeding(true)
    assertEquals(TorrentSessionState.SEEDING, source.sessionOf("kept")?.state?.value)
    source.stopSeeding("kept")
    source.assertNoLeaks()
  }

  @Test
  fun completion_whilePausedUnderSeed_savesIntentAndSeedsOnResume() = harness {
    val source = source(uploadsPaused = true)
    val store = MemoryTaskStore()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(0, 1))

    assertFalse(task.awaitCompleted().sharing)

    // The session finished instead of seeding, and the task is meant to seed once uploads resume.
    eventually("the seeding intent to be saved") { store.intent(task.taskId) == true }
    assertNull(source.sessionOf(task.taskId))
    source.assertNoLeaks()

    source.setUploadsPaused(false)

    task.awaitSeeding(true)
    source.stopSeeding(task.taskId)
    source.assertNoLeaks()
  }

  @Test
  fun restore_startedPaused_completedTaskWithoutIntent_isNotMarked() = harness {
    val store = MemoryTaskStore()
    store.save(v1Record("plain", MultiFileTorrent(listOf(40_000, 30_000)), root / "plain",
      sharing = null))
    store.save(v1Record("stopped", MultiFileTorrent(listOf(50_000, 20_000)), root / "stopped",
      sharing = false))
    val source = source(uploadsPaused = true)
    val ketch = ketch(source, store)
    ketch.start()
    delay(300)
    source.setUploadsPaused(false)
    delay(500)

    // Restored completions are no completions: neither task gained an intent or a session.
    assertNull(store.intent("plain"))
    assertEquals(false, store.intent("stopped"))
    for (task in ketch.tasks.value) {
      assertFalse(assertIs<DownloadState.Completed>(task.state.value).sharing)
      assertNull(source.sessionOf(task.taskId))
    }
    assertEquals(0, created.load())
    source.assertNoLeaks()
  }

  @Test
  fun setUploadPolicy_whileDownloadingThenSeed_seedsKeptIntentsLive() = harness {
    val store = MemoryTaskStore()
    store.save(v1Record("kept", MultiFileTorrent(listOf(40_000, 30_000)), root / "kept"))
    val source = source(TorrentUploadPolicy.WHILE_DOWNLOADING)
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.tasks.value.single()
    delay(300)
    assertFalse(assertIs<DownloadState.Completed>(task.state.value).sharing)

    source.setUploadPolicy(TorrentUploadPolicy.SEED_AFTER_COMPLETION)

    task.awaitSeeding(true)
    assertEquals(true, store.intent("kept"))
    source.stopSeeding("kept")
    source.assertNoLeaks()
  }

  @Test
  fun setSeeding_trueWhilePaused_keepsIntentAndSeedsOnResume() = harness {
    val source = source()
    val store = MemoryTaskStore()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(0))
    task.awaitCompleted()
    task.awaitSeeding(true)
    val controller = assertNotNull(ketch.controller(TorrentController))
    val stopped = controller.setSeeding(task.taskId, false, ketch.context(task.taskId, "stop"))
    task.awaitSeeding(false)
    source.setUploadsPaused(true)

    val started = controller.setSeeding(task.taskId, true,
      TorrentCommandContext("seed", stopped.revision))

    assertEquals(true, started.seeding)
    assertEquals(true, store.intent(task.taskId))
    assertNull(source.sessionOf(task.taskId))
    assertEquals(TorrentActivity.STOPPED, controller.snapshot(task.taskId)?.activity)

    source.setUploadsPaused(false)

    task.awaitSeeding(true)
    assertEquals(TorrentSessionState.SEEDING, source.sessionOf(task.taskId)?.state?.value)
    source.stopSeeding(task.taskId)
    source.assertNoLeaks()
  }

  private suspend fun Ketch.context(taskId: String, key: String): TorrentCommandContext {
    val snapshot = assertNotNull(assertNotNull(controller(TorrentController)).snapshot(taskId))
    return TorrentCommandContext(key, snapshot.revision)
  }

  @Test
  fun setSeeding_false_stopsTheSessionAndClearsIntent() = harness {
    val source = source()
    val store = MemoryTaskStore()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(0, 1))
    task.awaitCompleted()
    task.awaitSeeding(true)
    eventually("the seeding intent to be saved") { store.intent(task.taskId) == true }
    val controller = assertNotNull(ketch.controller(TorrentController))
    eventually("the snapshot to show seeding") {
      controller.snapshot(task.taskId)?.activity == TorrentActivity.SEEDING
    }

    val result = controller.setSeeding(task.taskId, false, ketch.context(task.taskId, "stop"))

    assertEquals(false, result.seeding)
    task.awaitSeeding(false)
    assertNull(source.sessionOf(task.taskId))
    assertEquals(false, store.intent(task.taskId))
    val snapshot = assertNotNull(controller.snapshot(task.taskId))
    assertEquals(TorrentActivity.STOPPED, snapshot.activity)
    assertTrue(snapshot.selectionComplete)
    source.assertNoLeaks()
  }

  @Test
  fun setSeeding_true_seedsAfterRecheck() = harness {
    val source = source()
    val store = MemoryTaskStore()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(0, 2))
    task.awaitCompleted()
    task.awaitSeeding(true)
    val controller = assertNotNull(ketch.controller(TorrentController))
    val stopped = controller.setSeeding(task.taskId, false, ketch.context(task.taskId, "stop"))
    task.awaitSeeding(false)
    val connects = networks.single().connects.load()

    val started = controller.setSeeding(task.taskId, true,
      TorrentCommandContext("seed", stopped.revision))

    assertEquals(true, started.seeding)
    assertTrue(started.revision.sequence > stopped.revision.sequence)
    task.awaitSeeding(true)
    assertEquals(TorrentSessionState.SEEDING, source.sessionOf(task.taskId)?.state?.value)
    assertEquals(true, store.intent(task.taskId))
    // An exact retry starts nothing more.
    assertEquals(started, controller.setSeeding(task.taskId, true,
      TorrentCommandContext("seed", stopped.revision)))
    assertTrue(networks.single().connects.load() >= connects)
    controller.setSeeding(task.taskId, false, TorrentCommandContext("again", started.revision))
    task.awaitSeeding(false)
    source.assertNoLeaks()
  }

  @Test
  fun setSeeding_policyOff_isDenied() = harness {
    val source = source(TorrentUploadPolicy.DISABLED)
    val store = MemoryTaskStore()
    val ketch = ketch(source, store)
    ketch.start()
    val task = ketch.download(request(1))
    assertFalse(task.awaitCompleted().sharing)
    val controller = assertNotNull(ketch.controller(TorrentController))
    assertFalse(controller.capabilities().supports(TorrentCapability.SEEDING))

    val error = assertFailsWith<TorrentCommandException> {
      controller.setSeeding(task.taskId, true, ketch.context(task.taskId, "seed"))
    }

    assertEquals(TorrentCommandError.POLICY_DENIED, error.error)
    assertNull(source.sessionOf(task.taskId))
    assertNull(store.intent(task.taskId))
    source.assertNoLeaks()
  }

  private companion object {
    /** A tracker no torrent of these tests names. */
    const val EXTRA_TRACKER = "http://extra.example/announce"
  }
}
