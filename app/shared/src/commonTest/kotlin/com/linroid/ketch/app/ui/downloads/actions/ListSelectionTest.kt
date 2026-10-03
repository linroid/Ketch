package com.linroid.ketch.app.ui.downloads.actions

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.state.SelectionState
import com.linroid.ketch.app.state.rowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListSelectionTest {
  private val downloading = DownloadState.Downloading(DownloadProgress(10, 100, 5))

  @Test
  fun click_plain_selectsTheRowAndInspectsIt() = actionsTest { f ->
    val rows = List(3) { rowOf(f.add(downloading)) }
    val selection = ListSelection(f.state)

    selection.click(rows[1].key, rows.map { it.key }, RowClick())

    assertEquals(setOf(rows[1].key), f.state.selectedKeys)
    assertEquals(rows[1].key, f.state.inspectedTask)
  }

  @Test
  fun click_withToggle_keepsTheInspectedRow() = actionsTest { f ->
    val rows = List(3) { rowOf(f.add(downloading)) }
    val keys = rows.map { it.key }
    val selection = ListSelection(f.state)

    selection.click(keys[0], keys, RowClick())
    selection.click(keys[2], keys, RowClick(toggle = true))

    assertEquals(setOf(keys[0], keys[2]), f.state.selectedKeys)
    assertEquals(keys[0], f.state.inspectedTask)
  }

  @Test
  fun click_tapWithNothingSelected_onlyInspects() = actionsTest { f ->
    val rows = List(2) { rowOf(f.add(downloading)) }
    val selection = ListSelection(f.state)

    selection.click(rows[0].key, rows.map { it.key }, RowClick(touch = true))

    assertTrue(f.state.selectedKeys.isEmpty())
    assertEquals(rows[0].key, f.state.inspectedTask)
  }

  @Test
  fun click_tapInSelectionMode_togglesTheRow() = actionsTest { f ->
    val rows = List(3) { rowOf(f.add(downloading)) }
    val keys = rows.map { it.key }
    val selection = ListSelection(f.state)

    selection.longPress(keys[0])
    selection.click(keys[2], keys, RowClick(touch = true))
    selection.click(keys[0], keys, RowClick(touch = true))

    assertEquals(setOf(keys[2]), f.state.selectedKeys)
  }

  @Test
  fun contextClick_insideTheSelection_returnsItInDisplayOrder() = actionsTest { f ->
    val rows = List(4) { rowOf(f.add(downloading)) }
    val keys = rows.map { it.key }
    val selection = ListSelection(f.state)
    selection.update(SelectionState(selected = setOf(keys[3], keys[1])))

    assertEquals(listOf(keys[1], keys[3]), selection.contextClick(keys[3], keys))
    assertEquals(listOf(keys[0]), selection.contextClick(keys[0], keys))
    assertEquals(setOf(keys[0]), f.state.selectedKeys)
  }

  @Test
  fun prune_removedTask_leavesTheSelection() = actionsTest { f ->
    val rows = List(2) { rowOf(f.add(downloading)) }
    val selection = ListSelection(f.state)
    selection.update(SelectionState().selectAllVisible(rows.map { it.key }))

    selection.prune(setOf(rows[0].key))

    assertEquals(setOf(rows[0].key), f.state.selectedKeys)
  }

  @Test
  fun showMenu_rowOutsideTheSelection_actsOnItAloneAndKeepsTheSelection() = actionsTest { f ->
    val rows = List(3) { rowOf(f.add(downloading)) }
    val selection = ListSelection(f.state)
    val menu = RowMenuState()
    val keyboard = ListKeyboard(selection, f.runner, menu, backgroundScope)
    val actions = ListActions(selection, f.runner, menu, keyboard)
    actions.rows = rows
    val selected = rows.take(2).map { it.key }
    selection.update(SelectionState().selectAllVisible(selected))

    actions.showMenu(rows[2])
    assertEquals(listOf(rows[2]), menu.request?.rows)
    actions.showMenu(rows[0])
    assertEquals(rows.take(2), menu.request?.rows)

    assertEquals(selected.toSet(), f.state.selectedKeys)
  }

  @Test
  fun isSelectionMode_pointerNeedsTwoRowsTouchOne() {
    assertFalse(isSelectionMode(1, pointer = true))
    assertTrue(isSelectionMode(2, pointer = true))
    assertFalse(isSelectionMode(0, pointer = false))
    assertTrue(isSelectionMode(1, pointer = false))
  }
}
