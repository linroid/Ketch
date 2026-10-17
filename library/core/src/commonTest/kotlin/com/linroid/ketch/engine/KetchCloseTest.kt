package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.encodeState
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KetchCloseTest {
  @Test
  fun close_whileDownloading_pausesForShutdownAndKeepsPartialFile() = runTest {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-close-test"
    platformFileSystem.createDirectories(folder)
    val output = folder / "partial.bin"
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = InMemoryTaskStore()
    val ketch = Ketch(
      httpEngine = FakeHttpEngine(),
      taskStore = store,
      config = DownloadConfig(retryCount = 0),
      additionalSources = listOf(HalfWaySource()),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    try {
      val task = ketch.download(
        DownloadRequest(url = "fixture:partial", destination = Destination(output.toString()))
      )
      runCurrent()
      assertIs<DownloadState.Downloading>(task.state.value)

      ketch.close()
      runCurrent()

      val paused = assertIs<DownloadState.Paused>(task.state.value)
      assertEquals(PauseReason.Shutdown, paused.reason)
      assertEquals(4L, paused.progress.downloadedBytes)
      assertEquals(4L, platformFileSystem.metadataOrNull(folder / "partial.bin.ketch")?.size)
      assertFalse(platformFileSystem.exists(output))
      // The next start() resumes it.
      assertEquals(TaskState.DOWNLOADING, store.load(task.taskId)?.state)
    } finally {
      platformFileSystem.deleteRecursively(folder)
    }
  }

  @Test
  fun close_whileStarting_pausesForShutdown() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = InMemoryTaskStore()
    val ketch = Ketch(
      httpEngine = FakeHttpEngine(),
      taskStore = store,
      config = DownloadConfig(retryCount = 0),
      additionalSources = listOf(StuckSource()),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    val task = ketch.download(DownloadRequest(url = "stuck:file"))
    runCurrent()
    // It holds a slot but is still resolving.
    assertIs<DownloadState.Queued>(task.state.value)

    ketch.close()
    runCurrent()

    val paused = assertIs<DownloadState.Paused>(task.state.value)
    assertEquals(PauseReason.Shutdown, paused.reason)
    assertEquals(null, task.queuePosition.value)
    // The next start() resumes it.
    assertEquals(TaskState.QUEUED, store.load(task.taskId)?.state)
  }

  @Test
  fun shutdown_whileDownloading_savesTheFinalStateBeforeSourcesStopAndTheEngineCloses() = runTest {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-shutdown-test"
    platformFileSystem.createDirectories(folder)
    val dispatcher = StandardTestDispatcher(testScheduler)
    val http = FakeHttpEngine()
    val source = GoodbyeSource(http)
    val store = InMemoryTaskStore()
    val ketch = Ketch(
      httpEngine = http,
      taskStore = store,
      config = DownloadConfig(retryCount = 0),
      additionalSources = listOf(source),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    try {
      val task = ketch.download(
        DownloadRequest(url = "goodbye:file", destination = Destination(folder.toString()))
      )
      runCurrent()
      assertIs<DownloadState.Downloading>(task.state.value)

      ketch.shutdown(10.seconds)

      // The checkpoint the source made while it stopped, which took a while.
      val record = store.load(task.taskId)
      assertEquals(source.encodeState(GoodbyeSource.STOPPED_AT), record?.sourceState)
      assertEquals(TaskState.DOWNLOADING, record?.state)
      assertEquals(PauseReason.Shutdown, assertIs<DownloadState.Paused>(task.state.value).reason)
      assertEquals(
        listOf("checkpoint, HTTP open", "stop, HTTP open", "close"),
        source.events,
      )
      assertTrue(http.closed)
    } finally {
      platformFileSystem.deleteRecursively(folder)
    }
  }

  @Test
  fun shutdown_returnsAfterItsTimeout_andTheRestStopsLater() = runTest {
    val http = FakeHttpEngine()
    val stopped = CompletableDeferred<Unit>()
    val source = object : DownloadSource<Unit> by StuckSource() {
      override suspend fun stop() = stopped.await()
    }
    val ketch = Ketch(httpEngine = http, additionalSources = listOf(source))

    ketch.shutdown(50.milliseconds)
    assertFalse(http.closed)

    stopped.complete(Unit)
    withContext(Dispatchers.Default) {
      withTimeout(5.seconds) {
        while (!http.closed) delay(10)
      }
    }
  }

  /**
   * Writes the first half of an 8-byte file and waits until it is cancelled, then takes a while
   * to stop, saying goodbye over HTTP and saving where it stopped, as a torrent does.
   */
  private class GoodbyeSource(private val http: FakeHttpEngine) : DownloadSource<Int> {
    val events = mutableListOf<String>()
    private var checkpoint = 0
    override val stateSerializer = Int.serializer()
    override val type = "goodbye"
    override fun canHandle(input: ResolveInput): Boolean {
      val url = (input as? ResolveInput.Url)?.url ?: return false
      return url.startsWith("goodbye:")
    }
    override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
      val url = (input as ResolveInput.Url).url
      return ResolvedSource(
        url = url, sourceType = type, totalBytes = 8, supportsResume = true,
        suggestedFileName = "goodbye.bin", maxSegments = 1,
      )
    }
    override suspend fun download(context: DownloadContext, state: Int?) {
      context.fileAccessor.writeAt(0, byteArrayOf(1, 2, 3, 4))
      context.segments.value = listOf(Segment(0, 0, 7, 4))
      context.onProgress(4, 8)
      checkpoint = 4
      try {
        awaitCancellation()
      } finally {
        withContext(NonCancellable) {
          delay(500)
          events += "checkpoint, ${http.state()}"
          checkpoint = STOPPED_AT
        }
      }
    }
    override suspend fun store(context: DownloadContext): Int = checkpoint
    override suspend fun stop() {
      delay(50)
      events += "stop, ${http.state()}"
    }
    override fun close() {
      events += "close"
    }
    private fun FakeHttpEngine.state() = if (closed) "HTTP closed" else "HTTP open"

    companion object {
      const val STOPPED_AT = 6
    }
  }

  /** Never finishes resolving. */
  private class StuckSource : DownloadSource<Unit> {
    override val stateSerializer = Unit.serializer()
    override val type = "stuck"
    override fun canHandle(input: ResolveInput): Boolean {
      val url = (input as? ResolveInput.Url)?.url ?: return false
      return url.startsWith("stuck:")
    }
    override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource =
      awaitCancellation()
    override suspend fun download(context: DownloadContext, state: Unit?) = awaitCancellation()
  }

  /** Writes the first half of an 8-byte file, then waits until it is cancelled. */
  private class HalfWaySource : DownloadSource<Unit> {
    override val stateSerializer = Unit.serializer()
    override val type = "fixture"
    override fun canHandle(input: ResolveInput): Boolean {
      val url = (input as? ResolveInput.Url)?.url ?: return false
      return url.startsWith("fixture:")
    }
    override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
      val url = (input as ResolveInput.Url).url
      return ResolvedSource(
        url = url, sourceType = type, totalBytes = 8, supportsResume = true,
        suggestedFileName = "partial.bin", maxSegments = 1,
      )
    }
    override suspend fun download(context: DownloadContext, state: Unit?) {
      context.fileAccessor.writeAt(0, byteArrayOf(1, 2, 3, 4))
      context.segments.value = listOf(Segment(0, 0, 7, 4))
      context.onProgress(4, 8)
      awaitCancellation()
    }
  }
}
