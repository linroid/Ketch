package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.state.ListFixtures.START
import com.linroid.ketch.app.state.ListFixtures.downloading
import com.linroid.ketch.app.state.ListFixtures.row
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class SelectionStateTest {
  // Downloading: d1 d2 · Waiting: w1 · Added earlier, collapsed: e1 e10 e11 … e9.
  private val groups = arrangeRows(
    listOf(
      row("d1", downloading(900)),
      row("d2", downloading(100)),
      row("w1", DownloadState.Queued)
    ) + (1..51).map { row("e$it", DownloadState.Completed("/f", 1), createdAt = START - 30.days) },
    ListArrangement(),
    START,
    TimeZone.UTC
  )
  private val visible = groups.flatMap { group -> group.rows.map { it.key } }

  @Test
  fun selectRange_acrossGroups_selectsVisibleOrderBetweenAnchorAndKey() {
    val selection = SelectionState().select(key("d2")).selectRange(key("e1"), visible)

    assertEquals(setOf(key("d2"), key("w1"), key("e1")), selection.selected)
    assertEquals(key("d2"), selection.anchor)
    assertEquals(key("e1"), selection.focused)
  }

  @Test
  fun selectRange_upwards_includesBothEnds() {
    val selection = SelectionState().select(key("w1")).selectRange(key("d1"), visible)

    assertEquals(setOf(key("d1"), key("d2"), key("w1")), selection.selected)
  }

  @Test
  fun selectRange_again_replacesThePreviousRange() {
    val selection = SelectionState().select(key("d1"))
      .selectRange(key("w1"), visible)
      .selectRange(key("d2"), visible)

    assertEquals(setOf(key("d1"), key("d2")), selection.selected)
  }

  @Test
  fun selectRange_additive_keepsToggledRows() {
    val selection = SelectionState().select(key("e5"))
      .toggle(key("d1"))
      .selectRange(key("w1"), visible, additive = true)

    assertEquals(setOf(key("e5"), key("d1"), key("d2"), key("w1")), selection.selected)
  }

  @Test
  fun selectRange_anchorFilteredOut_selectsOnlyKey() {
    val selection = SelectionState().select(key("gone")).selectRange(key("w1"), visible)

    assertEquals(SelectionState().select(key("w1")), selection)
  }

  @Test
  fun selectRange_keyNotVisible_changesNothing() {
    val selection = SelectionState().select(key("d1"))

    assertSame(selection, selection.selectRange(key("gone"), visible))
  }

  @Test
  fun toggle_selectedRow_removesItAndMovesAnchor() {
    val selection = SelectionState().select(key("d1")).toggle(key("d2")).toggle(key("d1"))

    assertEquals(setOf(key("d2")), selection.selected)
    assertEquals(key("d1"), selection.anchor)
  }

  @Test
  fun selectAllVisible_includesCollapsedGroups() {
    val selection = SelectionState().select(key("w1")).selectAllVisible(visible)

    assertTrue(groups.last().collapsedByDefault)
    assertEquals(54, selection.count)
    assertTrue(key("e51") in selection)
    assertEquals(key("w1"), selection.anchor)
  }

  @Test
  fun moveFocus_arrowKeys_moveAndSelect() {
    val start = SelectionState().select(key("d2"))

    assertEquals(SelectionState().select(key("w1")), start.moveFocus(visible, 1))
    assertEquals(SelectionState().select(key("d1")), start.moveFocus(visible, -1))
    assertEquals(SelectionState().select(key("d1")), start.moveFocus(visible, -5))
  }

  @Test
  fun moveFocus_homeAndEnd_jumpToEnds() {
    val start = SelectionState().select(key("w1"))

    assertEquals(key("d1"), start.moveFocus(visible, Int.MIN_VALUE).focused)
    assertEquals(visible.last(), start.moveFocus(visible, Int.MAX_VALUE).focused)
  }

  @Test
  fun moveFocus_withoutFocus_startsAtTheMatchingEnd() {
    assertEquals(key("d1"), SelectionState().moveFocus(visible, 1).focused)
    assertEquals(visible.last(), SelectionState().moveFocus(visible, -1).focused)
  }

  @Test
  fun moveFocus_extend_growsFromAnchor() {
    val selection = SelectionState().select(key("d2"))
      .moveFocus(visible, 1, extend = true)
      .moveFocus(visible, 1, extend = true)

    assertEquals(setOf(key("d2"), key("w1"), visible[3]), selection.selected)
    assertEquals(key("d2"), selection.anchor)
    assertEquals(visible[3], selection.focused)
  }

  @Test
  fun clear_keepsFocus() {
    val selection = SelectionState().select(key("d1")).toggle(key("d2")).clear()

    assertTrue(selection.selected.isEmpty())
    assertEquals(key("d2"), selection.focused)
  }

  @Test
  fun prune_removedTasks_dropsThem() {
    val selection = SelectionState().select(key("d1")).toggle(key("d2"))

    val pruned = selection.prune(setOf(key("d1")))

    assertEquals(setOf(key("d1")), pruned.selected)
    assertEquals(null, pruned.anchor)
    assertEquals(null, pruned.focused)
  }

  @Test
  fun prune_nothingRemoved_returnsSameState() {
    val selection = SelectionState().select(key("d1"))

    assertSame(selection, selection.prune(visible.toSet()))
  }

  @Test
  fun click_plain_selectsOnlyTheRow() {
    val selection = SelectionState().select(key("d1")).toggle(key("d2"))

    assertEquals(SelectionState().select(key("w1")), selection.click(key("w1"), visible))
  }

  @Test
  fun click_withToggle_addsTheRow() {
    val selection = SelectionState().select(key("d1")).click(key("w1"), visible, toggle = true)

    assertEquals(setOf(key("d1"), key("w1")), selection.selected)
  }

  @Test
  fun click_withRange_selectsFromTheAnchor() {
    val selection = SelectionState().select(key("d1")).click(key("w1"), visible, range = true)

    assertEquals(setOf(key("d1"), key("d2"), key("w1")), selection.selected)
  }

  @Test
  fun click_withToggleAndRange_addsTheRange() {
    val selection = SelectionState().select(key("e5"))
      .toggle(key("d1"))
      .click(key("w1"), visible, toggle = true, range = true)

    assertEquals(setOf(key("e5"), key("d1"), key("d2"), key("w1")), selection.selected)
  }

  @Test
  fun contextClick_insideTheSelection_keepsIt() {
    val selection = SelectionState().select(key("d1")).toggle(key("w1"))

    val clicked = selection.contextClick(key("d1"))

    assertEquals(selection.selected, clicked.selected)
    assertEquals(key("d1"), clicked.focused)
  }

  @Test
  fun contextClick_outsideTheSelection_selectsOnlyTheRow() {
    val selection = SelectionState().select(key("d1")).toggle(key("w1"))

    assertEquals(SelectionState().select(key("d2")), selection.contextClick(key("d2")))
  }

  @Test
  fun targets_rowInTheSelection_isEverySelectedVisibleRowInOrder() {
    val selection = SelectionState().select(key("w1")).toggle(key("d1")).toggle(key("gone"))

    assertEquals(listOf(key("d1"), key("w1")), selection.targets(key("w1"), visible))
  }

  @Test
  fun targets_rowOutsideTheSelection_isTheRowAlone() {
    val selection = SelectionState().select(key("w1")).toggle(key("d1"))

    assertEquals(listOf(key("d2")), selection.targets(key("d2"), visible))
  }

  @Test
  fun band_coveredRows_replaceTheSelection() {
    val selection = SelectionState().select(key("e1")).band(listOf(key("d2"), key("w1")))

    assertEquals(setOf(key("d2"), key("w1")), selection.selected)
    assertEquals(key("d2"), selection.anchor)
    assertEquals(key("w1"), selection.focused)
  }

  @Test
  fun band_withBase_addsToIt() {
    val base = setOf(key("e1"))

    val selection = SelectionState().select(key("e1")).band(listOf(key("d1")), base)

    assertEquals(setOf(key("e1"), key("d1")), selection.selected)
  }

  @Test
  fun band_nothingCovered_keepsOnlyTheBase() {
    val selection = SelectionState().select(key("d1")).band(emptyList())

    assertTrue(selection.selected.isEmpty())
    assertEquals(key("d1"), selection.anchor)
  }

  private fun key(id: String) = TaskKey(LOCAL_DEVICE_ID, id)
}
