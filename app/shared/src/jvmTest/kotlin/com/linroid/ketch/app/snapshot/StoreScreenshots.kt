package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalPlatformWindowInsets
import androidx.compose.ui.platform.PlatformInsets
import androidx.compose.ui.platform.PlatformWindowInsets
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linroid.ketch.app.App
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.platform.localDeviceKindOverride
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DiscoverRequest
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Screenshots for the App Store and Google Play: the real app on a [StoreDevice] over
 * [StoreEnvironment], framed under a caption on the brand's colors, at the exact size each store
 * takes and without an alpha channel, which the App Store refuses. Also the Play feature
 * graphic.
 *
 * Writes `store/<set>/<nn>-<shot>.png`, `store/feature-graphic.png` and `store/play-icon.png` under
 * [SnapshotHarness.outputDir]; `art/render-store-assets.sh` renders them and copies them into
 * the fastlane folders of `app/android` and `app/ios`. Each screen is rendered on its own at
 * twice its density in the final image, then framed and composed at twice the final size and
 * scaled down once, as [ShowcaseSnapshots] does. Captions are English.
 */
class StoreScreenshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @AfterTest
  fun restorePlatform() = actAs(null)

  @Test
  fun iphone_everyShot_rendersAt6point9Inches() = renderSet(StoreSet.IPhone)

  @Test
  fun ipad_everyShot_rendersAt13Inches() = renderSet(StoreSet.IPad)

  @Test
  fun androidPhone_everyShot_rendersAt9by16() = renderSet(StoreSet.AndroidPhone)

  @Test
  fun androidTablet_everyShot_rendersAt16by9() = renderSet(StoreSet.AndroidTablet)

  @Test
  fun featureGraphic_downloadsOnAPhone_rendersAt1024by500() {
    val device = StoreDevice.AndroidPhone
    actAs(device)
    val screen = renderScreen(device, SnapshotTheme.Light, FEATURE_PHONE_SCALE) {}
    val icon = Image.makeFromEncoded(File(ART_DIR, "icon-1024.png").readBytes())
    val full = SnapshotHarness.render(FEATURE_WIDTH.dp, FEATURE_HEIGHT.dp, RENDER_SCALE) {
      FeatureGraphic(screen, icon)
    }
    writeOpaque(File(StoreDir, "feature-graphic.png"), full, FEATURE_WIDTH, FEATURE_HEIGHT)
  }

  /**
   * The Play icon: the opaque full-bleed square of the iOS app icon, which Play masks itself,
   * written as the 32-bit PNG with an alpha channel that Play asks for.
   */
  @Test
  fun playIcon_squareIcon_rendersAt512WithAlpha() {
    val icon = Image.makeFromEncoded(File(IOS_ICON).readBytes())
    val surface = Surface.makeRasterN32Premul(PLAY_ICON_SIZE, PLAY_ICON_SIZE)
    surface.canvas.drawImageRect(
      icon,
      Rect.makeWH(icon.width.toFloat(), icon.height.toFloat()),
      Rect.makeWH(PLAY_ICON_SIZE.toFloat(), PLAY_ICON_SIZE.toFloat()),
      SamplingMode.MITCHELL,
      null,
      true,
    )
    val encoded = checkNotNull(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG))
    File(StoreDir, "play-icon.png").apply { parentFile.mkdirs() }.writeBytes(encoded.bytes)
  }
}

/**
 * The screenshots of one store slot: [shots] on [device], on a [width] by [height] pixel canvas
 * whose caption starts [captionTop] from the top in [headline] and [subline] pixel type, with
 * the device below [deviceTop] and above [bottom].
 */
