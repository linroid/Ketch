package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

  private fun resumeStateFor(infoHash: String): SourceResumeState =
    TorrentDownloadSource.buildResumeState(
      infoHash = infoHash,
      totalBytes = 1024L,
      resumeData = ByteArray(0),
      selectedFileIds = setOf("0"),
      savePath = "/tmp/torrent",
    )

  @Test
  fun cleanup_callsRemoveTorrentWithDeleteFilesTrue() = runTest {
    val engine = FakeTorrentEngine()
    val source = buildSource(engine)

    source.cleanup(buildContext(), resumeStateFor(infoHash))

    assertEquals(1, engine.removedTorrents.size)
    assertEquals(infoHash, engine.removedTorrents[0].first)
    assertEquals(true, engine.removedTorrents[0].second)
  }

  @Test
  fun cleanup_nullResumeState_isNoOp() = runTest {
    val engine = FakeTorrentEngine()
    val source = buildSource(engine)

    source.cleanup(buildContext(), resumeState = null)

    assertTrue(engine.removedTorrents.isEmpty())
  }

  @Test
  fun cleanup_corruptResumeState_isNoOp() = runTest {
    val engine = FakeTorrentEngine()
    val source = buildSource(engine)
    val corrupt = SourceResumeState("torrent", "not-json")

    source.cleanup(buildContext(), corrupt)

    assertTrue(engine.removedTorrents.isEmpty())
  }

  @Test
  fun cleanup_engineThrows_swallowsException() = runTest {
    val engine = ThrowingFakeEngine()
    val source = buildSource(engine)

    // Must not propagate.
    source.cleanup(buildContext(), resumeStateFor(infoHash))

    assertEquals(1, engine.removeAttempts)
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
}
