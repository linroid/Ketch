package com.linroid.ketch.app.ui.shell

import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.testController
import com.linroid.ketch.config.KetchConfig
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellStateTest {

  private fun TestScope.controller(): AppController =
    testController(RecordingKetchApi(), RecordingConfigStore(KetchConfig()), "This phone")

  @Test
  fun show_otherDestinationWhilePhoneSearchIsOpen_clearsTheSearch() = runTest {
    val controller = controller()
    val shell = ShellState(controller.state)
    shell.searchOpen = true
    controller.state.searchQuery = "ubuntu"

    shell.show(AppDestination.Devices)

    assertFalse(shell.searchOpen)
    assertEquals("", controller.state.searchQuery)
    controller.close()
  }

  @Test
  fun show_downloadsWhilePhoneSearchIsOpen_keepsTheSearch() = runTest {
    val controller = controller()
    val shell = ShellState(controller.state)
    shell.searchOpen = true
    controller.state.searchQuery = "ubuntu"

    shell.show(AppDestination.Downloads)

    assertTrue(shell.searchOpen)
    assertEquals("ubuntu", controller.state.searchQuery)
    controller.close()
  }

  @Test
  fun closeSearch_searchClosed_leavesTheWideHeadersSearchAlone() = runTest {
    val controller = controller()
    val shell = ShellState(controller.state)
    controller.state.searchQuery = "ubuntu"

    shell.closeSearch()

    assertEquals("ubuntu", controller.state.searchQuery)
    controller.close()
  }
}