private enum class StoreSet(
  val id: String,
  val device: StoreDevice,
  val width: Int,
  val height: Int,
  val captionTop: Int,
  val headline: Int,
  val subline: Int,
  val deviceTop: Int,
  val bottom: Int,
) {
  IPhone("iphone-6.9", StoreDevice.IPhone, 1320, 2868, 160, 104, 48, 640, 120),
  IPad("ipad-13", StoreDevice.IPad, 2752, 2064, 120, 100, 48, 420, 110),
  AndroidPhone("android-phone", StoreDevice.AndroidPhone, 1440, 2560, 140, 108, 50, 610, 100),
  AndroidTablet("android-tablet-10", StoreDevice.AndroidTablet, 2560, 1440, 84, 78, 38, 304, 70),
  ;

  /** What this slot shows, in order. */
  val shots: List<StoreShot> get() = if (device.tablet) tabletShots(device) else phoneShots(device)
}

/**
 * One screenshot: the app in [theme] once [setup] has run, under [headline] and [subline].
 */
private class StoreShot(
  val id: String,
  val headline: String,
  val subline: String,
  val theme: SnapshotTheme = SnapshotTheme.Light,
  val setup: suspend ShotScope.() -> Unit = {},
)

/** What a [StoreShot]'s setup can drive: the app, the scene and the lanes of the large file. */
private class ShotScope(
  val environment: StoreEnvironment,
  val scene: SnapshotScene,
  private val feeder: CoroutineScope,
) {
  val state: AppState get() = environment.controller.state

  /**
   * Moves [StoreData.MAPS]'s lanes on at [StoreData.MAPS_LANE_RATES], and waits until the
   * lane rates the app measures have settled on them.
   */
  suspend fun feedLanes() {
    val maps = environment.phoneTasks.first { it.taskId == StoreData.MAPS_ID }
    feeder.launch {
      // Fed by the clock the lane rates are measured with, so they read as MAPS_LANE_RATES.
      var last = SnapshotClock.timeSource.markNow()
      while (isActive) {
        delay(FEED_INTERVAL)
        val now = SnapshotClock.timeSource.markNow()
        ShowcaseData.advance(maps, StoreData.MAPS_LANE_RATES, now - last)
        last = now
      }
    }
    // The lane rates start from nothing and average in once a second; let them settle.
    delay(LANE_RATE_WAIT)
  }

  /** Shows [StoreData.MAPS] in the inspector on its Connections tab. */
  suspend fun showConnections() {
    feedLanes()
    state.inspect(environment.phoneKey(StoreData.MAPS_ID))
    scene.settle()
    scene.clickOnText(CONNECTIONS)
  }

  /** Shows the Devices page after a few seconds of every device's speed. */
  suspend fun showDevices() {
    environment.playSpeedHistory(SPEED_HISTORY_SECONDS)
    state.runInShell(KetchCommands.Devices)
  }

  /** Runs the store's Discover search and picks its best result. */
  fun showDiscover() {
    state.openDiscover(DiscoverRequest(STORE_DISCOVER_QUERY))
    state.aiDiscover.selected = setOf(STORE_DISCOVER_PICK)
  }
}

private fun phoneShots(device: StoreDevice): List<StoreShot> = listOfNotNull(
  StoreShot(
    id = "connections",
    headline = "Faster downloads,\nlane by lane",
    subline = "Every file splits into parallel connections, each shown live",
  ) {
    showConnections()
    // The sheet opens half way; hold it up far enough for every lane, with the list above it.
    val x = device.width * SHEET_GRIP_X
    scene.drag(x, device.height * SHEET_GRIP_Y, x, device.height * SHEET_HELD_Y, release = false)
  },
  StoreShot(
    id = "downloads",
    headline = "Every download,\nin one place",
    subline = if (device.apple) {
      "Web and FTP links, running, waiting or done, with resume after restarts"
    } else {
      "Web links, FTP and torrents, running, waiting or done"
    },
  ),
  StoreShot(
    id = "devices",
    headline = "Your computers,\nin your pocket",
    subline = "Pair a Mac, PC or home server and run its downloads from here",
  ) { showDevices() },
  StoreShot(
    id = "add",
    headline = "Paste one link,\nor ten",
    subline = "Ketch reads each file's name and size, then picks the connections",
  ) { state.openIntake(IntakeRequest(text = ADD_LINKS)) },
  StoreShot(
    id = "speed",
    headline = "Full speed, or\nroom to breathe",
    subline = "A Slow lane for calls and streaming, switched on by a weekly schedule",
  ) { state.openSettings(SettingsTarget(SettingsTarget.Page.Speed)) },
  StoreShot(
    id = "discover",
    headline = "Describe it.\nDiscover finds it.",
    subline = "An AI agent searches the web, checks the links and ranks the files",
  ) { showDiscover() }.takeUnless { device.apple },
  StoreShot(
    id = "all-devices",
    headline = "Every device,\none list",
    subline = "In light or dark, and in ten languages",
    theme = SnapshotTheme.Dark,
  ) { state.showAllDevices() },
)

