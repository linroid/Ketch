package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.platform.FileActions
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceInfo
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.RowCapabilities
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.downloads.actions.RowActionRunner
import com.linroid.ketch.app.ui.downloads.actions.rememberRowActionRunner
import com.linroid.ketch.app.ui.inspector.InspectorContent
import com.linroid.ketch.app.ui.inspector.InspectorControls
import com.linroid.ketch.app.ui.inspector.InspectorPlacement
import com.linroid.ketch.app.ui.inspector.TaskInspector
import com.linroid.ketch.app.ui.list.RowCommands
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The task inspector's content (W3-INSPECTOR) in its three containers, docked at 320 and 480 dp,
 * as an overlay card and in a phone's sheet, for each state of [SampleData], with nothing and
 * with several downloads selected, in light and dark; see [SnapshotHarness] for how to run it.
 *
 * The Downloads page owns the containers, so these scenarios draw stand-ins for them around
 * the content of [TaskInspector] over a live [SampleEnvironment], with files that exist.
 */
class InspectorSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun inspector_eachState_rendersDocked() {
    for (theme in SnapshotTheme.entries) {
      for ((file, name) in STATES) {
        inspector("inspector-$file", Tall, theme) { inspect(name) }
      }
    }
  }

  @Test
  fun inspector_windowHeight_showsTheTopInView() {
    for (theme in SnapshotTheme.entries) {
      inspector("inspector-docked", Docked, theme) { inspect(UBUNTU) }
      inspector("inspector-widest", Widest, theme, width = WidestDock) { inspect(UBUNTU) }
      inspector("inspector-overlay", Overlay, theme, InspectorPlacement.Overlay) {
        inspect(FAILED)
      }
    }
  }

  @Test
  fun inspector_nothingSelected_showsTheDevice() {
    for (theme in SnapshotTheme.entries) {
      inspector("inspector-scope", Tall, theme) { null }
    }
  }

  @Test
  fun inspector_severalSelected_sharesTheControls() {
    for (theme in SnapshotTheme.entries) {
      inspector("inspector-selection", Tall, theme) {
        selectedKeys = setOf(UBUNTU, TORRENT, QUEUED, COMPLETED)
          .mapTo(LinkedHashSet()) { data.keyOf(it) }
        null
      }
    }
  }

  @Test
  fun inspector_phone_fillsTheSheet() {
    for (theme in SnapshotTheme.entries) {
      inspector("inspector-phone", PhoneTall, theme, InspectorPlacement.Sheet) { inspect(UBUNTU) }
      inspector("inspector-phone-failed", SnapshotSize.Phone, theme, InspectorPlacement.Sheet) {
        inspect(FAILED)
      }
    }
  }

  @Test
  fun inspector_keyboard_showsFocus() {
    for (theme in SnapshotTheme.entries) {
      inspector(
        name = "inspector-focus",
        size = Docked,
        theme = theme,
        interact = { repeat(FOCUS_TABS) { pressKey(Key.Tab) } },
      ) { inspect(UBUNTU) }
    }
  }

  @Test
  fun inspector_clicks_openThePopoverMenusAndNotes() {
    for (theme in SnapshotTheme.entries) {
      inspector("inspector-speed-popover", Docked, theme, interact = { click(303.dp, 339.dp) }) {
        inspect(UBUNTU)
      }
      inspector("inspector-more-menu", Docked, theme, interact = { click(278.dp, 199.dp) }) {
        inspect(UBUNTU)
      }
      inspector("inspector-urgent-note", Docked, theme, interact = { click(275.dp, 354.dp) }) {
        inspect(QUEUED)
      }
      inspector("inspector-start-menu", Docked, theme, interact = { click(185.dp, 483.dp) }) {
        inspect(UBUNTU)
      }
      inspector(
        name = "inspector-reschedule-note",
        size = Docked,
        theme = theme,
        interact = {
          click(185.dp, 483.dp)
          click(230.dp, 576.dp)
        },
      ) { inspect(UBUNTU) }
      inspector("inspector-copy-hover", Docked, theme, interact = { hover(220.dp, 551.dp) }) {
        inspect(UBUNTU)
      }
    }
  }

  @Test
  fun controls_remoteDevice_disableStart() {
    for (theme in SnapshotTheme.entries) {
      val data = SampleData.downloads()
      val environment = runBlocking(SnapshotHarness.ui) {
        SampleEnvironment(data, theme, DensityMode.Compact)
      }
      try {
        runBlocking(SnapshotHarness.ui) { withTimeout(START) { environment.start() } }
        val state = environment.controller.state
        val tooltip: suspend SnapshotScene.() -> Unit = {
          hover(185.dp, 220.dp)
          settle(minimum = 1.seconds)
        }
        snapshot("inspector-controls-remote", Small, theme, interact = tooltip) {
          CompositionLocalProvider(LocalAppState provides state) {
            Pane(DockedWidth, InspectorPlacement.Docked) {
              Column(Modifier.padding(KetchTheme.spacing.s4)) {
                val runner = rememberRowActionRunner()
                InspectorControls(state, listOf(remoteRow()), runner, emptySet())
              }
            }
          }
        }
      } finally {
        runBlocking(SnapshotHarness.ui) { environment.close() }
      }
    }
  }

  private fun inspector(
    name: String,
    size: SnapshotSize,
    theme: SnapshotTheme,
    placement: InspectorPlacement = InspectorPlacement.Docked,
    width: Dp = if (placement == InspectorPlacement.Overlay) OverlayWidth else DockedWidth,
    interact: suspend SnapshotScene.() -> Unit = {},
    setup: Setup.() -> TaskKey?,
  ) {
    val data = SampleData.downloads()
    val density = if (size.density == KetchDensity.Compact) {
      DensityMode.Compact
    } else {
      DensityMode.Comfortable
    }
    val environment = runBlocking(SnapshotHarness.ui) { SampleEnvironment(data, theme, density) }
    try {
      val key = runBlocking(SnapshotHarness.ui) {
        withTimeout(START) { environment.start() }
        Setup(environment.controller.state, data).setup()
      }
      val state = environment.controller.state
      snapshot(name, size, theme, interact) {
        CompositionLocalProvider(LocalAppState provides state) {
          val scope = rememberCoroutineScope()
          val runner = remember(scope) {
            val commands = RowCommands(state, InspectorFiles, InspectorClipboard, scope) {}
            RowActionRunner(state, commands, InspectorFiles, InspectorClipboard, scope)
          }
          Pane(width, placement) {
            InspectorContent(state, key, placement, onClose = {}, runner = runner)
          }
        }
      }
    } finally {
      runBlocking(SnapshotHarness.ui) { environment.close() }
    }
  }

  /** What a scenario picks to inspect. */
  private class Setup(state: AppState, val data: SampleData) {
    private val appState = state

    var selectedKeys
      get() = appState.selectedKeys
      set(value) {
        appState.selectedKeys = value
      }

    fun inspect(name: String): TaskKey = data.keyOf(name)
  }

  private companion object {
    val DockedWidth = 320.dp
    val WidestDock = 480.dp
    val OverlayWidth = 340.dp
    val Docked = SnapshotSize(360.dp, 800.dp, KetchDensity.Compact)
    val Widest = SnapshotSize(520.dp, 800.dp, KetchDensity.Compact)
    val Overlay = SnapshotSize(400.dp, 800.dp, KetchDensity.Compact)
    val Tall = SnapshotSize(360.dp, 1500.dp, KetchDensity.Compact)
    val Small = SnapshotSize(360.dp, 420.dp, KetchDensity.Compact)
    val PhoneTall = SnapshotSize(390.dp, 1700.dp, KetchDensity.Comfortable)
    val START = 5.seconds
    const val FOCUS_TABS = 4

    const val UBUNTU = "ubuntu-24.04-desktop-amd64.iso"
    const val TORRENT = "archlinux-2026.10.01-x86_64.iso"
    const val FAILED = "model-weights.safetensors"
    const val COMPLETED = "vacation-photos-2026.zip"
    const val QUEUED = "blender-4.2-macos-arm64.dmg"

    /** File name suffixes and the sample task each shows. */
    val STATES = listOf(
      "downloading" to UBUNTU,
      "torrent" to TORRENT,
      "queued" to QUEUED,
      "scheduled" to "llama-3.1-8b-instruct-q4_k_m.gguf",
      "paused" to "android-studio-2024.2.1.12-mac_arm.dmg",
      "completed" to COMPLETED,
      "failed" to FAILED,
      "canceled" to "screen-recording-2026-09-28.mov"
    )
  }
}

