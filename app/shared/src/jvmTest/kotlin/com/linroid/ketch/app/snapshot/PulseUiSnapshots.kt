package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.MessagePlacement
import com.linroid.ketch.app.feedback.ToastMode
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DevicePulse
import com.linroid.ketch.app.state.DiskSpace
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.ObservedPeak
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.PulseState
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.downloads.DownloadsScreen
import com.linroid.ketch.app.ui.downloads.KetchLayoutInfo
import com.linroid.ketch.app.ui.feedback.ActivityContent
import com.linroid.ketch.app.ui.feedback.Banner
import com.linroid.ketch.app.ui.feedback.BannerHost
import com.linroid.ketch.app.ui.feedback.BannerStack
import com.linroid.ketch.app.ui.feedback.BannerTone
import com.linroid.ketch.app.ui.feedback.CONNECTING_GRACE
import com.linroid.ketch.app.ui.feedback.ToastHost
import com.linroid.ketch.app.ui.feedback.deviceBanner
import com.linroid.ketch.app.ui.pulse.PulseBar
import com.linroid.ketch.app.ui.pulse.PulseBarContent
import com.linroid.ketch.app.ui.pulse.PulseSubtitle
import com.linroid.ketch.app.ui.pulse.PulseSummary
import com.linroid.ketch.app.ui.pulse.SpeedHistoryContent
import com.linroid.ketch.app.ui.pulse.SpeedModeOptions
import com.linroid.ketch.app.ui.pulse.SpeedModePillContent
import com.linroid.ketch.app.ui.pulse.SpeedModeView
import com.linroid.ketch.app.ui.pulse.totalHistory
import com.linroid.ketch.config.SpeedLimitMode
import com.linroid.ketch.config.SpeedSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The Pulse bar, the speed mode pill and popover, the phone's Pulse sheet, banners, toasts and
 * the Activity popover (W3-PULSE-UI); see [SnapshotHarness] for how to run it.
 */
class PulseUiSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun pulseBar_everyState_rendersEachModeAndWidth() {
    val size = SnapshotSize(1052.dp, 640.dp, KetchDensity.Compact)
    SnapshotTheme.entries.forEach { theme ->
      snapshot("pulse-bar-states", size, theme) {
        Column(
          verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s3),
          modifier = Modifier.padding(KetchTheme.spacing.s4),
        ) {
          BarSample("Full speed, sharing, 3 unread", PulseSamples.busy, PulseSamples.full, 3)
          BarSample("Standing cap, idle", PulseSamples.idle, PulseSamples.capped, 0)
          BarSample(
            label = "Slow lane, disk short, selection",
            pulse = PulseSamples.diskShort,
            mode = PulseSamples.slowLane,
            unread = 12,
            selection = "3 selected · 2.40 GB · 9.1 MB/s",
          )
          BarSample("Auto rule, not shared", PulseSamples.notShared, PulseSamples.auto, 0)
          BarSample("Remote connecting", PulseSamples.connecting, PulseSamples.full, 0)
          BarSample("Remote offline", PulseSamples.offline, PulseSamples.capped, 1)
        }
      }
    }
  }

  @Test
  fun pulseBar_narrowCards_collapseFromTheRight() {
    val size = SnapshotSize(720.dp, 420.dp, KetchDensity.Compact)
    SnapshotTheme.entries.forEach { theme ->
      snapshot("pulse-bar-widths", size, theme) {
        Column(
          verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s3),
          modifier = Modifier.padding(KetchTheme.spacing.s4),
        ) {
          // The medium tier's narrowest card, with the longest pill: what does not fit goes.
          BarSample(
            label = "640 dp, Auto rule",
            pulse = PulseSamples.busy,
            mode = PulseSamples.auto,
            unread = 2,
            width = 640.dp,
          )
          BarSample(
            label = "640 dp, Auto rule, selection",
            pulse = PulseSamples.busy,
            mode = PulseSamples.auto,
            unread = 2,
            selection = "3 selected · 19.94 GB · 23.7 MB/s",
            width = 640.dp,
          )
          listOf(680.dp, 560.dp, 440.dp, 360.dp).forEach { width ->
            BarSample(
              label = "${width.value.toInt()} dp",
              pulse = PulseSamples.busy,
              mode = PulseSamples.slowLane,
              unread = 2,
              width = width,
            )
          }
        }
      }
    }
  }

  @Test
  fun card_desktopAndMedium_showsBarBannerAndToasts() {
    for (size in listOf(SnapshotSize.Desktop, SnapshotSize(680.dp, 700.dp, KetchDensity.Compact))) {
      SnapshotTheme.entries.forEach { theme ->
        val setup: suspend AppState.() -> Unit = {
          messages.post(
            level = MessageLevel.Info,
            title = "Downloads pause when Ketch is in the background",
            actions = listOf(MessageAction("Use a computer instead") {}),
            toast = ToastMode.Sticky,
            placement = MessagePlacement.Banner,
          )
          messages.post(MessageLevel.Success, "Slow lane on · 3 MB/s", actions = undo())
          messages.post(
            level = MessageLevel.Error,
            title = "Couldn't pause imagenet-part03.tar on MacBook Pro",
            actions = listOf(MessageAction("Try again") {}),
            cause = IllegalStateException("Connection lost"),
          )
        }
        withPulseApp(theme, size, slowLane = true, setup) { state ->
          snapshot("pulse-card", size, theme) { PulseCard(state, size.width) }
        }
      }
    }
  }

  @Test
  fun card_selectionAndRemoteDevice_showTheirSummaries() {
    val select: suspend AppState.() -> Unit = {
      selectedKeys = setOf("ubuntu", "imagenet-03", "android-studio")
        .mapTo(LinkedHashSet()) { TaskKey(LOCAL_DEVICE_ID, it) }
    }
    withPulseApp(SnapshotTheme.Light, SnapshotSize.Desktop, slowLane = false, select) { state ->
      snapshot("pulse-card-selection", SnapshotSize.Desktop, SnapshotTheme.Light) {
        PulseCard(state, SnapshotSize.Desktop.width)
      }
    }
    SnapshotTheme.entries.forEach { theme ->
      val toNas: suspend AppState.() -> Unit = {
        switchInstance(instances.value.filterIsInstance<RemoteInstance>().first())
        withTimeout(10.seconds) {
          pulse.state.first { pulse -> pulse.devices.any { it.deviceId != LOCAL_DEVICE_ID } }
        }
      }
      withPulseApp(theme, SnapshotSize.Desktop, slowLane = false, toNas) { state ->
        snapshot(
          name = "pulse-card-remote",
          size = SnapshotSize.Desktop,
          theme = theme,
          interact = { delay(CONNECTING_GRACE + 500.milliseconds) },
        ) {
          PulseCard(state, SnapshotSize.Desktop.width)
        }
      }
    }
  }

  @Test
  fun popovers_speedModeAndHistory_renderInPlace() {
    val size = SnapshotSize(720.dp, 560.dp, KetchDensity.Compact)
    SnapshotTheme.entries.forEach { theme ->
      for (slowLane in listOf(false, true)) {
        withPulseApp(theme, size, slowLane = slowLane) { state ->
          val name = if (slowLane) "speed-popover-slow" else "speed-popover-full"
          snapshot(name, size, theme) {
            CompositionLocalProvider(LocalAppState provides state) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s6),
                modifier = Modifier.padding(KetchTheme.spacing.s4),
              ) {
                Panel(280.dp) { SpeedModeOptions(state, onOpenSettings = {}) }
                Panel(320.dp) {
                  val histories = state.speedHistory.histories.value
                  val chart = totalHistory(histories.values, SampleData.NOW)
                  SpeedHistoryContent(
                    samples = chart.samples,
                    end = chart.end,
                    limitLabel = if (slowLane) "Slow lane · 3 MB/s" else null,
                    limit = if (slowLane) 3_145_728 else null,
                    devices = state.pulse.state.value.devices,
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  @Test
  fun popover_clickedChevron_opensAbovePill() {
    withPulseApp(SnapshotTheme.Dark, SnapshotSize.Desktop, slowLane = true) { state ->
      snapshot(
        name = "pulse-card-popover",
        size = SnapshotSize.Desktop,
        theme = SnapshotTheme.Dark,
        interact = {
          // The chevron of "Slow lane · 3 MB/s", at the start of the bar.
          click(x = 166.dp, y = 776.dp)
        },
      ) {
        PulseCard(state, SnapshotSize.Desktop.width)
      }
    }
  }

  @Test
  fun popover_chevronClickedTwice_closesAgain() {
    withPulseApp(SnapshotTheme.Light, SnapshotSize.Desktop, slowLane = true) { state ->
      snapshot(
        name = "pulse-card-popover-closed",
        size = SnapshotSize.Desktop,
        theme = SnapshotTheme.Light,
        interact = {
          click(x = 166.dp, y = 776.dp)
          click(x = 166.dp, y = 776.dp)
        },
      ) {
        PulseCard(state, SnapshotSize.Desktop.width)
      }
    }
  }

  @Test
  fun activity_historyAndEmpty_renderInPlace() {
    val size = SnapshotSize(860.dp, 600.dp, KetchDensity.Compact)
    SnapshotTheme.entries.forEach { theme ->
      snapshot("activity-popover", size, theme) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s6),
          modifier = Modifier.padding(KetchTheme.spacing.s4),
        ) {
          Panel(380.dp) {
            ActivityContent(
              history = ActivitySamples.history,
              active = ActivitySamples.history.take(1),
              unread = 3,
              deviceName = { if (it == LOCAL_DEVICE_ID) "This Mac" else "NAS-Basement" },
              activeDeviceId = LOCAL_DEVICE_ID,
              onMarkAllRead = {},
              onClear = {},
              onShowTask = {},
              now = SampleData.NOW,
            )
          }
          Panel(380.dp) {
            ActivityContent(
              history = emptyList(),
              active = emptyList(),
              unread = 0,
              deviceName = { it },
              activeDeviceId = LOCAL_DEVICE_ID,
              onMarkAllRead = {},
              onClear = {},
              onShowTask = {},
              now = SampleData.NOW,
            )
          }
        }
      }
    }
  }

  @Test
  fun banners_desktopAndPhone_renderEveryCondition() {
    val sizes = listOf(
      SnapshotSize(1052.dp, 300.dp, KetchDensity.Compact),
      SnapshotSize(390.dp, 420.dp, KetchDensity.Comfortable)
    )
    for (size in sizes) {
      SnapshotTheme.entries.forEach { theme ->
        snapshot("banners", size, theme) {
          Box(Modifier.fillMaxSize().background(KetchTheme.colors.surface)) {
            BannerStack(BannerSamples.all)
          }
        }
      }
    }
  }

  @Test
  fun phone_subtitleAndSheet_renderTheSummary() {
    SnapshotTheme.entries.forEach { theme ->
      withPulseApp(theme, SnapshotSize.Phone, slowLane = false) { state ->
        snapshot("pulse-sheet", SnapshotSize.Phone, theme) {
          CompositionLocalProvider(LocalAppState provides state) {
            Column(Modifier.fillMaxSize().background(KetchTheme.colors.surface)) {
              val spacing = KetchTheme.spacing
              Column(Modifier.padding(horizontal = spacing.s4, vertical = spacing.s3)) {
                Text(
                  text = "Downloads",
                  style = KetchTheme.typography.pageTitle,
                  color = KetchTheme.colors.textPrimary,
                )
                PulseSubtitle(state, onClick = {})
              }
              Column(
                Modifier
                  .fillMaxWidth()
                  .ketchSurface(
                    KetchElevationLevel.E4,
                    KetchTheme.shapes.sheetTop,
                    KetchTheme.colors.surfaceRaised
                  )
                  .padding(KetchTheme.density.pagePadding)
              ) {
                PulseSummary(PulseSamples.busy, limit = null, onShowTab = {})
                Box(Modifier.padding(top = KetchTheme.spacing.s6)) {
                  Column { SpeedModeOptions(state, onOpenSettings = {}, fillModes = true) }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun BarSample(
  label: String,
  pulse: PulseState,
  mode: SpeedModeView,
  unread: Int,
  selection: String? = null,
  width: Dp? = null,
) {
  Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1)) {
    Text(label, style = KetchTheme.typography.caption, color = KetchTheme.colors.textSecondary)
    Box(
      Modifier
        .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
        .clip(KetchTheme.shapes.sm)
        .background(KetchTheme.colors.surface)
    ) {
      PulseBarContent(
        pulse = pulse,
        unread = unread,
        selection = selection,
        onShowTab = {},
        onSpeedClick = {},
        onHealthClick = {},
        onActivityClick = {},
        pill = { SpeedModePillContent(mode, onToggle = {}, onOptions = {}) },
      )
    }
  }
}

/** The content card as the shell will lay it out: banners, the page, toasts and the bar. */
@Composable
private fun PulseCard(state: AppState, width: Dp) {
  val spacing = KetchTheme.spacing
  CompositionLocalProvider(LocalAppState provides state) {
    Box(Modifier.fillMaxSize().padding(spacing.cardInset)) {
      Column(
        Modifier
          .fillMaxSize()
          .ketchSurface(
            KetchElevationLevel.E1,
            KetchTheme.shapes.card,
            KetchTheme.colors.surface,
            KetchTheme.colors.hairline
          )
          .clip(KetchTheme.shapes.card)
      ) {
        BannerHost(state)
        Box(Modifier.weight(1f)) {
          DownloadsScreen(state, KetchLayoutInfo.of(width), Modifier.fillMaxSize())
          ToastHost(
            messages = state.messages,
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .padding(horizontal = spacing.s4)
              .padding(bottom = spacing.s2),
          )
        }
        PulseBar(state)
      }
    }
  }
}

@Composable
private fun Panel(width: Dp, content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier = Modifier
      .width(width)
      .ketchSurface(
        KetchElevationLevel.E3,
        KetchTheme.shapes.menu,
        KetchTheme.colors.surfaceRaised,
        KetchTheme.colors.hairline
      )
      .padding(KetchTheme.spacing.s4),
    content = content,
  )
}

private fun undo() = listOf(MessageAction("Undo") {})

/**
 * Renders an app showing [SampleData.downloads] with a speed mode on the embedded device, at
 * full speed or in the slow lane, with three minutes of speed history. [setup] runs on the UI
 * thread before [render] takes the snapshots.
 */
private fun withPulseApp(
  theme: SnapshotTheme,
  size: SnapshotSize,
  slowLane: Boolean,
  setup: suspend AppState.() -> Unit = {},
  render: (AppState) -> Unit,
) {
  val data = SampleData.downloads()
  val density = size.density.toMode()
  val speedScope = CoroutineScope(SupervisorJob() + SnapshotHarness.ui)
  val manager = runBlocking(SnapshotHarness.ui) {
    InstanceManager(
      factory = InstanceFactory(
        deviceName = data.deviceName,
        embeddedFactory = { SampleKetchApi(data) },
      ),
      initialRemotes = data.remotes,
      configStore = RecordingConfigStore(data.config(theme, density)),
    )
  }
  val engine = checkNotNull(manager.embedded)
  val speedMode = runBlocking(SnapshotHarness.ui) {
    SpeedModeController(
      config = { engine.status().config },
      apply = { engine.updateConfig(it) },
      scope = speedScope,
      settings = SpeedSettings(
        mode = if (slowLane) SpeedLimitMode.SlowLane else SpeedLimitMode.Full,
      ),
      observedPeak = ObservedPeak(10L shl 20, SampleData.NOW.toEpochMilliseconds()),
    )
  }
  val controller = runBlocking(SnapshotHarness.ui) {
    AppController(
      instanceManager = manager,
      context = SnapshotHarness.ui,
      speedMode = speedMode,
      clock = SampleData.CLOCK,
    )
  }
  try {
    runBlocking(SnapshotHarness.ui) {
      repeat(3) { yield() }
      seedHistory(controller, data)
      withTimeout(5.seconds) { controller.taskList.rows.first { it.size == data.tasks.size } }
      controller.state.setup()
    }
    // The harness renders on the UI thread itself, so the snapshot is taken from this one.
    render(controller.state)
  } finally {
    runBlocking(SnapshotHarness.ui) {
      controller.close()
      speedScope.cancel()
      manager.instances.value.filterIsInstance<RemoteInstance>().forEach { it.instance.close() }
      manager.close()
    }
  }
}

private fun seedHistory(controller: AppController, data: SampleData) {
  val keys = data.tasks.associate { TaskKey(LOCAL_DEVICE_ID, it.taskId) to it.state.value }
  for (second in 180 downTo 0) {
    val speeds = keys.mapValues { (_, state) ->
      val speed = (state as? DownloadState.Downloading)?.progress?.bytesPerSecond
      speed?.let { (it * (0.8 + 0.18 * sin(second * PI / 23))).roundToLong() }
    }
    controller.speedHistory.record(SampleData.NOW - second.seconds, speeds)
  }
}

private object PulseSamples {
  private val history = List(60) { i ->
    (26_000_000 * (0.75 + 0.2 * sin(i * PI / 9) + 0.05 * sin(i * 1.7))).roundToLong()
  }
  private val disk = DiskSpace(412_316_860_416, 994_662_584_320, "/Users/alex/Downloads")

  private fun device(
    health: DeviceHealth = DeviceHealth.Local(sharingPort = 8642),
    counts: PulseCounts = PulseCounts(
      downloading = 3,
      waiting = 3,
      paused = 1,
      done = 4,
      failed = 2,
    ),
    failures: Int = 1,
    speed: Long = 27_997_000,
    disk: DiskSpace? = this.disk,
    pending: Long = 0,
    history: List<Long> = this.history,
    name: String = "This Mac",
    id: String = LOCAL_DEVICE_ID,
  ) = DevicePulse(
    deviceId = id,
    name = name,
    health = health,
    counts = counts,
    failures = failures,
    speed = speed,
    cap = SpeedLimit.Unlimited,
    downloadedBytes = 0,
    sizeBytes = 0,
    sizesKnown = true,
    pendingBytes = pending,
    disk = disk,
    history = history,
  )

  val busy = PulseState(listOf(device()))
  val idle = PulseState(
    listOf(device(counts = PulseCounts(done = 4), failures = 0, speed = 0, history = emptyList()))
  )
  val diskShort = PulseState(listOf(device(pending = 500_000_000_000)))
  val notShared = PulseState(listOf(device(health = DeviceHealth.Local())))
  val connecting = PulseState(
    listOf(
      device(
        health = DeviceHealth.Connecting,
        name = "NAS-Basement",
        id = "nas.local:8642",
        speed = 0,
        disk = null,
      )
    )
  )
  val offline = PulseState(
    listOf(
      device(
        health = DeviceHealth.Offline(),
        name = "NAS-Basement",
        id = "nas.local:8642",
        speed = 0,
      )
    )
  )

  val full = SpeedModeView(SpeedMode.Full, SpeedLimit.Unlimited, "Full speed", null)
  val capped = SpeedModeView(SpeedMode.Full, SpeedLimit.mbps(20), "Capped · 20 MB/s", null)
  val slowLane = SpeedModeView(SpeedMode.SlowLane, SpeedLimit.mbps(3), "Slow lane · 3 MB/s", null)
  val auto = SpeedModeView(
    mode = SpeedMode.Auto(slowLane = true, until = SampleData.NOW + 3.hours + 30.minutes),
    limit = SpeedLimit.mbps(1),
    label = "Auto · Slow lane until 18:00",
    controller = null,
  )
}

private object ActivitySamples {
  private val now = SampleData.NOW
  private fun message(
    id: Long,
    level: MessageLevel,
    title: String,
    detail: String? = null,
    minutesAgo: Long,
    deviceId: String? = LOCAL_DEVICE_ID,
    actions: List<MessageAction> = emptyList(),
  ) = AppMessage(
    id = id,
    level = level,
    title = title,
    detail = detail,
    deviceId = deviceId,
    actions = actions,
    at = now - minutesAgo.minutes,
  )

  val history = listOf(
    message(6, MessageLevel.Success, "Slow lane on · 3 MB/s", minutesAgo = 1, actions = undo()),
    // An unread title that wraps, to show the dot and the time staying on its first line.
    message(
      id = 7,
      level = MessageLevel.Success,
      title = "Added ubuntu-24.04.5-live-server-amd64.iso → This Mac",
      minutesAgo = 4,
    ),
    message(
      id = 5,
      level = MessageLevel.Error,
      title = "Download failed",
      detail = "model-weights.safetensors: Access denied (403)",
      minutesAgo = 12,
      actions = listOf(MessageAction("Retry") {}),
    ),
    message(
      id = 4,
      level = MessageLevel.Success,
      title = "Download complete",
      detail = "q3-report.pdf · 2.4 MB · took 1s · avg 1.7 MB/s",
      minutesAgo = 120,
    ),
    message(
      id = 3,
      level = MessageLevel.Warning,
      title = "nas.local:8642 went offline",
      minutesAgo = 300,
      deviceId = "nas.local:8642",
    ),
    message(2, MessageLevel.Info, "Removed 5 downloads", minutesAgo = 900, actions = undo()),
    message(
      id = 1,
      level = MessageLevel.Success,
      title = "All downloads finished",
      detail = "6 files · 8.2 GB",
      minutesAgo = 3000,
      deviceId = "nas.local:8642",
    )
  )
}

private object BannerSamples {
  val all: List<Banner> = listOfNotNull(
    deviceBanner(
      health = DeviceHealth.Connecting,
      name = "NAS-Basement",
      connectingLong = true,
      localName = null,
      onRetry = {},
      onSwitchToLocal = {},
      onEnterToken = {},
    ),
    deviceBanner(
      health = DeviceHealth.Offline("Connection refused"),
      name = "NAS-Basement",
      connectingLong = false,
      localName = "This Mac",
      onRetry = {},
      onSwitchToLocal = {},
      onEnterToken = {},
    )?.copy(id = "offline"),
    deviceBanner(
      health = DeviceHealth.Unauthorized,
      name = "NAS-Basement",
      connectingLong = false,
      localName = null,
      onRetry = {},
      onSwitchToLocal = {},
      onEnterToken = {},
    )?.copy(id = "token"),
    Banner(
      id = "background",
      tone = BannerTone.Info,
      text = "Downloads pause when Ketch is in the background",
      actions = listOf(MessageAction("Use a computer instead") {}),
      onDismiss = {},
    )
  )
}
