package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.TransferStalledException
import com.linroid.ketch.core.file.platformFileSystem
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the engine's handling of stalled and slow transfers and its retry policy: a stall is
 * a retryable network error, retries start over after progress, and unlimited retries go on.
 * Everything runs on virtual time.
 */
class StalledTransferRetryTest {
  @Test
  fun stallMidBody_isRetriedFromSavedProgressAndCompletes() = runTest {
    val engine = FakeHttpEngine(stallAfterBytes = 500, stallTimes = 1)
    withKetch(engine, DownloadConfig(idleTimeoutMs = 10_000, retryCount = 1)) { ketch, output ->
      val task = ketch.download(request(output))
      runCurrent()
      assertIs<DownloadState.Downloading>(task.state.value)

      advanceTimeBy(9_000)
      runCurrent()
      assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals(1, engine.downloadCallCount)

      advanceTimeBy(5_000)
      runCurrent()
      assertIs<DownloadState.Completed>(task.state.value)
      assertEquals(500L..999L, engine.lastDownloadRange)
      assertContentEquals(engine.content, platformFileSystem.read(output) { readByteArray() })
    }
  }

  @Test
  fun stall_withIdleTimeoutOff_waits() = runTest {
    val engine = FakeHttpEngine(stallAfterBytes = 500)
    withKetch(engine, DownloadConfig(idleTimeoutMs = 0)) { ketch, output ->
      val task = ketch.download(request(output))
      advanceTimeBy(600_000)
      runCurrent()
      assertIs<DownloadState.Downloading>(task.state.value)
      assertEquals(1, engine.downloadCallCount)
    }
  }

  @Test
  fun slowerThanMinSpeed_failsWithStallOnceRetriesRunOut() = runTest {
    // 10 bytes every 2 seconds: 5 B/s, never idle for long.
    val engine = FakeHttpEngine(chunkSize = 10, chunkDelayMs = 2_000)
    val config = DownloadConfig(minSpeed = 100, retryCount = 0)
    withKetch(engine, config) { ketch, output ->
      val task = ketch.download(request(output))
      advanceTimeBy(DownloadConfig.MIN_SPEED_WINDOW_MS + 1_000)
      runCurrent()
      val failed = assertIs<DownloadState.Failed>(task.state.value)
      val error = assertIs<KetchError.Network>(failed.error)
      assertIs<TransferStalledException>(error.cause)
    }
  }

  @Test
  fun retries_startOverAfterAnAttemptMakesProgress() = runTest {
    // Each transfer fails after 300 bytes, but every attempt saves more of the file.
    val engine = FakeHttpEngine(failAfterBytes = 300)
    withKetch(engine, DownloadConfig(retryCount = 1)) { ketch, output ->
      val task = ketch.download(request(output))
      advanceTimeBy(60_000)
      runCurrent()
      assertIs<DownloadState.Completed>(task.state.value)
      assertEquals(4, engine.downloadCallCount)
      assertContentEquals(engine.content, platformFileSystem.read(output) { readByteArray() })
    }
  }

  @Test
  fun retries_withoutProgress_giveUpAfterRetryCount() = runTest {
    val engine = FakeHttpEngine(failAfterBytes = 0)
    withKetch(engine, DownloadConfig(retryCount = 2)) { ketch, output ->
      val task = ketch.download(request(output))
      advanceTimeBy(60_000)
      runCurrent()
      assertIs<DownloadState.Failed>(task.state.value)
      assertEquals(3, engine.downloadCallCount)
    }
  }

  @Test
  fun unlimitedRetries_keepRetryingWithCappedDelays() = runTest {
    val engine = FakeHttpEngine(failAfterBytes = 0)
    val config = DownloadConfig(
      retryCount = 0,
      unlimitedRetries = true,
      retryDelayMs = 1_000,
      maxRetryDelayMs = 4_000,
    )
    withKetch(engine, config) { ketch, output ->
      val task = ketch.download(request(output))
      advanceTimeBy(10 * 60_000)
      runCurrent()
      assertIs<DownloadState.Downloading>(task.state.value)
      // At most 4 s apart once capped, so at least 600 / 4 attempts in ten minutes.
      assertTrue(engine.downloadCallCount >= 150, "attempts: ${engine.downloadCallCount}")

      engine.failAfterBytes = -1
      advanceTimeBy(5_000)
      runCurrent()
      assertIs<DownloadState.Completed>(task.state.value)
    }
  }

  private suspend fun TestScope.withKetch(
    engine: FakeHttpEngine,
    config: DownloadConfig,
    block: suspend TestScope.(Ketch, Path) -> Unit,
  ) {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-stall-${Random.nextLong()}"
    platformFileSystem.createDirectories(folder)
    val dispatcher = StandardTestDispatcher(testScheduler)
    val ketch = Ketch(
      httpEngine = engine,
      config = config,
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    try {
      block(ketch, folder / "file.bin")
    } finally {
      ketch.close()
      runCurrent()
      platformFileSystem.deleteRecursively(folder)
    }
  }

  private fun request(output: Path) = DownloadRequest(
    url = "https://example.com/file.bin",
    destination = Destination(output.toString()),
    connections = 1,
  )
}
