package com.linroid.ketch.app.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import com.linroid.ketch.app.theme.WindowChrome
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacWindowChromeTest {
  @Test
  fun usesFullWindowContent_onlyOnMacWithoutTheFallbackFlag() {
    assertTrue(usesFullWindowContent(DesktopOs.MAC, decorated = false))
    assertFalse(usesFullWindowContent(DesktopOs.MAC, decorated = true))
    assertFalse(usesFullWindowContent(DesktopOs.WINDOWS, decorated = false))
    assertFalse(usesFullWindowContent(DesktopOs.LINUX, decorated = false))
  }

  @Test
  fun windowChrome_fullWindowContent_leavesRoomForTheTrafficLights() {
    assertEquals(
      WindowChrome(top = 28.dp, leading = 78.dp),
      windowChrome(fullWindowContent = true, WindowPlacement.Floating, macVersion = "15.5"),
    )
    assertEquals(
      WindowChrome(top = 28.dp, leading = 78.dp),
      windowChrome(fullWindowContent = true, WindowPlacement.Maximized, macVersion = "15.5"),
    )
  }

  @Test
  fun windowChrome_macOs26OrLater_usesTheTallerTitleBar() {
    assertEquals(
      WindowChrome(top = 32.dp, leading = 78.dp),
      windowChrome(fullWindowContent = true, WindowPlacement.Floating, macVersion = "26.0"),
    )
    assertEquals(
      WindowChrome(top = 32.dp, leading = 78.dp),
      windowChrome(fullWindowContent = true, WindowPlacement.Floating, macVersion = "27.0"),
    )
  }

  @Test
  fun windowChrome_fullScreenOrNativeTitleBar_leavesNoRoom() {
    assertEquals(WindowChrome.None, windowChrome(true, WindowPlacement.Fullscreen))
    assertEquals(WindowChrome.None, windowChrome(false, WindowPlacement.Floating))
  }

  @Test
  fun windowAppearance_followsTheTheme() {
    assertEquals("NSAppearanceNameDarkAqua", windowAppearance(darkTheme = true))
    assertEquals("NSAppearanceNameAqua", windowAppearance(darkTheme = false))
  }

  @Test
  fun titleBarAction_systemSetting_mapsToWhatADoubleClickDoes() {
    assertEquals(TitleBarAction.Zoom, titleBarAction(null))
    assertEquals(TitleBarAction.Zoom, titleBarAction("Maximize\n"))
    assertEquals(TitleBarAction.Zoom, titleBarAction("Fill"))
    assertEquals(TitleBarAction.Minimize, titleBarAction("Minimize\n"))
    assertEquals(TitleBarAction.None, titleBarAction("None"))
  }

  @Test
  fun titleBarLayout_pressOnEmptySpace_reachesTheTitleBar() {
    val scene = TitleBarScene()

    scene.press(x = 200f, y = 20f)

    assertEquals(1, scene.titleBarPresses)
    assertEquals(0, scene.buttonClicks)
  }

  @Test
  fun titleBarLayout_pressOnAButton_staysWithTheButton() {
    val scene = TitleBarScene()

    scene.press(x = 20f, y = 20f)

    assertEquals(0, scene.titleBarPresses)
    assertEquals(1, scene.buttonClicks)
  }

  @Test
  fun titleBarLayout_pressBelowTheTitleBar_isNotATitleBarPress() {
    val scene = TitleBarScene()

    scene.press(x = 200f, y = 120f)

    assertEquals(0, scene.titleBarPresses)
  }

  @Test
  fun onDoubleClick_secondClick_runsTheActionOnce() {
    var doubleClicks = 0
    val scene = ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
      Box(Modifier.fillMaxSize().onDoubleClick { doubleClicks++ })
    }
    try {
      scene.render()
      scene.click(Offset(10f, 10f), clickCount = 1)
      assertEquals(0, doubleClicks)
      scene.click(Offset(10f, 10f), clickCount = 2)
      assertEquals(1, doubleClicks)
    } finally {
      scene.close()
    }
  }

  @Test
  fun onStillClick_clickInPlace_runsTheAction() {
    assertEquals(1, stillClicks(pressedAt = Offset(500f, 300f), releasedAt = Offset(500f, 300f)))
  }

  @Test
  fun onStillClick_pressThatDragsTheWindow_isNoClick() {
    // The window follows the pointer, so only its place on the screen changes.
    assertEquals(0, stillClicks(pressedAt = Offset(500f, 300f), releasedAt = Offset(560f, 340f)))
  }

  /** Presses and releases at the same place in a window, at the given places on the screen. */
  private fun stillClicks(pressedAt: Offset, releasedAt: Offset): Int {
    var clicks = 0
    val scene = ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
      Box(Modifier.fillMaxSize().onStillClick { clicks++ })
    }
    try {
      scene.render()
      val position = Offset(10f, 10f)
      val source = Canvas()
      fun mouse(id: Int, screen: Offset) = MouseEvent(
        source, id, 0L, InputEvent.BUTTON1_DOWN_MASK, position.x.toInt(), position.y.toInt(),
        screen.x.toInt(), screen.y.toInt(), 1, false, MouseEvent.BUTTON1,
      )
      scene.sendPointerEvent(PointerEventType.Move, position)
      scene.sendPointerEvent(
        eventType = PointerEventType.Press,
        position = position,
        buttons = PointerButtons(isPrimaryPressed = true),
        button = PointerButton.Primary,
        nativeEvent = mouse(MouseEvent.MOUSE_PRESSED, pressedAt),
      )
      scene.sendPointerEvent(
        eventType = PointerEventType.Release,
        position = position,
        buttons = PointerButtons(),
        button = PointerButton.Primary,
        nativeEvent = mouse(MouseEvent.MOUSE_RELEASED, releasedAt),
      )
    } finally {
      scene.close()
    }
    return clicks
  }

  /** A [TitleBarLayout] 60 px tall over content with a 40 px button at the top left. */
  private class TitleBarScene {
    var titleBarPresses = 0
    var buttonClicks = 0
    private val scene = ImageComposeScene(width = 400, height = 200, density = Density(1f)) {
      TitleBarLayout(
        height = 60.dp,
        titleBar = { modifier ->
          Box(
            modifier.pointerInput(Unit) {
              awaitPointerEventScope {
                while (true) {
                  if (awaitPointerEvent().type == PointerEventType.Press) titleBarPresses++
                }
              }
            },
          )
        },
      ) {
        Box(Modifier.fillMaxSize()) {
          Box(Modifier.size(40.dp).clickable { buttonClicks++ })
        }
      }
    }

    /** Clicks at [x], [y] once, then closes the scene. */
    fun press(x: Float, y: Float) {
      try {
        scene.render()
        scene.click(Offset(x, y), clickCount = 1)
      } finally {
        scene.close()
      }
    }
  }
}

private fun ImageComposeScene.click(position: Offset, clickCount: Int) {
  val source = Canvas()
  fun mouse(id: Int) = MouseEvent(
    source, id, 0L, InputEvent.BUTTON1_DOWN_MASK, position.x.toInt(), position.y.toInt(),
    clickCount, false, MouseEvent.BUTTON1,
  )
  sendPointerEvent(PointerEventType.Move, position)
  sendPointerEvent(
    eventType = PointerEventType.Press,
    position = position,
    buttons = PointerButtons(isPrimaryPressed = true),
    button = PointerButton.Primary,
    nativeEvent = mouse(MouseEvent.MOUSE_PRESSED),
  )
  sendPointerEvent(
    eventType = PointerEventType.Release,
    position = position,
    buttons = PointerButtons(),
    button = PointerButton.Primary,
    nativeEvent = mouse(MouseEvent.MOUSE_RELEASED),
  )
}
