package com.linroid.ketch

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.engine.FakeHttpEngine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KetchUpdateConfigTest {
  private val firstDir = Files.createTempDirectory("ketch-config-a").toFile()
  private val secondDir = Files.createTempDirectory("ketch-config-b").toFile()

  @Test
  fun updateConfig_newDirectory_appliesToNextDownload() = runTest {
    withKetch(DownloadConfig(defaultDirectory = firstDir.path)) { ketch, source ->
      ketch.updateConfig(DownloadConfig(defaultDirectory = secondDir.path))
      ketch.download(DownloadRequest(url = "fixture://host/file"))
      runCurrent()
      assertEquals(File(secondDir, "fixture.bin").path, source.outputPath)
    }
  }

  @Test
  fun updateConfig_missingDirectory_throwsAndKeepsConfig() = runTest {
    val initial = DownloadConfig(defaultDirectory = firstDir.path)
    withKetch(initial) { ketch, _ ->
      val missing = File(firstDir, "missing").path
      assertFailsWith<IllegalArgumentException> {
        ketch.updateConfig(DownloadConfig(defaultDirectory = missing))
      }
      assertEquals(initial, ketch.status().config)
    }
  }

  @Test
  fun updateConfig_fileAsDirectory_throws() = runTest {
    val file = File(firstDir, "file.txt").apply { writeText("x") }
    withKetch(DownloadConfig(defaultDirectory = firstDir.path)) { ketch, _ ->
      assertFailsWith<IllegalArgumentException> {
        ketch.updateConfig(DownloadConfig(defaultDirectory = file.path))
      }
    }
  }

  @Test
  fun updateConfig_unchangedMissingDirectory_isAccepted() = runTest {
    // A folder deleted after startup must not block unrelated changes.
    val missing = File(firstDir, "missing").path
    withKetch(DownloadConfig(defaultDirectory = missing)) { ketch, _ ->
      val updated = DownloadConfig(
        defaultDirectory = missing,
        speedLimit = SpeedLimit.kbps(100),
      )
      ketch.updateConfig(updated)
      assertEquals(updated, ketch.status().config)
    }
  }

  @Test
  fun status_nullDirectory_reportsPlatformDefault() = runTest {
    withKetch(DownloadConfig(defaultDirectory = null)) { ketch, _ ->
      val expected = File(System.getProperty("user.home"), "Downloads").absolutePath
      assertEquals(expected, ketch.status().system.downloadDirectory)
    }
  }

  @Test
  fun updateConfig_connectionsPerDownload_appliesToNextResolve() = runTest {
    withKetch(DownloadConfig(maxConnectionsPerDownload = 4)) { ketch, _ ->
      ketch.updateConfig(DownloadConfig(maxConnectionsPerDownload = 2))
      assertEquals(2, ketch.resolve("https://example.com/file.bin").maxSegments)
    }
  }

  private suspend fun TestScope.withKetch(
    config: DownloadConfig,
    block: suspend (Ketch, RecordingSource) -> Unit,
  ) {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val source = RecordingSource()
    val ketch = Ketch(
      httpEngine = FakeHttpEngine(),
      config = config,
      additionalSources = listOf(source),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    try {
      block(ketch, source)
    } finally {
      ketch.close()
      runCurrent()
      firstDir.deleteRecursively()
      secondDir.deleteRecursively()
    }
  }

  /** Completes at once and records where the engine told it to write. */
  private class RecordingSource : DownloadSource {
    override val type = "fixture"
    override val managesOwnFileIo = true
    var outputPath: String? = null

    override fun canHandle(url: String): Boolean = url.startsWith("fixture:")

    override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
      ResolvedSource(
        url = url, sourceType = type, totalBytes = 4, supportsResume = true,
        suggestedFileName = "fixture.bin", maxSegments = 1,
      )

    override suspend fun download(context: DownloadContext) {
      outputPath = context.outputPath
      context.segments.value = listOf(Segment(index = 0, start = 0, end = 3, downloadedBytes = 4))
    }

    override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) =
      download(context)

    override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long): SourceResumeState =
      SourceResumeState(type, "{}")
  }
}
