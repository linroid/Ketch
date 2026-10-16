package com.linroid.ketch.app.feedback

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.state.ListTestTask
import com.linroid.ketch.app.state.StatusFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OngoingDownloadsTest {

  private fun downloading(downloaded: Long, total: Long, speed: Long = 0) =
    DownloadState.Downloading(DownloadProgress(downloaded, total, speed))

  private fun task(
    id: String,
    state: DownloadState,
    segments: List<Segment> = emptyList(),
    position: Int? = null,
    url: String = "https://example.com/$id.bin",
  ) = ListTestTask(
    id,
    state,
    DownloadRequest(url),
    segments = segments,
    queuePosition = MutableStateFlow(position),
  )

  // Waits in the queue at position; a queued task without one holds a slot and is starting.
  private fun queued(id: String, position: Int) =
    task(id, DownloadState.Queued, position = position)

  private fun starting(id: String, url: String = "https://example.com/$id.bin") =
    task(id, DownloadState.Queued, url = url)

  // Connection ranges of equal length covering the first size bytes.
  private fun ranges(count: Int, size: Long): List<Segment> {
    val each = size / count
    return List(count) { Segment(it, it * each, (it + 1) * each - 1) }
  }

  @Test
  fun of_nothingDownloadingOrQueued_returnsNull() = runTest {
    val tasks = listOf(
      task("done", DownloadState.Completed("/downloads/done.bin", 10)),
      task("paused", DownloadState.Paused(DownloadProgress(5, 10))),
      task("failed", DownloadState.Failed(KetchError.Network()))
    )

    assertNull(OngoingDownloads.of(tasks))
  }

  @Test
  fun of_severalDownloading_reportsCountSpeedSizeAndTimeLeft() = runTest {
    val tasks = listOf(
      task("a", downloading(512 * MB, GB, speed = MB)),
      task("b", downloading(256 * MB, GB, speed = MB)),
      task("c", downloading(0, GB, speed = 2 * MB)),
    )

    val downloads = assertNotNull(OngoingDownloads.of(tasks))

    assertEquals("Downloading 3 files · 4.0 MB/s", downloads.title.load())
    assertEquals("768.0 MB of 3.00 GB · about 10 min left", downloads.text.load())
    assertEquals(250, downloads.permille)
    assertEquals(StatusFilter.Downloading, downloads.filter)
    assertNull(downloads.lanes)
  }

  @Test
  fun of_timeLeftUnderAMinuteOrOverAnHour_saysSo() = runTest {
    val soon = OngoingDownloads.of(listOf(task("a", downloading(0, 1000, speed = 100))))
    val later = OngoingDownloads.of(listOf(task("a", downloading(0, 3_900_000, speed = 1000))))

    assertEquals("0 B of 1000 B · less than a minute left", soon?.text.load())
    assertEquals("about 1h 5m left", later?.text.load()?.substringAfter(" · "))
  }

  @Test
  fun of_sizeUnknown_isIndeterminateAndShowsBytesSoFar() = runTest {
    val downloads = assertNotNull(OngoingDownloads.of(listOf(task("a", downloading(5 * MB, 0)))))

    assertEquals("Downloading 1 file", downloads.title.load())
    assertEquals("5.0 MB", downloads.text.load())
    assertNull(downloads.permille)
    assertNull(downloads.percent)
    assertEquals(listOf("a.bin  5.0 MB"), downloads.lines.load())
    assertEquals(listOf(OngoingDownloads.PROGRESS_MAX), downloads.lanes)
  }

  @Test
  fun of_moreBytesThanTheSize_capsProgressAtFull() = runTest {
    val downloads = assertNotNull(OngoingDownloads.of(listOf(task("a", downloading(1100, 1000)))))

    assertEquals(OngoingDownloads.PROGRESS_MAX, downloads.permille)
    assertEquals(listOf("a.bin  100%"), downloads.lines.load())
  }

  @Test
  fun of_everyTaskQueued_waitsOnTheWaitingTab() = runTest {
    val tasks = listOf(queued("a", 1), queued("b", 2))

    val downloads = assertNotNull(OngoingDownloads.of(tasks))

    assertEquals("Waiting to download 2 files", downloads.title.load())
    assertNull(downloads.text.load())
    assertNull(downloads.permille)
    assertEquals(listOf("a.bin  Waiting", "b.bin  Waiting"), downloads.lines.load())
    assertEquals(StatusFilter.Waiting, downloads.filter)
  }

  @Test
  fun of_moreFilesThanLines_listsDownloadsFirstAndCountsTheRest() = runTest {
    val tasks = List(3) { queued("q$it", it + 1) } +
      List(4) { task("d$it", downloading(500, 1000, speed = 100)) }

    val downloads = assertNotNull(OngoingDownloads.of(tasks))

    assertEquals(
      listOf(
        "d0.bin  50% · 100 B/s",
        "d1.bin  50% · 100 B/s",
        "d2.bin  50% · 100 B/s",
        "d3.bin  50% · 100 B/s",
        "q0.bin  Waiting"
      ),
      downloads.lines.load()
    )
    assertEquals(2, downloads.more)
  }

  @Test
  fun of_singleDownload_givesOneLanePerConnection() = runTest {
    val tasks = listOf(task("a", downloading(450, 1000), ranges(4, 1000)))

    val downloads = assertNotNull(OngoingDownloads.of(tasks))

    assertEquals(listOf(250, 250, 250, 250), downloads.lanes)
    assertEquals(45, downloads.percent)
    assertEquals(450, downloads.lanePosition)
  }

  @Test
  fun of_moreConnectionsThanAndroidDraws_mergesNeighbouringLanes() = runTest {
    val tasks = listOf(task("a", downloading(0, 1600), ranges(16, 1600)))

    val lanes = assertNotNull(OngoingDownloads.of(tasks)?.lanes)

    assertEquals(List(8) { 125 }, lanes)
  }

  @Test
  fun of_singleDownloadWithAQueue_hasNoLanes() = runTest {
    val tasks = listOf(
      task("a", downloading(450, 1000), ranges(4, 1000)),
      queued("b", 1)
    )

    assertNull(OngoingDownloads.of(tasks)?.lanes)
  }

  @Test
  fun of_magnetStarting_downloadsAndFindsPeers() = runTest {
    val tasks = listOf(starting("m", url = "magnet:?xt=urn:btih:$INFO_HASH&dn=ubuntu.iso"))

    val downloads = assertNotNull(OngoingDownloads.of(tasks))

    assertEquals("Downloading 1 file", downloads.title.load())
    assertNull(downloads.text.load())
    assertNull(downloads.permille)
    assertEquals(listOf("ubuntu.iso  Finding peers"), downloads.lines.load())
    assertNull(downloads.lanes)
    assertEquals(StatusFilter.Downloading, downloads.filter)
  }

  @Test
  fun of_linkStarting_saysStarting() = runTest {
    val downloads = assertNotNull(OngoingDownloads.of(listOf(starting("a"))))

    assertEquals(listOf("a.bin  Starting"), downloads.lines.load())
  }

  @Test
  fun of_startingBesideADownload_countsItWithoutProgress() = runTest {
    val tasks = listOf(
      starting("s"),
      task("d", downloading(450, 1000, speed = 100), ranges(4, 1000)),
    )

    val downloads = assertNotNull(OngoingDownloads.of(tasks))

    assertEquals("Downloading 2 files · 100 B/s", downloads.title.load())
    assertEquals("450 B of 1000 B · less than a minute left", downloads.text.load())
    assertEquals(450, downloads.permille)
    assertNull(downloads.lanes)
  }

  @Test
  fun of_startingAndQueued_listsStartingBeforeWaiting() = runTest {
    val tasks = listOf(queued("q", 1), starting("s"), task("d", downloading(500, 1000)))

    val downloads = assertNotNull(OngoingDownloads.of(tasks))

    assertEquals(
      listOf("d.bin  50%", "s.bin  Starting", "q.bin  Waiting"),
      downloads.lines.load()
    )
    assertEquals(StatusFilter.Downloading, downloads.filter)
  }

  @Test
  fun of_queuedOnADeviceWithoutPositions_waits() = runTest {
    val downloads = assertNotNull(OngoingDownloads.of(listOf(starting("a")), features = emptySet()))

    assertEquals("Waiting to download 1 file", downloads.title.load())
    assertEquals(listOf("a.bin  Waiting"), downloads.lines.load())
    assertEquals(StatusFilter.Waiting, downloads.filter)
  }

  private companion object {
    const val MB = 1024L * 1024
    const val GB = 1024L * MB
    const val INFO_HASH = "3f2a91c0d4e5f60718293a4b5c6d7e8f90a1b2c3"
  }
}
