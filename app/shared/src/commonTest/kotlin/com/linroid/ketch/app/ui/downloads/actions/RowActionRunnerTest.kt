package com.linroid.ketch.app.ui.downloads.actions

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.TaskKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RowActionRunnerTest {
  private val downloading = DownloadState.Downloading(DownloadProgress(10, 100, 5))
  private val completed = DownloadState.Completed("/downloads/a.iso", totalBytes = 100)

  @Test
  fun batch_mixedSelection_countsTheRowsEachActionAppliesTo() = runTest {
    val f = ActionsFixture(this)
    val rows = listOf(
      f.add(downloading),
      f.add(downloading),
      f.add(completed),
      f.add(DownloadState.Failed(KetchError.Network())),
      f.add(DownloadState.Canceled),
    ).map { rowOf(it) }

    val counts = f.runner.batch(rows).associate { it.action to it.count }

    assertEquals(2, counts[RowAction.Pause])
    assertEquals(2, counts[RowAction.Retry])
    assertEquals(1, counts[RowAction.Open])
    assertEquals(5, counts[RowAction.Remove])
    assertEquals(5, counts[RowAction.CopyLink])
    assertFalse(RowAction.Resume in counts)
    f.close()
  }

  @Test
  fun run_pauseOnSelection_reportsTheRowsItLeftAlone() = runTest {
    val f = ActionsFixture(this)
    val running = listOf(f.add(downloading), f.add(downloading))
    val done = f.add(completed)
    val rows = (running + done).map { rowOf(it) }

    f.runner.run(RowAction.Pause, rows)
    runCurrent()

    running.forEach { assertEquals(listOf("pause"), it.calls) }
    assertTrue(done.calls.isEmpty())
    val message = f.messages().last()
    assertEquals("Paused 2 downloads · 1 already finished", message.title)
    assertEquals(MessageLevel.Success, message.level)
    f.close()
  }

  @Test
  fun run_pauseWhereOneFails_warnsAndRetriesOnlyThatOne() = runTest {
    val f = ActionsFixture(this)
    val good = f.add(downloading)
    val bad = f.add(downloading).apply { failure = IllegalStateException("Connection lost") }

    f.runner.run(RowAction.Pause, listOf(rowOf(good), rowOf(bad)))
    runCurrent()

    val message = f.messages().last()
    assertEquals("Paused 1 download · 1 failed", message.title)
    assertEquals(MessageLevel.Warning, message.level)
    assertEquals("Connection lost", message.detail)
    bad.failure = null
    message.actions.single { it.label == "Try again" }.onClick()
    runCurrent()
    assertEquals(listOf("pause"), good.calls)
    assertEquals(listOf("pause", "pause"), bad.calls)
    f.close()
  }

  @Test
  fun run_pauseAfterTheScreenLeft_stillPausesEveryRow() = runTest {
    val f = ActionsFixture(this)
    val tasks = List(2) { f.add(downloading) }
    val left = CoroutineScope(Job().apply { cancel() })
    val runner = RowActionRunner(f.state, f.runner.commands, f.files, f.clipboard, left)

    runner.run(RowAction.Pause, tasks.map { rowOf(it) })
    runCurrent()

    tasks.forEach { assertEquals(listOf("pause"), it.calls) }
    assertEquals("Paused 2 downloads", f.messages().last().title)
    f.close()
  }

  @Test
  fun run_pauseWhereAllFail_postsOneErrorNamingTheDevice() = runTest {
    val f = ActionsFixture(this)
    val tasks = List(2) {
      f.add(downloading).apply { failure = IllegalStateException("Connection lost") }
    }

    f.runner.run(RowAction.Pause, tasks.map { rowOf(it) })
    runCurrent()

    val errors = f.messages().filter { it.level == MessageLevel.Error }
    assertEquals(listOf("Couldn't pause 2 downloads on This Mac"), errors.map { it.title })
    f.close()
  }

  @Test
  fun run_copyLinksOfSelection_copiesOneLinePerRow() = runTest {
    val f = ActionsFixture(this)
    val rows = listOf("a", "b").map {
      rowOf(f.add(downloading, DownloadRequest("https://example.com/$it.iso")))
    }

    f.runner.run(RowAction.CopyLink, rows)
    runCurrent()

    val copied = "https://example.com/a.iso\nhttps://example.com/b.iso"
    assertEquals(listOf(copied), f.clipboard.written)
    assertEquals("Copied 2 links", f.messages().last().title)
    f.close()
  }

  @Test
  fun shareSpeed_threeRows_capsEachAtAThird() = runTest {
    val f = ActionsFixture(this)
    val tasks = List(3) { f.add(downloading) }

    f.runner.shareSpeed(tasks.map { rowOf(it) }, SpeedLimit.of(3_000_000))
    runCurrent()

    tasks.forEach { assertEquals(SpeedLimit.of(1_000_000), it.request.speedLimit) }
    f.close()
  }

  @Test
  fun run_stopAndDiscard_asksFirst() = runTest {
    val f = ActionsFixture(this)
    val row = rowOf(f.add(downloading))

    f.runner.run(RowAction.StopAndDiscard, listOf(row))

    assertEquals(RowDialog.Discard(listOf(row)), f.runner.dialog)
    f.close()
  }

  @Test
  fun menu_completedRowWhereFilesGoToTheTrash_offersRemoveAndTrash() = runTest {
    val f = ActionsFixture(this, canTrash = true)
    val row = rowOf(f.add(completed))

    val menu = f.runner.menu(row)

    assertTrue(RowAction.RemoveAndTrash in menu)
    assertFalse(RowAction.RemoveAndDelete in menu)
    assertFalse(RowAction.RemoveAndTrash in f.runner.menu(rowOf(row.task, RemoteDevice)))
    f.close()
  }

  @Test
  fun checkFile_missingFile_offersDownloadAgainInsteadOfOpen() = runTest {
    val f = ActionsFixture(this)
    val row = rowOf(f.add(completed))
    f.files.missing += "/downloads/a.iso"

    f.runner.checkFile(row)
    runCurrent()

    assertTrue(f.runner.isFileMissing(row))
    assertEquals(listOf(RowAction.DownloadAgain), f.runner.hover(row))
    assertFalse(RowAction.Open in f.runner.menu(row))
    f.close()
  }

  @Test
  fun clearMissing_someFilesGone_removesOnlyThoseOnceTheUndoWindowEnds() = runTest {
    val f = ActionsFixture(this)
    val gone = f.add(completed)
    val kept = f.add(DownloadState.Completed("/downloads/b.iso", totalBytes = 100))
    val running = f.add(downloading)
    backgroundScope.launch { f.state.tasks.collect {} }
    runCurrent()
    f.files.missing += "/downloads/a.iso"

    f.runner.clearMissing(listOf(gone, kept).map { rowOf(it) })
    runCurrent()

    assertEquals(listOf(kept, running), f.state.tasks.value)
    val message = f.messages().last()
    assertEquals("Cleared 1 download with a missing file", message.title)
    assertTrue(message.actions.any { it.label == "Undo" })
    assertTrue(gone.calls.isEmpty())
    advanceTimeBy(7.seconds)
    runCurrent()
    assertEquals(listOf("remove deleteFiles=false"), gone.calls)
    assertTrue(kept.calls.isEmpty())
    assertTrue(running.calls.isEmpty())
    f.close()
  }

  @Test
  fun clearMissing_everyFileInPlace_removesNothing() = runTest {
    val f = ActionsFixture(this)
    val task = f.add(completed)

    f.runner.clearMissing(listOf(rowOf(task)))
    advanceTimeBy(7.seconds)
    runCurrent()

    assertTrue(task.calls.isEmpty())
    val message = f.messages().last()
    assertEquals("No missing files to clear", message.title)
    assertEquals(MessageLevel.Info, message.level)
    f.close()
  }

  @Test
  fun clearMissing_filePutBackSinceChecked_readsAsPresentAgain() = runTest {
    val f = ActionsFixture(this)
    val task = f.add(completed)
    val row = rowOf(task)
    f.files.missing += "/downloads/a.iso"
    f.runner.checkFile(row)
    runCurrent()
    f.files.missing.clear()

    f.runner.clearMissing(listOf(row))
    runCurrent()

    assertFalse(f.runner.isFileMissing(row))
    assertTrue(task.calls.isEmpty())
    assertEquals("No missing files to clear", f.messages().last().title)
    f.close()
  }

  @Test
  fun remove_withFilesToTheTrash_removesThenTrashesOnceTheUndoWindowEnds() = runTest {
    val f = ActionsFixture(this, canTrash = true)
    val task = f.add(completed)
    backgroundScope.launch { f.state.tasks.collect {} }
    runCurrent()
    val row = rowOf(task)

    f.runner.remove(listOf(row), withFiles = true)
    runCurrent()

    assertTrue(f.state.tasks.value.isEmpty())
    assertTrue(task.calls.isEmpty())
    advanceTimeBy(7.seconds)
    runCurrent()
    assertEquals(listOf("remove deleteFiles=false"), task.calls)
    assertEquals(listOf("trash /downloads/a.iso"), f.files.calls)
    assertEquals("Moved 1 file to the Trash", f.messages().last().title)
    f.close()
  }

  @Test
  fun remove_trashRefusesTheFile_keepsTheTask() = runTest {
    val f = ActionsFixture(this, canTrash = true)
    val task = f.add(completed)
    f.files.refused += "/downloads/a.iso"

    f.runner.remove(listOf(rowOf(task)), withFiles = true)
    advanceTimeBy(7.seconds)
    runCurrent()

    assertTrue(task.calls.isEmpty())
    val error = f.messages().last()
    assertTrue(error.title.startsWith("Couldn't move "), error.title)
    assertTrue(error.title.endsWith(" to the Trash"), error.title)
    assertEquals("The download stays in the list", error.detail)
    f.close()
  }

  @Test
  fun remove_finishedFileAlreadyGone_removesTheTaskWithoutTrashing() = runTest {
    val f = ActionsFixture(this, canTrash = true)
    val task = f.add(completed)
    f.files.missing += "/downloads/a.iso"

    f.runner.remove(listOf(rowOf(task)), withFiles = true)
    advanceTimeBy(7.seconds)
    runCurrent()

    assertEquals(listOf("remove deleteFiles=false"), task.calls)
    assertTrue(f.files.calls.isEmpty())
    f.close()
  }

  @Test
  fun remove_withFilesUndone_keepsTheTaskAndFile() = runTest {
    val f = ActionsFixture(this, canTrash = true)
    val task = f.add(completed)
    backgroundScope.launch { f.state.tasks.collect {} }
    runCurrent()

    f.runner.remove(listOf(rowOf(task)), withFiles = true)
    runCurrent()
    f.messages().last().actions.single { it.label == "Undo" }.onClick()
    advanceTimeBy(7.seconds)
    runCurrent()

    assertEquals(listOf(task.taskId), f.state.tasks.value.map { it.taskId })
    assertTrue(task.calls.isEmpty())
    assertTrue(f.files.calls.isEmpty())
    f.close()
  }

  @Test
  fun remove_finishedAndPartialFilesWithTrash_trashesOneAndDeletesTheOther() = runTest {
    val f = ActionsFixture(this, canTrash = true)
    val done = f.add(completed)
    val partial = f.add(DownloadState.Paused(DownloadProgress(40, 100)))
    val canceled = f.add(DownloadState.Canceled)

    f.runner.remove(listOf(done, partial, canceled).map { rowOf(it) }, withFiles = true)
    advanceTimeBy(7.seconds)
    runCurrent()

    assertEquals(listOf("remove deleteFiles=false"), done.calls)
    assertEquals(listOf("remove deleteFiles=true"), partial.calls)
    assertEquals(listOf("trash /downloads/a.iso"), f.files.calls)
    assertEquals("Moved 1 file to the Trash", f.messages().last().title)
    f.close()
  }

  @Test
  fun remove_partialFileWithFiles_letsTheDeviceDeleteIt() = runTest {
    val f = ActionsFixture(this, canTrash = true)
    val task = f.add(DownloadState.Paused(DownloadProgress(40, 100)))

    f.runner.remove(listOf(rowOf(task)), withFiles = true)
    advanceTimeBy(7.seconds)
    runCurrent()

    assertEquals(listOf("remove deleteFiles=true"), task.calls)
    assertTrue(f.files.calls.isEmpty())
    f.close()
  }

  @Test
  fun remove_hidesTheRowsFromTheSelectionAndInspector() = runTest {
    val f = ActionsFixture(this, canTrash = true)
    val row = rowOf(f.add(completed))
    f.state.selectedKeys = setOf(row.key, TaskKey(LOCAL_DEVICE_ID, "other"))
    f.state.inspect(row.key)

    f.runner.remove(listOf(row), withFiles = true)

    assertEquals(setOf(TaskKey(LOCAL_DEVICE_ID, "other")), f.state.selectedKeys)
    assertEquals(null, f.state.inspectedTask)
    f.close()
  }
}
