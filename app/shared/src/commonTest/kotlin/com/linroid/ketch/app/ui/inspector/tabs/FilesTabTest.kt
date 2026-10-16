package com.linroid.ketch.app.ui.inspector.tabs

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.ListFixtures.START
import com.linroid.ketch.app.state.ListFixtures.downloading
import com.linroid.ketch.app.state.ListFixtures.row
import com.linroid.ketch.app.state.FileOrder
import com.linroid.ketch.app.state.FileSort
import com.linroid.ketch.app.state.SpeedHistory
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.TimelineEntry
import com.linroid.ketch.app.state.TimelineKind
import com.linroid.ketch.app.state.FileSelectionModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesTabTest {
  @Test
  fun taskFiles_segments_namedByIdFromTheResolvedList() = runTest {
    val row = torrent(
      files = listOf(SourceFile("0", "Show/S01E01.mkv", 100), SourceFile("1", "S01E02.mkv", 200)),
      segments = listOf(seg(0, 0, 99, 100), seg(1, 100, 299, 50), seg(4, 300, 349, 0)),
    )

    val files = taskFiles(row)

    assertEquals(listOf("Show/S01E01.mkv", "S01E02.mkv", null), files.map { it.path })
    assertEquals(listOf("S01E01.mkv", "S01E02.mkv", "File 5"), files.map { it.label }.load())
    assertEquals("File 5", files[2].pathText.load())
    assertEquals(listOf(100, 25, 0), files.map { it.percent })
    assertEquals(listOf(true, false, false), files.map { it.isDone })
  }

  @Test
  fun taskFiles_noSegmentsYet_listsTheSelection() {
    val row = torrent(
      files = listOf(SourceFile("0", "a.mkv", 100), SourceFile("1", "b.nfo", 2)),
      selected = setOf("0"),
      state = DownloadState.Queued,
    )

    val files = taskFiles(row)

    assertEquals(listOf("a.mkv"), files.map { it.path })
    assertEquals(0L, files.single().downloaded)
  }

  @Test
  fun taskFiles_completed_everyFileDone() {
    val row = torrent(
      files = listOf(SourceFile("0", "a.mkv", 100)),
      state = DownloadState.Completed("/downloads/a", totalBytes = 100),
    )

    assertEquals(listOf(true), taskFiles(row).map { it.isDone })
  }

  @Test
  fun filesSummary_partlyDone_countsFilesAndBytes() = runTest {
    val files = listOf(
      file("0", size = GIB, downloaded = GIB),
      file("1", size = GIB * 3, downloaded = GIB / 2)
    )

    assertEquals("1 of 2 files done" to "1.5 of 4.0 GB", filesSummary(files, listed = 2).load())
    assertEquals(
      "1 of 2 files done" to "1.5 of 4.0 GB · 3 not selected",
      filesSummary(files, listed = 5).load()
    )
  }

  @Test
  fun filesSummary_allDone_showsTheTotalSize() = runTest {
    val files = listOf(file("0", size = 1024, downloaded = 1024))

    assertEquals("1 file done" to "1.0 KB", filesSummary(files, listed = 0).load())
    assertEquals("All 2 files done", filesSummary(files + files, listed = 0).first.load())
  }

  @Test
  fun sortedFor_eachOrder_ordersTheFiles() {
    val files = listOf(
      file("0", path = "b.mkv", size = 10, downloaded = 10, start = 0, number = 1),
      file("1", path = "C.mkv", size = 30, downloaded = 0, start = 10, number = 2),
      file("2", path = "a.mkv", size = 20, downloaded = 5, start = 40, number = 3)
    )

    assertEquals(listOf("2", "1", "0"), files.sortedFor(FileSort(FileOrder.Progress)).map { it.id })
    assertEquals(listOf("2", "0", "1"), files.sortedFor(FileSort(FileOrder.Name)).map { it.id })
    assertEquals(listOf("1", "2", "0"), files.sortedFor(FileSort(FileOrder.Size)).map { it.id })
    assertEquals(listOf("0", "1", "2"), files.sortedFor(FileSort(FileOrder.Torrent)).map { it.id })
    assertEquals(
      listOf("1", "0", "2"),
      files.sortedFor(FileSort(FileOrder.Name).reverse()).map { it.id }
    )
  }

  @Test
  fun taskFiles_selectable_listsUnselectedFiles() = runTest {
    val row = torrent(
      files = listOf(
        SourceFile("0", "Show/S01E01.mkv", 100),
        SourceFile("1", "Show/S01E02.mkv", 200),
        SourceFile("2", "Show/sample.mkv", 50),
      ),
      segments = listOf(seg(1, 100, 299, 50)),
      selected = setOf("1"),
    )

    val files = taskFiles(row, selectable = true)

    assertEquals(listOf("0", "1", "2"), files.map { it.id })
    assertEquals(listOf(false, true, false), files.map { it.selected })
    assertEquals(listOf(0L, 50L, 0L), files.map { it.downloaded })
    assertEquals(listOf(0L, 100L, 300L), files.map { it.start })
    assertEquals(listOf("S01E01.mkv", "S01E02.mkv", "sample.mkv"), files.map { it.label }.load())
    assertEquals(listOf("1"), taskFiles(row).map { it.id })
    assertEquals(3, taskFileCount(row, selectable = true))
    assertEquals(1, taskFileCount(row))
  }

  @Test
  fun taskFiles_namesFromTheDevice_neverFallBackToNumbers() = runTest {
    val row = torrent(files = emptyList(), segments = listOf(seg(0, 0, 99, 10)))
    val listed = listOf(SourceFile("0", "Show/S01E01.mkv", 100), SourceFile("1", "b.mkv", 5))

    val files = taskFiles(row, listed, selectable = true)

    assertEquals(listOf("S01E01.mkv", "b.mkv"), files.map { it.label }.load())
    assertEquals(listOf(true, true), files.map { it.selected })
  }

  @Test
  fun toggle_lastSelectedFile_staysChecked() = runTest {
    val row = torrent(
      files = listOf(SourceFile("0", "a.mkv", 100), SourceFile("1", "b.mkv", 100)),
      selected = setOf("1"),
    )
    val model = FileSelectionModel(row.task, null, launch = { backgroundScope.launch(block = it) })
    val applied = setOf("1")

    model.toggle(listOf("1"), applied)

    assertTrue(model.keepOne)
    assertEquals(applied, model.checked(applied))
    assertNull(model.pending)
    model.toggle(listOf("0"), applied)
    assertFalse(model.keepOne)
    assertEquals(setOf("0", "1"), model.checked(applied))
  }

  @Test
  fun taskFileCount_awaiting_countsEveryFile() {
    val awaiting = DownloadState.Paused(DownloadProgress(0, 300), PauseReason.AwaitingFileSelection)
    val row = torrent(
      files = listOf(SourceFile("0", "a.mkv", 100), SourceFile("1", "b.nfo", 2)),
      state = awaiting,
    )

    assertEquals(2, taskFileCount(row, selectable = true))
    assertEquals(2, taskFileCount(row))
    assertEquals(listOf(true, true), taskFiles(row, selectable = true).map { it.selected })
  }

  @Test
  fun taskFiles_singleChoice_marksTheChosenVariantWithTheTaskProgress() = runTest {
    val row = variants(state = DownloadState.Downloading(DownloadProgress(150, 600, 10)))

    val files = taskFiles(row, selectable = true)

    assertEquals(listOf(true, false), files.map { it.selected })
    assertEquals(listOf(150L, 0L), files.map { it.downloaded })
    assertEquals(listOf("hd"), taskFiles(row).map { it.id })
    assertEquals(1, taskFileCount(row))
    assertEquals(2, taskFileCount(row, selectable = true))
    val chosen = variants(selected = setOf("sd"), state = DownloadState.Queued)
    assertEquals(listOf(false, true), taskFiles(chosen, selectable = true).map { it.selected })
  }

  @Test
  fun inspectorTabs_variantsOnASelectingDevice_showFilesWithoutBeingATorrent() {
    val row = variants(state = DownloadState.Queued)
    val features = setOf(KetchFeatures.FILE_SELECTION)

    assertFalse(row.isTorrent)
    assertTrue(filesSelectable(row, features))
    assertFalse(filesSelectable(row, emptySet()))
    assertEquals(
      listOf(InspectorTab.Overview, InspectorTab.Files),
      inspectorTabs(row, history = null, timeline = emptyList(), features = features)
    )
    assertEquals(2, InspectorTab.Files.count(row, features))
  }

  @Test
  fun filesSelectable_httpTaskWithoutFiles_isFalse() {
    val row = row("a", downloading(500, total = 2000))

    assertFalse(filesSelectable(row, setOf(KetchFeatures.FILE_SELECTION)))
  }

  @Test
  fun matching_query_filtersByPathIgnoringCase() {
    val files = listOf(file("0", path = "Show/S01E01.mkv"), file("1", path = "extras/info.nfo"))

    assertEquals(listOf("0"), files.matching(" show ").map { it.id })
    assertEquals(listOf("0", "1"), files.matching("").map { it.id })
  }

  @Test
  fun blocks_doneNeighbours_shareOneBlock() {
    val files = listOf(
      file("0", size = 10, downloaded = 10, start = 0),
      file("1", size = 20, downloaded = 20, start = 10),
      file("2", size = 0, start = 30),
      file("3", size = 30, downloaded = 5, start = 30),
      file("4", size = 10, downloaded = 10, start = 60)
    )

    val blocks = files.blocks()

    assertEquals(listOf(0L to 29L, 30L to 59L, 60L to 69L), blocks.map { it.start to it.end })
    assertEquals(listOf(30L, 5L, 10L), blocks.map { it.downloadedBytes })
  }

  @Test
  fun inspectorTabs_threeHundredFileTorrent_showsFilesAndNoConnections() {
    val sources = (0 until 300).map { SourceFile(it.toString(), "Pack/file-$it.bin", 1000) }
    val segments = (0 until 300).map { seg(it, it * 1000L, it * 1000L + 999, 500) }
    val row = torrent(files = sources, segments = segments)

    val tabs = inspectorTabs(row, history = null, timeline = emptyList())

    assertEquals(listOf(InspectorTab.Overview, InspectorTab.Files), tabs)
    assertEquals(300, InspectorTab.Files.count(row))
    assertEquals(300, taskFiles(row).size)
  }

  @Test
  fun taskFileCount_withAndWithoutSegments_countsWhatTheTabLists() {
    val files = listOf(SourceFile("0", "a.mkv", 100), SourceFile("1", "b.nfo", 2))
    val selection = torrent(files = files, selected = setOf("1"), state = DownloadState.Queued)
    val running = torrent(files = files, segments = listOf(seg(0, 0, 99, 10), seg(1, 100, 101, 0)))

    assertEquals(taskFiles(selection).size, taskFileCount(selection))
    assertEquals(1, taskFileCount(selection))
    assertEquals(taskFiles(running).size, taskFileCount(running))
    assertEquals(2, taskFileCount(running))
  }

  @Test
  fun inspectorTabs_segmentedHttpTask_showsConnectionsAndActivity() {
    val row = row("a", downloading(500, total = 2000))
      .copy(segments = listOf(seg(0, 0, 999, 1000), seg(1, 1000, 1999, 0)))
    val history = SpeedHistory.of(100, START)

    val tabs = inspectorTabs(row, history, timeline = emptyList())

    assertEquals(
      listOf(InspectorTab.Overview, InspectorTab.Connections, InspectorTab.Activity),
      tabs
    )
    assertEquals(1, InspectorTab.Connections.count(row))
    assertNull(InspectorTab.Activity.count(row))
  }

  @Test
  fun inspectorTabs_nothingToShow_onlyOverview() {
    val completed = row("a", DownloadState.Completed("/downloads/a.bin", totalBytes = 1000))
      .copy(segments = listOf(seg(0, 0, 999, 1000)))
    val magnet = torrent(files = emptyList(), state = downloading(0))

    assertEquals(listOf(InspectorTab.Overview), inspectorTabs(completed, null, emptyList()))
    assertEquals(listOf(InspectorTab.Overview), inspectorTabs(magnet, null, emptyList()))
  }

  @Test
  fun inspectorTabs_timelineOnly_showsActivity() {
    val row = row("a", DownloadState.Queued)
    val timeline = listOf(TimelineEntry(START, TimelineKind.Added, verbatim("Added")))

    assertEquals(
      listOf(InspectorTab.Overview, InspectorTab.Activity),
      inspectorTabs(row, null, timeline)
    )
  }

  private fun torrent(
    files: List<SourceFile>,
    segments: List<Segment> = emptyList(),
    selected: Set<String> = emptySet(),
    state: DownloadState = DownloadState.Downloading(DownloadProgress(0, 1000, 10)),
  ): TaskRow {
    val request = DownloadRequest(
      url = "magnet:?xt=urn:btih:4c7f3e2b9d1a8f6e5c0b7a3d2e1f9c8b7a6d5e4f",
      selectedFileIds = selected,
      resolvedSource = ResolvedSource(
        url = "magnet:?xt=urn:btih:4c7f3e2b9d1a8f6e5c0b7a3d2e1f9c8b7a6d5e4f",
        sourceType = "torrent",
        totalBytes = files.sumOf { it.size },
        supportsResume = true,
        suggestedFileName = null,
        maxSegments = 1,
        files = files,
      ),
    )
    return row("t", state, request = request).copy(segments = segments)
  }

  /** A stream whose source offers two variants, one of which downloads. */
  private fun variants(
    selected: Set<String> = emptySet(),
    state: DownloadState,
  ): TaskRow {
    val url = "https://example.com/show/master.m3u8"
    val request = DownloadRequest(
      url = url,
      selectedFileIds = selected,
      resolvedSource = ResolvedSource(
        url = url,
        sourceType = "hls",
        totalBytes = 900,
        supportsResume = false,
        suggestedFileName = "show.ts",
        maxSegments = 1,
        files = listOf(SourceFile("hd", "1080p.ts", 600), SourceFile("sd", "480p.ts", 300)),
        selectionMode = FileSelectionMode.SINGLE,
      ),
    )
    return row("v", state, request = request)
  }

  private suspend fun Pair<UiText, UiText>.load(): Pair<String, String> =
    first.load() to second.load()

  private fun file(
    id: String,
    path: String = "file-$id.bin",
    size: Long = 100,
    downloaded: Long = 0,
    start: Long = 0,
    number: Int = 0,
  ) = TaskFile(
    id = id,
    path = path,
    start = start,
    size = size,
    downloaded = downloaded,
    number = number,
  )

  private fun seg(index: Int, start: Long, end: Long, downloaded: Long) =
    Segment(index = index, start = start, end = end, downloadedBytes = downloaded)

  private companion object {
    const val GIB = 1L shl 30
  }
}