private fun tabletShots(device: StoreDevice): List<StoreShot> = listOfNotNull(
  StoreShot(
    id = "connections",
    headline = "Every connection, live",
    subline = "Parallel connections you can add or remove while a download runs",
  ) { showConnections() },
  StoreShot(
    id = "all-devices",
    headline = "Every device, one list",
    subline = "Watch and control the downloads on your computers and home server",
  ) {
    environment.playSpeedHistory(SPEED_HISTORY_SECONDS)
    state.showAllDevices()
  },
  StoreShot(
    id = "discover",
    headline = "Describe it. Discover finds it.",
    subline = "An AI agent searches the web, checks the links and ranks the files",
  ) { showDiscover() }.takeUnless { device.apple },
  StoreShot(
    id = "speed",
    headline = "Full speed, or room to breathe",
    subline = "A Slow lane for calls and streaming, switched on by a weekly schedule",
  ) { state.openSettings(SettingsTarget(SettingsTarget.Page.Speed)) },
  StoreShot(
    id = "add",
    headline = "Paste one link, or ten",
    subline = "Ketch reads each file's name and size, in light or dark",
    theme = SnapshotTheme.Dark,
  ) { state.openIntake(IntakeRequest(text = ADD_LINKS)) },
)

/**
 * Renders every shot of [set] to `store/<set>/<nn>-<shot>.png`, in a folder of its own that it
 * empties first, so a shot taken off the list does not linger there.
 */
private fun renderSet(set: StoreSet, style: FrameStyle = STORE_FRAME) {
  actAs(set.device)
  val frame = DeviceFrame.of(set, style)
  val folder = File(StoreDir, set.id).apply { deleteRecursively() }
  set.shots.forEachIndexed { index, shot ->
    val screen = renderScreen(set.device, shot.theme, frame.scale, shot.setup)
    SnapshotHarness.write("store-${set.id}-${shot.id}-screen", screen)
    val full = SnapshotHarness.render(set.width.dp, set.height.dp, RENDER_SCALE) {
      StorePage(set, shot, style, frame, screen)
    }
    val name = "%02d-%s.png".format(index + 1, shot.id)
    writeOpaque(File(folder, name), full, set.width, set.height)
  }
}

/**
 * Makes the app name the device it runs on, and write its shortcuts, as on [device]; `null`
 * goes back to this computer's.
 */
private fun actAs(device: StoreDevice?) {
  localDeviceKindOverride = device?.kind
  KeyboardPlatform.override = when {
    device == null -> null
    device.apple -> KeyboardPlatform.Mac
    else -> KeyboardPlatform.Pc
  }
}

/**
 * The app on [device] in [theme] after [setup], rendered at [scale] final pixels per dp and
 * twice that in pixels.
 */
private fun renderScreen(
  device: StoreDevice,
  theme: SnapshotTheme,
  scale: Float,
  setup: suspend ShotScope.() -> Unit,
): Image = withEnvironment(create = { StoreEnvironment(device, theme) }) { environment ->
  val feeder = CoroutineScope(SnapshotHarness.ui)
  try {
    SnapshotHarness.render(
      width = device.width,
      height = device.height,
      scale = scale * RENDER_SCALE,
      interact = { ShotScope(environment, this, feeder).setup() },
    ) {
      StoreApp(environment.controller, device)
    }
  } finally {
    feeder.cancel()
  }
}

