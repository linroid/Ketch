package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalPlatformWindowInsets
import androidx.compose.ui.platform.PlatformInsets
import androidx.compose.ui.platform.PlatformWindowInsets
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.App
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.shell.canvasWash
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The README showcase: the real app on a desktop window, an Android phone and an iOS phone, with
 * the `ketch` command line behind them, on the app's own canvas. Each screen is rendered on its
 * own at its native size and at twice its pixel density in the final image, so popups such as
 * the phone's inspector sheet stay inside it, then framed and composed at twice the final size
 * and scaled down once.
 *
 * Writes `showcase-light.png` and `showcase-dark.png`, 2400 × 1200 with transparent rounded
 * corners, for `art/`; see [SnapshotHarness] for how to run it.
 */
class ShowcaseSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun showcase_lightAndDark_composesEveryDevice() {
    for (theme in SnapshotTheme.entries) {
      val desktop = desktopScreen(theme)
      val android = androidScreen(theme)
      val ios = iosScreen(theme)
      SnapshotHarness.write("showcase-${theme.id}-desktop", desktop)
      SnapshotHarness.write("showcase-${theme.id}-android", android)
      SnapshotHarness.write("showcase-${theme.id}-ios", ios)
      val full = SnapshotHarness.render(CanvasWidth, CanvasHeight, RENDER_SCALE) {
        Showcase(theme, Screens(desktop, android, ios))
      }
      SnapshotHarness.write("showcase-${theme.id}-2x", full)
      SnapshotHarness.write("showcase-${theme.id}", downscale(full))
    }
  }
}

/** The three app screens of one theme. */
private class Screens(val desktop: Image, val android: Image, val ios: Image)

/** The laptop's window on Studio: the Downloads table with the ISO in the docked inspector. */
private fun desktopScreen(theme: SnapshotTheme): Image =
  withEnvironment(
    create = { ShowcaseEnvironment(ShowcaseDevice.Desktop, theme, DensityMode.Compact) },
  ) { environment ->
    val state = environment.controller.state
    SnapshotHarness.render(
      width = DesktopWidth,
      height = DesktopHeight,
      scale = DESKTOP_SCALE * RENDER_SCALE,
      interact = {
        // The Pulse bar's sparkline samples once a second; give it some history.
        environment.playSpeedHistory(SPEED_HISTORY_SECONDS)
        state.inspect(environment.studioKey(ShowcaseData.ISO_ID))
      },
    ) {
      MacWindow(environment.controller)
    }
  }

/**
 * The Android phone: the Devices page, Studio on top with its last minute of speed under it,
 * then the home server and the laptop.
 */
private fun androidScreen(theme: SnapshotTheme): Image =
  withEnvironment(
    create = {
      ShowcaseEnvironment(ShowcaseDevice.Phone, theme, DensityMode.Comfortable, ShowcaseDiscovery)
    },
  ) { environment ->
    SnapshotHarness.render(
      width = PhoneWidth,
      height = PhoneHeight,
      scale = ANDROID_SCALE * RENDER_SCALE,
      interact = {
        environment.playSpeedHistory(SPEED_HISTORY_SECONDS)
        click(DevicesTab.first, DevicesTab.second)
      },
    ) {
      PhoneApp(environment.controller, PhoneKind.Android)
    }
  }

/** The iOS phone: the ISO's inspector sheet, its lanes moving. */
private fun iosScreen(theme: SnapshotTheme): Image =
  withEnvironment(
    create = { ShowcaseEnvironment(ShowcaseDevice.Phone, theme, DensityMode.Comfortable) },
  ) { environment ->
    val state = environment.controller.state
    val iso = environment.studioTasks.first { it.taskId == ShowcaseData.ISO_ID }
    val feeder = CoroutineScope(SnapshotHarness.ui)
    feeder.launch {
      // Fed by the clock the lane rates are measured with, so they read as ISO_LANE_RATES.
      var last = TimeSource.Monotonic.markNow()
      while (isActive) {
        delay(FEED_INTERVAL)
        val now = TimeSource.Monotonic.markNow()
        ShowcaseData.advance(iso, ShowcaseData.ISO_LANE_RATES, now - last)
        last = now
      }
    }
    try {
      SnapshotHarness.render(
        width = PhoneWidth,
        height = PhoneHeight,
        scale = IOS_SCALE * RENDER_SCALE,
        interact = {
          // The lane rates start from nothing and average in once a second; let them settle.
          delay(LANE_RATE_WAIT)
          state.inspect(environment.studioKey(ShowcaseData.ISO_ID))
          settle()
          // The sheet opens half way; show the Connections tab and hold the sheet up far enough
          // for every lane, with the list still showing above it.
          click(ConnectionsTab.first, ConnectionsTab.second)
          drag(SheetGrip.first, SheetGrip.second, SheetGrip.first, SheetHeld, release = false)
        },
      ) {
        PhoneApp(environment.controller, PhoneKind.Ios)
      }
    } finally {
      feeder.cancel()
    }
  }

