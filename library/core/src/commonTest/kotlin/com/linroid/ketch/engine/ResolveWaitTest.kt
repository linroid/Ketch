package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.resolve
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.task.InMemoryTaskStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** A preview and a starting task both wait as long as finding the content takes. */
class ResolveWaitTest {
  private val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-resolve-wait-test"

  @Test
  fun preview_waitsUntilFound() = runTest {
    val source = SlowSource()
    val ketch = ketch(source)
    try {
      val preview = async { ketch.resolve("slow:file") }
      advanceTimeBy(10.minutes)
      assertTrue(preview.isActive)

      source.found.complete(Unit)

      assertEquals("empty.bin", preview.await().suggestedFileName)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun task_waitsUntilFound() = runTest {
    platformFileSystem.createDirectories(folder)
    val source = SlowSource()
    val ketch = ketch(source)
    try {
      val task = ketch.download(
        DownloadRequest("slow:file", destination = Destination((folder / "empty.bin").toString())),
      )
      advanceTimeBy(10.minutes)
      assertIs<DownloadState.Queued>(task.state.value)

      source.found.complete(Unit)
      advanceUntilIdle()

      assertIs<DownloadState.Completed>(task.state.value)
    } finally {
      ketch.close()
      platformFileSystem.deleteRecursively(folder)
    }
  }

  private fun TestScope.ketch(source: DownloadSource<*>): Ketch {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return Ketch(
      httpEngine = FakeHttpEngine(),
      taskStore = InMemoryTaskStore(),
      config = DownloadConfig(retryCount = 0),
      additionalSources = listOf(source),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
  }

  /** Answers only once [found] completes, and downloads an empty file. */
  private class SlowSource : DownloadSource<Unit> {
    override val stateSerializer = Unit.serializer()
    val found = CompletableDeferred<Unit>()
    override val type = "slow"
    override fun canHandle(input: ResolveInput): Boolean =
      input is ResolveInput.Url && input.url.startsWith("slow:")
    override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
      found.await()
      return ResolvedSource(
        url = (input as ResolveInput.Url).url, sourceType = type, totalBytes = 0,
        supportsResume = true, suggestedFileName = "empty.bin", maxSegments = 1,
      )
    }
    override suspend fun download(context: DownloadContext, state: Unit?) = context.onProgress(0, 0)
  }
}
