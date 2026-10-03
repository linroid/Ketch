package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.ui.geometry.Offset
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.DeviceOption
import com.linroid.ketch.app.components.MenuEntry
import com.linroid.ketch.app.components.buildMenu
import com.linroid.ketch.app.components.startTimeOptions
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.rowOf
import com.linroid.ketch.app.ui.inspector.autoConnectionsOf
import com.linroid.ketch.app.ui.inspector.urgentVictim
import com.linroid.ketch.app.ui.shell.FleetFixtures.presence
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RowMenuTest {
  private val downloading = DownloadState.Downloading(DownloadProgress(10, 100, 5))
  private val completed = DownloadState.Completed("/downloads/a.iso", totalBytes = 100)

  @Test
  fun closeIfCurrent_menuReplacedByAnother_keepsTheNewOne() = actionsTest { f ->
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
  }

  @Test
  fun rowMenuEntries_downloadingRow_listsControlsThenDestructiveAfterADivider() = actionsTest { f ->
    val entries = buildMenu {
      rowMenuEntries(listOf(rowOf(f.add(downloading))), f.runner, RowMenuContext())
    }

    assertEquals(
      listOf(
        "Pause", "Speed limit", "Connections", "Priority", "Start later", "Copy link", "Details",
        "—", "Stop and discard progress…", "Remove from list",
      ),
      entries.map { labelOf(it) },
    )
  }

  @Test
  fun rowMenuEntries_completedRow_namesThePlatformsFileManager() =
    actionsTest(canTrash = true) { f ->
      val entries = buildMenu {
        rowMenuEntries(
          listOf(rowOf(f.add(completed))),
          f.runner,
          RowMenuContext(revealLabel = verbatim("Show in Finder")),
        )
      }

      val labels = entries.map { labelOf(it) }
      assertTrue("Show in Finder" in labels)
      assertEquals("Remove and trash file…", labels.last())
    }

  @Test
  fun rowMenuEntries_selection_countsWhatEachActionAppliesTo() = actionsTest { f ->
    val rows = listOf(f.add(downloading), f.add(downloading), f.add(completed)).map { rowOf(it) }

    val labels = buildMenu { rowMenuEntries(rows, f.runner, RowMenuContext()) }.map { labelOf(it) }

    assertEquals("Pause 2 downloads", labels.first())
    assertTrue("Copy 3 links" in labels)
    assertTrue("Remove 3 downloads from list" in labels)
  }

  @Test
  fun rowMenuEntries_otherDevices_offerSendToWithOfflineOnesDisabled() = actionsTest { f ->
    val row = rowOf(f.add(downloading))
    val laptop = SendTarget(
      EmbeddedInstance(f.api, "Laptop"),
      DeviceOption("laptop", verbatim("Laptop"), DeviceHealth.Live, "Laptop"),
    )
    val nas = SendTarget(
      EmbeddedInstance(f.api, "NAS"),
      DeviceOption("nas", verbatim("NAS"), DeviceHealth.Offline(), "NAS"),
    )

    val entries = buildMenu {
      rowMenuEntries(listOf(row), f.runner, RowMenuContext(devices = listOf(laptop, nas)))
    }

    val send = entries.filterIsInstance<MenuEntry.Submenu>().single { it.label.load() == "Send to" }
    val items = send.entries.filterIsInstance<MenuEntry.Item>()
    assertEquals(
      listOf("Laptop" to true, "NAS" to false),
      items.map { it.label.load() to it.enabled }
    )
    assertEquals("Offline", items.last().caption.load())
  }

  @Test
  fun rowMenuEntries_noOtherDevice_leavesSendToOut() = actionsTest { f ->
    val labels = buildMenu {
      rowMenuEntries(listOf(rowOf(f.add(downloading))), f.runner, RowMenuContext())
    }.map { labelOf(it) }

    assertFalse("Send to" in labels)
  }

  @Test
  fun speedEntries_selection_checksTheSharedLimitAndOffersSharing() = actionsTest { f ->
    val request = DownloadRequest("https://example.com/a.iso", speedLimit = SpeedLimit.mbps(2))
    val rows = List(2) { rowOf(f.add(downloading, request)) }

    val entries = buildMenu { speedEntries(rows, f.runner) }

    val checked = entries.filterIsInstance<MenuEntry.Item>().single { it.checked == true }
    assertEquals("2 MB/s", checked.label.load())
    val share = entries.filterIsInstance<MenuEntry.Submenu>().single()
    assertEquals("Share across these 2", share.label.load())
    val five = share.entries.filterIsInstance<MenuEntry.Item>()
      .single { it.label.load() == "5 MB/s" }
    assertEquals("2.5 MB/s each", five.caption.load())
  }

  @Test
  fun rowMenuEntries_mixedTorrentSelection_setsConnectionsOnTheOthersOnly() = actionsTest { f ->
    val magnet = DownloadRequest("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")
    val torrent = f.add(downloading, magnet)
    val http = f.add(downloading)

    val entries = buildMenu {
      rowMenuEntries(listOf(rowOf(torrent), rowOf(http)), f.runner, RowMenuContext())
    }
    val connections = entries.filterIsInstance<MenuEntry.Submenu>()
      .single { it.label.load().startsWith("Connections") }
    connections.entries.filterIsInstance<MenuEntry.Item>()
      .single { it.label.load() == "16" }
      .onClick()
    runCurrent()

    assertEquals(emptyList(), torrent.calls)
    assertEquals(listOf("connections 16"), http.calls)
  }

  @Test
  fun connectionTargets_leaveTorrentsOutOfAMixedSelectionOnly() = actionsTest { f ->
    val magnet = DownloadRequest("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")
    val torrents = List(2) { rowOf(f.add(downloading, magnet)) }
    val http = rowOf(f.add(downloading))

    assertEquals(listOf(http), connectionTargets(torrents + http))
    assertEquals(torrents, connectionTargets(torrents))
  }

  @Test
  fun autoConnectionsOf_rowWithSegments_countsThemBeforeTheDeviceDefault() = actionsTest { f ->
    val task = f.add(downloading)
    val fresh = rowOf(task)
    task.segments.value = List(3) { Segment(it, it * 100L, it * 100L + 99) }
    val opened = rowOf(task)

    assertEquals(3, autoConnectionsOf(f.state, listOf(opened)))
    assertEquals(4, autoConnectionsOf(f.state, listOf(fresh)))
    assertEquals(4, autoConnectionsOf(f.state, listOf(opened, fresh)))
  }

  @Test
  fun priorityEntries_urgentOnQueuedRow_startsItNowAndNamesTheVictim() = actionsTest { f ->
    val queued = f.add(DownloadState.Queued)

    val entries = buildMenu { priorityEntries(listOf(rowOf(queued)), f.runner, "debian.iso") }
    val urgent = entries.filterIsInstance<MenuEntry.Item>().first()
    urgent.onClick()
    runCurrent()

    assertEquals("Starts now · may pause debian.iso", urgent.caption.load())
    assertTrue("priority ${DownloadPriority.URGENT}" in queued.calls)
  }

  @Test
  fun urgentVictim_rowsThatAllRun_namesNone() = actionsTest { f ->
    // Urgent starts nothing here, so nothing makes room, however full the device is.
    assertNull(urgentVictim(f.state, listOf(rowOf(f.add(downloading)))))
    assertNull(urgentVictim(f.state, emptyList()))
  }

  @Test
  fun sendTargets_withPresence_takeItsHealthAndSummary() = actionsTest { f ->
    val nas = RemoteInstance(
      RecordingKetchApi("NAS"),
      RemoteConfig(host = "nas.local", name = "NAS"),
      MutableStateFlow(ConnectionState.Connected),
    )
    val row = rowOf(f.add(downloading))
    val presence = presence(nas, "NAS", health = DeviceHealth.Unauthorized, counts = PulseCounts(2))

    val listed = sendTargets(listOf(nas), listOf(row)).single().option
    val known = sendTargets(listOf(nas), listOf(row), listOf(presence)).single().option

    assertEquals(DeviceHealth.Live, listed.health)
    assertEquals(DeviceHealth.Unauthorized, known.health)
    assertEquals("2 active", known.summary?.load())
  }

  @Test
  fun startLaterEntries_scheduleThatPassed_checksNothingAndOffersNoClear() = actionsTest { f ->
    val now = Instant.fromEpochSeconds(1_800_000_000)
    val context = RowMenuContext(now = now, zone = TimeZone.UTC)
    val later = startTimeOptions(context.now, context.zone)[1].schedule
    val started = f.add(downloading, DownloadRequest("https://example.com/a.iso", schedule = later))
    val waiting = f.add(DownloadState.Scheduled(later))

    val passed = buildMenu { startLaterEntries(listOf(rowOf(started)), f.runner, context) }
    val pending = buildMenu { startLaterEntries(listOf(rowOf(waiting)), f.runner, context) }

    val items = passed.filterIsInstance<MenuEntry.Item>()
    assertTrue(items.none { it.checked == true || it.label.load() == "Clear" })
    val shown = pending.filterIsInstance<MenuEntry.Item>()
    assertEquals(1, shown.count { it.checked == true })
    assertTrue(shown.any { it.label.load() == "Clear" })
  }

  @Test
  fun batchLabel_countsAndPlurals() = runTest {
    assertEquals("Pause 1 download", batchLabel(RowAction.Pause, 1, null).load())
    assertEquals("Start 2 downloads now", batchLabel(RowAction.StartNow, 2, null).load())
    assertEquals("Copy 1 link", batchLabel(RowAction.CopyLink, 1, null).load())
    assertEquals(
      "Show in Finder (3)",
      batchLabel(RowAction.ShowInFolder, 3, verbatim("Show in Finder")).load()
    )
    assertEquals("Speed limit", batchLabel(RowAction.SpeedLimit, 3, null).load())
    assertEquals("Download 2 files again", batchLabel(RowAction.DownloadAgain, 2, null).load())
    assertEquals(
      "Remove 1 download and its file…",
      batchLabel(RowAction.RemoveAndTrash, 1, null).load(),
    )
    assertEquals(
      "Remove 3 downloads and their files…",
      batchLabel(RowAction.RemoveAndDelete, 3, null).load(),
    )
  }

  private suspend fun labelOf(entry: MenuEntry): String = when (entry) {
    is MenuEntry.Item -> entry.label.load()
    is MenuEntry.Submenu -> entry.label.load()
    MenuEntry.Divider -> "—"
    is MenuEntry.Header -> entry.text.load()
    is MenuEntry.Custom -> "custom"
  }
}
