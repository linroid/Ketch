package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.App
import com.linroid.ketch.app.components.AddButtonMode
import com.linroid.ketch.app.components.KetchAddButton
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.DropHoverState
import com.linroid.ketch.app.ui.LocalWindowDrop
import com.linroid.ketch.app.ui.downloads.DownloadsScreen
import com.linroid.ketch.app.ui.downloads.KetchLayoutInfo
import com.linroid.ketch.app.ui.downloads.LocalPageClipboard
import com.linroid.ketch.config.ClipboardMode
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.delay
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Downloads header's Add button: plain, hovered (lanes and the ember gradient), offering a
 * copied link as a split button, as a drop target, and the lane that flies to a new row; see
 * [SnapshotHarness] for how to run it.
 */
class AddButtonSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun addButton_plain() {
    appSnapshots("add-button", listOf(SnapshotSize.Desktop))
  }

  @Test
  fun addButton_hovered_splitsIntoLanes() {
    for (theme in SnapshotTheme.entries) {
      addAppSnapshot("add-button-hover", theme, motion = true) {
        scene.hover(AddX, AddY)
      }
    }
  }

  @Test
  fun addButton_copiedLink_becomesASplitButton() {
    for (theme in SnapshotTheme.entries) {
      addAppSnapshot("add-button-clip", theme, clip = LONG_LINK)
    }
  }

  @Test
  fun addButton_copiedLinkArrives_shimmersOnce() {
    addAppSnapshot("add-button-sheen", SnapshotTheme.Light, clip = LONG_LINK, motion = true) {
      captureFrames("add-button-sheen", count = SHEEN_FRAMES, every = FLIGHT_FRAME_GAP)
    }
  }

  @Test
  fun addButton_copiedLinkOnNarrowerWindows_fitsTheHeader() {
    for (size in listOf(SnapshotSize.SmallDesktop, SnapshotSize.Medium)) {
      addAppSnapshot("add-button-clip", SnapshotTheme.Light, clip = LONG_LINK, size = size)
    }
  }

  @Test
  fun addButton_copiedLinkHovered_litsTheMainPart() {
    for (theme in SnapshotTheme.entries) {
      addAppSnapshot("add-button-clip-hover", theme, clip = LONG_LINK) {
        scene.hover(ClipMainX, AddY)
      }
    }
  }

  @Test
  fun addButton_dragOverTheWindow_becomesADropTarget() {
    // The shell covers the card with its drop berths, so this renders the page on its own.
    for (theme in SnapshotTheme.entries) {
      pageSnapshot("add-button-drop", theme)
    }
  }

  @Test
  fun addButton_states_sideBySide() {
    for (theme in SnapshotTheme.entries) {
      snapshot("add-button-states", GallerySize, theme, interact = {
        hover(GalleryHoverX, GalleryHoverY)
      }) {
        StateGallery()
      }
    }
  }

  @Test
  fun addButton_quickAdd_flysALaneToTheNewRow() {
    for (theme in SnapshotTheme.entries) {
      addAppSnapshot("add-flight", theme, motion = true) {
        state.quickAdd(listOf(LONG_LINK))
        captureFrames("add-flight-${theme.id}", count = FLIGHT_FRAMES, every = FLIGHT_FRAME_GAP)
      }
    }
  }
}

/** A link with a long file name, as copied from a download page. */
private const val LONG_LINK =
  "https://download.fedoraproject.org/pub/fedora/linux/releases/41/Workstation/x86_64/iso/" +
    "Fedora-Workstation-Live-x86_64-41-1.4.iso"

/** Where the plain Add button sits at 1280 × 800, by its glyph. */
private val AddX = 1216.dp
private val AddY = 34.dp

/** The main part of the split button at 1280 × 800. */
private val ClipMainX = 1090.dp

private val GallerySize = SnapshotSize(720.dp, 420.dp, KetchDensity.Compact)
private val GalleryHoverX = 240.dp
private val GalleryHoverY = 114.dp

private const val FLIGHT_FRAMES = 14
private const val SHEEN_FRAMES = 24
private val FLIGHT_FRAME_GAP = 45.milliseconds

/**
 * Renders the real app at [size] like [appSnapshot], with [clip] on a clipboard that reads
 * without a notice and, with [motion], motion on.
 */
