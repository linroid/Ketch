package com.linroid.ketch.app.ui.downloads.actions

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.state.RowAction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionBarTest {
  private val downloading = DownloadState.Downloading(DownloadProgress(10, 100, 5))
  private val paused = DownloadState.Paused(DownloadProgress(10, 100))
  private val completed = DownloadState.Completed("/downloads/a.iso", totalBytes = 1_048_576)

  @Test
  fun barVerbs_twoRunningOneFinished_countsPauseTwoOfThree() = runTest {
    val f = ActionsFixture(this)
    val rows = listOf(f.add(downloading), f.add(downloading), f.add(completed)).map { rowOf(it) }

    val (bar, _) = barVerbs(f.runner.batch(rows), canSend = false)

    val pause = bar.first()
    assertEquals(RowAction.Pause, pause.action)
    assertEquals(2, pause.count)
    assertEquals("Pause 2 of 3 selected", pause.tooltip(rows.size))
    f.close()
  }

  @Test
  fun barVerbs_mixedSelection_showsOnlyVerbsThatApplyInOrder() = runTest {
    val f = ActionsFixture(this)
    val rows = listOf(
      f.add(downloading),
      f.add(downloading),
      f.add(paused),
      f.add(DownloadState.Failed(KetchError.Network())),
    ).map { rowOf(it) }

    val (bar, _) = barVerbs(f.runner.batch(rows), canSend = false)

    assertEquals(
      listOf("Pause", "Resume", "Retry", "Priority", "Speed", "Copy links", "Remove…"),
      bar.map { it.label },
    )
    assertEquals(listOf(2, 1, 1), bar.take(3).map { it.count })
    assertEquals("Resume 1 of 4 selected", bar[1].tooltip(rows.size))
    f.close()
  }

  @Test
  fun barVerbs_finishedFiles_leaveRunningVerbsOutAndOfferOpenInMore() = runTest {
    val f = ActionsFixture(this)
    val rows = List(2) { rowOf(f.add(completed)) }
    val batch = f.runner.batch(rows)

    val (bar, more) = barVerbs(batch, canSend = false, revealLabel = "Show in Finder")

    assertFalse(bar.any { it.action == RowAction.Pause || it.action == RowAction.Priority })
    assertEquals("Copy 2 links", bar.single { it.action == RowAction.CopyLink }.tooltip(2))
    assertTrue(more.any { it.label == "Open" && it.count == 2 })
    assertTrue(more.any { it.label == "Show in Finder" })
    f.close()
  }

  @Test
  fun barVerbs_withAnotherDevice_offersSendTo() = runTest {
    val f = ActionsFixture(this)
    val rows = List(2) { rowOf(f.add(downloading)) }

    val withDevice = barVerbs(f.runner.batch(rows), canSend = true).first
    val alone = barVerbs(f.runner.batch(rows), canSend = false).first

    assertTrue(withDevice.any { it.kind == BarVerbKind.SendTo })
    assertFalse(alone.any { it.kind == BarVerbKind.SendTo })
    f.close()
  }

  @Test
  fun barVerbs_remove_asksWithTheDialog() = runTest {
    val f = ActionsFixture(this)
    val rows = List(3) { rowOf(f.add(downloading)) }

    val remove = barVerbs(f.runner.batch(rows), canSend = false).first.last()

    assertEquals(BarVerbKind.RemoveDialog, remove.kind)
    assertEquals("Remove 3 downloads…", remove.tooltip(rows.size))
    f.close()
  }

  @Test
  fun selectionSummary_countsRowsAndKnownBytes() = runTest {
    val f = ActionsFixture(this)
    val rows = listOf(f.add(completed), f.add(completed), f.add(DownloadState.Queued))
      .map { rowOf(it) }

    assertEquals("3 selected · 2.0 MB", selectionSummary(rows))
    assertEquals("1 selected", selectionSummary(listOf(rows.last())))
    f.close()
  }

  @Test
  fun skipNote_groupsTheReasons() = runTest {
    val f = ActionsFixture(this)
    val rows = listOf(f.add(completed), f.add(paused), f.add(paused)).map { rowOf(it) }

    assertEquals("1 already finished · 2 already paused", skipNote("pause", rows))
    assertEquals(null, skipNote("pause", emptyList()))
    f.close()
  }

  @Test
  fun skipNote_resume_namesWhyEachRowWasLeftAlone() = runTest {
    val f = ActionsFixture(this)
    val rows = listOf(
      f.add(downloading),
      f.add(DownloadState.Failed(KetchError.Network())),
      f.add(DownloadState.Canceled),
    ).map { rowOf(it) }

    assertEquals("1 already running · 1 with an error · 1 canceled", skipNote("resume", rows))
    f.close()
  }
}