/** The frame every store screenshot uses; change it to re-render the sets in another style. */
private val STORE_FRAME = FrameStyle.GraphiteWide

/**
 * How a store screenshot shows its device: a phone in [finish], `null` for the screen alone,
 * with its bezel and band [thickness] times as wide as a real phone's. Tablets keep their own
 * border, in the same finish.
 */
private enum class FrameStyle(
  val label: String,
  val finish: PhoneFinish? = PhoneFinish.Graphite,
  val thickness: Float = 1f,
) {
  /** A graphite phone, whole, under the caption, its border as narrow as a real phone's. */
  Graphite("Graphite"),

  /** The same with a wider border. */
  GraphiteWide("Graphite, wider", thickness = 1.8f),

  /** The same with the widest border. */
  GraphiteWidest("Graphite, widest", thickness = 2.6f),

  /** A silver band with a wider border. */
  SilverWide("Silver, wider", PhoneFinish.Silver, thickness = 1.8f),

  /** A matte light body all the way to the screen, as product mockups draw it. */
  Clay("Clay", PhoneFinish.Clay, thickness = 2.2f),

  /** The screen alone, rounded and floating under the caption, with no hardware. */
  Frameless("Frameless", finish = null),

  /** A larger graphite phone with a wider border that runs off the bottom edge. */
  Bleed("Bleed", thickness = 1.8f),

  /** The screen filling the whole image as the device captures it, with no caption. */
  FullScreen("Full screen", finish = null),
  ;

  /** The bezel and band of a phone of [kind] in this style. */
  fun geometry(kind: PhoneKind): PhoneGeometry = PhoneGeometry.of(kind).let { real ->
    real.copy(bezel = real.bezel * thickness, frame = real.frame * thickness)
  }
}

/**
 * Where and how large a [StoreSet]'s device is drawn, in final pixels: [scale] pixels per dp of
 * the screen, its top left at [x], [y].
 */
private class DeviceFrame(val scale: Float, val x: Float, val y: Float) {
  companion object {
    fun of(set: StoreSet, style: FrameStyle = STORE_FRAME): DeviceFrame {
      val device = set.device
      val edge = when {
        device.tablet -> edgeOf(device).value * 2
        style.finish == null -> 0f
        else -> style.geometry(phoneKind(device)).run { (bezel + frame).value * 2 }
      }
      val bodyWidth = device.width.value + edge
      val bodyHeight = device.height.value + edge
      if (style == FrameStyle.FullScreen) return DeviceFrame(set.width / bodyWidth, 0f, 0f)
      if (style == FrameStyle.Bleed) {
        val scale = set.width * BLEED_WIDTH / bodyWidth
        return DeviceFrame(scale, (set.width - bodyWidth * scale) / 2, set.deviceTop.toFloat())
      }
      val room = (set.height - set.deviceTop - set.bottom).toFloat()
      val maxWidth = set.width * MAX_DEVICE_WIDTH
      val scale = min(room / bodyHeight, maxWidth / bodyWidth)
      val x = (set.width - bodyWidth * scale) / 2
      val y = set.deviceTop + (room - bodyHeight * scale) / 2
      return DeviceFrame(scale, x, y)
    }

    /** Bezel and band around [device]'s screen, in dp of the screen. */
    fun edgeOf(device: StoreDevice): Dp = if (device.tablet) {
      TabletGeometry.of(device).run { bezel + frame }
    } else {
      PhoneGeometry.of(phoneKind(device)).run { bezel + frame }
    }
  }
}

private fun phoneKind(device: StoreDevice): PhoneKind =
  if (device.apple) PhoneKind.Ios else PhoneKind.Android

/** The app on [device], under its status bar and over its gesture area. */
@OptIn(InternalComposeUiApi::class)
@Composable
private fun StoreApp(controller: AppController, device: StoreDevice) {
  val (top, bottom) = if (device.tablet) {
    TabletGeometry.of(device).run { statusBar to bottomInset }
  } else {
    PhoneGeometry.of(phoneKind(device)).run { statusBar to bottomInset }
  }
  val density = LocalDensity.current
  val insets = remember(density, top, bottom) {
    with(density) { StoreInsets(top = top.roundToPx(), bottom = bottom.roundToPx()) }
  }
  CompositionLocalProvider(LocalPlatformWindowInsets provides insets) {
    App(controller)
  }
}

