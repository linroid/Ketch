package com.linroid.ketch.app.ui.shell

import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.ui.shell.FleetFixtures.NAS_ID
import com.linroid.ketch.app.ui.shell.FleetFixtures.mac
import com.linroid.ketch.app.ui.shell.FleetFixtures.nas
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.UiPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDropsTest {
  private val rows = DeviceDrag.Rows(listOf("a", "b").map { TaskKey(LOCAL_DEVICE_ID, it) })

  /** This Mac and a connected NAS, with quick add on. */
  private fun TestScope.fleet() = fleet(
    mac = RecordingKetchApi("This Mac"),
    nas = RecordingKetchApi("NAS"),
    store = RecordingConfigStore(KetchConfig(ui = UiPreferences(quickAdd = true))),
  )

  @Test
  fun dropHint_content_offersTheFreeSpace() = runTest {
    val hint = dropHint(DeviceDrag.Content, nas(), move = false)

    assertEquals("Drop here · 1.8 TB free" to true, hint.loaded())
  }

  @Test
  fun dropHint_rowsFromAnotherDevice_sendsOrMovesThem() = runTest {
    val send = dropHint(rows, nas(), move = false, moveKey = "Alt")

    assertEquals("Send 2 here · Alt moves" to true, send.loaded())
    assertEquals("Move 2 here" to true, dropHint(rows, nas(), move = true).loaded())
  }

  @Test
  fun dropHint_rowsWithoutAMoveKey_leavesTheKeyOut() = runTest {
    assertEquals("Send 2 here" to true, dropHint(rows, nas(), move = false).loaded())
  }

  @Test
  fun dropHint_rowsOnTheirOwnDevice_turnsTheDropDown() {
    assertFalse(dropHint(rows, mac(), move = false).accepts)
  }

  @Test
  fun dropHint_unreachableDevice_turnsTheDropDown() = runTest {
    val offline = nas(health = DeviceHealth.Offline())

    assertEquals(
      "Not reachable now" to false,
      dropHint(DeviceDrag.Content, offline, move = false).loaded(),
    )
    assertFalse(dropHint(rows, nas(connected = false), move = false).accepts)
  }

  @Test
  fun dropTextOn_oneLinkOnTheNas_addsItThereWithoutSwitching() = runTest {
    val fleet = fleet()

    fleet.state.dropTextOn(fleet.remote, "https://example.com/ubuntu.iso")
    runCurrent()

    assertEquals("https://example.com/ubuntu.iso", fleet.nas.requests.single().url)
    assertTrue(fleet.mac.requests.isEmpty())
    assertEquals(LOCAL_DEVICE_ID, fleet.state.activeInstance.value?.deviceId)
    assertFalse(fleet.state.showAddDialog)
  }

  @Test
  fun dropTextOn_magnet_opensTheSheetAimedAtTheNas() = runTest {
    val fleet = fleet()
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=ubuntu"

    fleet.state.dropTextOn(fleet.remote, magnet)
    runCurrent()

    assertTrue(fleet.state.showAddDialog)
    assertEquals(NAS_ID, fleet.state.intakeRequest?.targetDeviceId)
    assertTrue(fleet.nas.requests.isEmpty())
  }

  private suspend fun DropHint.loaded(): Pair<String, Boolean> = text.load() to accepts
}
