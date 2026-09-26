package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentSessionAdmissionTest {
  @Test
  fun countAndMemoryLimitsApplyTogetherWithoutConsumingCreditOnFailure() {
    val metadata = fixture("a")
    val spec = TorrentTaskSpec("task", metadata, "/tmp/admission/a", emptySet())
    val size = sessionStateWeight(spec).toInt()
    val budget = TorrentBufferBudget(size)
    val config = TorrentConfig(maxPiecesPerTorrent = 1, maxFilesPerTorrent = 1)
    assertFailsWith<IllegalArgumentException> {
      admitSession(spec.copy(metadata = metadata.copy(pieceHashes = ByteArray(40))), config, budget)
    }
    assertFailsWith<IllegalArgumentException> {
      admitSession(spec.copy(metadata = metadata.copy(files = metadata.files + metadata.files)),
        config, budget)
    }
    assertFailsWith<IllegalStateException> {
      admitSession(spec.copy(resumeData = ByteArray(size)), config, budget)
    }
    assertEquals(0, budget.allocated)
    val lease = admitSession(spec, config, budget)
    assertEquals(size, budget.allocated)
    assertFailsWith<IllegalStateException> { admitSession(spec, config, budget) }
    lease.close()
    assertEquals(0, budget.allocated)
  }

  @Test
  fun sessionStateWeight_doesNotScaleWithPieceLength() {
    val metadata = fixture("a")
    val small = TorrentTaskSpec("task", metadata, "/tmp/admission/a", emptySet())
    val large = small.copy(metadata = metadata.copy(pieceLength = 16L * 1024 * 1024,
      totalBytes = 16L * 1024 * 1024))
    // Piece buffers are charged to the transfer budget, never to shared session state.
    assertEquals(sessionStateWeight(small), sessionStateWeight(large))
    val config = TorrentConfig()
    assertTrue(sessionStateWeight(large) * config.maxActiveTorrents <= config.maxSessionStateBytes)
  }

  @Test
  fun engineRejectsBeforeCreatingStorageAndReturnsCreditOnFailedConstruction() = runTest {
    withContext(Dispatchers.Default) {
      val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "admission-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
      val metadata = fixture("a")
      val spec = TorrentTaskSpec("valid", metadata, (root / "payload").toString(), emptySet())
      val size = sessionStateWeight(spec).toInt()
      val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false, maxSessionStateBytes = size))
      try {
        engine.start()
        assertFailsWith<IllegalArgumentException> { engine.addTask(spec.copy(taskId = "!")) }
        assertEquals(0, engine.admittedSessionBytes)
        assertFalse(FileSystem.SYSTEM.exists(root))
        val session = engine.addTask(spec)
        assertEquals(size, engine.admittedSessionBytes)
        val second = spec.copy(taskId = "other", metadata = fixture("b"))
        assertFailsWith<IllegalStateException> { engine.addTask(second) }
        assertFalse(FileSystem.SYSTEM.exists(root))
        session.pause()
        assertEquals(size, engine.admittedSessionBytes) // Paused sessions still own their indexes.
        engine.removeTorrent(metadata.infoHash.hex, false)
        assertEquals(0, engine.admittedSessionBytes)
        engine.addTask(second)
        assertTrue(engine.admittedSessionBytes > 0)
      } finally {
        engine.stop()
        FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
      }
      assertEquals(0, engine.admittedSessionBytes)
    }
  }

  @Test
  fun nonSuspendingEngineCloseEventuallyReturnsSessionCredit() = runTest {
    withContext(Dispatchers.Default) {
      val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false))
      try {
        engine.start()
        engine.addTask(TorrentTaskSpec("task", fixture("a"),
          (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "admission-close").toString(), emptySet()))
        assertTrue(engine.admittedSessionBytes > 0)
        engine.close()
        withTimeout(5000) {
          while (engine.admittedSessionBytes != 0) delay(1)
        }
      } finally { engine.stop() }
    }
  }

  @Test
  fun failedDeletionRemainsChargedAndCannotSilentlySucceedOnRetry() = runTest {
    withContext(Dispatchers.Default) {
      val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "admission-removal-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
      val metadata = fixture("a")
      val output = (root / "payload").toString()
      val checkpoint = TorrentCheckpoint("different-task", metadata, output, emptySet(),
        BooleanArray(1), emptyList(), emptyList()).encode()
      val spec = TorrentTaskSpec("task", metadata, output, emptySet(), resumeData = checkpoint)
      val size = sessionStateWeight(spec).toInt()
      val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false, maxSessionStateBytes = size))
      try {
        engine.start()
        engine.addTask(spec)
        repeat(2) {
          assertFailsWith<IllegalArgumentException> {
            engine.removeTorrent(metadata.infoHash.hex, deleteFiles = true)
          }
          assertEquals(size, engine.admittedSessionBytes)
        }
        val other = TorrentTaskSpec("other", fixture("b"), (root / "other").toString(), emptySet())
        assertFailsWith<IllegalStateException> { engine.addTask(other) }
        assertFalse(FileSystem.SYSTEM.exists(root))
        engine.removeTorrent(metadata.infoHash.hex, deleteFiles = false)
        assertEquals(0, engine.admittedSessionBytes)
        engine.addTask(other)
      } finally {
        engine.stop()
        FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
      }
      assertEquals(0, engine.admittedSessionBytes)
    }
  }

  @Test
  fun closedRuntimeLedgerRejectsNewAdmissionAndReturnsCreditOnce() {
    val config = TorrentConfig()
    val budget = TorrentBufferBudget(config.maxSessionStateBytes)
    val ledger = TorrentAdmissionLedger(budget)
    val spec = TorrentTaskSpec("task", fixture("a"), "/tmp/admission-ledger", emptySet())
    val lease = ledger.admit(spec, config)
    assertTrue(budget.allocated > 0)
    ledger.close()
    ledger.release(lease)
    ledger.close()
    assertEquals(0, budget.allocated)
    assertFailsWith<IllegalStateException> { ledger.admit(spec, config) }
    assertEquals(0, budget.allocated)
  }

  private fun fixture(name: String): TorrentMetadata = TorrentMetadata.fromBencode(
    Bencode.encode(mapOf("info" to mapOf(
      "name" to name, "length" to 1L, "piece length" to 1L,
      "pieces" to sha1Digest(byteArrayOf(1))
    )))
  )
}