/** A device's system bars: the status bar on top and the gesture area at the bottom, in pixels. */
@OptIn(InternalComposeUiApi::class)
private class StoreInsets(top: Int, bottom: Int) : PlatformWindowInsets {
  override val statusBars: PlatformInsets = PlatformInsets(0, top, 0, 0)
  override val navigationBars: PlatformInsets = PlatformInsets(0, 0, 0, bottom)
  override val systemBars: PlatformInsets = PlatformInsets(0, top, 0, bottom)
}

/**
 * A store screenshot: the caption over the device in [style], on the brand's colors, or for
 * [FrameStyle.FullScreen] the screen alone.
 */
@Composable
private fun StorePage(
  set: StoreSet,
  shot: StoreShot,
  style: FrameStyle,
  frame: DeviceFrame,
  screen: Image,
) {
  val dark = shot.theme == SnapshotTheme.Dark
  KetchTheme(darkTheme = dark, density = DensityMode.Comfortable, reduceMotion = true) {
    val colors = KetchTheme.colors
    val bitmap = remember(screen) { screen.toComposeImageBitmap() }
    val device = set.device
    if (style == FrameStyle.FullScreen) {
      Box(Modifier.size(set.width.dp, set.height.dp)) {
        ScreenImage(bitmap, Modifier.fillMaxSize())
        Box(Modifier.nativeScale(device.width, device.height, frame.scale)) {
          PhoneChrome(phoneKind(device), colors.textPrimary, STATUS_TIME, cutout = false)
        }
      }
      return@KetchTheme
    }
    Box(Modifier.size(set.width.dp, set.height.dp).storeBackground(dark)) {
      Caption(
        headline = shot.headline,
        subline = shot.subline,
        headlineSize = set.headline,
        sublineSize = set.subline,
        maxWidth = (set.width * CAPTION_WIDTH).dp,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = set.captionTop.dp),
      )
      // A bleeding phone is taller than the page: measured at its own size, it runs off the
      // bottom edge rather than being squeezed to fit.
      val position = Modifier
        .offset(frame.x.dp, frame.y.dp)
        .wrapContentSize(Alignment.TopStart, unbounded = true)
      when {
        device.tablet -> Tablet(
          device = device,
          screen = bitmap,
          scale = frame.scale,
          ink = colors.textPrimary,
          dark = dark,
          time = STATUS_TIME,
          finish = style.finish ?: PhoneFinish.Graphite,
          modifier = position,
        )
        style == FrameStyle.Frameless -> FloatingScreen(
          device = device,
          screen = bitmap,
          scale = frame.scale,
          ink = colors.textPrimary,
          dark = dark,
          modifier = position,
        )
        else -> Phone(
          kind = phoneKind(device),
          screen = bitmap,
          screenWidth = device.width,
          screenHeight = device.height,
          scale = frame.scale,
          ink = colors.textPrimary,
          dark = dark,
          time = STATUS_TIME,
          finish = style.finish ?: PhoneFinish.Graphite,
          geometry = style.geometry(phoneKind(device)),
          modifier = position,
        )
      }
    }
  }
}

/**
 * [device]'s screen without hardware: [screen] drawn [scale] pixels per dp with the phone's
 * corner radius, its status bar in [ink] and a light rim, floating on a shadow.
 */
