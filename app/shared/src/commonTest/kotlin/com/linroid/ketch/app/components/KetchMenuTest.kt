package com.linroid.ketch.app.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KetchMenuTest {

  private val clicks = mutableListOf<String>()
  private var dismissals = 0

  private val entries = buildMenu {
    header("Task")
    item(label = "Pause", onClick = { clicks += "pause" })
    item(label = "Start now", onClick = { clicks += "start" }, enabled = false)
    divider()
    submenu(label = "Speed limit") {
      item(label = "Unlimited", onClick = { clicks += "unlimited" })
      item(label = "1 MB/s", onClick = { clicks += "1m" })
    }
    item(label = "Columns", onClick = { clicks += "columns" }, keepOpen = true)
  }

  private fun MenuLevel.press(vararg keys: MenuKey) {
    keys.forEach { handle(it, entries, dismiss = { dismissals++ }) }
  }

  @Test
  fun buildMenu_dividersAtTheEndsOrRepeated_areDropped() {
    val menu = buildMenu {
      divider()
      item(label = "A", onClick = {})
      divider()
      divider()
      item(label = "B", onClick = {})
      divider()
    }

    assertEquals(3, menu.size)
    assertIs<MenuEntry.Item>(menu[0])
    assertEquals(MenuEntry.Divider, menu[1])
    assertIs<MenuEntry.Item>(menu[2])
  }

  @Test
  fun nextSelectable_skipsHeadersDividersAndDisabledItemsAndWraps() {
    assertEquals(1, nextSelectable(entries, -1, 1))
    assertEquals(4, nextSelectable(entries, 1, 1))
    assertEquals(1, nextSelectable(entries, 5, 1))
    assertEquals(5, nextSelectable(entries, -1, -1))
  }

  @Test
  fun nextSelectable_nothingSelectable_isMinusOne() {
    assertEquals(-1, nextSelectable(buildMenu { header("Empty") }, -1, 1))
  }

  @Test
  fun handle_downThenEnter_runsTheItemAndDismisses() {
    val root = MenuLevel()

    root.press(MenuKey.Down, MenuKey.Enter)

    assertEquals(listOf("pause"), clicks)
    assertEquals(1, dismissals)
  }

  @Test
  fun handle_rightOnSubmenu_opensItAndMovesTheKeysIntoIt() {
    val root = MenuLevel()

    root.press(MenuKey.Down, MenuKey.Down, MenuKey.Right, MenuKey.Down, MenuKey.Enter)

    assertEquals(4, root.openIndex)
    assertEquals(listOf("1m"), clicks)
    assertEquals(1, dismissals)
  }

  @Test
  fun handle_leftOrEscapeInSubmenu_closesOnlyTheSubmenu() {
    val root = MenuLevel()
    root.press(MenuKey.Down, MenuKey.Down, MenuKey.Right)

    root.press(MenuKey.Left)
    assertNull(root.child)
    assertEquals(4, root.highlighted)

    root.press(MenuKey.Right, MenuKey.Escape)
    assertNull(root.child)
    assertEquals(0, dismissals)

    root.press(MenuKey.Escape)
    assertEquals(1, dismissals)
  }

  @Test
  fun handle_movingAwayFromASubmenuOpenedByPointer_closesIt() {
    val root = MenuLevel()
    root.openSubmenu(4)

    root.press(MenuKey.Down)

    assertNull(root.child)
    assertEquals(5, root.highlighted)
  }

  @Test
  fun handle_keepOpenItem_runsWithoutDismissing() {
    val root = MenuLevel()

    root.press(MenuKey.Up, MenuKey.Enter)

    assertEquals(listOf("columns"), clicks)
    assertEquals(0, dismissals)
  }

  @Test
  fun handle_whileCustomContentIsEditing_leavesEveryKeyButEscape() {
    val root = MenuLevel()
    root.press(MenuKey.Down)

    for (key in listOf(MenuKey.Up, MenuKey.Down, MenuKey.Right, MenuKey.Enter)) {
      assertFalse(root.handle(key, entries, dismiss = { dismissals++ }, editing = true))
    }
    assertEquals(1, root.highlighted)
    assertEquals(emptyList(), clicks)

    assertTrue(root.handle(MenuKey.Escape, entries, dismiss = { dismissals++ }, editing = true))
    assertEquals(1, dismissals)
  }

  @Test
  fun menuFocus_focusMovingBetweenCustomEntries_staysEditingUntilAllLoseIt() {
    val focus = MenuFocus()

    focus.update("speed", hasFocus = true)
    focus.update("name", hasFocus = true)
    focus.update("speed", hasFocus = false)
    assertTrue(focus.editing)

    focus.update("name", hasFocus = false)
    assertFalse(focus.editing)
  }

  @Test
  fun handle_rightOnPlainItem_isNotUsed() {
    val root = MenuLevel()
    root.press(MenuKey.Down)

    assertFalse(root.handle(MenuKey.Right, entries, dismiss = { dismissals++ }))
    assertTrue(root.handle(MenuKey.Down, entries, dismiss = { dismissals++ }))
  }
}
