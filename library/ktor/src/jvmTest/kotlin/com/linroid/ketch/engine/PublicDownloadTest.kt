package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.Ketch
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Real HTTPS smoke tests; enable with -PpublicDownloadTests=true. */
class PublicDownloadTest {
  @Test
  fun githubReleaseFile_downloadsWithExpectedChecksum() = publicDownload { ketch, directory ->
    val task = ketch.download(
      DownloadRequest(
        url = "https://raw.githubusercontent.com/git/git/v2.46.0/README.md",
        destination = Destination(File(directory, "README.md").absolutePath),
        connections = 1,
      )
    )
    val file = completedFile(task)
    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
      .joinToString("") { "%02x".format(it) }
    assertEquals(3652L, file.length())
    assertEquals("4f4e044593fd16cce4a6afa7af10f4f9b4cceece89480e71c9e22a5cde67f241", digest)
  }

  @Test
  fun redirect_segmentedDownload_preservesEveryByte() = publicDownload { ketch, directory ->
    val task = ketch.download(
      DownloadRequest(
        url = "https://httpbingo.org/redirect-to?url=%2Frange%2F262144",
        destination = Destination(File(directory, "redirect.bin").absolutePath),
        connections = 4,
      )
    )
    assertContentEquals(rangeContent(), completedFile(task).readBytes())
    assertEquals(4, task.segments.value.size)
    assertTrue(task.segments.value.all { it.isComplete })
  }

  @Test
  fun pauseResume_segmentedDownload_preservesEveryByte() = publicDownload { ketch, directory ->
    val task = ketch.download(
      DownloadRequest(
        url = "https://httpbingo.org/range/262144",
        destination = Destination(File(directory, "resumed.bin").absolutePath),
        connections = 4,
        speedLimit = SpeedLimit.kbps(32),
      )
    )
    val downloading = task.state.first {
      it.isTerminal || it is DownloadState.Downloading && it.progress.downloadedBytes > 0
    }
    assertIs<DownloadState.Downloading>(downloading)
    task.pause()
    val paused = assertIs<DownloadState.Paused>(task.state.value)
    assertTrue(paused.progress.downloadedBytes in 1L until 262144L)
    task.setSpeedLimit(SpeedLimit.Unlimited)
    task.resume()
    assertContentEquals(rangeContent(), completedFile(task).readBytes())
    assertTrue(task.segments.value.all { it.isComplete })
  }

  private fun rangeContent(): ByteArray = ByteArray(262144) { ('a'.code + it % 26).toByte() }

  private suspend fun completedFile(task: DownloadTask): File {
    val state = task.state.first { it.isTerminal }
    return File(assertIs<DownloadState.Completed>(state, "Download ended as $state").outputPath)
  }

  private fun publicDownload(block: suspend (Ketch, File) -> Unit) = runTest(timeout = 90.seconds) {
    // Real I/O and rate limits must use wall-clock time, not runTest's virtual clock.
    withContext(Dispatchers.IO) {
      val directory = Files.createTempDirectory("ketch-public-download-").toFile()
      val ketch = Ketch(
        httpEngine = KtorHttpEngine(),
        config = DownloadConfig(retryCount = 1, progressIntervalMs = 20),
      )
      try {
        block(ketch, directory)
      } finally {
        ketch.close()
        directory.deleteRecursively()
      }
    }
  }
}