/** The app on a phone of [kind], under its status bar and over its gesture area. */
@OptIn(InternalComposeUiApi::class)
@Composable
private fun PhoneApp(controller: AppController, kind: PhoneKind) {
  val geometry = PhoneGeometry.of(kind)
  val density = LocalDensity.current
  val insets = remember(density, geometry) {
    with(density) {
      PhoneInsets(top = geometry.statusBar.roundToPx(), bottom = geometry.bottomInset.roundToPx())
    }
  }
  CompositionLocalProvider(LocalPlatformWindowInsets provides insets) {
    App(controller)
  }
}

/** A phone's system bars: the status bar on top and the gesture area at the bottom, in pixels. */
@OptIn(InternalComposeUiApi::class)
private class PhoneInsets(top: Int, bottom: Int) : PlatformWindowInsets {
  override val statusBars: PlatformInsets = PlatformInsets(0, top, 0, 0)
  override val navigationBars: PlatformInsets = PlatformInsets(0, 0, 0, bottom)
  override val systemBars: PlatformInsets = PlatformInsets(0, top, 0, bottom)
}

/** The whole picture: the canvas, the terminal, the desktop window and the two phones. */
@Composable
private fun Showcase(theme: SnapshotTheme, screens: Screens) {
  val dark = theme == SnapshotTheme.Dark
  KetchTheme(darkTheme = dark, density = DensityMode.Compact, reduceMotion = true) {
    val colors = KetchTheme.colors
    val card = RoundedCornerShape(CardRadius)
    val rim = if (dark) DarkRim else colors.hairline
    val desktop = remember(screens) { screens.desktop.toComposeImageBitmap() }
    val android = remember(screens) { screens.android.toComposeImageBitmap() }
    val ios = remember(screens) { screens.ios.toComposeImageBitmap() }
    Box(
      Modifier
        .size(CanvasWidth, CanvasHeight)
        .clip(card)
        .canvasWash(colors, if (dark) DarkEmberRadius else EmberRadius)
        .blooms(colors),
    ) {
      TerminalWindow(
        title = "ketch",
        lines = TerminalSession,
        width = TerminalWidth,
        rim = if (dark) DarkRim else Color.Transparent,
        dark = dark,
        fontSize = TERMINAL_FONT,
        lineHeight = TERMINAL_LINE,
        modifier = Modifier.offset(TerminalX, TerminalY),
      )
      DesktopWindow(
        screen = desktop,
        width = DesktopWidth * DESKTOP_SCALE,
        height = DesktopHeight * DESKTOP_SCALE,
        radius = WindowRadius,
        rim = rim,
        dark = dark,
        modifier = Modifier.offset(DesktopX, DesktopY),
      )
      Phone(
        kind = PhoneKind.Android,
        screen = android,
        screenWidth = PhoneWidth,
        screenHeight = PhoneHeight,
        scale = ANDROID_SCALE,
        ink = colors.textPrimary,
        dark = dark,
        time = STATUS_TIME,
        modifier = Modifier.offset(AndroidX, AndroidY),
      )
      Phone(
        kind = PhoneKind.Ios,
        screen = ios,
        screenWidth = PhoneWidth,
        screenHeight = PhoneHeight,
        scale = IOS_SCALE,
        ink = colors.textPrimary,
        dark = dark,
        time = STATUS_TIME,
        modifier = Modifier.offset(IosX, IosY),
      )
      Box(Modifier.fillMaxSize().border(CardEdge, colors.hairline, card))
    }
  }
}

/**
 * The brand's light on the canvas: an accent bloom behind the desktop window's top left and an
 * ember one behind the phones, so the devices sit on light rather than on a flat wash.
 */
