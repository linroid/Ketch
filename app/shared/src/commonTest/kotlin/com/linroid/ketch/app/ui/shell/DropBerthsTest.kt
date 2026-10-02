package com.linroid.ketch.app.ui.shell

import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.ui.shell.FleetFixtures.nas
import kotlin.test.Test
import kotlin.test.assertEquals

class DropBerthsTest {
  private val min = 140.dp
  private val gap = 16.dp

  @Test
  fun berthColumns_upToFourDevices_putsThemInOneRow() {
    assertEquals(1, berthColumns(1, 1000.dp, min, gap))
    assertEquals(3, berthColumns(3, 1000.dp, min, gap))
    assertEquals(4, berthColumns(4, 1000.dp, min, gap))
  }

  @Test
  fun berthColumns_moreThanFour_usesTwoRows() {
    assertEquals(3, berthColumns(5, 1000.dp, min, gap))
    assertEquals(4, berthColumns(8, 1000.dp, min, gap))
  }

  @Test
  fun berthColumns_narrowWindow_fitsFewerInARow() {
    assertEquals(2, berthColumns(3, 358.dp, min, gap))
    assertEquals(1, berthColumns(3, 150.dp, min, gap))
  }

  @Test
  fun berthCaption_online_showsFreeSpaceAndActivity() {
    val device = nas(counts = PulseCounts(downloading = 3))

    assertEquals("1.8 TB free · 3 active", berthCaption(device))
  }

  @Test
  fun berthCaption_idleWithoutDisk_readsReady() {
    assertEquals("Ready", berthCaption(nas(disk = null)))
  }

  @Test
  fun berthCaption_unreachable_saysWhy() {
    assertEquals("Offline", berthCaption(nas(health = DeviceHealth.Offline())))
    assertEquals("Not connected", berthCaption(nas(connected = false)))
  }
}