/**
 * A stand-in for the container the Downloads page puts the inspector in: the docked pane with
 * its divider, the raised overlay card, or the phone's sheet.
 */
@Composable
private fun Pane(width: Dp, placement: InspectorPlacement, content: @Composable () -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  when (placement) {
    InspectorPlacement.Docked -> Row(Modifier.fillMaxSize().background(colors.surface)) {
      Spacer(Modifier.fillMaxHeight().weight(1f).background(colors.surfaceSunken))
      Spacer(Modifier.fillMaxHeight().width(spacing.s0_5 / 2).background(colors.divider))
      Box(Modifier.fillMaxHeight().width(width)) { content() }
    }
    InspectorPlacement.Overlay -> Box(
      contentAlignment = Alignment.TopEnd,
      modifier = Modifier.fillMaxSize().background(colors.surface).padding(spacing.cardInset),
    ) {
      Box(
        Modifier
          .width(width)
          .fillMaxHeight()
          .ketchSurface(KetchElevationLevel.E3, KetchTheme.shapes.lg, colors.surfaceRaised),
      ) { content() }
    }
    InspectorPlacement.Sheet -> Box(
      Modifier
        .fillMaxSize()
        .background(colors.scrim)
        .padding(top = spacing.s16),
    ) {
      Box(
        Modifier
          .fillMaxWidth()
          .fillMaxHeight()
          .ketchSurface(KetchElevationLevel.E0, KetchTheme.shapes.sheetTop, colors.surfaceRaised),
      ) { content() }
    }
  }
}