private fun Modifier.blooms(colors: KetchColors): Modifier = drawWithCache {
  val dark = colors.isDark
  val accent = Brush.radialGradient(
    colors = listOf(colors.accent.copy(alpha = if (dark) 0.26f else 0.22f), Color.Transparent),
    center = Offset(AccentBloom.first.toPx(), AccentBloom.second.toPx()),
    radius = AccentBloomRadius.toPx(),
  )
  val (warm, hot) = colors.brandEmber
  val ember = Brush.radialGradient(
    0f to warm.copy(alpha = if (dark) 0.15f else 0.46f),
    0.45f to hot.copy(alpha = if (dark) 0.08f else 0.14f),
    1f to Color.Transparent,
    center = Offset(EmberBloom.first.toPx(), EmberBloom.second.toPx()),
    radius = EmberBloomRadius.toPx(),
  )
  onDrawBehind {
    drawRect(accent)
    drawRect(ember)
  }
}

/** [image] at half its size, filtered once. */
private fun downscale(image: Image): Image {
  val width = image.width / 2
  val height = image.height / 2
  val surface = Surface.makeRasterN32Premul(width, height)
  surface.canvas.drawImageRect(
    image,
    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
    Rect.makeWH(width.toFloat(), height.toFloat()),
    SamplingMode.MITCHELL,
    null,
    true,
  )
  return surface.makeImageSnapshot()
}

/**
 * Two `ketch` runs as the command line prints them: a finished download and one under a speed
 * limit. The version banner, the destination and the timestamped log lines are left out.
 */
private val TerminalSession = listOf(
  prompt("ketch https://downloads.example.com/runtime-22.20-arm64.tar.gz"),
  TerminalLine("[Downloading] 100%  49.0 MB / 49.0 MB  10.2 MB/s"),
  TerminalLine("Download completed: runtime-22.20-arm64.tar.gz"),
  prompt("ketch --speed-limit 8m https://data.example.net/eval/benchmark-suite-v3.tar"),
  TerminalLine("Speed limit: 8.0 MB/s"),
  TerminalLine("[Downloading] 41%  325.0 MB / 784.0 MB  7.9 MB/s [limit: 8.0 MB/s]"),
)

private fun prompt(command: String) =
  TerminalLine(listOf("~ ❯ " to TerminalInk.Accent, command to TerminalInk.Default))

// The final image is 2400 × 1200 px; the showcase lays out in dp of one final pixel each and
// renders at twice that.
private const val RENDER_SCALE = 2f
private val CanvasWidth = 2400.dp
private val CanvasHeight = 1200.dp
private val CardRadius = 40.dp
private val CardEdge = 2.dp
private val EmberRadius = 700.dp
// The wash's ember stays in the corner on a dark canvas, where it would turn muddy.
private val DarkEmberRadius = 160.dp
private val DarkRim = Color.White.copy(alpha = 0.11f)

private val DesktopWidth = 1272.dp
// Short enough that the inspector's Details end on a whole row with room above the Pulse bar.
private val DesktopHeight = 740.dp
private const val DESKTOP_SCALE = 1.01f
private val DesktopX = 175.dp
private val DesktopY = 120.dp
private val WindowRadius = 14.dp

private val PhoneWidth = 390.dp
private val PhoneHeight = 844.dp
private const val ANDROID_SCALE = 0.93f
private val AndroidX = 1496.dp
// Half way between the window's top and the iOS phone's, so the three cascade.
private val AndroidY = 197.dp
private const val IOS_SCALE = 0.93f
private val IosX = 1896.dp
private val IosY = 274.dp
private const val STATUS_TIME = "14:30"

// The terminal peeks out from under the window's bottom left, its title bar showing.
private val TerminalX = 115.dp
private val TerminalY = 861.dp
private val TerminalWidth = 840.dp
private const val TERMINAL_FONT = 14f
private const val TERMINAL_LINE = 22f

private val AccentBloom = 420.dp to 220.dp
private val AccentBloomRadius = 1000.dp
private val EmberBloom = 1890.dp to 620.dp
private val EmberBloomRadius = 660.dp

/** Where the Devices tab of the Android bottom bar is, in the phone's dp. */
private val DevicesTab = 324.dp to 771.dp

/**
 * Where the half-open sheet shows its Connections tab, and a blank spot beside its handle to
 * drag it by without the handle's tooltip, in the phone's dp.
 */
private val ConnectionsTab = 153.dp to 690.dp
private val SheetGrip = 300.dp to 462.dp
private val SheetHeld = 206.dp

private const val SPEED_HISTORY_SECONDS = 20
private val LANE_RATE_WAIT = 14.seconds
private val FEED_INTERVAL = 16.milliseconds
