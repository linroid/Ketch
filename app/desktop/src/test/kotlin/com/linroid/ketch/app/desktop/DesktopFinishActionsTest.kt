package com.linroid.ketch.app.desktop

import com.linroid.ketch.app.state.FinishAction
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopFinishActionsTest {

  @Test
  fun commands_sleepAndShutDownPerSystem() {
    assertEquals(
      listOf("osascript", "-e", "tell application \"System Events\" to sleep"),
      finishCommand(DesktopOs.MAC, FinishAction.Sleep),
    )
    assertEquals(
      listOf("systemctl", "suspend"),
      finishCommand(DesktopOs.LINUX, FinishAction.Sleep),
    )
    assertEquals(
      listOf("osascript", "-e", "tell application \"System Events\" to shut down"),
      finishCommand(DesktopOs.MAC, FinishAction.ShutDown),
    )
    assertEquals(
      listOf("shutdown", "/s", "/t", "0"),
      finishCommand(DesktopOs.WINDOWS, FinishAction.ShutDown),
    )
    assertEquals(
      listOf("systemctl", "poweroff"),
      finishCommand(DesktopOs.LINUX, FinishAction.ShutDown),
    )
  }

  @Test
  fun perform_quitQuitsAndOthersRunTheirCommand() = runTest {
    var quits = 0
    val ran = mutableListOf<List<String>>()
    val actions = DesktopFinishActions(DesktopOs.LINUX, quit = { quits++ }, run = { ran += it })

    actions.perform(FinishAction.Quit)
    actions.perform(FinishAction.ShutDown)
    actions.perform(FinishAction.Nothing)

    assertEquals(1, quits)
    assertEquals(listOf(listOf("systemctl", "poweroff")), ran)
  }

  @Test
  fun perform_failingCommandThrows() = runTest {
    val actions = DesktopFinishActions(
      DesktopOs.MAC,
      quit = {},
      run = { throw IOException("refused") },
    )
    assertFailsWith<IOException> { actions.perform(FinishAction.Sleep) }
  }
}
