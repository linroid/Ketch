package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.torrent.TorrentCapabilities
import com.linroid.ketch.api.torrent.TorrentController
import com.linroid.ketch.api.torrent.TorrentFileEntry
import com.linroid.ketch.api.torrent.TorrentFileOrder
import com.linroid.ketch.api.torrent.TorrentFilePage
import com.linroid.ketch.api.torrent.TorrentPageRequest
import com.linroid.ketch.api.torrent.TorrentRevision
import com.linroid.ketch.api.torrent.TorrentSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TorrentFilesModelTest {
  private val files = listOf(
    SourceFile("0", "Show/S01E01.mkv", 1_000),
    SourceFile("1", "Show/S01E02.mkv", 1_000),
    SourceFile("2", "Show/sample.mkv", 100),
  )
  private val all = setOf("0", "1", "2")

  @Test
  fun load_withoutResolvedSource_pagesTheController() = runTest {
    val controller = PagedController(files, pageSize = 2)
    val task = RecordingKetchApi().add(downloading(), DownloadRequest(MAGNET))
    val model = model(task, controller)

    assertTrue(model.needsNames(task.request))
    model.load()

    assertEquals(files, model.listed)
    assertEquals(files, model.filesOf(task.request))
    assertEquals(listOf(null, "2"), controller.cursors)
    assertFalse(model.needsNames(task.request))
  }

  @Test
  fun load_resolvedSource_keepsItsNames() = runTest {
    val controller = PagedController(files, pageSize = 2)
    val request = DownloadRequest(MAGNET, resolvedSource = source())
    val task = RecordingKetchApi().add(downloading(), request)
    val model = model(task, controller)

    assertFalse(model.needsNames(request))
    assertSame(request.resolvedSource?.files, model.filesOf(request))
  }

  @Test
  fun apply_changedChecks_callsSelectFiles() = runTest {
    val task = RecordingKetchApi().add(downloading(), DownloadRequest(MAGNET))
    val model = model(task)

    model.toggle(listOf("2"), applied = all)
    assertTrue(model.isDirty(all, waiting = false))
    model.apply(all)
    runCurrent()

    assertEquals(listOf("select 0,1"), task.calls)
    assertEquals(setOf("0", "1"), task.request.selectedFileIds)
    model.sync(task.request.selectedFileIds)
    assertNull(model.pending)
    assertFalse(model.applying)
  }

  @Test
  fun apply_waitingTask_startsWithChosenFiles() = runTest {
    val waiting = DownloadState.Paused(DownloadProgress(0, 0), PauseReason.AwaitingFileSelection)
    val task = RecordingKetchApi().add(waiting, DownloadRequest(MAGNET, resolvedSource = source()))
    val model = model(task)
    val applied = appliedSelection(task.request, task.state.value, files)

    assertEquals(all, applied)
    assertTrue(model.isDirty(applied, waiting = true))
    model.toggle(listOf("0", "1"), applied)
    model.apply(applied)
    runCurrent()

    assertEquals(listOf("select 2"), task.calls)
    assertEquals(DownloadState.Downloading(RecordingTask.PROGRESS), task.state.value)
  }

  @Test
  fun apply_failure_revertsAndReportsFailure() = runTest {
    val task = RecordingKetchApi().add(downloading(), DownloadRequest(MAGNET))
    task.failure = IllegalStateException("A file is in the way")
    val failures = mutableListOf<Throwable>()
    val model = model(task, onFailure = { failures += it })

    model.toggle(listOf("0"), applied = all)
    model.apply(all)
    runCurrent()

    assertEquals(listOf("A file is in the way"), failures.map { it.message })
    assertNull(model.pending)
    assertEquals(all, model.checked(all))
    assertFalse(model.applying)
  }

  @Test
  fun toggle_backToTheAppliedFiles_isNoLongerPending() = runTest {
    val task = RecordingKetchApi().add(downloading(), DownloadRequest(MAGNET))
    val model = model(task)

    model.toggle(listOf("2"), applied = all)
    model.toggle(listOf("2"), applied = all)

    assertNull(model.pending)
    assertFalse(model.isDirty(all, waiting = false))
  }

  private fun TestScope.model(
    task: RecordingTask,
    controller: TorrentController? = null,
    onFailure: (Throwable) -> Unit = {},
  ) = TorrentFilesModel(
    task = task,
    controller = controller,
    launch = { block -> backgroundScope.launch(block = block) },
    onFailure = onFailure,
  )

  private fun downloading(): DownloadState = DownloadState.Downloading(RecordingTask.PROGRESS)

  private fun source() = ResolvedSource(
    url = MAGNET,
    sourceType = "torrent",
    totalBytes = 2_100,
    supportsResume = true,
    suggestedFileName = "Show",
    maxSegments = 3,
    files = files,
  )

  private companion object {
    const val MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Show"
  }
}

/** A controller that lists [files] in pages of [pageSize], recording the cursors it was given. */
internal class PagedController(
  private val files: List<SourceFile>,
  private val pageSize: Int,
) : TorrentController {
  val cursors = mutableListOf<String?>()
  private val revision = TorrentRevision("epoch", 1)

  override suspend fun capabilities(): TorrentCapabilities = TorrentCapabilities()

  override suspend fun snapshot(taskId: String): TorrentSnapshot? = null

  override fun observe(taskId: String): Flow<TorrentSnapshot?> = emptyFlow()

  override suspend fun files(
    taskId: String,
    page: TorrentPageRequest,
    order: TorrentFileOrder,
    descending: Boolean,
  ): TorrentFilePage {
    cursors += page.cursor
    val start = page.cursor?.toInt() ?: 0
    val end = minOf(start + pageSize, files.size)
    val entries = files.subList(start, end).map { TorrentFileEntry(it.id, it.name, it.size, true) }
    val next = end.takeIf { it < files.size }?.toString()
    return TorrentFilePage(taskId, revision, 0, files.size, entries, next)
  }
}
