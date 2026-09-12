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

  private fun store(root: Path, task: String = "task", provider: FileSystem = torrentFileSystem) =
    TorrentV2PieceStore(document, root / "payload", emptySet(), task, TorrentBufferBudget(65_536),
      Semaphore(1), provider, root / "creation")

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
      assertTrue(recovered.commit(1, byteArrayOf(2)))
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
}
