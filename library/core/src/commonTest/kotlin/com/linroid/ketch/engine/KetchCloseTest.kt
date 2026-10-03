package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
      assertEquals(4L, platformFileSystem.metadataOrNull(output)?.size)
      // The next start() resumes it.
      assertEquals(TaskState.DOWNLOADING, store.load(task.taskId)?.state)
    } finally {
      platformFileSystem.deleteRecursively(folder)
    }
  }

  /** Writes the first half of an 8-byte file, then waits until it is cancelled. */
  private class HalfWaySource : DownloadSource {
    override val type = "fixture"
    override fun canHandle(url: String): Boolean = url.startsWith("fixture:")
    override suspend fun resolve(url: String, properties: Map<String, String>) = ResolvedSource(
      url = url, sourceType = type, totalBytes = 8, supportsResume = true,
      suggestedFileName = "partial.bin", maxSegments = 1,
    )
    override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long) =
      SourceResumeState(type, "")
    override suspend fun download(context: DownloadContext) {
      context.fileAccessor.writeAt(0, byteArrayOf(1, 2, 3, 4))
      context.segments.value = listOf(Segment(0, 0, 7, 4))
      context.onProgress(4, 8)
      awaitCancellation()
    }
    override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) =
      download(context)
  }
}
