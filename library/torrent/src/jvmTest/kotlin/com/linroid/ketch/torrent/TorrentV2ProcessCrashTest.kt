package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileHandle
import okio.FileSystem
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TorrentV2ProcessCrashTest {
  @Test
  fun partialPayloadWriteRecoversOwnershipButNotAvailability() = runTest { crash("write") }

  @Test
  fun flushedPayloadRecoversWithoutAnyCheckpoint() = runTest { crash("before-checkpoint") }

  @Test
  fun exitBeforeReplacementPreservesThePreviousCompleteCheckpoint() = runTest {
    crash("before-replace")
  }

  @Test
  fun exitAfterReplacementLoadsTheNewCompleteCheckpoint() = runTest { crash("after-replace") }

  private suspend fun crash(mode: String) = withContext(Dispatchers.IO) {
    val root = Files.createTempDirectory("ketch-v2-crash").toFile()
    root.resolve("neighbor").writeText("keep")
    var child: Process? = null
    try {
      val urls = generateSequence(javaClass.classLoader) { it.parent }
        .filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }
        .map { File(it.toURI()).absolutePath }.toList()
      val classpath = (urls + System.getProperty("java.class.path").split(File.pathSeparator))
        .distinct().joinToString(File.pathSeparator)
      child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
        "-cp", classpath, "com.linroid.ketch.torrent.CrashingV2TorrentProcess",
        root.absolutePath, mode).redirectErrorStream(true)
        .redirectOutput(root.resolve("child.log")).start()
      assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Child did not reach its crash boundary")
      assertEquals(73, child.exitValue(), root.resolve("child.log").readText().take(4000))
      val path = root.absolutePath.toPath()
      val store = CrashingV2TorrentProcess.store(path)
      val catalog = TorrentContentCatalog(path / "catalog")
      val file = TorrentCheckpointFile(path / "checkpoint", "crash", catalog)
      if (mode.endsWith("replace")) {
        val loaded = assertNotNull(file.load())
        assertEquals(if (mode == "after-replace") 8L else 4L, loaded.checkpoint.receivedBytes)
        store.restore(loaded.checkpoint)
      } else {
        assertFalse(root.resolve("checkpoint").exists())
      }
      store.initialize()
      assertContentEquals(booleanArrayOf(false, false), store.verifiedPieces())
      assertContentEquals(booleanArrayOf(mode != "write", mode.endsWith("replace")),
        store.recheck())
      assertTrue(store.commit(0, byteArrayOf(1, 2, 3, 4)))
      assertTrue(store.commit(1, byteArrayOf(5, 6, 7, 8)))
      assertTrue(store.completed())
      store.cleanup()
      assertFalse(root.resolve("payload").exists())
      assertEquals("keep", root.resolve("neighbor").readText())
    } finally {
      child?.let {
        if (it.isAlive) {
          it.destroyForcibly()
          check(it.waitFor(5, TimeUnit.SECONDS)) { "Child did not terminate" }
        }
      }
      root.deleteRecursively()
    }
  }
}

/** Controlled process exits bypass coroutine finally blocks, close handlers and shutdown hooks. */
internal object CrashingV2TorrentProcess {
  private fun document() = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
    "meta version" to 2L, "piece length" to 16_384L,
    "file tree" to mapOf(
      "a" to mapOf("" to mapOf("length" to 4L,
        "pieces root" to sha256Digest(byteArrayOf(1, 2, 3, 4)))),
      "b" to mapOf("" to mapOf("length" to 4L,
        "pieces root" to sha256Digest(byteArrayOf(5, 6, 7, 8))))
    )
  ), "piece layers" to emptyMap<String, Any>())))

  fun store(root: Path, fileSystem: FileSystem = torrentFileSystem) = TorrentV2PieceStore(
    document(), root / "payload", emptySet(), "crash", TorrentBufferBudget(65_536), Semaphore(1),
    fileSystem, root / "creation"
  )

  @JvmStatic
  fun main(args: Array<String>): Unit = runBlocking {
    val root = args[0].toPath()
    val mode = args[1]
    var replace = false
    val provider = object : ForwardingFileSystem(torrentFileSystem) {
      override fun atomicMove(source: Path, target: Path) {
        if (replace && target.name == "checkpoint" && mode == "before-replace") {
          Runtime.getRuntime().halt(73)
        }
        super.atomicMove(source, target)
        if (replace && target.name == "checkpoint" && mode == "after-replace") {
          Runtime.getRuntime().halt(73)
        }
      }

      override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
      ): FileHandle {
        val delegate = super.openReadWrite(file, mustCreate, mustExist)
        if (file.name != "a" || mode != "write") return delegate
        return object : FileHandle(readWrite = true) {
          override fun protectedRead(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
          ) = delegate.read(fileOffset, array, arrayOffset, byteCount)
          override fun protectedWrite(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
          ) {
            delegate.write(fileOffset, array, arrayOffset, maxOf(1, byteCount / 2))
            delegate.flush()
            Runtime.getRuntime().halt(73)
          }
          override fun protectedFlush() = delegate.flush()
          override fun protectedResize(size: Long) = delegate.resize(size)
          override fun protectedSize() = delegate.size()
          override fun protectedClose() = delegate.close()
        }
      }
    }
    val store = store(root, provider)
    val catalog = TorrentContentCatalog(root / "catalog")
    val checkpoint = TorrentCheckpointFile(root / "checkpoint", "crash", catalog, provider)
    store.initialize()
    store.commit(0, byteArrayOf(1, 2, 3, 4))
    if (mode == "before-checkpoint") Runtime.getRuntime().halt(73)
    checkpoint.save(document(), store.checkpoint(catalog, receivedBytes = 4))
    store.commit(1, byteArrayOf(5, 6, 7, 8))
    replace = true
    checkpoint.save(document(), store.checkpoint(catalog, receivedBytes = 8))
    error("Crash boundary was not reached")
  }
}
