package com.linroid.ketch.app.snapshot

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.app.state.waitsInQueue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The snapshot devices report every feature, so their waiting tasks must report positions as the
 * engine would; a waiting task without one reads "Starting", which the engine never reports for a
 * task behind full slots.
 */
class SampleQueueTest {
  @Test
  fun sampleDownloads_waitingTasks_reportEnginePositions() {
    assertEnginePositions(SampleData.downloads().tasks, SampleData.DOWNLOAD_CONFIG)
  }

  @Test
  fun showcaseStudio_waitingTasks_reportEnginePositions() {
    assertEnginePositions(ShowcaseData.studioTasks(), ShowcaseData.StudioConfig)
  }

  @Test
  fun download_slotsFull_waitsBehindTheOthers() = runTest {
    val api = SampleKetchApi(SampleData.downloads())

    val added = api.download(DownloadRequest("https://example.com/new.iso"))

    assertEquals(3, added.queuePosition.value)
  }

  @Test
  fun download_slotFree_starts() = runTest {
    val config = SampleData.DOWNLOAD_CONFIG.copy(maxConcurrentDownloads = 10)
    val api = SampleKetchApi(SampleData.downloads(config))

    val added = api.download(DownloadRequest("https://example.com/new.iso"))

    assertNull(added.queuePosition.value)
  }

  private fun assertEnginePositions(tasks: List<DownloadTask>, config: DownloadConfig) {
    val running = tasks.count { it.state.value is DownloadState.Downloading }
    val slots = config.maxConcurrentDownloads
    val waiting = tasks.filter { it.state.value.waitsInQueue }
      .sortedWith(compareBy({ -it.request.priority.ordinal }, { it.createdAt }))
    val expected = if (running >= slots) List(waiting.size) { it + 1 } else waiting.map { null }

    assertEquals(
      waiting.map { it.taskId }.zip(expected),
      waiting.map { it.taskId to it.queuePosition.value },
    )
  }
}
