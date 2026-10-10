package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentV2CreationRecoveryTest {
  private val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
    "meta version" to 2L, "piece length" to 16_384L,
    "file tree" to mapOf(
      "a" to mapOf("" to mapOf("length" to 1L, "pieces root" to sha256Digest(byteArrayOf(1)))),
      "b" to mapOf("" to mapOf("length" to 1L, "pieces root" to sha256Digest(byteArrayOf(2))))
    )
  ), "piece layers" to emptyMap<String, Any>())))

  private fun root(): Path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-creation-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}").also {
    torrentFileSystem.createDirectory(it)
  }

  private fun store(
    root: Path,
    task: String = "task",
    provider: FileSystem = torrentFileSystem,
    selected: Set<String> = emptySet(),
    legacy: List<Set<String>> = emptyList(),
  ) = TorrentV2PieceStore(document, root / "payload", selected, task, TorrentBufferBudget(65_536),
    Semaphore(1), provider, root / "creation", legacy)

  private fun temporaryFiles(root: Path): List<Path> =
    torrentFileSystem.list(root).filter { it.name.startsWith(".creation-") }

  @Test
  fun recoversCreatedFilesBeforeAnyCheckpointAndRechecksPayload() = runTest {
    val root = root()
    try {
      val original = store(root)
      original.initialize()
      original.commit(0, byteArrayOf(1))
      original.close()
      val recovered = store(root)
      recovered.initialize()
      assertContentEquals(booleanArrayOf(false, false), recovered.verifiedPieces())
      assertContentEquals(booleanArrayOf(true, false), recovered.recheck())
      assertFalse(recovered.completed())
      assertEquals(CommitOutcome.VERIFIED, recovered.commit(1, byteArrayOf(2)))
      assertTrue(recovered.completed())
      recovered.cleanup()
      assertFalse(torrentFileSystem.exists(root / "payload"))
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun recoversPartialInitializationAndCreatesOnlyUncreatedFiles() = runTest {
    val root = root()
    try {
      val provider = object : ForwardingFileSystem(torrentFileSystem) {
        override fun openReadWrite(
          file: Path,
          mustCreate: Boolean,
          mustExist: Boolean,
        ): FileHandle {
          if (file.name == "b") throw IOException("Injected interruption before creation")
          return super.openReadWrite(file, mustCreate, mustExist)
        }
      }
      val original = store(root, provider = provider)
      assertFailsWith<IOException> { original.initialize() }
      original.close()
      torrentFileSystem.write(root / "payload" / "a") { writeByte(1) }
      val recovered = store(root)
      recovered.initialize()
      assertContentEquals(booleanArrayOf(true, false), recovered.recheck())
      recovered.cleanup()
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun tornFinalAppendIsDiscardedButCompleteCorruptionIsRejected() = runTest {
    val root = root()
    try {
      val original = store(root)
      original.initialize()
      original.close()
      val log = root / "creation"
      val complete = torrentFileSystem.read(log) { readByteArray() }
      torrentFileSystem.openReadWrite(log, mustExist = true).use { handle ->
        val tail = Buffer().writeInt(128).writeByte(1).readByteArray()
        handle.write(complete.size.toLong(), tail, 0, tail.size)
      }
      val recovered = store(root)
      recovered.initialize()
      recovered.close()
      assertContentEquals(complete, torrentFileSystem.read(log) { readByteArray() })
      val corrupt = complete.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
      torrentFileSystem.write(log) { write(corrupt) }
      val invalid = store(root)
      assertFailsWith<IllegalArgumentException> { invalid.initialize() }
      invalid.cleanup()
      assertTrue(torrentFileSystem.exists(root / "payload" / "a"))
      assertContentEquals(corrupt, torrentFileSystem.read(log) { readByteArray() })
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun failedOwnershipAppendCanRetryLiveButRestartPreservesUnclaimedPaths() = runTest {
    for (restart in listOf(false, true)) {
      val root = root()
      try {
        var fail = true
        val provider = object : ForwardingFileSystem(torrentFileSystem) {
          override fun openReadWrite(
            file: Path,
            mustCreate: Boolean,
            mustExist: Boolean,
          ): FileHandle {
            if (file.name == "creation" && mustExist && fail) {
              fail = false
              throw IOException("Injected failure before ownership append")
            }
            return super.openReadWrite(file, mustCreate, mustExist)
          }
        }
        val original = store(root, provider = provider)
        assertFailsWith<IOException> { original.initialize() }
        assertTrue(torrentFileSystem.exists(root / "payload"))
        if (restart) {
          original.close()
          val recovered = store(root)
          assertFailsWith<IOException> { recovered.initialize() }
          recovered.cleanup()
          assertTrue(torrentFileSystem.exists(root / "payload"))
        } else {
          original.initialize()
          original.close()
          val recovered = store(root)
          recovered.initialize()
          recovered.cleanup()
          assertFalse(torrentFileSystem.exists(root / "payload"))
        }
      } finally {
        torrentFileSystem.deleteRecursively(root)
      }
    }
  }

  @Test
  fun foreignTaskAndReplacedPayloadClaimsCannotBeAdoptedOrDeleted() = runTest {
    val root = root()
    try {
      val original = store(root)
      original.initialize()
      original.close()
      val foreignTask = store(root, task = "other")
      assertFailsWith<IllegalArgumentException> { foreignTask.initialize() }
      foreignTask.cleanup()
      val path = root / "payload" / "a"
      torrentFileSystem.atomicMove(path, root / "preserved")
      torrentFileSystem.write(path) { writeUtf8("foreign") }
      val recovered = store(root)
      assertFailsWith<IllegalArgumentException> { recovered.initialize() }
      recovered.cleanup()
      assertEquals("foreign", torrentFileSystem.read(path) { readUtf8() })
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun open_legacyBindingOfCheckpointSelection_isAccepted() = runTest {
    val root = root()
    try {
      // An older build bound the log to the selection the task had then: only a.
      val original = store(root, selected = setOf("0"))
      original.initialize()
      assertEquals(CommitOutcome.VERIFIED, original.commit(0, byteArrayOf(1)))
      val checkpoint = original.checkpoint()
      original.close()
      // Neither the new binding nor the current selection's: the checkpoint's selection opens it.
      val restored = store(root, selected = setOf("0", "1"))
      restored.restore(checkpoint)
      restored.initialize()
      assertContentEquals(booleanArrayOf(true, false), restored.recheck())
      restored.close()
      // So does the selection a task's resume state mirrors, without any checkpoint.
      val mirrored = store(root, selected = setOf("1"), legacy = listOf(setOf("0")))
      mirrored.initialize()
      mirrored.cleanup()
      assertFalse(torrentFileSystem.exists(root / "payload"))
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun open_unchangedSelection_keepsTheLegacyBinding() = runTest {
    val root = root()
    try {
      val original = store(root, selected = setOf("0"))
      original.initialize()
      original.close()
      val written = torrentFileSystem.read(root / "creation") { readByteArray() }
      // Reopened with the same selection, the log stays readable by builds that bind to it.
      val reopened = store(root, selected = setOf("0"))
      reopened.initialize()
      reopened.close()
      assertContentEquals(written, torrentFileSystem.read(root / "creation") { readByteArray() })
      // A store that never had that selection cannot open it.
      val other = store(root, selected = setOf("1"))
      assertFailsWith<IllegalArgumentException> { other.initialize() }
      other.close()
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun changeSelection_rebindsTheLogWithoutSelection() = runTest {
    val root = root()
    try {
      val original = store(root, selected = setOf("0"))
      original.initialize()
      val legacy = torrentFileSystem.read(root / "creation") { readByteArray() }
      original.changeSelection(setOf("0", "1"))
      original.changeSelection(setOf("1"))
      original.close()
      val bound = torrentFileSystem.read(root / "creation") { readByteArray() }
      assertFalse(legacy.contentEquals(bound))
      assertTrue(temporaryFiles(root).isEmpty())
      // Bound without a selection, any selection opens it, including one never used.
      for (selection in listOf(setOf("0"), setOf("1"), setOf("0", "1"))) {
        val reopened = store(root, selected = selection)
        reopened.initialize()
        reopened.close()
      }
      assertContentEquals(bound, torrentFileSystem.read(root / "creation") { readByteArray() })
      val last = store(root)
      last.initialize()
      last.cleanup()
      assertFalse(torrentFileSystem.exists(root / "payload"))
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun recreateDeletedPath_rewritesTheStaleRecord() = runTest {
    val root = root()
    try {
      val original = store(root)
      original.initialize()
      original.changeSelection(setOf("0"))
      // b is deselected, and the user deletes it; selected again, it is created anew.
      torrentFileSystem.delete(root / "payload" / "b")
      original.changeSelection(setOf("0", "1"))
      assertEquals(CommitOutcome.VERIFIED, original.commit(1, byteArrayOf(2)))
      original.close()
      val recovered = store(root)
      recovered.initialize()
      assertContentEquals(booleanArrayOf(false, true), recovered.recheck())
      recovered.cleanup()
      assertFalse(torrentFileSystem.exists(root / "payload"))
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun rewrite_interrupted_leavesOldOrNewLogIntact() = runTest {
    for (afterMove in listOf(false, true)) {
      val root = root()
      try {
        var fail = true
        val provider = object : ForwardingFileSystem(torrentFileSystem) {
          override fun atomicMove(source: Path, target: Path) {
            if (fail && target.name == "creation" && !afterMove) {
              fail = false
              throw IOException("Injected failure before the log is replaced")
            }
            super.atomicMove(source, target)
            if (fail && target.name == "creation") {
              fail = false
              throw IOException("Injected failure after the log is replaced")
            }
          }
        }
        val original = store(root, selected = setOf("0"), provider = provider)
        original.initialize()
        assertFailsWith<IOException> { original.changeSelection(setOf("0", "1")) }
        assertEquals(setOf("0"), original.selectedIds())
        original.close()
        assertTrue(temporaryFiles(root).isEmpty())
        // Either the old log, bound to a, or the new one, bound to nothing, opens whole.
        val reopened = store(root, selected = if (afterMove) setOf("1") else setOf("0"))
        reopened.initialize()
        reopened.cleanup()
        assertFalse(torrentFileSystem.exists(root / "payload"))
      } finally {
        torrentFileSystem.deleteRecursively(root)
      }
    }
  }
}
