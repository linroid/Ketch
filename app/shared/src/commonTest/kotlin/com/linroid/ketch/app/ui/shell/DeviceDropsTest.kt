package com.linroid.ketch.app.ui.shell

import com.linroid.ketch.app.RecordingConfigStore
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
  fun dropHint_content_offersTheFreeSpace() {
    val hint = dropHint(DeviceDrag.Content, nas(), move = false)

    assertEquals(DropHint("Drop here · 1.8 TB free", true), hint)
  }

  @Test
  fun dropHint_rowsFromAnotherDevice_sendsOrMovesThem() {
    val send = dropHint(rows, nas(), move = false, moveKey = "Alt")

    assertEquals(DropHint("Send 2 here · Alt moves", true), send)
    assertEquals(DropHint("Move 2 here", true), dropHint(rows, nas(), move = true))
  }

  @Test
  fun dropHint_rowsWithoutAMoveKey_leavesTheKeyOut() {
    assertEquals(DropHint("Send 2 here", true), dropHint(rows, nas(), move = false))
  }

  @Test
  fun dropHint_rowsOnTheirOwnDevice_turnsTheDropDown() {
    assertFalse(dropHint(rows, mac(), move = false).accepts)
  }

  @Test
  fun dropHint_unreachableDevice_turnsTheDropDown() {
    val offline = nas(health = DeviceHealth.Offline())

    assertEquals(
      DropHint("Not reachable now", false),
      dropHint(DeviceDrag.Content, offline, move = false)
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
}
