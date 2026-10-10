package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TorrentV2StoreRecoveryTest {
  private val payload = byteArrayOf(1, 2, 3)
  private val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
    "meta version" to 2L, "piece length" to 16_384L,
    "file tree" to mapOf("dir" to mapOf("file" to mapOf("" to mapOf("length" to 3L,
      "pieces root" to sha256Digest(payload)))), "empty" to mapOf("" to mapOf("length" to 0L)))
  ), "piece layers" to emptyMap<String, Any>())))

  private fun root(): Path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-v2-recovery-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}").also {
    torrentFileSystem.createDirectory(it)
  }

  private fun store(
    root: Path,
    doc: TorrentV2Document = document,
    selected: Set<String> = emptySet(),
  ) = TorrentV2PieceStore(
    doc, root / "payload", selected, "task", TorrentBufferBudget(65_536), Semaphore(1)
  )

  private suspend fun saved(root: Path): TorrentV2Checkpoint {
    val original = store(root)
    original.initialize()
    assertEquals(CommitOutcome.VERIFIED, original.commit(0, payload))
    val checkpoint = original.checkpoint(TorrentContentCatalog(root / "catalog"),
      receivedBytes = 100, uploadedBytes = 50)
    original.close()
    return checkpoint
  }

  @Test
  fun newStoreResolvesCatalogButDoesNotTrustPersistedHintBits() = runTest {
    val root = root()
    try {
      val original = saved(root)
      val snapshot = TorrentV2Checkpoint.create(document, original.taskId, original.output,
        original.selected, original.owned, original.verifiedHint,
        layoutGeneration = 7, selectionGeneration = 9, receivedBytes = 100, uploadedBytes = 50)
      torrentFileSystem.write(root / "checkpoint") { write(snapshot.encode()) }
      val decoded = assertNotNull(TorrentV2Checkpoint.decode(
        torrentFileSystem.read(root / "checkpoint") { readByteArray() }))
      val catalog = TorrentContentCatalog(root / "catalog")
      val content = assertNotNull(catalog.get(decoded.identity))
      val restored = store(root, content)
      restored.restore(decoded)
      assertFalse(restored.completed())
      restored.initialize()
      assertFalse(restored.completed())
      assertContentEquals(booleanArrayOf(false), restored.verifiedPieces())
      assertFailsWith<IllegalStateException> { restored.read(0) }
      assertContentEquals(booleanArrayOf(true), restored.recheck())
      assertTrue(restored.completed())
      val read = restored.read(0)
      try { assertContentEquals(payload, read.bytes) } finally { read.close() }
      val next = restored.checkpoint(catalog)
      assertEquals(7L, next.layoutGeneration)
      assertEquals(9L, next.selectionGeneration)
      assertEquals(100L, next.receivedBytes)
      assertEquals(50L, next.uploadedBytes)
      assertFailsWith<IllegalArgumentException> { restored.checkpoint(catalog, receivedBytes = 99) }
      restored.cleanup()
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun failedBindingValidationLeavesTheStoreEmptyAndRetryable() = runTest {
    val root = root()
    try {
      val snapshot = saved(root)
      fun changed(task: String = "task", output: String = snapshot.output,
                  selected: Set<String> = snapshot.selected) = TorrentV2Checkpoint.create(
        document, task, output, selected, snapshot.owned, snapshot.verifiedHint
      )
      val restored = store(root)
      for (wrong in listOf(changed(task = "other"),
        changed(output = (root / "other").toString()))) {
        assertFailsWith<IllegalArgumentException> { restored.restore(wrong) }
      }
      // Another selection is no mismatch: the task may have changed its files since.
      val other = store(root)
      other.restore(changed(selected = setOf("0")))
      other.close()
      restored.restore(snapshot)
      restored.initialize()
      assertContentEquals(booleanArrayOf(true), restored.recheck())
      restored.cleanup()
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun replacedFilesAreNeitherAdoptedNorDeletedOnFailedRecovery() = runTest {
    val root = root()
    try {
      val snapshot = saved(root)
      val file = root / "payload" / "dir" / "file"
      torrentFileSystem.atomicMove(file, root / "moved")
      torrentFileSystem.write(file) { writeUtf8("foreign replacement") }
      val restored = store(root)
      assertFailsWith<IllegalArgumentException> { restored.restore(snapshot) }
      restored.cleanup()
      assertEquals("foreign replacement", torrentFileSystem.read(file) { readUtf8() })
      assertTrue(torrentFileSystem.exists(root / "payload"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun changedPayloadCannotRecoverAvailabilityFromCheckpointHints() = runTest {
    val root = root()
    try {
      val snapshot = saved(root)
      val file = root / "payload" / "dir" / "file"
      torrentFileSystem.openReadWrite(file, mustExist = true).use { handle ->
        handle.write(0, byteArrayOf(9), 0, 1)
      }
      val restored = store(root)
      restored.restore(snapshot)
      restored.initialize()
      assertContentEquals(booleanArrayOf(false), restored.recheck())
      assertFalse(restored.completed())
      assertFailsWith<IllegalStateException> { restored.read(0) }
      assertEquals(CommitOutcome.VERIFIED, restored.commit(0, payload))
      assertTrue(restored.completed())
      restored.cleanup()
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun recoveredOwnedFilesHaveExactLengthsBeforeCompletion() = runTest {
    val root = root()
    try {
      val snapshot = saved(root)
      val file = root / "payload" / "dir" / "file"
      val empty = root / "payload" / "empty"
      torrentFileSystem.openReadWrite(file, mustExist = true).use { handle ->
        handle.write(3, byteArrayOf(9), 0, 1)
      }
      torrentFileSystem.write(empty) { writeByte(9) }
      val restored = store(root)
      restored.restore(snapshot)
      restored.initialize()
      restored.recheck()
      assertTrue(restored.completed())
      assertEquals(3L, torrentFileSystem.metadata(file).size)
      assertEquals(0L, torrentFileSystem.metadata(empty).size)
      restored.cleanup()
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun restore_otherSelection_advancesGeneration() = runTest {
    val root = root()
    try {
      val snapshot = saved(root)
      assertEquals(setOf("0", "1"), snapshot.selected)
      val same = store(root)
      same.restore(snapshot)
      same.initialize()
      assertEquals(0L, same.checkpoint().selectionGeneration)
      same.close()
      val restored = store(root, selected = setOf("0"))
      restored.restore(snapshot)
      restored.initialize()
      assertContentEquals(booleanArrayOf(true), restored.recheck())
      val next = restored.checkpoint()
      assertEquals(1L, next.selectionGeneration)
      assertEquals(setOf("0"), next.selected)
      restored.cleanup()
      assertFalse(torrentFileSystem.exists(root / "payload"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun restore_deselectedFileDeleted_dropsTheRecord() = runTest {
    val root = root()
    try {
      val snapshot = saved(root)
      torrentFileSystem.delete(root / "payload" / "empty")
      // Only dir/file is selected now, so the deleted empty file's claim is simply dropped.
      val restored = store(root, selected = setOf("0"))
      restored.restore(snapshot)
      restored.initialize()
      assertContentEquals(booleanArrayOf(true), restored.recheck())
      assertTrue(restored.completed())
      val owned = restored.checkpoint().owned.map { it.components }
      assertFalse(listOf("empty") in owned)
      assertTrue(listOf("dir", "file") in owned)
      restored.cleanup()
      assertFalse(torrentFileSystem.exists(root / "payload"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun restore_selectedFileDeleted_stillFails() = runTest {
    val root = root()
    try {
      val snapshot = saved(root)
      torrentFileSystem.delete(root / "payload" / "dir" / "file")
      val restored = store(root, selected = setOf("0"))
      assertFailsWith<IllegalArgumentException> { restored.restore(snapshot) }
      restored.cleanup()
      // Nothing was adopted, so nothing was deleted.
      assertTrue(torrentFileSystem.exists(root / "payload" / "empty"))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }
}
