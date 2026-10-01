package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.ui.geometry.Offset
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.DeviceOption
import com.linroid.ketch.app.components.MenuEntry
import com.linroid.ketch.app.components.buildMenu
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.RowAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RowMenuTest {
  private val downloading = DownloadState.Downloading(DownloadProgress(10, 100, 5))
  private val completed = DownloadState.Completed("/downloads/a.iso", totalBytes = 100)

  @Test
  fun closeIfCurrent_menuReplacedByAnother_keepsTheNewOne() = runTest {
    val f = ActionsFixture(this)
    val first = rowOf(f.add(downloading))
    val second = rowOf(f.add(completed))
    val menu = RowMenuState()

    menu.open(first.key, listOf(first), Offset(4f, 8f))
    val opened = checkNotNull(menu.request)
    menu.open(second.key, listOf(second))
    menu.closeIfCurrent(opened)

    assertEquals(second.key, menu.request?.anchor)
    assertEquals(null, menu.request?.position)
    menu.closeIfCurrent(checkNotNull(menu.request))
    assertFalse(menu.isOpen)
    f.close()
  }

  @Test
  fun rowMenuEntries_downloadingRow_listsControlsThenDestructiveAfterADivider() = runTest {
    val f = ActionsFixture(this)
    val entries = buildMenu {
      rowMenuEntries(listOf(rowOf(f.add(downloading))), f.runner, RowMenuContext())
    }

    assertEquals(
      listOf(
        "Pause", "Speed limit", "Connections", "Priority", "Start later", "Copy link", "Details",
        "—", "Stop and discard progress…", "Remove from list",
      ),
      entries.map(::labelOf),
    )
    f.close()
  }

  @Test
  fun rowMenuEntries_completedRow_namesThePlatformsFileManager() = runTest {
    val f = ActionsFixture(this, canTrash = true)
    val entries = buildMenu {
      rowMenuEntries(
        listOf(rowOf(f.add(completed))),
        f.runner,
        RowMenuContext(revealLabel = "Show in Finder"),
      )
    }

    val labels = entries.map(::labelOf)
    assertTrue("Show in Finder" in labels)
    assertEquals("Remove and trash file…", labels.last())
    f.close()
  }

  @Test
  fun rowMenuEntries_selection_countsWhatEachActionAppliesTo() = runTest {
    val f = ActionsFixture(this)
    val rows = listOf(f.add(downloading), f.add(downloading), f.add(completed)).map { rowOf(it) }

    val labels = buildMenu { rowMenuEntries(rows, f.runner, RowMenuContext()) }.map(::labelOf)

    assertEquals("Pause 2 downloads", labels.first())
    assertTrue("Copy 3 links" in labels)
    assertTrue("Remove 3 downloads from list" in labels)
    f.close()
  }

  @Test
  fun rowMenuEntries_otherDevices_offerSendToWithOfflineOnesDisabled() = runTest {
    val f = ActionsFixture(this)
    val row = rowOf(f.add(downloading))
    val laptop = SendTarget(
      EmbeddedInstance(f.api, "Laptop"),
      DeviceOption("laptop", "Laptop", DeviceHealth.Live),
    )
    val nas = SendTarget(
      EmbeddedInstance(f.api, "NAS"),
      DeviceOption("nas", "NAS", DeviceHealth.Offline()),
    )

    val entries = buildMenu {
      rowMenuEntries(listOf(row), f.runner, RowMenuContext(devices = listOf(laptop, nas)))
    }

    val send = entries.filterIsInstance<MenuEntry.Submenu>().single { it.label == "Send to" }
    val items = send.entries.filterIsInstance<MenuEntry.Item>()
    assertEquals(listOf("Laptop" to true, "NAS" to false), items.map { it.label to it.enabled })
    assertEquals("Offline", items.last().caption)
    f.close()
  }

  @Test
  fun rowMenuEntries_noOtherDevice_leavesSendToOut() = runTest {
    val f = ActionsFixture(this)

    val labels = buildMenu {
      rowMenuEntries(listOf(rowOf(f.add(downloading))), f.runner, RowMenuContext())
    }.map(::labelOf)

    assertFalse("Send to" in labels)
    f.close()
  }

  @Test
  fun speedEntries_selection_checksTheSharedLimitAndOffersSharing() = runTest {
    val f = ActionsFixture(this)
    val request = DownloadRequest("https://example.com/a.iso", speedLimit = SpeedLimit.mbps(2))
    val rows = List(2) { rowOf(f.add(downloading, request)) }

    val entries = buildMenu { speedEntries(rows, f.runner) }

    val checked = entries.filterIsInstance<MenuEntry.Item>().single { it.checked == true }
    assertEquals("2 MB/s", checked.label)
    val share = entries.filterIsInstance<MenuEntry.Submenu>().single()
    assertEquals("Share across these 2", share.label)
    val five = share.entries.filterIsInstance<MenuEntry.Item>().single { it.label == "5 MB/s" }
    assertEquals("2.5 MB/s each", five.caption)
    f.close()
  }

  @Test
  fun priorityEntries_urgentOnQueuedRow_startsItNowAndNamesTheVictim() = runTest {
    val f = ActionsFixture(this)
    val queued = f.add(DownloadState.Queued)

    val entries = buildMenu { priorityEntries(listOf(rowOf(queued)), f.runner, "debian.iso") }
    val urgent = entries.filterIsInstance<MenuEntry.Item>().first()
    urgent.onClick()
    runCurrent()

    assertEquals("Starts now · may pause debian.iso", urgent.caption)
    assertTrue("priority ${DownloadPriority.URGENT}" in queued.calls)
    f.close()
  }

  @Test
  fun batchLabel_countsAndPlurals() {
    assertEquals("Pause 1 download", batchLabel(RowAction.Pause, 1, null))
    assertEquals("Start 2 downloads now", batchLabel(RowAction.StartNow, 2, null))
    assertEquals("Copy 1 link", batchLabel(RowAction.CopyLink, 1, null))
    assertEquals("Show in Finder (3)", batchLabel(RowAction.ShowInFolder, 3, "Show in Finder"))
    assertEquals("Speed limit", batchLabel(RowAction.SpeedLimit, 3, null))
    assertEquals("Download 2 files again", batchLabel(RowAction.DownloadAgain, 2, null))
    assertEquals(
      "Remove 1 download and its file…",
      batchLabel(RowAction.RemoveAndTrash, 1, null),
    )
    assertEquals(
      "Remove 3 downloads and their files…",
      batchLabel(RowAction.RemoveAndDelete, 3, null),
    )
  }

  private fun labelOf(entry: MenuEntry): String = when (entry) {
    is MenuEntry.Item -> entry.label
    is MenuEntry.Submenu -> entry.label
    MenuEntry.Divider -> "—"
    is MenuEntry.Header -> entry.text
    is MenuEntry.Custom -> "custom"
  }
}
