package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionTrackerEditTest {
  private val old = listOf(listOf("https://old/announce"))
  private val next = listOf(listOf("https://next/announce"))
  private val bytes = byteArrayOf(1, 2, 3, 4)
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
    "announce" to "https://old/announce", "info" to mapOf("name" to "seed",
      "length" to 4L, "piece length" to 4L, "pieces" to sha1Digest(bytes), "private" to 1L)
  )))

  private class Provider : ForwardingFileSystem(torrentFileSystem) {
    var failMove = false
    var afterMove: (() -> Unit)? = null
    override fun atomicMove(source: Path, target: Path) {
      if (target.name == "checkpoint" && failMove) throw IOException("Injected rename failure")
      super.atomicMove(source, target)
      if (target.name == "checkpoint") afterMove?.invoke()
    }
  }

  private inner class Fixture(parent: CoroutineScope) {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-session-edit-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val provider = Provider()
    val state = TorrentBufferBudget(256 * 1024)
    val network = createTorrentNetwork()
    val store = TorrentPieceStore(metadata, root / "seed", emptySet(), "edited", provider)
    val discoveries = MutableStateFlow<List<List<List<String>>>>(emptyList())
    var cleanup: suspend () -> Unit = {}
    val session = KotlinTorrentSession(store, network, TorrentBufferBudget(1024 * 1024), parent,
      uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION,
      trackerConfigurationBudget = state,
      discover = { _, owner ->
        discoveries.value += listOf(owner.trackerTiers())
        try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup() } }
      },
    )
  }

  private suspend fun fixture(block: suspend Fixture.() -> Unit) =
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          val fixture = Fixture(this)
          torrentFileSystem.createDirectories(fixture.root)
          torrentFileSystem.write(fixture.root / "seed") { write(bytes) }
          try { fixture.block() } finally {
            fixture.session.close()
            fixture.network.close()
            torrentFileSystem.deleteRecursively(fixture.root, mustExist = false)
          }
          assertEquals(0, fixture.state.allocated)
        }
      }
    }

  @Test
  fun failedEditKeepsOldOwnerAndResumesOldDiscovery() = runTest {
    fixture {
      assertTrue(session.replaceTrackers(old))
      val retained = state.allocated
      session.resume()
      discoveries.first { it.size == 1 }
      provider.failMove = true
      assertFailsWith<IOException> { session.replaceTrackers(next) }
      discoveries.first { it.size == 2 }
      assertEquals(listOf(old, old), discoveries.value)
      assertEquals(retained, state.allocated)
      assertEquals(old, session.trackerTiers())
      provider.failMove = false
      assertTrue(session.replaceTrackers(next))
      discoveries.first { it.size == 3 }
      assertEquals(next, discoveries.value.last())
    }
  }

  @Test
  fun canceledCallerAfterCommitRetainsNewOwnerThroughPauseAndResume() = runTest {
    fixture {
      assertTrue(session.replaceTrackers(old))
      var operation: Deferred<Boolean>? = null
      provider.afterMove = { assertNotNull(operation).cancel() }
      operation = async(start = CoroutineStart.LAZY) { session.replaceTrackers(next) }
      operation.start()
      assertFailsWith<CancellationException> { operation.await() }
      provider.afterMove = null
      assertEquals(next, session.trackerTiers())
      assertTrue(state.allocated > 0)
      session.resume()
      discoveries.first { it.isNotEmpty() }
      assertEquals(next, discoveries.value.single())
      session.pause()
      val saved = assertNotNull(TorrentCheckpoint.decode(assertNotNull(session.saveResumeData())))
      assertEquals(next, assertNotNull(saved.trackerConfiguration).tiers)
      session.close()
      assertEquals(0, state.allocated)
      assertEquals(null, session.saveResumeData())
    }
  }

  @Test
  fun editSnapshotsInputAndRejectsAnotherWhileOldDiscoveryJoins() = runTest {
    fixture {
      assertTrue(session.replaceTrackers(old))
      session.resume()
      discoveries.first { it.isNotEmpty() }
      val joining = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      cleanup = { joining.complete(Unit); release.await() }
      val input = mutableListOf(mutableListOf("https://next/announce"))
      val operation = async { session.replaceTrackers(input) }
      try {
        joining.await()
        input[0][0] = "https://mutated/announce"
        val occupied = state.allocated
        assertFalse(session.replaceTrackers(emptyList()))
        assertEquals(occupied, state.allocated)
        assertEquals(1, discoveries.value.size)
      } finally { release.complete(Unit) }
      assertTrue(operation.await())
      discoveries.first { it.size == 2 }
      assertEquals(next, discoveries.value.last())
    }
  }

  @Test
  fun cancelDuringJoinRetainsCreditUntilCleanupThenPreservesOldConfiguration() = runTest {
    fixture {
      assertTrue(session.replaceTrackers(old))
      val retained = state.allocated
      session.resume()
      discoveries.first { it.isNotEmpty() }
      val joining = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      cleanup = { joining.complete(Unit); release.await() }
      val operation = async { session.replaceTrackers(next) }
      try {
        joining.await()
        operation.cancel()
        assertFalse(operation.isCompleted)
        assertTrue(state.allocated > retained)
        assertEquals(old, session.trackerTiers())
      } finally { release.complete(Unit) }
      assertFailsWith<CancellationException> { operation.await() }
      assertEquals(retained, state.allocated)
      assertEquals(old, session.trackerTiers())
      assertEquals(TorrentSessionState.PAUSED, session.state.value)
      session.resume()
      discoveries.first { it.size == 2 }
      assertEquals(old, discoveries.value.last())
    }
  }

  @Test
  fun checkpointWorkspaceAdmissionFailureReturnsConfigurationCreditWithoutPausing() = runTest {
    fixture {
      session.resume()
      discoveries.first { it.isNotEmpty() }
      val occupied = assertNotNull(state.reserve(state.capacity - 8192))
      try {
        assertFalse(session.replaceTrackers(next))
        assertEquals(state.capacity - 8192, state.allocated)
        assertEquals(1, discoveries.value.size)
        assertEquals(old, session.trackerTiers())
      } finally { occupied.close() }
    }
  }

  @Test
  fun rejectedAdmissionDoesNotPauseTheActiveSession() = runTest {
    fixture {
      session.resume()
      discoveries.first { it.isNotEmpty() }
      val occupied = assertNotNull(state.reserve(state.capacity))
      try {
        assertFalse(session.replaceTrackers(next))
        assertEquals(1, discoveries.value.size)
        assertEquals(old, session.trackerTiers())
      } finally { occupied.close() }
    }
  }

  @Test
  fun engineEditsRestartTrackersAndPreserveEmptyOverrideAcrossResume() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
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
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-engine-edit-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        torrentFileSystem.createDirectories(root)
        torrentFileSystem.write(root / "seed") { write(bytes) }
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION), http = http)
        try {
          engine.start()
          val session = engine.addTask(TorrentTaskSpec("edited", metadata,
            (root / "seed").toString(), emptySet()))
          val initial = engine.admittedSessionBytes
          session.resume()
          requests.first { it.any { url -> "event=started" in url } }
          assertTrue(session.replaceTrackers(next))
          requests.first { it.any { url -> url.startsWith("https://next/") } }
          val oldStop = requests.value.indexOfFirst {
            it.startsWith("https://old/") && "event=stopped" in it
          }
          val newStart = requests.value.indexOfFirst { it.startsWith("https://next/") }
          assertTrue(oldStop >= 0 && oldStop < newStart)
          assertTrue(engine.admittedSessionBytes > initial)
          assertTrue(session.replaceTrackers(emptyList()))
          session.pause()
          val count = requests.value.size
          session.resume()
          assertEquals(TorrentSessionState.SEEDING, session.state.first {
            it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
          }, session.failure.value?.stackTraceToString())
          session.pause()
          assertEquals(count, requests.value.size)
          val saved = assertNotNull(TorrentCheckpoint.decode(
            assertNotNull(session.saveResumeData())))
          assertEquals(emptyList(), assertNotNull(saved.trackerConfiguration).tiers)
          assertTrue(session.replaceTrackers(next))
          session.resume()
          requests.first { it.size > count }
        } finally {
          engine.stop()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
        assertEquals(0, engine.admittedSessionBytes)
      }
    }
  }
}