private fun addAppSnapshot(
  name: String,
  theme: SnapshotTheme,
  clip: String? = null,
  motion: Boolean = false,
  size: SnapshotSize = SnapshotSize.Desktop,
  setup: suspend AppScenario.() -> Unit = {},
): File = withSample(theme) { env ->
  val clipboard = SnapshotClipboard(clip)
  SnapshotHarness.capture(
    name = "$name-${theme.id}-${size.id}",
    size = size,
    interact = {
      val scenario = AppScenario(env.controller, env.data, this)
      scenario.state.appSettings.saveUi {
        it.copy(
          reduceMotion = !motion,
          clipboardMode = if (clip != null) ClipboardMode.Suggest else it.clipboardMode,
        )
      }
      scenario.setup()
    },
  ) {
    CompositionLocalProvider(LocalPageClipboard provides clipboard) {
      App(env.controller)
    }
  }
}

/** Renders the Downloads page alone in a card while a drag hovers the window. */
private fun pageSnapshot(name: String, theme: SnapshotTheme): File = withSample(theme) { env ->
  val size = SnapshotSize.Desktop
  SnapshotHarness.capture("$name-${theme.id}-${size.id}", size) {
    val drop = remember { DropHoverState().apply { enter(Unit) } }
    KetchTheme(
      darkTheme = theme == SnapshotTheme.Dark,
      density = DensityMode.Compact,
      reduceMotion = true,
    ) {
      CompositionLocalProvider(
        LocalClock provides SampleData.CLOCK,
        LocalWindowDrop provides drop,
        LocalPageClipboard provides SnapshotClipboard(null),
      ) {
        Box(Modifier.fillMaxSize().background(KetchTheme.colors.canvas).padding(8.dp)) {
          Box(
            Modifier
              .fillMaxSize()
              .ketchSurface(
                level = KetchElevationLevel.E1,
                shape = KetchTheme.shapes.card,
                fill = KetchTheme.colors.surface,
                border = KetchTheme.colors.hairline,
              ),
          ) {
            DownloadsScreen(env.controller.state, KetchLayoutInfo.of(1264.dp))
          }
        }
      }
    }
  }
}

/** Every state of the button in a header strip each, the first one hovered. */
@Composable
private fun StateGallery() {
  val noop = {}
  Column(
    verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s3),
    modifier = Modifier.fillMaxSize().padding(KetchTheme.spacing.s6),
  ) {
    GalleryRow("Plain") {
      KetchAddButton(AddButtonMode.Plain, noop, noop, noop)
    }
    GalleryRow("Hovered") {
      KetchAddButton(AddButtonMode.Plain, noop, noop, noop)
    }
    GalleryRow("Copied link") {
      KetchAddButton(
        AddButtonMode.Clip("ubuntu-24.04.1-desktop-amd64.iso", "Download", "1"),
        noop,
        noop,
        noop,
      )
    }
    GalleryRow("Drag over the window") {
      KetchAddButton(AddButtonMode.Drop(over = false), noop, noop, noop)
    }
    GalleryRow("Drag over the button") {
      KetchAddButton(AddButtonMode.Drop(over = true), noop, noop, noop)
    }
  }
}

@Composable
private fun GalleryRow(label: String, content: @Composable () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .height(KetchTheme.spacing.pageHeaderHeight)
      .ketchSurface(
        level = KetchElevationLevel.E1,
        shape = KetchTheme.shapes.md,
        fill = KetchTheme.colors.surface,
        border = KetchTheme.colors.hairline,
      )
      .padding(horizontal = KetchTheme.spacing.s4),
  ) {
    Text(
      text = label,
      style = KetchTheme.typography.label,
      color = KetchTheme.colors.textSecondary,
      modifier = Modifier.width(GalleryLabelWidth),
    )
    content()
  }
}

private val GalleryLabelWidth: Dp = 160.dp

/**
 * Writes [count] frames, [every] apart, to `<prefix>-<n>.png` while the scene runs on, to look
 * at motion that has settled by the time a snapshot is taken.
 */
private suspend fun AppScenario.captureFrames(
  prefix: String,
  count: Int,
  every: Duration,
) {
  val begin = SnapshotClock.timeSource.markNow()
  var next = 0
  while (next < count) {
    val image = scene.renderFrame()
    if (begin.elapsedNow() >= every * next) {
      writeFrame("$prefix-${next.toString().padStart(2, '0')}", image)
      next++
    }
    delay(FRAME_STEP)
  }
}

private val FRAME_STEP = 8.milliseconds

private fun writeFrame(name: String, image: Image) {
  val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "Couldn't encode $name" }
  SnapshotHarness.outputDir.mkdirs()
  File(SnapshotHarness.outputDir, "$name.png").writeBytes(data.bytes)
}

