package com.linroid.ketch.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.setContent
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.speedText
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.PulseState
import com.linroid.ketch.app.theme.darkKetchColors
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * What the tray icon shows of the downloads.
 *
 * @property progress share of the downloads received while downloads of known size run, in steps
 *   of 1/32 so changes too small to see do not redraw the icon; `null` shows the sails in full.
 * @property failed whether a failure has not been seen yet.
 * @property dimmed whether everything left is paused.
 */
internal data class TrayIconLook(
  val progress: Float? = null,
  val failed: Boolean = false,
  val dimmed: Boolean = false,
)

/** How the tray icon shows [pulse], with [unseenFailures] failures not seen yet. */
internal fun trayIconLook(pulse: PulseState, unseenFailures: Int): TrayIconLook {
  val counts = pulse.onlineCounts
  val active = counts.downloading > 0
  val progress = pulse.progress?.takeIf { active }
    ?.let { (it.coerceIn(0f, 1f) * PROGRESS_STEPS).toInt() / PROGRESS_STEPS.toFloat() }
  return TrayIconLook(
    progress = progress,
    failed = unseenFailures > 0,
    dimmed = !active && counts.waiting == 0 && counts.paused > 0,
  )
}

/**
 * The speed the macOS menu bar shows after the icon: that of the online devices of [pulse] while
 * they download; `null` otherwise.
 */
internal fun trayIconSpeed(pulse: PulseState): Long? =
  pulse.onlineSpeed.takeIf { pulse.onlineCounts.downloading > 0 }

/**
 * An image for the tray, [size] points large.
 *
 * @property painter draws the image at any scale of [size].
 */
internal data class TrayImage(val painter: Painter, val size: Size)

/**
 * The tray icon in [look]: the sail, with the menu bar's [speed] after it on macOS when not
 * `null`.
 */
@Composable
internal fun rememberTrayIcon(look: TrayIconLook, speed: Long?): TrayImage {
  val style = TrayIconStyle.current
  val sail = rememberVectorPainter(KetchIcon.Sail.imageVector)
  val label = if (style.label) rememberSpeedLabel(speed) else null
  // The label only widens while downloads run, so the menu bar items beside it hold still.
  val widest = remember { WidestLabel() }
  val labelWidth = widest.fit(label?.size?.width)
  val painter = remember(sail, look, label, style) { TrayIconPainter(sail, look, style, label) }
  val width = if (label == null) style.extent else style.extent + LABEL_GAP + labelWidth
  return TrayImage(painter, Size(width, style.extent))
}

// [speed] as the menu bar shows it, laid out in points.
@Composable
private fun rememberSpeedLabel(speed: Long?): TextLayoutResult? {
  val measurer = remember {
    TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
  }
  val text = speed?.let { speedText(it).resolve() } ?: return null
  return remember(measurer, text) {
    measurer.measure(text, LABEL_STYLE, softWrap = false, maxLines = 1)
  }
}

private class WidestLabel {
  private var widest = 0

  /** The width to give a label [width] points wide, or no label: the widest since the last. */
  fun fit(width: Int?): Int {
    widest = if (width == null) 0 else maxOf(widest, width)
    return widest
  }
}

/**
 * The system tray icon (the menu bar extra on macOS) showing [image], with [menu] as its menu;
 * clicking it on Windows and Linux calls [onAction], and [state] sends notifications from it.
 *
 * Unlike Compose's `Tray`, which fits every image in a square, the macOS menu bar item takes the
 * width of [image], so text can follow the icon. Windows and Linux scale it to their tray's size.
 */
