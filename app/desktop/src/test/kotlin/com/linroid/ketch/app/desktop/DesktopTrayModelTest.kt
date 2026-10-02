package com.linroid.ketch.app.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.window.Notification
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.feedback.ActivityEvent
import com.linroid.ketch.app.feedback.NotificationCopy
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyChord
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.detail
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DevicePulse
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.PulseState
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.config.DockBadgeMode
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.config.SpeedLimitMode
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class DesktopTrayModelTest {
  private val now = Instant.parse("2026-10-01T10:00:00Z")

  @Test
  fun trayMenu_nothingRuns_disablesPauseAll() {
    val menu = trayMenu(tray(pulse(PulseCounts(paused = 2, done = 3))))

    assertFalse(menu.pauseAll().enabled)
    assertTrue(menu.resumeAll().enabled)
  }

  @Test
  fun trayMenu_downloadingOrQueued_enablesPauseAll() {
    val downloading = trayMenu(tray(pulse(PulseCounts(downloading = 1))))
    val queued = trayMenu(tray(pulse(PulseCounts(waiting = 1))))

    assertTrue(downloading.pauseAll().enabled)
    assertTrue(queued.pauseAll().enabled)
    assertFalse(queued.resumeAll().enabled)
  }

  @Test
  fun trayMenu_devicesOnlineAndOffline_pausesAndResumesTheOnlineOnes() {
    val pulse = PulseState(
      devices = listOf(
        device(PulseCounts(downloading = 1), failures = 0),
        device(PulseCounts(paused = 1), 0, DeviceHealth.Live, deviceId = NAS),
        device(PulseCounts(waiting = 4), 0, DeviceHealth.Offline(), deviceId = DEN),
      ),
    )

    val menu = trayMenu(tray(pulse))

    assertEquals(MenuAction.PauseAll(listOf(LOCAL, NAS)), menu.pauseAll().action)
    assertEquals(MenuAction.ResumeAll(listOf(LOCAL, NAS)), menu.resumeAll().action)
  }

  @Test
  fun trayMenu_offlineDeviceOnly_disablesPauseAll() {
    val pulse = PulseState(
      devices = listOf(
        device(PulseCounts(done = 1), failures = 0),
        device(PulseCounts(downloading = 2), 0, DeviceHealth.Offline(), deviceId = NAS),
      ),
    )

    assertFalse(trayMenu(tray(pulse)).pauseAll().enabled)
  }

  @Test
  fun trayMenu_idleOrFailing_headerIsTheStatusSentence() {
    val idle = pulse(PulseCounts(done = 4))
    val failing = pulse(PulseCounts(failed = 1), failures = 1)

    val idleHeader = trayMenu(tray(idle)).first()
    val failingHeader = trayMenu(tray(failing)).first()

    assertEquals(MenuEntry.Header("All quiet"), idleHeader)
    assertEquals(MenuEntry.Header("1 download needs attention"), failingHeader)
    assertEquals(MenuEntry.Header(failing.sentence(now = now)), failingHeader)
  }

  @Test
  fun trayMenu_withoutSpeedController_leavesOutSpeed() {
    val menu = trayMenu(tray(pulse(PulseCounts())))

    assertTrue(menu.none { it is MenuEntry.Submenu && it.label == "Speed" })
  }

  @Test
  fun trayMenu_slowLane_checksSlowLaneWithItsSpeed() {
    val speed = TraySpeed(SpeedLimitMode.SlowLane, SpeedLimit.mbps(1), hasRules = false)

    val menu = trayMenu(tray(pulse(PulseCounts()), speed = speed))
    val choices = menu.submenu("Speed").entries.filterIsInstance<MenuEntry.Item>()

    assertEquals(listOf("Full speed", "Slow lane · 1 MB/s", "Auto"), choices.map { it.label })
    assertEquals(listOf(false, true, false), choices.map { it.checked })
    assertFalse(choices.last().enabled)
  }

  @Test
  fun trayMenu_recentDownloads_openTheirFiles() {
    val recent = listOf(RecentDownload("ubuntu.iso", "/Downloads/ubuntu.iso"))

    val empty = trayMenu(tray(pulse(PulseCounts()))).submenu("Recent")
    val listed = trayMenu(tray(pulse(PulseCounts()), recent = recent)).submenu("Recent")

    assertFalse(empty.enabled)
    assertTrue(listed.enabled)
    val item = listed.entries.single() as MenuEntry.Item
    assertEquals(MenuAction.OpenFile("/Downloads/ubuntu.iso", "ubuntu.iso"), item.action)
  }

  @Test
  fun trayMenu_anyContext_hasNoShortcuts() {
    // AWT tray menus throw on a shortcut.
    val speed = TraySpeed(SpeedLimitMode.Full, SpeedLimit.mbps(1), hasRules = true)
    val recent = listOf(RecentDownload("ubuntu.iso", "/Downloads/ubuntu.iso"))
    val counts = PulseCounts(downloading = 1, paused = 1)
    val devices = listOf(
      TrayDevice(presence(counts = counts), speed = speed),
      TrayDevice(presence(NAS_NAME, remote = true, counts = counts), limit = SpeedLimit.Unlimited),
    )

    val menu = trayMenu(tray(pulse(counts), speed = speed, recent = recent, devices = devices))

    assertTrue(menu.flatten().filterIsInstance<MenuEntry.Item>().all { it.shortcut == null })
  }

  @Test
  fun trayMenu_devices_listEachWithWhatItIsDoing() {
    val devices = listOf(
      TrayDevice(presence(counts = PulseCounts(downloading = 2), speed = 6_710_886)),
      TrayDevice(presence(NAS_NAME, remote = true, counts = PulseCounts(failed = 1))),
      TrayDevice(presence(DEN_NAME, remote = true, health = DeviceHealth.Offline())),
    )

    val labels = trayMenu(tray(pulse(PulseCounts()), devices = devices))
      .submenu("Devices").entries.filterIsInstance<MenuEntry.Submenu>().map { it.label }

    assertEquals(
      listOf("This Mac — 6.4 MB/s · 2 active", "NAS-Basement — 1 failed", "Den-PC — Offline"),
      labels,
    )
  }

  @Test
  fun trayMenu_noDevices_offersToAddOne() {
    val devices = trayMenu(tray(pulse(PulseCounts()))).submenu("Devices").entries

    assertEquals(listOf(MenuEntry.Item(MenuAction.AddDevice, "Add device…")), devices)
  }

  @Test
  fun trayMenu_onlineDevice_actsOnThatDevice() {
    val counts = PulseCounts(downloading = 1, failed = 2)
    val nas = TrayDevice(presence(NAS_NAME, remote = true, counts = counts, speed = 2_831_155))

    val entries = trayMenu(tray(pulse(PulseCounts()), devices = listOf(nas)))
      .submenu("Devices").submenu("NAS-Basement — 2.7 MB/s · 1 active · 2 failed").entries
    val items = entries.filterIsInstance<MenuEntry.Item>()

    assertEquals(
      listOf(
        MenuAction.ShowDevice(NAS),
        MenuAction.PauseAll(listOf(NAS)),
        MenuAction.ResumeAll(listOf(NAS)),
        MenuAction.RetryFailed(listOf(NAS)),
        MenuAction.AddClipboardLink(NAS),
        MenuAction.StayConnected(NAS, watch = false),
      ),
      items.map { it.action },
    )
    assertEquals(listOf(true, true, false, true, true, true), items.map { it.enabled })
    assertEquals("Retry 2 failed", items[3].label)
    assertEquals(true, items.last().checked)
  }

  @Test
  fun trayMenu_thisComputer_switchesItsSpeedModeAndCannotBeDisconnected() {
    val speed = TraySpeed(SpeedLimitMode.Full, SpeedLimit.mbps(1), hasRules = true)
    val mac = TrayDevice(presence(), speed = speed)

    val entries = trayMenu(tray(pulse(PulseCounts()), devices = listOf(mac)))
      .submenu("Devices").submenu("This Mac — Idle").entries
    val modes = entries.submenu("Speed").entries.filterIsInstance<MenuEntry.Item>()

    assertEquals(
      SpeedLimitMode.entries.map { MenuAction.SetSpeedMode(it) },
      modes.map { it.action },
    )
    assertEquals(listOf(true, false, false), modes.map { it.checked })
    assertTrue(entries.none { it is MenuEntry.Item && it.action is MenuAction.StayConnected })
  }

  @Test
  fun trayMenu_deviceWithoutSpeedMode_picksItsSpeedLimit() {
    val custom = SpeedLimit.mbps(3)
    val nas = TrayDevice(presence(NAS_NAME, remote = true), limit = custom)

    val limits = trayMenu(tray(pulse(PulseCounts()), devices = listOf(nas)))
      .submenu("Devices").submenu("NAS-Basement — Idle").entries
      .submenu("Speed").entries.filterIsInstance<MenuEntry.Item>()

    assertEquals(
      listOf("Unlimited", "512 KB/s", "1 MB/s", "2 MB/s", "3 MB/s", "5 MB/s", "10 MB/s"),
      limits.map { it.label },
    )
    assertEquals(listOf("3 MB/s"), limits.filter { it.checked == true }.map { it.label })
    assertEquals(MenuAction.SetSpeedLimit(NAS, custom), limits[4].action)
  }

  @Test
  fun trayMenu_offlineDevice_offersRetryNow() {
    val den = TrayDevice(presence(DEN_NAME, remote = true, health = DeviceHealth.Offline()))

    val entries = trayMenu(tray(pulse(PulseCounts()), devices = listOf(den)))
      .submenu("Devices").submenu("Den-PC — Offline").entries

    assertEquals(
      listOf(
        MenuAction.ShowDevice(DEN),
        MenuAction.Reconnect(DEN),
        MenuAction.StayConnected(DEN, watch = false),
      ),
      entries.filterIsInstance<MenuEntry.Item>().map { it.action },
    )
  }

  @Test
  fun trayMenu_deviceRejectedItsToken_asksForANewOne() {
    val nas = presence(NAS_NAME, remote = true, health = DeviceHealth.Unauthorized)

    val entries = trayMenu(tray(pulse(PulseCounts()), devices = listOf(TrayDevice(nas))))
      .submenu("Devices").submenu("NAS-Basement — Needs token").entries

    assertTrue(entries.any { it is MenuEntry.Item && it.action == MenuAction.EnterToken(NAS) })
    assertTrue(entries.none { it is MenuEntry.Item && it.action is MenuAction.Reconnect })
  }

  @Test
  fun trayMenu_deviceNotKeptConnected_offersToStayConnected() {
    val den = presence(
      DEN_NAME,
      remote = true,
      health = DeviceHealth.Offline(),
      connected = false,
      watched = false,
    )

    val entries = trayMenu(tray(pulse(PulseCounts()), devices = listOf(TrayDevice(den))))
      .submenu("Devices").submenu("Den-PC — Not connected").entries
    val items = entries.filterIsInstance<MenuEntry.Item>()

    assertEquals(
      listOf(MenuAction.ShowDevice(DEN), MenuAction.StayConnected(DEN, watch = true)),
      items.map { it.action },
    )
    assertEquals(false, items.last().checked)
  }

  @Test
  fun trayDeviceStatus_onlineDevice_saysWhatItIsDoing() {
    val slowLane = presence(
      counts = PulseCounts(downloading = 1, waiting = 2),
      speed = 1_048_576,
      speedMode = SpeedMode.SlowLane,
    )

    assertEquals("Idle", trayDeviceStatus(presence(counts = PulseCounts(done = 3))))
    assertEquals("2 waiting", trayDeviceStatus(presence(counts = PulseCounts(waiting = 2))))
    assertEquals("3 paused", trayDeviceStatus(presence(counts = PulseCounts(paused = 3))))
    assertEquals("1.0 MB/s · 1 active · Slow lane", trayDeviceStatus(slowLane))
    assertEquals("Connecting", trayDeviceStatus(presence(health = DeviceHealth.Connecting)))
  }

  @Test
  fun trayTooltip_downloading_showsSpeedAndActiveCount() {
    val pulse = pulse(PulseCounts(downloading = 3, waiting = 2), speed = 4_404_019)

    assertEquals("Ketch — ↓ 4.2 MB/s · 3 active", trayTooltip(pulse, now))
  }

  @Test
  fun trayTooltip_idle_showsTheSentence() {
    assertEquals("Ketch — All quiet", trayTooltip(pulse(PulseCounts()), now))
  }

  @Test
  fun fleetPulse_devicesNotConnected_areLeftOut() {
    val presence = listOf(
      presence(counts = PulseCounts(downloading = 1)),
      presence(NAS_NAME, remote = true, counts = PulseCounts(downloading = 2), speed = 2_000),
      presence(DEN_NAME, remote = true, counts = PulseCounts(downloading = 4), connected = false),
    )
    val local = device(PulseCounts(downloading = 1), 0, speed = 1_000, downloaded = 5, size = 10)

    val fleet = fleetPulse(presence, listOf(local), SpeedMode.SlowLane)

    assertEquals(listOf(LOCAL, NAS), fleet.devices.map { it.deviceId })
    assertEquals(local, fleet.devices.first())
    assertEquals(3, fleet.counts.downloading)
    assertEquals(3_000, fleet.totalSpeed)
    assertEquals(0.5f, fleet.progress)
    assertEquals(SpeedMode.SlowLane, fleet.mode)
  }

  @Test
  fun fleetPulse_beforeThePresenceIsIn_keepsTheOnlineDevicesThePulseKnows() {
    val local = device(PulseCounts(downloading = 1), failures = 0)
    val den = device(PulseCounts(), 0, DeviceHealth.Offline(), deviceId = DEN)

    val fleet = fleetPulse(emptyList(), listOf(local, den), SpeedMode.Full)

    assertEquals(listOf(local), fleet.devices)
  }

  @Test
  fun menuShortcut_globalChords_belongToTheMenu() {
    val owned = listOf(
      KetchCommands.Add to KeyChord(Key.N, primary = true),
      KetchCommands.AddClipboardLink to KeyChord(Key.V, primary = true, shift = true),
      KetchCommands.PauseAll to KeyChord(Key.P, primary = true, shift = true),
      KetchCommands.SlowLane to KeyChord(Key.L, primary = true, shift = true),
      KetchCommands.tab(StatusFilter.All) to KeyChord(Key.One, primary = true),
      KetchCommands.AllDevices to KeyChord(Key.Zero, primary = true, alt = true),
    )

    for ((command, chord) in owned) {
      assertEquals(chord, menuShortcut(command, KeyboardPlatform.Mac), command.id)
    }
  }

  @Test
  fun menuShortcut_chordsOfFocusedControls_stayWithThem() {
    val kept = listOf(
      KetchCommands.PasteLinks,
      KetchCommands.Undo,
      KetchCommands.SelectAll,
      KetchCommands.CopyLink,
      KetchCommands.TogglePause,
      KetchCommands.Open,
      KetchCommands.Remove,
      KetchCommands.Reveal,
      KetchCommands.Retry,
      // The add sheet uses ⌘O and ⌥⌘1 to ⌥⌘9 too.
      KetchCommands.OpenTorrent,
      KetchCommands.device(1),
    )

    for (command in kept) assertNull(menuShortcut(command, KeyboardPlatform.Mac), command.id)
  }

  @Test
  fun commandItem_chordTheMenuCannotOwn_showsItAsText() {
    val copy = commandItem(KetchCommands.CopyLink, KeyboardPlatform.Mac)
    val add = commandItem(KetchCommands.Add, KeyboardPlatform.Mac)

    assertEquals("Copy link (⌘C)", copy.label)
    assertNull(copy.shortcut)
    assertEquals("New download…", add.label)
  }

  @Test
  fun menuBar_nothingRuns_disablesPauseAll() {
    val menus = menuBar(context(counts = PulseCounts(done = 2)))
    val downloads = menus.menu("Downloads")

    assertFalse(downloads.item(KetchCommands.PauseAll).enabled)
    assertFalse(downloads.item(KetchCommands.ResumeAll).enabled)
    assertFalse(downloads.item(KetchCommands.RetryFailed).enabled)
  }

  @Test
  fun menuBar_pendingOperation_namesItInUndo() {
    val pending = menuBar(context(undoLabel = "Pause All")).menu("Edit")
    val none = menuBar(context(undoLabel = null)).menu("Edit")

    assertEquals("Undo Pause All (⌘Z)", pending.item(KetchCommands.Undo).label)
    assertTrue(pending.item(KetchCommands.Undo).enabled)
    assertFalse(none.item(KetchCommands.Undo).enabled)
  }

  @Test
  fun menuBar_selection_namesTheToggleAndEnablesItsActions() {
    val running = menuBar(context(selection = listOf(selected(TaskPhase.Running))))
      .menu("Downloads")
    val paused = menuBar(context(selection = listOf(selected(TaskPhase.Paused))))
      .menu("Downloads")
    val done = menuBar(context(selection = listOf(selected(TaskPhase.Completed))))
      .menu("Downloads")
    val remoteDone = menuBar(context(selection = listOf(selected(TaskPhase.Completed, false))))
      .menu("Downloads")

    assertEquals("Pause (Space)", running.item(KetchCommands.TogglePause).label)
    assertEquals("Resume (Space)", paused.item(KetchCommands.TogglePause).label)
    assertFalse(done.item(KetchCommands.TogglePause).enabled)
    assertTrue(paused.item(KetchCommands.Retry).enabled)
    assertFalse(running.item(KetchCommands.Retry).enabled)
    assertTrue(done.item(KetchCommands.Reveal).enabled)
    assertFalse(remoteDone.item(KetchCommands.Reveal).enabled)
  }

  @Test
  fun menuBar_noSelection_disablesTaskActions() {
    val downloads = menuBar(context()).menu("Downloads")

    val actions = listOf(
      KetchCommands.Open,
      KetchCommands.ShowDetails,
      KetchCommands.CopyLink,
      KetchCommands.Remove
    )
    for (command in actions) {
      assertFalse(downloads.item(command).enabled, command.id)
    }
  }

  @Test
  fun menuBar_tabsAndDevices_checkTheActiveOnes() {
    val menus = menuBar(
      context(filter = StatusFilter.Failed, devices = listOf("This Mac", "NAS"), activeDevice = 1),
    )
    val tabs = StatusFilter.entries.map { menus.menu("View").item(KetchCommands.tab(it)) }
    val devices = menus.menu("Device").filterIsInstance<MenuEntry.Item>()

    assertEquals(StatusFilter.entries.map { it == StatusFilter.Failed }, tabs.map { it.checked })
    assertEquals(
      listOf("All devices", "This Mac (⌥⌘1)", "NAS (⌥⌘2)", "Pair a device…"),
      devices.map { it.label },
    )
    assertEquals(listOf(false, false, true, null), devices.map { it.checked })
    assertEquals(MenuAction.PairDevice, devices.last().action)
  }

  @Test
  fun menuBar_allDevicesShown_checksAllDevicesAlone() {
    val menus = menuBar(
      context(devices = listOf("This Mac", "NAS"), activeDevice = 1, allDevices = true),
    )
    val devices = menus.menu("Device").filterIsInstance<MenuEntry.Item>()

    assertEquals(listOf(true, false, false, null), devices.map { it.checked })
    assertTrue(devices.first().enabled)
  }

  @Test
  fun menuBar_oneDevice_disablesAllDevices() {
    val device = menuBar(context(devices = listOf("This Mac"))).menu("Device")

    assertFalse(device.item(KetchCommands.AllDevices).enabled)
  }

  @Test
  fun menuBar_editViewAndHelp_offerThePaletteDestinationsAndShortcuts() {
    val menus = menuBar(context())

    assertEquals(
      KeyChord(Key.K, primary = true),
      menus.menu("Edit").item(KetchCommands.Palette).shortcut,
    )
    for (command in listOf(KetchCommands.Discover, KetchCommands.Devices, KetchCommands.Activity)) {
      assertTrue(menus.menu("View").any { it.runs(command) }, command.id)
    }
    assertTrue(menus.menu("Help").any { it.runs(KetchCommands.Shortcuts) })
  }

  @Test
  fun menuBar_fileMenu_addsADevice() {
    val file = menuBar(context()).menu("File")

    assertTrue(file.any { it is MenuEntry.Item && it.action == MenuAction.AddDevice })
  }

  @Test
  fun menuBar_withoutSpeed_leavesOutSlowLane() {
    val menus = menuBar(context(devices = emptyList(), slowLane = null))

    assertTrue(menus.menu("Downloads").none { it.runs(KetchCommands.SlowLane) })
  }

  @Test
  fun taskbarModel_activeCount_badgesDownloadingAndWaiting() {
    val pulse = pulse(PulseCounts(downloading = 2, waiting = 3), downloaded = 45, size = 100)

    val model = taskbarModel(pulse, unseenFailures = 0, DockBadgeMode.ActiveCount)

    assertEquals(TaskbarModel("5", 45, TaskbarProgress.Normal), model)
  }

  @Test
  fun taskbarModel_offlineDevice_leavesOutTheTasksItLastReported() {
    val pulse = PulseState(
      devices = listOf(
        device(PulseCounts(waiting = 1), failures = 0),
        device(PulseCounts(downloading = 2), 0, DeviceHealth.Offline(), deviceId = NAS),
      ),
    )

    val model = taskbarModel(pulse, unseenFailures = 0, DockBadgeMode.ActiveCount)

    assertEquals(TaskbarModel("1", -1, TaskbarProgress.Off), model)
    assertEquals("Ketch — NAS-Basement is offline · retrying", trayTooltip(pulse, now))
  }

  @Test
  fun taskbarModel_unseenFailure_showsErrorUnlessOff() {
    val pulse = pulse(PulseCounts(downloading = 1, failed = 1), failures = 1)

    val active = taskbarModel(pulse, unseenFailures = 1, DockBadgeMode.ActiveCount)
    val failuresOnly = taskbarModel(pulse, unseenFailures = 1, DockBadgeMode.FailuresOnly)
    val off = taskbarModel(pulse, unseenFailures = 1, DockBadgeMode.Off)

    assertEquals("!", active.badge)
    assertEquals(TaskbarProgress.Error, active.state)
    assertEquals("!", failuresOnly.badge)
    assertNull(off.badge)
  }

  @Test
  fun taskbarModel_failuresOnlyWithoutFailures_showsNoBadge() {
    val pulse = pulse(PulseCounts(downloading = 2))

    assertNull(taskbarModel(pulse, unseenFailures = 0, DockBadgeMode.FailuresOnly).badge)
  }

  @Test
  fun taskbarModel_everythingPaused_showsPausedWithoutProgress() {
    val paused = taskbarModel(pulse(PulseCounts(paused = 2)), 0, DockBadgeMode.ActiveCount)
    val idle = taskbarModel(pulse(PulseCounts(done = 2)), 0, DockBadgeMode.ActiveCount)

    assertEquals(TaskbarModel(null, -1, TaskbarProgress.Paused), paused)
    assertEquals(TaskbarModel.Idle, idle)
  }

  @Test
  fun failureWatch_firstCount_isUnseenButNotNew() {
    val watch = FailureWatch()

    assertEquals(0, watch.update(failing(tasks = 3, failures = 2), NONE))
    assertEquals(2, watch.unseen)
  }

  @Test
  fun failureWatch_newFailure_isUnseenUntilViewed() {
    val watch = FailureWatch()
    watch.update(failing(tasks = 2, failures = 0), NONE)

    assertEquals(1, watch.update(failing(tasks = 2, failures = 1), NONE))
    assertEquals(1, watch.unseen)
    assertEquals(0, watch.update(failing(tasks = 2, failures = 1), setOf(LOCAL)))
    assertEquals(0, watch.unseen)
  }

  @Test
  fun failureWatch_retriedThenFailedAgain_countsAgain() {
    val watch = FailureWatch()
    watch.update(failing(tasks = 1, failures = 1), setOf(LOCAL))
    watch.update(failing(tasks = 1, failures = 0), NONE)

    assertEquals(1, watch.update(failing(tasks = 1, failures = 1), NONE))
    assertEquals(1, watch.unseen)
  }

  @Test
  fun failureWatch_failureRemoved_forgetsIt() {
    val watch = FailureWatch()
    watch.update(failing(tasks = 2, failures = 0), NONE)
    watch.update(failing(tasks = 2, failures = 2), NONE)

    watch.update(failing(tasks = 1, failures = 1), NONE)

    assertEquals(1, watch.unseen)
  }

  @Test
  fun failureWatch_deviceConnecting_takesItsBaselineOnceOnline() {
    val watch = FailureWatch()
    watch.update(failing(tasks = 0, failures = 0, health = DeviceHealth.Connecting), NONE)

    assertEquals(0, watch.update(failing(tasks = 4, failures = 2), NONE))
    assertEquals(2, watch.unseen)
  }

  @Test
  fun failureWatch_failedTasksArrivingWithTheList_areUnseenButNotNew() {
    val watch = FailureWatch()
    watch.update(failing(tasks = 0, failures = 0), NONE)

    assertEquals(0, watch.update(failing(tasks = 5, failures = 2), NONE))
    assertEquals(2, watch.unseen)
  }

  @Test
  fun failureWatch_deviceOffline_keepsItsCount() {
    val watch = FailureWatch()
    watch.update(failing(tasks = 2, failures = 1), NONE)
    watch.update(failing(tasks = 0, failures = 0, health = DeviceHealth.Offline()), NONE)

    assertEquals(1, watch.unseen)
    assertEquals(0, watch.update(failing(tasks = 2, failures = 1), NONE))
  }

  @Test
  fun failureWatch_switchingDevices_keepsWhatEachDeviceHasSeen() {
    val watch = FailureWatch()
    val mac = failing(tasks = 3, failures = 2)
    val nas = failing(tasks = 1, failures = 1, deviceId = "nas:8642")
    watch.update(mac, setOf(LOCAL))

    assertEquals(0, watch.update(nas, NONE))
    assertEquals(1, watch.unseen)
    assertEquals(0, watch.update(mac, NONE))
    assertEquals(0, watch.unseen)
  }

  @Test
  fun failureWatch_viewingOneDevice_leavesTheOthersUnseen() {
    val watch = FailureWatch()
    val both = failing(tasks = 1, failures = 1) +
      failing(tasks = 1, failures = 1, deviceId = NAS)

    watch.update(both, setOf(NAS))

    assertEquals(1, watch.unseen)
  }

  @Test
  fun desktopStatusUpdate_viewingFailures_seesOnlyTheShownDevices() {
    val status = DesktopStatus()
    val shown = pulse(PulseCounts(failed = 1), failures = 1)
    val nas = device(PulseCounts(failed = 2), failures = 2, DeviceHealth.Live, deviceId = NAS)
    val fleet = PulseState(devices = shown.devices + nas)

    status.update(shown, viewingFailures = true, fleet = fleet)

    assertEquals(2, status.unseenFailures)
    assertEquals(fleet, status.fleet)
  }

  @Test
  fun desktopStatusUpdate_pulseWithoutDevices_leavesFailuresAlone() {
    val status = DesktopStatus()
    status.update(PulseState(), viewingFailures = false)
    status.update(pulse(PulseCounts(failed = 1), failures = 1), viewingFailures = false)

    assertEquals(1, status.unseenFailures)
  }

  @Test
  fun taskbarModelWindowProgress_pausedOrFailedWithoutProgress_fillsTheBar() {
    val paused = taskbarModel(pulse(PulseCounts(paused = 2)), 0, DockBadgeMode.ActiveCount)
    val failed = taskbarModel(pulse(PulseCounts(failed = 1), failures = 1), 1, DockBadgeMode.Off)
    val downloading = pulse(PulseCounts(downloading = 1), downloaded = 45, size = 100)

    assertEquals(100, paused.windowProgress)
    assertEquals(100, failed.windowProgress)
    assertEquals(45, taskbarModel(downloading, 0, DockBadgeMode.Off).windowProgress)
    assertEquals(-1, TaskbarModel.Idle.windowProgress)
  }

  @Test
  fun notifySendCommand_textStartingWithDash_followsDoubleDash() {
    val command = notifySendCommand(NotificationCopy("Download failed", "-x.pdf: Access denied"))

    assertEquals(
      listOf("notify-send", "--app-name=Ketch", "--", "Download failed", "-x.pdf: Access denied"),
      command,
    )
  }

  @Test
  fun notificationType_failureAndOffline_raiseTheLevel() {
    val request = DownloadRequest(url = "https://example.com/a.iso")
    val failed = ActivityEvent.Failed(
      TaskKey("local", "1"),
      request,
      DownloadState.Failed(KetchError.Network()),
    )

    assertEquals(Notification.Type.Error, notificationType(failed))
    assertEquals(Notification.Type.Warning, notificationType(ActivityEvent.DeviceOffline("nas")))
    assertEquals(Notification.Type.Info, notificationType(ActivityEvent.QueueDrained("nas", 2, 0)))
  }

  private fun tray(
    pulse: PulseState,
    speed: TraySpeed? = null,
    recent: List<RecentDownload> = emptyList(),
    devices: List<TrayDevice> = emptyList(),
  ): TrayContext = TrayContext(pulse, speed, recent, now, devices)

  // A device as the app's devices list it: This Mac, else a remote device named [name].
  private fun presence(
    name: String = "This Mac",
    remote: Boolean = false,
    health: DeviceHealth = if (remote) DeviceHealth.Live else DeviceHealth.Local(),
    connected: Boolean = true,
    watched: Boolean = true,
    counts: PulseCounts = PulseCounts(),
    speed: Long = 0,
    speedMode: SpeedMode = SpeedMode.Full,
  ): DevicePresence {
    val entry = if (remote) {
      val config = RemoteConfig(host = name.lowercase(), name = name, watch = watched)
      RemoteInstance(NoApi, config, MutableStateFlow(ConnectionState.Connected))
    } else {
      EmbeddedInstance(NoApi, "Lins-MacBook-Pro")
    }
    return DevicePresence(
      entry = entry,
      name = name,
      detail = entry.detail,
      health = health,
      connected = connected,
      watched = watched,
      status = null,
      statusAt = null,
      lastSeen = null,
      speed = speed,
      counts = counts,
      failures = counts.failed,
      unseenFailures = 0,
      cap = SpeedLimit.Unlimited,
      disk = null,
      speedMode = speedMode,
      history = emptyList(),
    )
  }

  private fun pulse(
    counts: PulseCounts,
    failures: Int = 0,
    speed: Long = 0,
    downloaded: Long = 0,
    size: Long = 0,
  ): PulseState = PulseState(
    devices = listOf(device(counts, failures, speed = speed, downloaded = downloaded, size = size)),
  )

  // One device with [tasks] tasks, [failures] of them failed.
  private fun failing(
    tasks: Int,
    failures: Int,
    health: DeviceHealth = DeviceHealth.Local(),
    deviceId: String = "local",
  ): List<DevicePulse> {
    val counts = PulseCounts(done = tasks - failures, failed = failures)
    return listOf(device(counts, failures, health, deviceId))
  }

  private fun device(
    counts: PulseCounts,
    failures: Int,
    health: DeviceHealth = DeviceHealth.Local(),
    deviceId: String = "local",
    speed: Long = 0,
    downloaded: Long = 0,
    size: Long = 0,
    name: String = if (deviceId == LOCAL) "This Mac" else NAS_NAME,
  ): DevicePulse = DevicePulse(
    deviceId = deviceId,
    name = name,
    health = health,
    counts = counts,
    failures = failures,
    speed = speed,
    cap = SpeedLimit.Unlimited,
    downloadedBytes = downloaded,
    sizeBytes = size,
    sizesKnown = size > 0,
    pendingBytes = 0,
    disk = null,
    history = emptyList(),
  )

  private fun context(
    counts: PulseCounts = PulseCounts(),
    filter: StatusFilter = StatusFilter.All,
    devices: List<String> = listOf("This Mac"),
    activeDevice: Int? = 0,
    selection: List<SelectedTask> = emptyList(),
    undoLabel: String? = null,
    slowLane: Boolean? = false,
    allDevices: Boolean = false,
  ): MenuBarContext = MenuBarContext(
    counts = counts,
    failures = counts.failed,
    filter = filter,
    devices = devices,
    activeDevice = activeDevice,
    selection = selection,
    undoLabel = undoLabel,
    slowLane = slowLane,
    allDevices = allDevices,
    platform = KeyboardPlatform.Mac,
  )

  private fun selected(phase: TaskPhase, local: Boolean = true) = SelectedTask(phase, local)

  private fun MenuEntry.runs(command: KetchCommand): Boolean =
    this is MenuEntry.Item && action == MenuAction.Run(command)

  private fun List<MenuEntry>.item(command: KetchCommand): MenuEntry.Item =
    filterIsInstance<MenuEntry.Item>().single { it.runs(command) }

  private fun List<MenuEntry>.submenu(label: String): MenuEntry.Submenu =
    filterIsInstance<MenuEntry.Submenu>().single { it.label == label }

  private fun MenuEntry.Submenu.submenu(label: String): MenuEntry.Submenu = entries.submenu(label)

  private fun List<MenuEntry>.pauseAll(): MenuEntry.Item =
    filterIsInstance<MenuEntry.Item>().single { it.action is MenuAction.PauseAll }

  private fun List<MenuEntry>.resumeAll(): MenuEntry.Item =
    filterIsInstance<MenuEntry.Item>().single { it.action is MenuAction.ResumeAll }

  private fun List<MenuEntry>.flatten(): List<MenuEntry> =
    flatMap { if (it is MenuEntry.Submenu) it.entries.flatten() else listOf(it) }

  private fun List<MenuBarMenu>.menu(title: String): List<MenuEntry> =
    single { it.title == title }.entries

  private companion object {
    const val LOCAL = "local"
    const val NAS_NAME = "NAS-Basement"
    const val NAS = "nas-basement:8642"
    const val DEN_NAME = "Den-PC"
    const val DEN = "den-pc:8642"
    val NONE = emptySet<String>()
  }

  private object NoApi : KetchApi {
    override val backendLabel: String = "Test"
    override val tasks = MutableStateFlow(emptyList<DownloadTask>())

    override suspend fun download(request: DownloadRequest): DownloadTask = error("Not used")

    override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
      error("Not used")

    override suspend fun start() {}

    override suspend fun status(): KetchStatus = error("Not used")

    override suspend fun updateConfig(config: DownloadConfig) {}

    override fun close() {}
  }
}