@Composable
private fun FloatingScreen(
  device: StoreDevice,
  screen: ImageBitmap,
  scale: Float,
  ink: Color,
  dark: Boolean,
  modifier: Modifier = Modifier,
) {
  val kind = phoneKind(device)
  val shape = RoundedCornerShape(PhoneGeometry.of(kind).screenRadius * scale)
  Box(
    modifier
      .size(device.width * scale, device.height * scale)
      .deviceShadow(shape, dark, lift = 1.4f)
      .clip(shape),
  ) {
    ScreenImage(screen, Modifier.fillMaxSize())
    Box(Modifier.nativeScale(device.width, device.height, scale)) {
      PhoneChrome(kind, ink, STATUS_TIME, cutout = false)
    }
    Box(Modifier.fillMaxSize().border((2f * scale).dp, FloatingRim, shape))
  }
}

/** The headline and the line under it, centered, in white on the brand's colors. */
@Composable
private fun Caption(
  headline: String,
  subline: String,
  headlineSize: Int,
  sublineSize: Int,
  maxWidth: Dp,
  modifier: Modifier = Modifier,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier.widthIn(max = maxWidth),
  ) {
    Text(
      text = headline,
      color = Color.White,
      textAlign = TextAlign.Center,
      style = KetchTheme.typography.largeTitle.copy(
        fontSize = headlineSize.sp,
        lineHeight = (headlineSize * HEADLINE_LEADING).sp,
        letterSpacing = (-headlineSize * HEADLINE_TRACKING).sp,
      ),
    )
    Spacer(Modifier.height((sublineSize * SUBLINE_GAP).dp))
    Text(
      text = subline,
      color = Color.White.copy(alpha = SUBLINE_ALPHA),
      textAlign = TextAlign.Center,
      style = KetchTheme.typography.body.copy(
        fontSize = sublineSize.sp,
        lineHeight = (sublineSize * SUBLINE_LEADING).sp,
      ),
    )
  }
}

/**
 * The store pages' background: the accent deepening down its diagonal, with a soft light top
 * right and the logo's ember glowing bottom left; a dark navy for screenshots in dark.
 */
private fun Modifier.storeBackground(dark: Boolean): Modifier = drawWithCache {
  val base = Brush.linearGradient(
    colors = if (dark) listOf(DarkTop, DarkBottom) else listOf(LightTop, LightBottom),
    start = Offset.Zero,
    end = Offset(size.width, size.height),
  )
  val glow = Brush.radialGradient(
    colors = listOf(Color.White.copy(alpha = if (dark) 0.07f else 0.16f), Color.Transparent),
    center = Offset(size.width * 0.88f, size.height * 0.04f),
    radius = size.maxDimension * 0.55f,
  )
  val ember = Brush.radialGradient(
    0f to EmberWarm.copy(alpha = if (dark) 0.2f else 0.4f),
    0.4f to EmberHot.copy(alpha = if (dark) 0.07f else 0.12f),
    1f to Color.Transparent,
    center = Offset(size.width * 0.04f, size.height),
    radius = size.maxDimension * 0.48f,
  )
  onDrawBehind {
    drawRect(base)
    drawRect(glow)
    drawRect(ember)
  }
}

/**
 * Proportions of a tablet, in dp of its screen.
 *
 * @property bezel black border around the screen.
 * @property frame the metal band around the bezel.
 * @property screenRadius corner radius of the screen.
 * @property statusBar height of the status bar, which the app pads its top bar by.
 * @property bottomInset height of the gesture area, which the app pads its bottom by.
 */
private data class TabletGeometry(
  val bezel: Dp,
  val frame: Dp,
  val screenRadius: Dp,
  val statusBar: Dp,
  val bottomInset: Dp,
) {
  companion object {
    fun of(device: StoreDevice): TabletGeometry = if (device.apple) {
      TabletGeometry(16.dp, 3.dp, 18.dp, statusBar = 24.dp, bottomInset = 20.dp)
    } else {
      TabletGeometry(18.dp, 3.dp, 16.dp, statusBar = 28.dp, bottomInset = 20.dp)
    }
  }
}

/**
 * A generic tablet in landscape showing [screen], a capture of [device]'s screen, drawn [scale]
 * pixels per dp, its band in [finish], with a front camera in its top bezel. The status bar
 * reads [time] in [ink].
 */
