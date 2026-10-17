package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.SelectionUpdate
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.engine.TorrentSessionState
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Each run of a torrent task follows the task's session as Ketch runs it: the run pays its own
 * task limits, also on a session an earlier run opened, and the task shows the progress it
 * started with until the session describes this run.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentSourceRunTest {
  private object NoIo : FileAccessor {
    override suspend fun writeAt(offset: Long, data: ByteArray): Unit = error("Source owns I/O")
    override suspend fun flush(): Unit = error("Source owns I/O")
    override fun close() = Unit
    override suspend fun delete(): Unit = error("Source owns I/O")
    override suspend fun size(): Long = error("Source owns I/O")
    override suspend fun preallocate(size: Long): Unit = error("Source owns I/O")
  }

  /** A run of task [taskId] over [files] of [resolved], as Ketch would start it. */
  private fun context(
    taskId: String,
    resolved: ResolvedSource,
    output: Path,
    files: Set<String>,
    segments: List<Segment> = emptyList(),
    throttle: suspend (Int) -> Unit = {},
    globalThrottle: suspend (Int) -> Unit = {},
    selection: StateFlow<SelectionUpdate> = MutableStateFlow(SelectionUpdate(files, 0)),
    onProgress: suspend (Long, Long) -> Unit = { _, _ -> },
  ) = DownloadContext(
    taskId = taskId, url = resolved.url,
    request = DownloadRequest(resolved.url, selectedFileIds = files), fileAccessor = NoIo,
    segments = MutableStateFlow(segments), onProgress = onProgress, throttle = throttle,
    headers = emptyMap(), preResolved = resolved, outputPath = output.toString(),
    selection = selection, globalThrottle = globalThrottle,
  )

  /** The first report that differs from [previous], which the reports repeat until then. */
  private suspend fun ReceiveChannel<Pair<Long, Long>>.next(
    previous: Pair<Long, Long>,
  ): Pair<Long, Long> {
    // A pass every 200 ms: a few seconds of the same report means it is not changing.
    repeat(20) {
      val report = receive()
      if (report != previous) return report
    }
    fail("The task kept reporting $previous")
  }

  @Test
  fun download_takesOverASeeder_paysItsOwnTaskLimits() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        coroutineScope {
          val seeder = GatedSeeder()
          seeder.start(this)
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-source-run-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          torrentFileSystem.createDirectories(root)
          val engines = mutableListOf<KotlinTorrentEngine>()
          val source = TorrentDownloadSource(TorrentConfig(encryption = TorrentEncryption.DISABLED,
            dhtEnabled = false, uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION),
            seeder.http).also { source ->
            source.engineFactory = { config ->
              KotlinTorrentEngine(config, createTorrentNetwork(), TorrentHttp(seeder.http),
                listenHost = "127.0.0.1").also { engines += it }
            }
          }
          try {
            seeder.openAll()
            val torrent = seeder.torrent
            val resolved = source.resolveMetainfo(torrent.metainfo)
            val output = root / "out"
            // The first run downloads f0 under one per-task limit, then keeps seeding.
            val firstPaid = AtomicLong(0)
            val first = context("task", resolved, output, setOf("0"),
              throttle = { firstPaid.addAndFetch(it.toLong()) })
            source.download(first, null)
            val seeding = assertNotNull(source.sessionOf("task"))
            assertEquals(TorrentSessionState.SEEDING, seeding.state.value)
            val paidBefore = firstPaid.load()
            assertTrue(paidBefore > 0)
            // Its limit changed while it seeded: the run that adds f1 takes the seeder over.
            val state = assertNotNull(source.store(first))
            val secondPaid = AtomicLong(0)
            source.download(context("task", resolved, output, setOf("0", "1"),
              throttle = { secondPaid.addAndFetch(it.toLong()) }), state)
            assertSame(seeding, source.sessionOf("task"))
            assertContentEquals(torrent.payloads[1],
              torrentFileSystem.read(output / "f1") { readByteArray() })
            assertTrue(secondPaid.load() > 0, "the new run's limits were never paid")
            assertEquals(paidBefore, firstPaid.load(), "the finished run's limits were paid")
            source.stopSeeding("task")
            source.assertNoLeaks()
          } finally {
            source.close()
            engines.forEach { it.stop() }
            seeder.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
          engines.forEach { it.assertNoLeaks() }
        }
      }
    }
  }

  /** A source over a fake engine, which runs whatever [FakeTorrentEngine.addTorrentResult] is. */
  private fun fakeSource(engine: FakeTorrentEngine, policy: TorrentUploadPolicy) =
    TorrentDownloadSource(TorrentConfig(dhtEnabled = false, uploadPolicy = policy))
      .also { it.engineFactory = { engine } }

  @Test
  fun download_whileTheSessionChecksItsFiles_keepsTheRestoredProgress() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = fakeSource(engine, TorrentUploadPolicy.DISABLED)
        try {
          val torrent = MultiFileTorrent()
          val resolved = source.resolveMetainfo(torrent.metainfo)
          val hash = torrent.metadata.infoHash.hex
          val all = setOf("0", "1", "2")
          val session = FakeTorrentSession(hash)
          session.stateOnResume = TorrentSessionState.CHECKING_FILES
          engine.addTorrentResult = session
          // What the task saved: f0 and part of f1 verified.
          val restored = listOf(Segment(0, 0, 99_999, 100_000),
            Segment(1, 100_000, 169_999, 35_000), Segment(2, 170_000, 256_143))
          val state = TorrentSourceState(hash, torrent.sizeOf(0, 1, 2), "", all, "unused",
            encodeBase64(torrent.metainfo), version = 2, privacy = TorrentDiscoveryPrivacy.PUBLIC)
          val reports = Channel<Pair<Long, Long>>(Channel.UNLIMITED)
          val context = context("task", resolved, "unused".toPath(), all, segments = restored,
            onProgress = { downloaded, total -> reports.send(downloaded to total) })
          val run = launch { source.download(context, state) }
          // A check starts from nothing: the task keeps what it had, which is also what is saved.
          repeat(2) { assertEquals(135_000L to torrent.sizeOf(0, 1, 2), reports.receive()) }
          assertEquals(restored, context.segments.value)
          session.finish()
          run.join()
          source.assertNoLeaks()
        } finally { source.close() }
      }
    }
  }

  @Test
  fun download_takesOverASeederForNewFiles_reportsTheNewSelectionFirst() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = fakeSource(engine, TorrentUploadPolicy.SEED_AFTER_COMPLETION)
        try {
          val torrent = MultiFileTorrent()
          val resolved = source.resolveMetainfo(torrent.metainfo)
          val session = FakeTorrentSession(torrent.metadata.infoHash.hex)
          session.selectedFileIds = setOf("0")
          engine.addTorrentResult = session
          // The first run downloads f0 and keeps seeding it.
          val first = context("task", resolved, "unused".toPath(), setOf("0"))
          val seeds = launch {
            eventually("the session to resume") { session.resumed }
            session.seed()
          }
          source.download(first, null)
          seeds.join()
          val state = assertNotNull(source.store(first))
          // Ketch reopens the task with f1 added, f0 counted as done, and the seeder takes the
          // new selection only once its sidecar pieces are copied.
          val gate = CompletableDeferred<Unit>()
          session.changeSelectionGate = gate
          val planned = listOf(Segment(0, 0, 99_999, 100_000), Segment(1, 100_000, 169_999))
          val reports = Channel<Pair<Long, Long>>(Channel.UNLIMITED)
          val run = async {
            source.download(context("task", resolved, "unused".toPath(), setOf("0", "1"),
              segments = planned, onProgress = { downloaded, total ->
                reports.send(downloaded to total)
              }), state)
          }
          assertEquals(torrent.sizeOf(0) to torrent.sizeOf(0, 1), reports.receive())
          gate.complete(Unit)
          run.await()
          assertEquals(listOf(setOf("0", "1")), session.selectionChanges)
          source.stopSeeding("task")
          source.assertNoLeaks()
        } finally { source.close() }
      }
    }
  }

  @Test
  fun download_selectionChangesWhileTheSessionChecks_showsTheNewSelection() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = fakeSource(engine, TorrentUploadPolicy.DISABLED)
        try {
          val torrent = MultiFileTorrent()
          val resolved = source.resolveMetainfo(torrent.metainfo)
          val hash = torrent.metadata.infoHash.hex
          val all = setOf("0", "1", "2")
          val session = FakeTorrentSession(hash)
          session.stateOnResume = TorrentSessionState.CHECKING_FILES
          engine.addTorrentResult = session
          val restored = listOf(Segment(0, 0, 99_999, 100_000),
            Segment(1, 100_000, 169_999, 35_000), Segment(2, 170_000, 256_143))
          val state = TorrentSourceState(hash, torrent.sizeOf(0, 1, 2), "", all, "unused",
            encodeBase64(torrent.metainfo), version = 2, privacy = TorrentDiscoveryPrivacy.PUBLIC)
          val selection = MutableStateFlow(SelectionUpdate(all, 0))
          val reports = Channel<Pair<Long, Long>>(Channel.UNLIMITED)
          val context = context("task", resolved, "unused".toPath(), all, segments = restored,
            selection = selection,
            onProgress = { downloaded, total -> reports.send(downloaded to total) })
          val run = launch { source.download(context, state) }
          val everything = 135_000L to torrent.sizeOf(0, 1, 2)
          assertEquals(everything, reports.receive())
          // f0 is unchecked while the session checks, which starts its check again: the task
          // shows f1 and f2 as they were, in the size Ketch gave the selection.
          selection.value = SelectionUpdate(setOf("1", "2"), 1)
          val narrowed = 35_000L to torrent.sizeOf(1, 2)
          val narrowedSegments = listOf(Segment(1, 0, 69_999, 35_000), Segment(2, 70_000, 156_143))
          assertEquals(narrowed, reports.next(everything))
          assertEquals(narrowedSegments, context.segments.value)
          repeat(2) { assertEquals(narrowed, reports.receive()) }
          // Checked again, f0 shows the progress it had.
          selection.value = SelectionUpdate(all, 2)
          assertEquals(everything, reports.next(narrowed))
          assertEquals(restored, context.segments.value)
          // A pause keeps the progress of the files the task follows, which Ketch saves.
          selection.value = SelectionUpdate(setOf("1", "2"), 3)
          assertEquals(narrowed, reports.next(everything))
          run.cancel()
          run.join()
          assertEquals(narrowedSegments, context.segments.value)
          assertEquals(listOf(setOf("1", "2"), all, setOf("1", "2")), session.selectionChanges)
          source.assertNoLeaks()
        } finally { source.close() }
      }
    }
  }

  @Test
  fun download_runAgainForANewSelection_showsItsFilesFromTheStart() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = fakeSource(engine, TorrentUploadPolicy.DISABLED)
        try {
          val torrent = MultiFileTorrent()
          val resolved = source.resolveMetainfo(torrent.metainfo)
          val hash = torrent.metadata.infoHash.hex
          val all = setOf("0", "1", "2")
          val session = FakeTorrentSession(hash)
          session.stateOnResume = TorrentSessionState.CHECKING_FILES
          engine.addTorrentResult = session
          val state = TorrentSourceState(hash, torrent.sizeOf(0, 1, 2), "", all, "unused",
            encodeBase64(torrent.metainfo), version = 2, privacy = TorrentDiscoveryPrivacy.PUBLIC)
          // f0 was unchecked as the last run finished, so Ketch runs the task again with that
          // run's context, whose segments still describe every file.
          val previous = listOf(Segment(0, 0, 99_999, 100_000),
            Segment(1, 100_000, 169_999, 35_000), Segment(2, 170_000, 256_143))
          val reports = Channel<Pair<Long, Long>>(Channel.UNLIMITED)
          val context = context("task", resolved, "unused".toPath(), all, segments = previous,
            selection = MutableStateFlow(SelectionUpdate(setOf("1", "2"), 1)),
            onProgress = { downloaded, total -> reports.send(downloaded to total) })
          val run = launch { source.download(context, state) }
          assertEquals(35_000L to torrent.sizeOf(1, 2), reports.receive())
          assertEquals(listOf(Segment(1, 0, 69_999, 35_000), Segment(2, 70_000, 156_143)),
            context.segments.value)
          session.finish()
          run.join()
          source.assertNoLeaks()
        } finally { source.close() }
      }
    }
  }

  @Test
  fun download_waitingForAnEngineSlot_showsItsOwnFiles() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.DISABLED, maxActiveTorrents = 1))
          .also { it.engineFactory = { engine } }
        try {
          // Another torrent holds the only engine slot.
          val busy = MultiFileTorrent(sizes = listOf(50_000, 50_000))
          val busySession = FakeTorrentSession(busy.metadata.infoHash.hex)
          engine.addTorrentResult = busySession
          val busyResolved = source.resolveMetainfo(busy.metainfo)
          val running = CompletableDeferred<Unit>()
          val busyRun = launch {
            source.download(context("busy", busyResolved, "unused".toPath(), setOf("0", "1"),
              onProgress = { _, _ -> running.complete(Unit) }), null)
          }
          running.await()
          val torrent = MultiFileTorrent()
          val resolved = source.resolveMetainfo(torrent.metainfo)
          val hash = torrent.metadata.infoHash.hex
          val all = setOf("0", "1", "2")
          val state = TorrentSourceState(hash, torrent.sizeOf(0, 1, 2), "", all, "unused",
            encodeBase64(torrent.metainfo), version = 2, privacy = TorrentDiscoveryPrivacy.PUBLIC)
          // Run again for a selection without f0, with the last run's segments over every file.
          val previous = listOf(Segment(0, 0, 99_999, 100_000),
            Segment(1, 100_000, 169_999, 35_000), Segment(2, 170_000, 256_143))
          val reports = Channel<Pair<Long, Long>>(Channel.UNLIMITED)
          val context = context("task", resolved, "unused".toPath(), all, segments = previous,
            selection = MutableStateFlow(SelectionUpdate(setOf("1", "2"), 1)),
            onProgress = { downloaded, total -> reports.send(downloaded to total) })
          val waiting = launch { source.download(context, state) }
          assertEquals(35_000L to torrent.sizeOf(1, 2), reports.receive())
          assertEquals(listOf(Segment(1, 0, 69_999, 35_000), Segment(2, 70_000, 156_143)),
            context.segments.value)
          waiting.cancelAndJoin()
          busySession.finish()
          busyRun.join()
          source.assertNoLeaks()
        } finally { source.close() }
      }
    }
  }

  @Test
  fun download_lendsItsSessionToSeed_leavesItTheGlobalLimitOnly() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val engine = FakeTorrentEngine()
        val source = fakeSource(engine, TorrentUploadPolicy.SEED_AFTER_COMPLETION)
        try {
          val torrent = MultiFileTorrent()
          val resolved = source.resolveMetainfo(torrent.metainfo)
          val session = FakeTorrentSession(torrent.metadata.infoHash.hex)
          engine.addTorrentResult = session
          val taskPaid = AtomicLong(0)
          val globalPaid = AtomicLong(0)
          val taskLimit: suspend (Int) -> Unit = { taskPaid.addAndFetch(it.toLong()) }
          val globalLimit: suspend (Int) -> Unit = { globalPaid.addAndFetch(it.toLong()) }
          val context = context("task", resolved, "unused".toPath(), setOf("0"),
            throttle = taskLimit, globalThrottle = globalLimit)
          val seeds = launch {
            eventually("the session to resume") { session.resumed }
            assertSame(taskLimit, session.taskThrottle)
            session.seed()
          }
          source.download(context, null)
          seeds.join()
          assertSame(session, source.sessionOf("task"))
          // The task's limit ended with the run; should the seeder fetch a piece again, it still
          // pays Ketch's global one.
          assertSame(globalLimit, session.taskThrottle)
          source.stopSeeding("task")
          source.assertNoLeaks()
        } finally { source.close() }
      }
    }
  }
}
