package com.linroid.ketch.app.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.IntOffset
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.input.KetchCommands
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrayMenuContentTest {
  private val menu = TraySamples.menu

  @Test
  fun trayMenu_enterOnTheFirstItem_runsItAndCloses() = scene().use { scene ->
    scene.press(Key.DirectionDown).press(Key.Enter)

    assertEquals(listOf<MenuAction>(MenuAction.ShowWindow), scene.actions)
    assertEquals(1, scene.dismissals)
  }

  @Test
  fun trayMenu_arrowsIntoNestedSubmenus_runTheDevicesItems() = scene().use { scene ->
    val devices = TraySamples.submenuWith(menu) { it == MenuAction.AddDevice }
    val pc = devices.entries.first() as MenuEntry.Submenu
    val speed = TraySamples.submenuWith(pc.entries) { it is MenuAction.SetSpeedMode }
    val slowLane = speed.entries[1]

    scene.press(Key.DirectionDown, times = TraySamples.downsTo(devices))
      // → opens Devices on its first device, → opens that device's menu on Show.
      .press(Key.DirectionRight, times = 2)
      .press(Key.DirectionDown, times = TraySamples.downsTo(speed, pc.entries) - 1)
      .press(Key.DirectionRight)
      .press(Key.DirectionDown, times = TraySamples.downsTo(slowLane, speed.entries) - 1)
      .press(Key.Enter)

    assertEquals(listOf((slowLane as MenuEntry.Item).action), scene.actions)
    assertEquals(1, scene.dismissals)
  }

  @Test
  fun trayMenu_leftInASubmenu_goesBackWithoutClosing() = scene().use { scene ->
    val recent = TraySamples.submenuWith(menu) { it is MenuAction.OpenFile }

    scene.press(Key.DirectionDown, times = TraySamples.downsTo(recent))
      .press(Key.DirectionRight)
      .press(Key.DirectionLeft)
    assertEquals(0, scene.dismissals)

    // Recent is still highlighted: ↩ opens it again on its first download, which ↩ opens.
    scene.press(Key.Enter, times = 2)
    assertEquals(listOf((recent.entries.first() as MenuEntry.Item).action), scene.actions)
  }

  @Test
  fun trayMenu_escape_closesWithoutRunningAnything() = scene().use { scene ->
    scene.press(Key.DirectionDown).press(Key.Escape)

    assertTrue(scene.actions.isEmpty())
    assertEquals(1, scene.dismissals)
  }

  @Test
  fun trayMenu_clickOutside_closesWithoutRunningAnything() = scene().use { scene ->
    scene.click(Offset(10f, 10f))

    assertTrue(scene.actions.isEmpty())
    assertEquals(1, scene.dismissals)
  }

  @Test
  fun trayMenu_clickOnAnItem_runsIt() = scene().use { scene ->
    val quit = assertNotNull(scene.boundsOf(quitLabel))

    scene.click(quit.center)

    assertEquals(listOf<MenuAction>(MenuAction.Run(KetchCommands.Quit)), scene.actions)
    assertEquals(1, scene.dismissals)
  }

  @Test
  fun trayMenu_pointerOnATaskbarBelow_opensLeftOfItAboveTheBottomEdge() = scene().use { scene ->
    val quit = assertNotNull(scene.boundsOf(quitLabel))
    val margin = MARGIN_DP * SCALE

    // Quit is the last item: just above the bottom edge, all left of the pointer.
    assertTrue(quit.bottom <= HEIGHT - margin, "Quit ends at ${quit.bottom}")
    assertTrue(quit.bottom >= HEIGHT - margin - ROW_DP * SCALE, "Quit ends at ${quit.bottom}")
    assertTrue(quit.right <= POINTER.x, "Quit ends at ${quit.right}")
  }

  private fun scene() = TrayMenuScene(WIDTH, HEIGHT, SCALE, POINTER, menu).settle()

  private val quitLabel: String = runBlocking { KetchCommands.Quit.label.load() }

  private companion object {
    const val WIDTH = 1600
    const val HEIGHT = 1200
    const val SCALE = 2f
    const val MARGIN_DP = 8
    const val ROW_DP = 40

    // On a taskbar below the work area, near its right end.
    val POINTER = IntOffset(1500, 1260)
  }
}
