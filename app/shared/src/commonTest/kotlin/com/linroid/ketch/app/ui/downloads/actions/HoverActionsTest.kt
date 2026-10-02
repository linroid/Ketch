package com.linroid.ketch.app.ui.downloads.actions

import com.linroid.ketch.app.state.RowAction
import kotlin.test.Test
import kotlin.test.assertEquals

class HoverActionsTest {
  @Test
  fun hoverButtons_finishedFile_isOpenAndShowWithoutMore() {
    val buttons = hoverButtons(
      hover = listOf(RowAction.Open, RowAction.ShowInFolder),
      menu = listOf(RowAction.Open, RowAction.ShowInFolder, RowAction.CopyLink),
    )

    val open = listOf(RowAction.Open, RowAction.ShowInFolder)
    assertEquals(HoverButtons(open, more = false), buttons)
  }

  @Test
  fun hoverButtons_runningRow_isPauseAndMore() {
    val buttons = hoverButtons(
      hover = listOf(RowAction.Pause),
      menu = listOf(RowAction.Pause, RowAction.SpeedLimit, RowAction.Remove),
    )

    assertEquals(HoverButtons(listOf(RowAction.Pause), more = true), buttons)
  }

  @Test
  fun hoverButtons_menuWithNothingElse_hasNoMore() {
    val copy = listOf(RowAction.CopyPath)

    val buttons = hoverButtons(hover = copy, menu = copy)

    assertEquals(HoverButtons(listOf(RowAction.CopyPath), more = false), buttons)
  }

  @Test
  fun hoverButtons_noHoverAction_isOnlyMore() {
    val buttons = hoverButtons(hover = emptyList(), menu = listOf(RowAction.SendTo))

    assertEquals(HoverButtons(emptyList(), more = true), buttons)
  }
}
