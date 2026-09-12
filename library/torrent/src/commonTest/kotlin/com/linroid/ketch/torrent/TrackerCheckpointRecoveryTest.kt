package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrackerCheckpointRecoveryTest {
  private val bytes = byteArrayOf(1, 2, 3, 4)
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
    "announce" to "https://old/announce", "info" to mapOf("name" to "seed",
      "length" to 4L, "piece length" to 4L, "pieces" to sha1Digest(bytes), "private" to 1L)
  )))
  private val next = TrackerConfiguration.prepare(listOf(listOf("https://new/announce")))
  private fun root(): Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-tracker-recovery-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
  private fun store(root: Path) = TorrentPieceStore(metadata, root / "seed", emptySet(), "recover")

  @Test
  fun newerDiskEditWinsOverTaskStoreWithoutTrustingVerifiedHints() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        for ((corrupt, sameRevision) in listOf(false to false, true to false, false to true)) {
          val root = root()
          val requests = MutableStateFlow<List<String>>(emptyList())
          val http = TorrentHttp(object : HttpEngine {
            override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
              error("Unused")
            override suspend fun download(
              url: String,
              range: LongRange?,
              headers: Map<String, String>,
              onData: suspend (ByteArray) -> Unit,
            ) {
              requests.value += url
              onData(Bencode.encode(mapOf("interval" to 3600L, "peers" to ByteArray(0))))
            }
            override fun close() = Unit
          })
          val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
            uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION), http = http)
          try {
            val initial = store(root)
            initial.initialize()
            assertTrue(initial.commit(0, bytes))
            val stale = if (sameRevision) initial.replaceTrackerConfiguration(next, 10, 11)
              else initial.persistCheckpoint(10, 11)
            if (sameRevision) initial.persistCheckpoint(20, 21)
            else initial.replaceTrackerConfiguration(next, 20, 21)
            if (corrupt) torrentFileSystem.write(root / "seed") { write(byteArrayOf(0, 0, 0, 0)) }
            engine.start()
            val session = engine.addTask(TorrentTaskSpec("recover", metadata,
              (root / "seed").toString(), emptySet(), resumeData = stale))
            session.resume()
            session.trackerStatus.first {
              it.singleOrNull()?.outcome == TrackerStatus.Outcome.SUCCEEDED
            }
            assertEquals(TrackerConfigurationSnapshot(next.tiers, 1),
              session.trackerConfiguration())
            assertTrue(requests.value.all { it.startsWith("https://new/") })
            assertEquals(20L, session.receivedBytes)
            assertEquals(21L, session.uploadedBytes)
            assertEquals(if (corrupt) 0L else 4L, session.downloadedBytes.value)
            if (corrupt) assertFalse(session.state.value == TorrentSessionState.SEEDING)
            session.pause()
            val saved = assertNotNull(TorrentCheckpoint.decode(
              assertNotNull(session.saveResumeData())))
            assertEquals(1L, saved.trackerRevision)
            assertEquals(next.tiers, assertNotNull(saved.trackerConfiguration).tiers)
          } finally {
            engine.stop()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
          assertEquals(0, engine.admittedSessionBytes)
        }
      }
    }
  }

  @Test
  fun failedRecoveryCannotOverwriteNewerCheckpointDuringShutdown() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val root = root()
        val initial = store(root)
        initial.initialize()
        val stale = initial.persistCheckpoint()
        val committed = assertNotNull(TorrentCheckpoint.decode(
          initial.replaceTrackerConfiguration(next)))
        val path = committed.files.single { it.path.toPath().name == "checkpoint" }.path.toPath()
        val disk = torrentFileSystem.read(path) { readByteArray() }
        val spec = TorrentTaskSpec("recover", metadata, (root / "seed").toString(), emptySet(),
          resumeData = stale)
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          maxSessionStateBytes = sessionStateWeight(spec).toInt()))
        try {
          engine.start()
          val session = engine.addTask(spec)
          session.resume()
          session.state.first { it == TorrentSessionState.STOPPED }
          assertTrue(assertNotNull(session.failure.value).message.orEmpty().contains("budget"))
          session.pause()
          assertEquals(null, session.saveResumeData())
          engine.stop()
          assertContentEquals(disk, torrentFileSystem.read(path) { readByteArray() })
        } finally {
          engine.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
        assertEquals(0, engine.admittedSessionBytes)
      }
    }
  }

  @Test
  fun checkpointDecodeAdmissionAndCallbackCancellationReturnAllCredit() = runTest {
    val root = root()
    try {
      val store = store(root)
      store.initialize()
      store.replaceTrackerConfiguration(next)
      val tiny = TorrentBufferBudget(1024)
      var called = false
      assertFailsWith<IllegalStateException> {
        store.withPersistedCheckpoint(tiny) { called = true }
      }
      assertFalse(called)
      assertEquals(0, tiny.allocated)
      val budget = TorrentBufferBudget(1024 * 1024)
      assertFailsWith<CancellationException> {
        store.withPersistedCheckpoint(budget) {
          assertTrue(budget.allocated > 0)
          assertEquals(1L, it.trackerRevision)
          throw CancellationException("Canceled checkpoint consumer")
        }
      }
      assertEquals(0, budget.allocated)
    } finally { torrentFileSystem.deleteRecursively(root, mustExist = false) }
  }

  @Test
  fun replacedCheckpointIsNotTrustedOrDeleted() = runTest {
    val root = root()
    try {
      val initial = store(root)
      initial.initialize()
      val saved = assertNotNull(TorrentCheckpoint.decode(initial.replaceTrackerConfiguration(next)))
      val path = saved.files.single { it.path.toPath().name == "checkpoint" }.path.toPath()
      torrentFileSystem.atomicMove(path, root / "held-inode")
      val foreign = byteArrayOf(5, 6, 7)
      torrentFileSystem.write(path) { write(foreign) }
      val restored = store(root)
      restored.initialize()
      val budget = TorrentBufferBudget(1024 * 1024)
      assertFailsWith<IllegalArgumentException> {
        restored.withPersistedCheckpoint(budget) { error("Unowned checkpoint reached consumer") }
      }
      assertContentEquals(foreign, torrentFileSystem.read(path) { readByteArray() })
      assertEquals(0, budget.allocated)
    } finally { torrentFileSystem.deleteRecursively(root, mustExist = false) }
  }

  @Test
  fun ownedCheckpointWithWrongTaskBindingIsRejectedBeforeConsumption() = runTest {
    val root = root()
    try {
      val store = store(root)
      store.initialize()
      val saved = assertNotNull(TorrentCheckpoint.decode(store.replaceTrackerConfiguration(next)))
      val path = saved.files.single { it.path.toPath().name == "checkpoint" }.path.toPath()
      torrentFileSystem.write(path) { write(saved.copy(taskId = "another").encode()) }
      val budget = TorrentBufferBudget(1024 * 1024)
      assertFailsWith<IllegalArgumentException> {
        store.withPersistedCheckpoint(budget) { error("Wrong task reached consumer") }
      }
      assertEquals(0, budget.allocated)
    } finally { torrentFileSystem.deleteRecursively(root, mustExist = false) }
  }
}
