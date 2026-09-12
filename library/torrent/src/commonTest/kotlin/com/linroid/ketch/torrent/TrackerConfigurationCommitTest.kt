package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class TrackerConfigurationCommitTest {
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "file", "length" to 1L, "piece length" to 1L,
    "pieces" to sha1Digest(byteArrayOf(1))
  ))))
  private val old = TrackerConfiguration.prepare(listOf(listOf("https://old/announce")))
  private val next = TrackerConfiguration.prepare(listOf(listOf("https://next/announce")))

  private class Provider : ForwardingFileSystem(torrentFileSystem) {
    var beforeMove: ((Path, Path) -> Unit)? = null
    var afterMove: (() -> Unit)? = null
    override fun atomicMove(source: Path, target: Path) {
      if (target.name == "checkpoint") beforeMove?.invoke(source, target)
      super.atomicMove(source, target)
      if (target.name == "checkpoint") afterMove?.invoke()
    }
  }

  private fun root(): Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-tracker-commit-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"

  private fun checkpoint(bytes: ByteArray): TorrentCheckpoint =
    assertNotNull(TorrentCheckpoint.decode(bytes))

  @Test
  fun replacementIsPersistedAndOrdinaryCheckpointsRetainIt() = runTest {
    val root = root()
    try {
      val store = TorrentPieceStore(metadata, root / "file", emptySet(), "test")
      store.initialize()
      val saved = checkpoint(store.replaceTrackerConfiguration(next, 123, 456))
      assertEquals(next.tiers, assertNotNull(saved.trackerConfiguration).tiers)
      assertEquals(123L, saved.receivedBytes)
      assertEquals(456L, saved.uploadedBytes)
      val path = saved.files.single { it.path.toPath().name == "checkpoint" }.path.toPath()
      val disk = checkpoint(torrentFileSystem.read(path) { readByteArray() })
      assertEquals(next.tiers, assertNotNull(disk.trackerConfiguration).tiers)
      val repeated = checkpoint(store.persistCheckpoint(124, 457))
      assertEquals(next.tiers, assertNotNull(repeated.trackerConfiguration).tiers)
      assertEquals(next.tiers, assertNotNull(store.currentTrackerConfiguration()).tiers)
    } finally { torrentFileSystem.deleteRecursively(root, mustExist = false) }
  }

  @Test
  fun failedRenamePreservesOldConfigurationAndCheckpointThenCanRetry() = runTest {
    val root = root()
    try {
      val provider = Provider()
      val store = TorrentPieceStore(metadata, root / "file", emptySet(), "test", provider)
      store.initialize()
      store.replaceTrackerConfiguration(old)
      var previous: ByteArray? = null
      var path: Path? = null
      provider.beforeMove = { staged, target ->
        path = target
        previous = torrentFileSystem.read(target) { readByteArray() }
        val candidate = checkpoint(torrentFileSystem.read(staged) { readByteArray() })
        assertEquals(next.tiers, assertNotNull(candidate.trackerConfiguration).tiers)
        throw IOException("Injected checkpoint rename failure")
      }
      assertFailsWith<IOException> { store.replaceTrackerConfiguration(next) }
      assertEquals(old.tiers, assertNotNull(store.currentTrackerConfiguration()).tiers)
      assertContentEquals(assertNotNull(previous),
        torrentFileSystem.read(assertNotNull(path)) { readByteArray() })
      provider.beforeMove = null
      store.replaceTrackerConfiguration(next)
      assertEquals(next.tiers, assertNotNull(store.currentTrackerConfiguration()).tiers)
    } finally { torrentFileSystem.deleteRecursively(root, mustExist = false) }
  }

  @Test
  fun cancellationAfterRenameLeavesTheCommittedConfigurationQueryable() = runTest {
    val root = root()
    try {
      val provider = Provider()
      val store = TorrentPieceStore(metadata, root / "file", emptySet(), "test", provider)
      store.initialize()
      store.replaceTrackerConfiguration(old)
      var operation: Deferred<ByteArray>? = null
      provider.afterMove = { assertNotNull(operation).cancel() }
      operation = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
        store.replaceTrackerConfiguration(next)
      }
      operation.start()
      assertFailsWith<CancellationException> { operation.await() }
      provider.afterMove = null
      assertEquals(next.tiers, assertNotNull(store.currentTrackerConfiguration()).tiers)
      val snapshot = store.checkpoint()
      val path = snapshot.files.single { it.path.toPath().name == "checkpoint" }.path.toPath()
      val committed = checkpoint(torrentFileSystem.read(path) { readByteArray() })
      assertEquals(next.tiers, assertNotNull(committed.trackerConfiguration).tiers)
    } finally { torrentFileSystem.deleteRecursively(root, mustExist = false) }
  }
}
