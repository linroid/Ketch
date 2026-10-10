package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileHandle
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentProcessCrashTest {
  @Test
  fun processExitDuringPieceWrite_rechecksPartialBytesAndRecoversOwnership() = runTest {
    crash("write")
  }

  @Test
  fun processExitBeforeTaskStoreCheckpoint_recoversFlushedPiece() = runTest {
    crash("before-checkpoint")
  }

  @Test
  fun processExitAfterAtomicCheckpoint_keepsRecoverableOwnership() = runTest {
    crash("checkpoint")
  }

  @Test
  fun selectionExpand_killAfterCopy_recoversWithoutRedownload() = runTest {
    selectionCrash("selection-copy")
  }

  @Test
  fun selectionExpand_killDuringCopy_healsFromTheSidecar() = runTest {
    selectionCrash("selection-copy-partial")
  }

  /**
   * The child verifies piece 0 (all of `a`, one byte of `b`) with only `a` selected, then selects
   * `b` and dies while copying that byte from the sidecar. The restarted store, with the saved
   * selection, keeps piece 0 without downloading it again.
   */
  private suspend fun selectionCrash(mode: String) = withContext(Dispatchers.IO) {
    val root = Files.createTempDirectory("ketch-crash").toFile()
    root.resolve("neighbor").writeText("keep")
    try {
      runChild(root, mode)
      val store = TorrentPieceStore(CrashingTorrentProcess.packMetadata(),
        root.resolve("pack").absolutePath.toPath(), setOf(0, 1), "crash")
      store.initialize()
      assertContentEquals(booleanArrayOf(true, false), store.recheck())
      assertEquals(listOf<Byte>(1, 2, 3), root.resolve("pack/a").readBytes().toList())
      assertEquals(4.toByte(), root.resolve("pack/b").readBytes().first())
      assertContentEquals(longArrayOf(3, 1), store.progress())
      store.cleanup()
      assertFalse(root.resolve("pack").exists())
      assertEquals("keep", root.resolve("neighbor").readText())
    } finally {
      root.deleteRecursively()
    }
  }

  private fun runChild(root: File, mode: String) {
    val urls = generateSequence(javaClass.classLoader) { it.parent }
      .filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }
      .map { File(it.toURI()).absolutePath }.toList()
    val classpath = (urls + System.getProperty("java.class.path").split(File.pathSeparator))
      .distinct().joinToString(File.pathSeparator)
    val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
      "-cp", classpath, "com.linroid.ketch.torrent.CrashingTorrentProcess", root.absolutePath, mode)
      .redirectErrorStream(true).redirectOutput(root.resolve("child.log")).start()
    val exited = process.waitFor(20, TimeUnit.SECONDS)
    if (!exited) process.destroyForcibly()
    assertTrue(exited, "Child process did not exit")
    assertEquals(73, process.exitValue(), root.resolve("child.log").readText().take(4000))
  }

  private suspend fun crash(mode: String) = withContext(Dispatchers.IO) {
    val root = Files.createTempDirectory("ketch-crash").toFile()
    root.resolve("neighbor").writeText("keep")
    try {
      runChild(root, mode)
      val store = TorrentPieceStore(CrashingTorrentProcess.metadata(),
        root.resolve("payload").absolutePath.toPath(), emptySet(), "crash")
      store.initialize()
      assertContentEquals(booleanArrayOf(mode != "write", false), store.recheck())
      store.cleanup()
      assertFalse(root.resolve("payload").exists())
      assertEquals("keep", root.resolve("neighbor").readText())
    } finally {
      root.deleteRecursively()
    }
  }
}

/** Deliberately exits without running Kotlin cleanup at controlled filesystem boundaries. */
internal object CrashingTorrentProcess {
  fun metadata(): TorrentMetadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "payload", "length" to 8L, "piece length" to 4L,
    "pieces" to (sha1Digest(byteArrayOf(1, 2, 3, 4)) + sha1Digest(byteArrayOf(5, 6, 7, 8)))
  ))))

  /** Files `a` (1, 2, 3) and `b` (4, 5, 6) in pieces of 4 bytes; piece 0 ends in `b`. */
  fun packMetadata(): TorrentMetadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
    "info" to mapOf("name" to "pack", "piece length" to 4L,
      "pieces" to (sha1Digest(byteArrayOf(1, 2, 3, 4)) + sha1Digest(byteArrayOf(5, 6))),
      "files" to listOf(
        mapOf("length" to 3L, "path" to listOf("a")),
        mapOf("length" to 3L, "path" to listOf("b"))))
  )))

  @JvmStatic
  fun main(args: Array<String>): Unit = runBlocking {
    if (args[1].startsWith("selection-")) return@runBlocking selection(args[0].toPath(), args[1])
    val output = args[0].toPath() / "payload"
    val mode = args[1]
    val fs = object : ForwardingFileSystem(torrentFileSystem) {
      override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        val delegate = super.openReadWrite(file, mustCreate, mustExist)
        if (file.name != "payload" || mode != "write") return delegate
        return object : FileHandle(readWrite = true) {
          override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int =
            delegate.read(fileOffset, array, arrayOffset, byteCount)
          override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
            delegate.write(fileOffset, array, arrayOffset, maxOf(1, byteCount / 2))
            delegate.flush()
            Runtime.getRuntime().halt(73)
          }
          override fun protectedFlush() = delegate.flush()
          override fun protectedResize(size: Long) = delegate.resize(size)
          override fun protectedSize(): Long = delegate.size()
          override fun protectedClose() = delegate.close()
        }
      }
      override fun atomicMove(source: Path, target: Path) {
        super.atomicMove(source, target)
        if (mode == "checkpoint" && target.name == "checkpoint") Runtime.getRuntime().halt(73)
      }
    }
    val store = TorrentPieceStore(metadata(), output, emptySet(), "crash", fs)
    store.initialize()
    store.commit(0, byteArrayOf(1, 2, 3, 4))
    if (mode == "before-checkpoint") Runtime.getRuntime().halt(73)
    store.persistCheckpoint()
    error("Crash boundary was not reached")
  }

  /** Dies at the copy of a verified sidecar span into a newly selected file. */
  private suspend fun selection(root: Path, mode: String) {
    var armed = false
    val fs = object : ForwardingFileSystem(torrentFileSystem) {
      override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        val delegate = super.openReadWrite(file, mustCreate, mustExist)
        if (!armed || file.name != "b") return delegate
        return object : FileHandle(readWrite = true) {
          override fun protectedRead(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
          ): Int = delegate.read(fileOffset, array, arrayOffset, byteCount)
          override fun protectedWrite(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
          ) {
            // A partial copy writes a wrong byte, as a torn write could leave behind.
            if (mode == "selection-copy") delegate.write(fileOffset, array, arrayOffset, byteCount)
            else delegate.write(fileOffset, byteArrayOf(9), 0, 1)
            delegate.flush()
            Runtime.getRuntime().halt(73)
          }
          override fun protectedFlush() = delegate.flush()
          override fun protectedResize(size: Long) = delegate.resize(size)
          override fun protectedSize(): Long = delegate.size()
          override fun protectedClose() = delegate.close()
        }
      }
    }
    val store = TorrentPieceStore(packMetadata(), root / "pack", setOf(0), "crash", fs)
    store.initialize()
    check(store.commit(0, byteArrayOf(1, 2, 3, 4)) == CommitOutcome.VERIFIED)
    armed = true
    store.changeSelection(setOf(0, 1))
    error("Crash boundary was not reached")
  }
}