@Composable
internal fun ApplicationScope.SystemTrayIcon(
  image: TrayImage,
  state: TrayState,
  tooltip: String?,
  onAction: () -> Unit,
  menu: @Composable MenuScope.() -> Unit,
) {
  val mac = DesktopOs.current == DesktopOs.MAC
  val density = LocalDensity.current
  val awtImage = remember(image, density) {
    // macOS draws the menu bar on every display, so the image keeps a Retina variant even when
    // the main display has none.
    val scale = if (mac) maxOf(density.density, RETINA_SCALE) else density.density
    image.painter.toAwtImage(Density(scale), LayoutDirection.Ltr, image.size)
  }
  val currentOnAction by rememberUpdatedState(onAction)
  val currentMenu by rememberUpdatedState(menu)
  val tray = remember {
    TrayIcon(awtImage).apply {
      isImageAutoSize = !mac
      addActionListener { currentOnAction() }
    }
  }
  SideEffect {
    if (tray.image !== awtImage) tray.image = awtImage
    if (tray.toolTip != tooltip) tray.toolTip = tooltip
  }
  val composition = rememberCompositionContext()
  DisposableEffect(tray) {
    val popupMenu = PopupMenu()
    tray.popupMenu = popupMenu
    val menuComposition = popupMenu.setContent(composition) { currentMenu() }
    SystemTray.getSystemTray().add(tray)
    onDispose {
      menuComposition.dispose()
      SystemTray.getSystemTray().remove(tray)
    }
  }
  LaunchedEffect(tray, state) {
    state.notificationFlow.collect { tray.displayMessage(it) }
  }
}

private fun TrayIcon.displayMessage(notification: Notification) {
  val type = when (notification.type) {
    Notification.Type.None -> TrayIcon.MessageType.NONE
    Notification.Type.Info -> TrayIcon.MessageType.INFO
    Notification.Type.Warning -> TrayIcon.MessageType.WARNING
    Notification.Type.Error -> TrayIcon.MessageType.ERROR
  }
  displayMessage(notification.title, notification.message, type)
}

/**
 * How the tray icon looks on a system.
 *
 * @property extent height of the icon in points, which Windows and Linux scale to their tray's
 *   size and macOS to the menu bar's.
 * @property glyph color of the sail and the speed, or the colors of a gradient across the sail
 *   from its top left to its bottom right.
 * @property failure color of the dot for failures not seen yet.
 * @property pausedGlyph [glyph] while everything is paused, which grays the sail out instead of
 *   fading it; `null` fades it.
 * @property label whether the speed follows the icon.
 */
internal class TrayIconStyle(
  val extent: Float,
  val glyph: List<Color>,
  val failure: Color,
  val pausedGlyph: List<Color>? = null,
  val label: Boolean = false,
) {
  companion object {
    /** A template image, of which macOS only keeps the alpha to tint it for the menu bar. */
    val Template = TrayIconStyle(MAC_EXTENT, listOf(Color.Black), Color.Black, label = true)

    /** The sail in the app icon's colors, which show on light and dark taskbars alike. */
    val AppColors = TrayIconStyle(
      extent = WINDOWS_EXTENT,
      glyph = APP_GRADIENT,
      failure = FAILURE_RED,
      pausedGlyph = PAUSED_GRADIENT,
    )

    /** A light sail for the dark panels most Linux desktops use. */
    val Light: TrayIconStyle = darkKetchColors().let {
      TrayIconStyle(LINUX_EXTENT, listOf(it.textPrimary), it.status.failed.color)
    }

    /** The style of this system. */
    val current: TrayIconStyle = when (DesktopOs.current) {
      DesktopOs.MAC -> Template
      DesktopOs.WINDOWS -> AppColors
      DesktopOs.LINUX -> Light
    }
  }
}

/**
 * Draws the tray icon in [style]: [sail], filled as far as the downloads in [look] have come, a
 * dot for failures not seen yet, and [label] after it. The icon is the height of the image.
 */