@Composable
private fun Tablet(
  device: StoreDevice,
  screen: ImageBitmap,
  scale: Float,
  ink: Color,
  dark: Boolean,
  time: String,
  finish: PhoneFinish = PhoneFinish.Graphite,
  modifier: Modifier = Modifier,
) {
  val geometry = TabletGeometry.of(device)
  val edge = geometry.bezel + geometry.frame
  val bodyRadius = (geometry.screenRadius + edge) * scale
  val bodyShape = RoundedCornerShape(bodyRadius)
  val innerShape = RoundedCornerShape(bodyRadius - geometry.frame * scale)
  val screenShape = RoundedCornerShape(geometry.screenRadius * scale)
  Box(
    modifier
      .size((device.width + edge * 2) * scale, (device.height + edge * 2) * scale)
      .deviceShadow(bodyShape, dark, lift = 1.4f)
      .clip(bodyShape)
      .background(frameBrush(PhoneKind.Ios, dark, finish), bodyShape),
  ) {
    Box(Modifier.fillMaxSize().border((1.2f * scale).dp, frameEdge(dark, finish), bodyShape))
    Box(
      Modifier
        .padding(geometry.frame * scale)
        .fillMaxSize()
        .clip(innerShape)
        .background(TabletBezel, innerShape),
    )
    Box(
      Modifier
        .align(Alignment.TopCenter)
        .offset(y = (geometry.frame + geometry.bezel / 2 - LensSize / 2) * scale)
        .size(LensSize * scale)
        .clip(CircleShape)
        .background(TabletLens),
    )
    Box(
      Modifier
        .padding(edge * scale)
        .size(device.width * scale, device.height * scale)
        .clip(screenShape),
    ) {
      ScreenImage(screen, Modifier.fillMaxSize())
      Box(Modifier.nativeScale(device.width, device.height, scale)) {
        TabletChrome(device, geometry, ink, time)
      }
    }
  }
}

/** The status bar and the gesture handle of a tablet, in screen dp. */
@Composable
private fun TabletChrome(device: StoreDevice, geometry: TabletGeometry, ink: Color, time: String) {
  Box(Modifier.fillMaxSize()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .height(geometry.statusBar)
        .padding(horizontal = 24.dp),
    ) {
      StatusTime(time, ink, 14)
      Spacer(Modifier.weight(1f))
      StatusGlyphs(ink, wedgeSignal = !device.apple, verticalBattery = !device.apple)
    }
    Box(
      Modifier
        .align(Alignment.BottomCenter)
        .offset(y = (-7).dp)
        .size(width = if (device.apple) 300.dp else 160.dp, height = 5.dp)
        .clip(CircleShape)
        .background(ink.copy(alpha = 0.72f)),
    )
  }
}

/**
 * The Play feature graphic: the icon and name over a line about the app on the left, and a
 * phone showing the downloads list rising from the bottom edge on the right.
 */
@Composable
private fun FeatureGraphic(screen: Image, icon: Image) {
  KetchTheme(darkTheme = false, density = DensityMode.Comfortable, reduceMotion = true) {
    val colors = KetchTheme.colors
    val screenBitmap = remember(screen) { screen.toComposeImageBitmap() }
    val iconBitmap = remember(icon) { icon.toComposeImageBitmap() }
    Box(Modifier.size(FEATURE_WIDTH.dp, FEATURE_HEIGHT.dp).storeBackground(dark = false)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        modifier = Modifier.align(Alignment.CenterStart).padding(start = 64.dp, bottom = 8.dp),
      ) {
        Image(iconBitmap, contentDescription = null, modifier = Modifier.size(136.dp))
        Column(Modifier.width(400.dp)) {
          Text(
            text = "Ketch",
            color = Color.White,
            style = KetchTheme.typography.largeTitle.copy(
              fontSize = 84.sp,
              lineHeight = 92.sp,
              letterSpacing = (-2).sp,
            ),
          )
          Text(
            text = "Fast downloads on every device you own",
            color = Color.White.copy(alpha = SUBLINE_ALPHA),
            style = KetchTheme.typography.body.copy(fontSize = 28.sp, lineHeight = 36.sp),
          )
        }
      }
      Phone(
        kind = PhoneKind.Android,
        screen = screenBitmap,
        screenWidth = StoreDevice.AndroidPhone.width,
        screenHeight = StoreDevice.AndroidPhone.height,
        scale = FEATURE_PHONE_SCALE,
        ink = colors.textPrimary,
        dark = false,
        time = STATUS_TIME,
        finish = STORE_FRAME.finish ?: PhoneFinish.Graphite,
        geometry = STORE_FRAME.geometry(PhoneKind.Android),
        // Taller than the graphic: measured at its own size, it runs off the bottom edge rather
        // than being squeezed to fit.
        modifier = Modifier
          .offset(FeaturePhoneX, FeaturePhoneY)
          .wrapContentSize(Alignment.TopStart, unbounded = true),
      )
    }
  }
}

