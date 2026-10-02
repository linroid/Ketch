package com.linroid.ketch.app.ui.shell

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.ui.shell.FleetFixtures.NAS_ID
import com.linroid.ketch.app.ui.shell.FleetFixtures.mac
import com.linroid.ketch.app.ui.shell.FleetFixtures.nas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavRailTest {
  private val devices = listOf(mac(), nas())

  private fun row(id: String, state: DownloadState, deviceId: String = LOCAL_DEVICE_ID): TaskRow =
    ListFixtures.row(id, state, deviceId = deviceId)

  @Test
  fun aggregateProgress_downloadingTasks_weighsThemBySize() {
    val rows = listOf(
      row("a", DownloadState.Downloading(DownloadProgress(25, 100))),
      row("b", DownloadState.Downloading(DownloadProgress(275, 300)), deviceId = NAS_ID),
      row("c", DownloadState.Queued)
    )

    assertEquals(0.75f, aggregateProgress(rows, devices))
  }

  @Test
  fun aggregateProgress_unknownSizes_leavesThemOut() {
    val rows = listOf(
      row("a", DownloadState.Downloading(DownloadProgress(50, 0))),
      row("b", DownloadState.Downloading(DownloadProgress(10, 40)))
    )

    assertEquals(0.25f, aggregateProgress(rows, devices))
  }

  @Test
  fun aggregateProgress_unreachableDevice_leavesItsTasksOut() {
    val rows = listOf(
      row("a", DownloadState.Downloading(DownloadProgress(10, 40))),
      row("b", DownloadState.Downloading(DownloadProgress(0, 960)), deviceId = NAS_ID)
    )
    val offline = listOf(mac(), nas(health = DeviceHealth.Offline()))

    assertEquals(0.25f, aggregateProgress(rows, offline))
  }

  @Test
  fun aggregateProgress_nothingDownloading_isNull() {
    assertNull(aggregateProgress(listOf(row("a", DownloadState.Queued)), devices))
    assertNull(aggregateProgress(emptyList(), devices))
  }
}