internal class TrayIconPainter(
  private val sail: Painter,
  private val look: TrayIconLook,
  private val style: TrayIconStyle,
  private val label: TextLayoutResult? = null,
) : Painter() {
  override val intrinsicSize: Size get() = Size.Unspecified

  override fun DrawScope.onDraw() {
    val grayed = look.dimmed && style.pausedGlyph != null
    val alpha = if (look.dimmed && !grayed) DIMMED_ALPHA else 1f
    val colors = if (grayed) checkNotNull(style.pausedGlyph) else style.glyph
    val extent = size.height
    val single = colors.singleOrNull()
    if (single != null) {
      drawSail(extent, alpha, ColorFilter.tint(single))
    } else {
      // The sail's shape, then the gradient painted into it.
      val icon = Size(extent, extent)
      drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, icon), Paint()) }
      drawSail(extent, alpha, tint = null)
      drawRect(
        brush = Brush.linearGradient(
          colors = colors,
          start = Offset(extent * GRADIENT_INSET, 0f),
          end = Offset(extent * (1 - GRADIENT_INSET), extent),
        ),
        size = icon,
        blendMode = BlendMode.SrcIn,
      )
      drawIntoCanvas { it.restore() }
    }
    if (look.failed) drawFailureDot(extent)
    if (label != null) drawLabel(label, pixelsPerPoint = extent / style.extent, colors.first())
  }

  // The sails fill from their foot up as the downloads come in; the hull always shows in full.
  private fun DrawScope.drawSail(extent: Float, alpha: Float, tint: ColorFilter?) {
    val size = Size(extent, extent)
    val progress = look.progress
    if (progress == null) {
      with(sail) { draw(size, alpha, tint) }
      return
    }
    with(sail) { draw(size, alpha * SAIL_TRACK_ALPHA, tint) }
    val top = extent * (SAIL_FOOT - (SAIL_FOOT - SAIL_HEAD) * progress)
    clipRect(top = top) {
      with(sail) { draw(size, alpha, tint) }
    }
  }

  private fun DrawScope.drawFailureDot(extent: Float) {
    val radius = extent * DOT_SCALE / 2
    val center = Offset(extent - radius, radius)
    // A gap around the dot keeps it apart from the sail.
    drawCircle(Color.Transparent, radius * DOT_GAP_SCALE, center, blendMode = BlendMode.Clear)
    drawCircle(style.failure, radius, center)
  }

  // The label is laid out in points, its digits centered on the icon.
  private fun DrawScope.drawLabel(label: TextLayoutResult, pixelsPerPoint: Float, color: Color) {
    scale(pixelsPerPoint, pivot = Offset.Zero) {
      val baseline = (style.extent + LABEL_FONT_SIZE * LABEL_CAP_HEIGHT) / 2
      val topLeft = Offset(style.extent + LABEL_GAP, baseline - label.firstBaseline)
      drawText(label, color, topLeft)
    }
  }
}

// Compose's tray sizes: macOS menu bar items and Linux panels are 22 points high, Windows trays
// 16.
private const val MAC_EXTENT = 22f
private const val WINDOWS_EXTENT = 16f
private const val LINUX_EXTENT = 22f
private const val RETINA_SCALE = 2f

// The tile gradient of art/icon.svg, from 15% across the top to 85% across the bottom.
private val APP_GRADIENT = listOf(Color(0xFFFFB25B), Color(0xFFE0482B))
private val PAUSED_GRADIENT = listOf(Color(0xFFA6A6A6), Color(0xFF7A7A7A))
private const val GRADIENT_INSET = 0.15f
// Redder than the sail's orange.
private val FAILURE_RED = Color(0xFFE5202E)

// Where the sails of KetchIcon.Sail start and end, in its 20-unit grid.
private const val SAIL_HEAD = 2.25f / 20
private const val SAIL_FOOT = 14.32f / 20
private const val SAIL_TRACK_ALPHA = 0.3f

private const val PROGRESS_STEPS = 32
private const val DOT_SCALE = 0.25f
private const val DOT_GAP_SCALE = 1.5f
private const val DIMMED_ALPHA = 0.45f

// The speed in the macOS menu bar: the size of its other text, with figures of one width so the
// label holds still as the speed changes.
private const val LABEL_FONT_SIZE = 13f
private const val LABEL_CAP_HEIGHT = 0.7f
private const val LABEL_GAP = 3f
private val LABEL_STYLE = TextStyle(
  fontFamily = FontFamily.SansSerif,
  fontSize = LABEL_FONT_SIZE.sp,
  fontFeatureSettings = "tnum",
)