/** Draws [image] onto an opaque [width] by [height] canvas and writes it to [file] as a PNG. */
private fun writeOpaque(file: File, image: Image, width: Int, height: Int) {
  val surface = Surface.makeRaster(ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE))
  surface.canvas.drawImageRect(
    image,
    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
    Rect.makeWH(width.toFloat(), height.toFloat()),
    SamplingMode.MITCHELL,
    null,
    true,
  )
  val encoded = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)
  file.parentFile.mkdirs()
  file.writeBytes(checkNotNull(encoded) { "Couldn't encode ${file.name}" }.bytes)
}

private val StoreDir get() = File(SnapshotHarness.outputDir, "store")

/** `art/` at the root of the repository, from `app/shared`, where the tests run. */
private val ART_DIR = File("../../art")

/** The iOS app icon, `art/icon-square.svg` rendered as an opaque 1024 px square. */
private const val IOS_ICON = "../ios/Assets.xcassets/AppIcon.appiconset/icon-1024.png"
private const val PLAY_ICON_SIZE = 512

// Composed at twice the final size and scaled down once.
private const val RENDER_SCALE = 2f
private const val STATUS_TIME = "14:30"

private const val MAX_DEVICE_WIDTH = 0.86f
private const val BLEED_WIDTH = 0.9f
private val FloatingRim = Color.White.copy(alpha = 0.35f)

private const val CAPTION_WIDTH = 0.84f
private const val HEADLINE_LEADING = 1.08f
private const val HEADLINE_TRACKING = 0.02f
private const val SUBLINE_LEADING = 1.32f
private const val SUBLINE_GAP = 0.7f
private const val SUBLINE_ALPHA = 0.84f

private val LightTop = Color(0xFF6572F6)
private val LightBottom = Color(0xFF2E37A6)
private val DarkTop = Color(0xFF1D2140)
private val DarkBottom = Color(0xFF0B0D12)
private val EmberWarm = Color(0xFFFFB25B)
private val EmberHot = Color(0xFFE0482B)
private val TabletBezel = Color(0xFF07080A)
private val TabletLens = Color(0xFF1A1C21)
private val LensSize = 6.dp

private const val FEATURE_WIDTH = 1024
private const val FEATURE_HEIGHT = 500
private const val FEATURE_PHONE_SCALE = 0.74f
private val FeaturePhoneX = 650.dp
private val FeaturePhoneY = 64.dp

/** Lines the add sheet is opened with. */
private val ADD_LINKS = StoreData.LinkSizes.keys.joinToString("\n")

/** The Discover result picked in its screenshot. */
private const val STORE_DISCOVER_PICK = "https://maps.example.org/offline/alps-topo-2026.zip"

private const val CONNECTIONS = "Connections"

/** Where the half-open inspector sheet is dragged by, and held at, as fractions of the screen. */
private const val SHEET_GRIP_X = 0.77f
private const val SHEET_GRIP_Y = 0.547f
private const val SHEET_HELD_Y = 0.244f

private const val SPEED_HISTORY_SECONDS = 20
private val LANE_RATE_WAIT = 14.seconds
private val FEED_INTERVAL = 16.milliseconds
