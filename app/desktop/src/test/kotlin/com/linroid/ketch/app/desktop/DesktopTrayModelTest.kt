package com.linroid.ketch.app.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.window.Notification
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.feedback.ActivityEvent
import com.linroid.ketch.app.feedback.NotificationCopy
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyChord
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DevicePulse
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.PulseState
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.config.DockBadgeMode
import com.linroid.ketch.config.SpeedLimitMode
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
    val menu = trayMenu(TrayContext(pulse(PulseCounts(paused = 2, done = 3)), null, emptyList()))

    assertFalse(menu.item(KetchCommands.PauseAll).enabled)
    assertTrue(menu.item(KetchCommands.ResumeAll).enabled)
  }

  @Test
  fun trayMenu_downloadingOrQueued_enablesPauseAll() {
    val downloading = trayMenu(TrayContext(pulse(PulseCounts(downloading = 1)), null, emptyList()))
    val queued = trayMenu(TrayContext(pulse(PulseCounts(waiting = 1)), null, emptyList()))

    assertTrue(downloading.item(KetchCommands.PauseAll).enabled)
    assertTrue(queued.item(KetchCommands.PauseAll).enabled)
    assertFalse(queued.item(KetchCommands.ResumeAll).enabled)
  }

  @Test
  fun trayMenu_idleOrFailing_headerIsTheStatusSentence() {
    val idle = pulse(PulseCounts(done = 4))
    val failing = pulse(PulseCounts(failed = 1), failures = 1)

    val idleHeader = trayMenu(TrayContext(idle, null, emptyList(), now)).first()
    val failingHeader = trayMenu(TrayContext(failing, null, emptyList(), now)).first()

    assertEquals(MenuEntry.Header("All quiet"), idleHeader)
    assertEquals(MenuEntry.Header("1 download needs attention"), failingHeader)
    assertEquals(MenuEntry.Header(failing.sentence(now = now)), failingHeader)
  }

  @Test
  fun trayMenu_withoutSpeedController_leavesOutSpeed() {
    val menu = trayMenu(TrayContext(pulse(PulseCounts()), null, emptyList()))

    assertTrue(menu.none { it is MenuEntry.Submenu && it.label == "Speed" })
  }

  @Test
  fun trayMenu_slowLane_checksSlowLaneWithItsSpeed() {
    val speed = TraySpeed(SpeedLimitMode.SlowLane, SpeedLimit.mbps(1), hasRules = false)

    val menu = trayMenu(TrayContext(pulse(PulseCounts()), speed, emptyList()))
    val choices = menu.submenu("Speed").entries.filterIsInstance<MenuEntry.Item>()

    assertEquals(listOf("Full speed", "Slow lane · 1.0 MB/s", "Auto"), choices.map { it.label })
    assertEquals(listOf(false, true, false), choices.map { it.checked })
    assertFalse(choices.last().enabled)
  }

  @Test
  fun trayMenu_recentDownloads_openTheirFiles() {
    val recent = listOf(RecentDownload("ubuntu.iso", "/Downloads/ubuntu.iso"))

    val empty = trayMenu(TrayContext(pulse(PulseCounts()), null, emptyList())).submenu("Recent")
    val listed = trayMenu(TrayContext(pulse(PulseCounts()), null, recent)).submenu("Recent")

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

    val menu = trayMenu(TrayContext(pulse(counts), speed, recent))
    val items = menu.flatMap { if (it is MenuEntry.Submenu) it.entries else listOf(it) }

    assertTrue(items.filterIsInstance<MenuEntry.Item>().all { it.shortcut == null })
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
  fun menuShortcut_globalChords_belongToTheMenu() {
    val mac = KeyboardPlatform.Mac

    assertEquals(KeyChord(Key.N, primary = true), menuShortcut(KetchCommands.Add, mac))
    assertEquals(
      KeyChord(Key.V, primary = true, shift = true),
      menuShortcut(KetchCommands.AddClipboardLink, mac),
    )
    assertEquals(
      KeyChord(Key.P, primary = true, shift = true),
      menuShortcut(KetchCommands.PauseAll, mac),
    )
    assertEquals(
      KeyChord(Key.L, primary = true, shift = true),
      menuShortcut(KetchCommands.SlowLane, mac),
    )
    assertEquals(
      KeyChord(Key.One, primary = true),
      menuShortcut(KetchCommands.tab(StatusFilter.All), mac),
    )
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

    for (command in listOf(KetchCommands.Open, KetchCommands.CopyLink, KetchCommands.Remove)) {
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
    assertEquals(listOf("This Mac (⌥⌘1)", "NAS (⌥⌘2)"), devices.map { it.label })
    assertEquals(listOf(false, true), devices.map { it.checked })
  }

  @Test
  fun menuBar_withoutDevicesOrSpeed_leavesThemOut() {
    val menus = menuBar(context(devices = emptyList(), slowLane = null))

    assertTrue(menus.none { it.title == "Device" })
    assertTrue(menus.menu("Downloads").none { it.runs(KetchCommands.SlowLane) })
  }

  @Test
  fun taskbarModel_activeCount_badgesDownloadingAndWaiting() {
    val pulse = pulse(PulseCounts(downloading = 2, waiting = 3), downloaded = 45, size = 100)

    val model = taskbarModel(pulse, unseenFailures = 0, DockBadgeMode.ActiveCount)

    assertEquals(TaskbarModel("5", 45, TaskbarProgress.Normal), model)
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

    assertEquals(0, watch.update(failing(tasks = 3, failures = 2), viewing = false))
    assertEquals(2, watch.unseen)
  }

  @Test
  fun failureWatch_newFailure_isUnseenUntilViewed() {
    val watch = FailureWatch()
    watch.update(failing(tasks = 2, failures = 0), viewing = false)

    assertEquals(1, watch.update(failing(tasks = 2, failures = 1), viewing = false))
    assertEquals(1, watch.unseen)
    assertEquals(0, watch.update(failing(tasks = 2, failures = 1), viewing = true))
    assertEquals(0, watch.unseen)
  }

  @Test
  fun failureWatch_retriedThenFailedAgain_countsAgain() {
    val watch = FailureWatch()
    watch.update(failing(tasks = 1, failures = 1), viewing = true)
    watch.update(failing(tasks = 1, failures = 0), viewing = false)

    assertEquals(1, watch.update(failing(tasks = 1, failures = 1), viewing = false))
    assertEquals(1, watch.unseen)
  }

  @Test
  fun failureWatch_failureRemoved_forgetsIt() {
    val watch = FailureWatch()
    watch.update(failing(tasks = 2, failures = 0), viewing = false)
    watch.update(failing(tasks = 2, failures = 2), viewing = false)

    watch.update(failing(tasks = 1, failures = 1), viewing = false)

    assertEquals(1, watch.unseen)
  }

  @Test
  fun failureWatch_deviceConnecting_takesItsBaselineOnceOnline() {
    val watch = FailureWatch()
    watch.update(failing(tasks = 0, failures = 0, health = DeviceHealth.Connecting), false)

    assertEquals(0, watch.update(failing(tasks = 4, failures = 2), viewing = false))
    assertEquals(2, watch.unseen)
  }

  @Test
  fun failureWatch_failedTasksArrivingWithTheList_areUnseenButNotNew() {
    val watch = FailureWatch()
    watch.update(failing(tasks = 0, failures = 0), viewing = false)

    assertEquals(0, watch.update(failing(tasks = 5, failures = 2), viewing = false))
    assertEquals(2, watch.unseen)
  }

  @Test
  fun failureWatch_deviceOffline_keepsItsCount() {
    val watch = FailureWatch()
    watch.update(failing(tasks = 2, failures = 1), viewing = false)
    watch.update(failing(tasks = 0, failures = 0, health = DeviceHealth.Offline()), false)

    assertEquals(1, watch.unseen)
    assertEquals(0, watch.update(failing(tasks = 2, failures = 1), viewing = false))
  }

  @Test
  fun failureWatch_switchingDevices_keepsWhatEachDeviceHasSeen() {
    val watch = FailureWatch()
    val mac = failing(tasks = 3, failures = 2)
    val nas = failing(tasks = 1, failures = 1, deviceId = "nas:8642")
    watch.update(mac, viewing = true)

    assertEquals(0, watch.update(nas, viewing = false))
    assertEquals(1, watch.unseen)
    assertEquals(0, watch.update(mac, viewing = false))
    assertEquals(0, watch.unseen)
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
  ): DevicePulse = DevicePulse(
    deviceId = deviceId,
    name = "This Mac",
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
  ): MenuBarContext = MenuBarContext(
    counts = counts,
    failures = counts.failed,
    filter = filter,
    devices = devices,
    activeDevice = activeDevice,
    selection = selection,
    undoLabel = undoLabel,
    inspectorOpen = true,
    slowLane = slowLane,
    platform = KeyboardPlatform.Mac,
  )

  private fun selected(phase: TaskPhase, local: Boolean = true) = SelectedTask(phase, local)

  private fun MenuEntry.runs(command: KetchCommand): Boolean =
    this is MenuEntry.Item && action == MenuAction.Run(command)

  private fun List<MenuEntry>.item(command: KetchCommand): MenuEntry.Item =
    filterIsInstance<MenuEntry.Item>().single { it.runs(command) }

  private fun List<MenuEntry>.submenu(label: String): MenuEntry.Submenu =
    filterIsInstance<MenuEntry.Submenu>().single { it.label == label }

  private fun List<MenuBarMenu>.menu(title: String): List<MenuEntry> =
    single { it.title == title }.entries
}
