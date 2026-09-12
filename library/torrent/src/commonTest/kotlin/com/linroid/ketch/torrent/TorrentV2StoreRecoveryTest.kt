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

  private fun store(root: Path, doc: TorrentV2Document = document) = TorrentV2PieceStore(
    doc, root / "payload", emptySet(), "task", TorrentBufferBudget(65_536), Semaphore(1)
  )

  private suspend fun saved(root: Path): TorrentV2Checkpoint {
    val original = store(root)
    original.initialize()
    assertTrue(original.commit(0, payload))
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
        changed(output = (root / "other").toString()), changed(selected = setOf("0")))) {
        assertFailsWith<IllegalArgumentException> { restored.restore(wrong) }
      }
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
      assertTrue(restored.commit(0, payload))
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
}
