package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.engine.TorrentEngine
import com.linroid.ketch.torrent.engine.TorrentSession
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.storage.TorrentV2PieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TorrentDownloadSourceCleanupTest {

  private val infoHash = "aabbccddee11223344556677889900aabbccddee"

  private fun buildSource(engine: TorrentEngine): TorrentDownloadSource {
    val source = TorrentDownloadSource()
    source.engineFactory = { engine }
    return source
  }

  private object StubFileAccessor : FileAccessor {
    override suspend fun writeAt(offset: Long, data: ByteArray) = Unit
    override suspend fun flush() = Unit
    override fun close() = Unit
    override suspend fun delete() = Unit
    override suspend fun size(): Long = 0
    override suspend fun preallocate(size: Long) = Unit
  }

  private fun buildContext(): DownloadContext = DownloadContext(
    taskId = "t",
    url = "magnet:?xt=urn:btih:$infoHash",
    request = DownloadRequest(
      url = "magnet:?xt=urn:btih:$infoHash",
      destination = Destination("/tmp/"),
    ),
    fileAccessor = StubFileAccessor,
    segments = MutableStateFlow(emptyList()),
    onProgress = { _, _ -> },
    throttle = { _ -> },
    headers = emptyMap(),
  )

  private fun resumeStateFor(infoHash: String) = TorrentSourceState(
    infoHash = infoHash,
    totalBytes = 1024L,
    resumeData = "",
    selectedFileIds = setOf("0"),
    savePath = "/tmp/torrent",
  )

  @Test
  fun cleanup_legacyStateDoesNotStartEngineOrGuessOwnership() = runTest {
    val engine = FakeTorrentEngine()
    val source = buildSource(engine)

    source.cleanup(buildContext(), resumeStateFor(infoHash), deleteFiles = true)

    assertTrue(engine.removedTorrents.isEmpty())
    assertTrue(!engine.started)
  }

  @Test
  fun cleanup_nullResumeState_isNoOp() = runTest {
    val engine = FakeTorrentEngine()
    val source = buildSource(engine)

    source.cleanup(buildContext(), state = null, deleteFiles = true)

    assertTrue(engine.removedTorrents.isEmpty())
  }

  @Test
  fun cleanup_engineThrows_swallowsException() = runTest {
    val engine = ThrowingFakeEngine()
    val source = buildSource(engine)

    // Must not propagate.
    source.cleanup(buildContext(), resumeStateFor(infoHash), deleteFiles = true)

    assertEquals(0, engine.removeAttempts)
  }

  private class ThrowingFakeEngine : TorrentEngine {
    var removeAttempts = 0
    override val isRunning: Boolean = true
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun fetchMetadata(magnetUri: String): TorrentMetadata? = null
    override suspend fun addTorrent(
      infoHash: String,
      savePath: String,
      magnetUri: String?,
      torrentData: ByteArray?,
      selectedFileIndices: Set<Int>,
      resumeData: ByteArray?,
    ): TorrentSession = error("not used")
    override suspend fun removeTorrent(
      infoHash: String,
      deleteFiles: Boolean,
    ) {
      removeAttempts++
      throw RuntimeException("engine failure")
    }
    override fun setDownloadRateLimit(bytesPerSecond: Long) = Unit
    override fun setUploadRateLimit(bytesPerSecond: Long) = Unit
  }

  private fun temporaryRoot() = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-cleanup-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}").also {
    torrentFileSystem.createDirectories(it)
  }

  @Test
  fun cleanup_afterShrink_removesDeselectedOwnedFiles() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        coroutineScope {
          val seeder = GatedSeeder()
          seeder.start(this)
          val root = temporaryRoot()
          var engine: KotlinTorrentEngine? = null
          val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false), seeder.http)
          source.engineFactory = { config ->
            KotlinTorrentEngine(config, http = TorrentHttp(seeder.http),
              listenHost = "127.0.0.1").also { engine = it }
          }
          val ketch = Ketch(seeder.http, additionalSources = listOf(source))
          try {
            ketch.start()
            seeder.openAll()
            val task = ketch.download(DownloadRequest(GatedSeeder.URL,
              destination = Destination((root / "out").toString()),
              selectedFileIds = setOf("0", "1")))
            awaitStage("completion", task) { task.await().getOrThrow() }
            // A shrink keeps what the task has, and it still owns the deselected file.
            task.selectFiles(setOf("0"))
            assertEquals(seeder.torrent.sizeOf(0),
              assertIs<DownloadState.Completed>(task.state.value).totalBytes)
            assertTrue(torrentFileSystem.exists(root / "out" / "f1"))
            torrentFileSystem.write(root / "out" / "foreign") { writeUtf8("keep") }
            task.remove(deleteFiles = true)
            assertFalse(torrentFileSystem.exists(root / "out" / "f0"))
            assertFalse(torrentFileSystem.exists(root / "out" / "f1"))
            assertEquals("keep", torrentFileSystem.read(root / "out" / "foreign") { readUtf8() })
            source.assertNoLeaks()
          } finally {
            ketch.close()
            engine?.stop()
            seeder.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
          engine?.assertNoLeaks()
        }
      }
    }
  }

  @Test
  fun cleanupV2_legacyLog_usesCheckpointSelection() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5))
    val root = temporaryRoot()
    val output = root / "out"
    try {
      // An older build ran the task with only a, binding its creation log to that selection.
      val store = TorrentV2PieceStore(fixture.document, output, setOf("0"), "task",
        TorrentBufferBudget(4 * 1024 * 1024), Semaphore(1),
        creationLogPath = v2CreationLog(output, "task"))
      val checkpoint = try {
        store.initialize()
        fixture.seed(store, 0..1)
        store.checkpoint()
      } finally {
        store.close()
      }
      assertTrue(torrentFileSystem.exists(v2CreationLog(output, "task")))
      // The state mirrors b and the record chooses both: neither is the log's binding.
      val state = TorrentSourceState(fixture.document.info.hash.hex, 5,
        encodeBase64(checkpoint.encode()), setOf("1"), output.toString(),
        encodeBase64(fixture.metainfo), version = 3, privacy = TorrentDiscoveryPrivacy.PUBLIC)
      val context = DownloadContext(taskId = "task", url = "http://fixture/file.torrent",
        request = DownloadRequest("http://fixture/file.torrent",
          selectedFileIds = setOf("0", "1")),
        fileAccessor = StubFileAccessor, segments = MutableStateFlow(emptyList()),
        onProgress = { _, _ -> }, throttle = { _ -> }, headers = emptyMap(),
        outputPath = output.toString())
      TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
        .cleanup(context, state, deleteFiles = true)
      assertFalse(torrentFileSystem.exists(output / "a"))
      assertFalse(torrentFileSystem.exists(v2CreationLog(output, "task")))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun removeKeepingFiles_deletesTheSidecarButNoFile() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        coroutineScope {
          val seeder = GatedSeeder()
          seeder.start(this)
          val root = temporaryRoot()
          var engine: KotlinTorrentEngine? = null
          val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false), seeder.http)
          source.engineFactory = { config ->
            KotlinTorrentEngine(config, http = TorrentHttp(seeder.http),
              listenHost = "127.0.0.1").also { engine = it }
          }
          val ketch = Ketch(seeder.http, additionalSources = listOf(source))
          try {
            ketch.start()
            seeder.openAll()
            val task = ketch.download(DownloadRequest(GatedSeeder.URL,
              destination = Destination((root / "out").toString())))
            awaitStage("completion", task) { task.await().getOrThrow() }
            assertTrue(torrentFileSystem.list(root).any { it.name.startsWith(".ketch-") })
            task.remove(deleteFiles = false)
            assertTrue(torrentFileSystem.exists(root / "out" / "f0"))
            assertTrue(torrentFileSystem.exists(root / "out" / "f1"))
            // Nothing reads the checkpoint or journal once the task is gone.
            assertEquals(emptyList(),
              torrentFileSystem.list(root).filter { it.name.startsWith(".ketch-") })
            source.assertNoLeaks()
          } finally {
            ketch.close()
            engine?.stop()
            seeder.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
          engine?.assertNoLeaks()
        }
      }
    }
  }

  @Test
  fun removeKeepingFilesV2_deletesTheCreationLogButNoFile() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5))
    val root = temporaryRoot()
    val output = root / "out"
    try {
      val store = TorrentV2PieceStore(fixture.document, output, setOf("0", "1"), "task",
        TorrentBufferBudget(4 * 1024 * 1024), Semaphore(1),
        creationLogPath = v2CreationLog(output, "task"))
      val checkpoint = try {
        store.initialize()
        fixture.seed(store, 0..1)
        store.checkpoint()
      } finally {
        store.close()
      }
      assertTrue(torrentFileSystem.exists(v2CreationLog(output, "task")))
      val state = TorrentSourceState(fixture.document.info.hash.hex, 40_005,
        encodeBase64(checkpoint.encode()), setOf("0", "1"), output.toString(),
        encodeBase64(fixture.metainfo), version = 3, privacy = TorrentDiscoveryPrivacy.PUBLIC)
      val context = DownloadContext(taskId = "task", url = "http://fixture/file.torrent",
        request = DownloadRequest("http://fixture/file.torrent"),
        fileAccessor = StubFileAccessor, segments = MutableStateFlow(emptyList()),
        onProgress = { _, _ -> }, throttle = { _ -> }, headers = emptyMap(),
        outputPath = output.toString())
      TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
        .cleanup(context, state, deleteFiles = false)
      assertTrue(torrentFileSystem.exists(output / "a"))
      assertTrue(torrentFileSystem.exists(output / "b"))
      assertFalse(torrentFileSystem.exists(v2CreationLog(output, "task")))
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }
}