/** Files that exist, without touching this machine. */
private object InspectorFiles : FileActions {
  override val revealLabel: String = "Show in Finder"
  override val canShare: Boolean = false
  override val canTrash: Boolean = true

  override suspend fun open(path: String) {}

  override suspend fun reveal(path: String) {}

  override suspend fun share(path: String) {}

  override suspend fun exists(path: String): Boolean = true

  override suspend fun moveToTrash(path: String) {}
}

/** A clipboard that keeps nothing. */
private object InspectorClipboard : SystemClipboard {
  override val readsSilently: Boolean = true
  override val pasteEvents = emptyFlow<String>()

  override suspend fun hasLink(): Boolean = false

  override suspend fun readText(): String? = null

  override suspend fun writeText(text: String) {}
}

/** A download on a remote device, which cannot be rescheduled. */
private fun remoteRow() = ListFixtures.row(
  id = "remote",
  state = DownloadState.Downloading(DownloadProgress(900_000_000, 2_400_000_000, 3_100_000)),
  request = DownloadRequest(
    url = "https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/debian-12.iso",
    connections = 4,
    resolvedSource = ResolvedSource(
      url = "https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/debian-12.iso",
      sourceType = "http",
      totalBytes = 2_400_000_000,
      supportsResume = true,
      suggestedFileName = "debian-12.iso",
      maxSegments = 16,
    ),
  ),
  deviceId = "nas.local:8642",
  device = DeviceInfo("NAS-Basement", RowCapabilities.remote()),
  now = SampleData.NOW,
).copy(segments = listOf(Segment(0, 0, 2_399_999_999, 900_000_000)))
