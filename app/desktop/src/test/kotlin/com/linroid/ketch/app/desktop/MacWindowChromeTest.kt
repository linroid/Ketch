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
      windowChrome(fullWindowContent = true, WindowPlacement.Floating),
    )
    assertEquals(
      WindowChrome(top = 28.dp, leading = 78.dp),
      windowChrome(fullWindowContent = true, WindowPlacement.Maximized),
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
