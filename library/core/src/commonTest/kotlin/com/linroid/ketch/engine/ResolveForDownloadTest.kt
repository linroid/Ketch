package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.task.InMemoryTaskStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ResolveForDownloadTest {
  @Test
  fun tasksResolveForDownloadWhilePreviewsResolve() = runTest {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-resolve-for-download-test"
    platformFileSystem.createDirectories(folder)
    val dispatcher = StandardTestDispatcher(testScheduler)
    val source = RecordingSource()
    val ketch = Ketch(
      httpEngine = FakeHttpEngine(),
      taskStore = InMemoryTaskStore(),
      config = DownloadConfig(retryCount = 0),
      additionalSources = listOf(source),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    try {
      ketch.resolve("record:file")
      val task = ketch.download(
        DownloadRequest(
          url = "record:file",
          destination = Destination((folder / "empty.bin").toString()),
        )
      )
      advanceUntilIdle()

      assertIs<DownloadState.Completed>(task.state.value)
      assertEquals(listOf("preview", "task"), source.calls)
    } finally {
      ketch.close()
      platformFileSystem.deleteRecursively(folder)
    }
  }

  /** Records which resolve each caller used, and downloads an empty file. */
  private class RecordingSource : DownloadSource {
    val calls = mutableListOf<String>()
    override val type = "record"
    override fun canHandle(url: String): Boolean = url.startsWith("record:")
    override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource {
      calls += "preview"
      return resolved(url)
    }
    override suspend fun resolveForDownload(
      url: String,
      properties: Map<String, String>,
      config: DownloadConfig,
    ): ResolvedSource {
      calls += "task"
      return resolved(url)
    }
    override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long) =
      SourceResumeState(type, "")
    override suspend fun download(context: DownloadContext) = context.onProgress(0, 0)
    override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) =
      download(context)

    private fun resolved(url: String) = ResolvedSource(
      url = url, sourceType = type, totalBytes = 0, supportsResume = true,
      suggestedFileName = "empty.bin", maxSegments = 1,
    )
  }
}
