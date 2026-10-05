package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.darkKetchColors
import kotlin.math.roundToInt

/*
 * Generic device frames for the README showcase, drawn around real screens of the app. They
 * carry no logo, carrier, wallpaper or model-specific hardware. Sizes are in the showcase's
 * units, one pixel of the final image each, unless a parameter says it is in the screen's dp.
 */

/**
 * Measures the content at [width] by [height] and draws it [scale] times as large from the top
 * left, so it lays out as on a screen of that size and stays sharp when scaled.
 */
internal fun Modifier.nativeScale(width: Dp, height: Dp, scale: Float): Modifier =
  layout { measurable, _ ->
    val placeable = measurable.measure(Constraints.fixed(width.roundToPx(), height.roundToPx()))
    val scaledWidth = (placeable.width * scale).roundToInt()
    val scaledHeight = (placeable.height * scale).roundToInt()
    layout(scaledWidth, scaledHeight) {
      placeable.placeWithLayer(0, 0) {
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(0f, 0f)
      }
    }
  }

/** [image] filling the element pixel for pixel; the element is as large as the image. */
@Composable
internal fun ScreenImage(image: ImageBitmap, modifier: Modifier = Modifier) {
  Canvas(modifier) {
    drawImage(
      image = image,
      srcOffset = IntOffset.Zero,
      srcSize = IntSize(image.width, image.height),
      dstOffset = IntOffset.Zero,
      dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    )
  }
}

/**
 * The shadow of a floating device: a tight contact shadow under soft layers, about three times as
 * strong on a dark canvas.
 */
internal fun Modifier.deviceShadow(shape: Shape, dark: Boolean, lift: Float = 1f): Modifier {
  val strength = if (dark) DARK_SHADOW else 1f
  fun layer(y: Float, blur: Float, alpha: Float, spread: Float = 0f) = Shadow(
    radius = (blur * lift).dp,
    color = ShadowInk.copy(alpha = (alpha * strength).coerceAtMost(1f)),
    spread = (spread * lift).dp,
    offset = DpOffset(0.dp, (y * lift).dp),
  )
  return this
    .dropShadow(shape, layer(y = 3f, blur = 8f, alpha = 0.08f))
    .dropShadow(shape, layer(y = 8f, blur = 20f, alpha = 0.14f))
    .dropShadow(shape, layer(y = 16f, blur = 32f, alpha = 0.12f))
    .dropShadow(shape, layer(y = 40f, blur = 80f, alpha = 0.22f, spread = -14f))
}

/**
 * A desktop window of [width] by [height] around [screen], a capture of the whole window with
 * its own title bar, rounded by [radius] under a hairline [rim].
 */
@Composable
internal fun DesktopWindow(
  screen: ImageBitmap,
  width: Dp,
  height: Dp,
  radius: Dp,
  rim: Color,
  dark: Boolean,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(radius)
  Box(
    modifier
      .size(width, height)
      .deviceShadow(shape, dark, lift = 1.2f)
      .clip(shape),
  ) {
    ScreenImage(screen, Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize().border(1.dp, rim, shape))
  }
}

/** The two kinds of phone the showcase draws. */
internal enum class PhoneKind {
  /** A punch-hole camera, a short status bar and a gesture handle. */
  Android,

  /** A pill-shaped cutout, a tall status bar and a home indicator. */
  Ios,
}

/**
 * The look of a phone's body: a graphite or silver metal band around a black bezel, or [Clay], a
 * matte light body all the way to the screen, as product mockups draw it.
 */
internal enum class PhoneFinish { Graphite, Silver, Clay }

/**
 * Proportions of a [PhoneKind], in dp of its screen.
 *
 * @property bezel black border around the screen.
 * @property frame the metal band around the bezel.
 * @property screenRadius corner radius of the screen.
 * @property statusBar height of the status bar, which the app pads its top bar by.
 * @property bottomInset height of the gesture area, which the app pads its bottom by.
 */
@Immutable
internal data class PhoneGeometry(
  val bezel: Dp,
  val frame: Dp,
  val screenRadius: Dp,
  val statusBar: Dp,
  val bottomInset: Dp,
) {
  companion object {
    fun of(kind: PhoneKind): PhoneGeometry = when (kind) {
      PhoneKind.Android -> PhoneGeometry(
        bezel = 7.dp,
        frame = 2.5.dp,
        screenRadius = 34.dp,
        statusBar = 36.dp,
        bottomInset = 22.dp,
      )
      PhoneKind.Ios -> PhoneGeometry(
        bezel = 9.dp,
        frame = 3.dp,
        screenRadius = 47.dp,
        statusBar = 54.dp,
        bottomInset = 30.dp,
      )
    }
  }
}

/**
 * A phone of [kind] showing [screen], a capture of a [screenWidth] by [screenHeight] dp screen,
 * drawn [scale] showcase units per dp, its body in [finish] with the bezel and band of
 * [geometry]. The status bar reads [time] in [ink], the color of the app's text.
 */
@Composable
internal fun Phone(
  kind: PhoneKind,
  screen: ImageBitmap,
  screenWidth: Dp,
  screenHeight: Dp,
  scale: Float,
  ink: Color,
  dark: Boolean,
  time: String,
  finish: PhoneFinish = PhoneFinish.Graphite,
  geometry: PhoneGeometry = PhoneGeometry.of(kind),
  modifier: Modifier = Modifier,
) {
  val edge = geometry.bezel + geometry.frame
  val bodyRadius = (geometry.screenRadius + edge) * scale
  val bodyShape = RoundedCornerShape(bodyRadius)
  val innerShape = RoundedCornerShape(bodyRadius - geometry.frame * scale)
  val screenShape = RoundedCornerShape(geometry.screenRadius * scale)
  val width = (screenWidth + edge * 2) * scale
  val height = (screenHeight + edge * 2) * scale
  Box(
    modifier
      .size(width, height)
      .deviceShadow(bodyShape, dark)
      .clip(bodyShape)
      .background(frameBrush(kind, dark, finish), bodyShape),
  ) {
    // The band's bright edge, as light catches the metal.
    Box(Modifier.fillMaxSize().border((1.2f * scale).dp, frameEdge(dark, finish), bodyShape))
    Box(
      Modifier
        .padding(geometry.frame * scale)
        .fillMaxSize()
        .clip(innerShape)
        .background(if (finish == PhoneFinish.Clay) ClayBezel else Bezel, innerShape),
    )
    Box(
      Modifier
        .padding(edge * scale)
        .size(screenWidth * scale, screenHeight * scale)
        .clip(screenShape),
    ) {
      ScreenImage(screen, Modifier.fillMaxSize())
      Box(Modifier.nativeScale(screenWidth, screenHeight, scale)) {
        PhoneChrome(kind, ink, time)
      }
    }
  }
}

/**
 * What a phone of [kind] draws over the app, in screen dp: the status bar reading [time] in
 * [ink] and the gesture handle, with the camera cutout when [cutout] is set.
 */
@Composable
internal fun PhoneChrome(kind: PhoneKind, ink: Color, time: String, cutout: Boolean = true) {
  val geometry = PhoneGeometry.of(kind)
  when (kind) {
    PhoneKind.Android -> AndroidChrome(geometry, ink, time, cutout)
    PhoneKind.Ios -> IosChrome(geometry, ink, time, cutout)
  }
}

/** The punch-hole camera, the status bar and the gesture handle, in screen dp. */
@Composable
private fun AndroidChrome(geometry: PhoneGeometry, ink: Color, time: String, cutout: Boolean) {
  Box(Modifier.fillMaxSize()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .height(geometry.statusBar)
        .padding(horizontal = 22.dp),
    ) {
      StatusTime(time, ink, 14)
      Spacer(Modifier.weight(1f))
      StatusGlyphs(ink, wedgeSignal = true, verticalBattery = true)
    }
    if (cutout) {
      Box(
        Modifier
          .align(Alignment.TopCenter)
          .offset(y = 11.dp)
          .size(12.dp)
          .clip(CircleShape)
          .background(Lens),
      )
    }
    Box(
      Modifier
        .align(Alignment.BottomCenter)
        .offset(y = (-9).dp)
        .size(width = 104.dp, height = 4.dp)
        .clip(CircleShape)
        .background(ink.copy(alpha = 0.72f)),
    )
  }
}

/** The pill cutout, the status bar either side of it and the home indicator, in screen dp. */
@Composable
private fun IosChrome(geometry: PhoneGeometry, ink: Color, time: String, cutout: Boolean) {
  Box(Modifier.fillMaxSize()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .height(geometry.statusBar)
        .padding(start = 44.dp, end = 30.dp),
    ) {
      StatusTime(time, ink, 16)
      Spacer(Modifier.weight(1f))
      StatusGlyphs(ink, wedgeSignal = false, verticalBattery = false)
    }
    if (cutout) {
      Box(
        Modifier
          .align(Alignment.TopCenter)
          .offset(y = 11.dp)
          .size(width = 106.dp, height = 32.dp)
          .clip(CircleShape)
          .background(Lens),
      )
    }
    Box(
      Modifier
        .align(Alignment.BottomCenter)
        .offset(y = (-8).dp)
        .size(width = 136.dp, height = 5.dp)
        .clip(CircleShape)
        .background(ink),
    )
  }
}

@Composable
internal fun StatusTime(time: String, ink: Color, size: Int) {
  Text(
    text = time,
    color = ink,
    style = KetchTheme.typography.titleM.copy(fontSize = size.sp, letterSpacing = 0.sp),
  )
}

/** Signal, Wi-Fi and battery, drawn as plain shapes without numbers. */
@Composable
internal fun StatusGlyphs(ink: Color, wedgeSignal: Boolean, verticalBattery: Boolean) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (wedgeSignal) {
      Canvas(Modifier.size(14.dp, 13.dp)) {
        val path = Path().apply {
          moveTo(size.width, 0f)
          lineTo(size.width, size.height)
          lineTo(0f, size.height)
          close()
        }
        drawPath(path, ink)
      }
    } else {
      Canvas(Modifier.size(18.dp, 12.dp)) {
        val bar = size.width / 4 - 1.5.dp.toPx()
        for (index in 0..3) {
          val barHeight = size.height * (index + 1) / 4
          drawRoundRect(
            color = ink,
            topLeft = Offset(index * size.width / 4, size.height - barHeight),
            size = Size(bar, barHeight),
            cornerRadius = CornerRadius(1.dp.toPx()),
          )
        }
      }
    }
    WifiGlyph(ink)
    if (verticalBattery) {
      Canvas(Modifier.size(8.dp, 14.dp)) {
        val nub = 2.dp.toPx()
        drawRoundRect(
          color = ink,
          topLeft = Offset(size.width * 0.3f, 0f),
          size = Size(size.width * 0.4f, nub),
        )
        drawRoundRect(
          color = ink,
          topLeft = Offset(0f, nub * 0.8f),
          size = Size(size.width, size.height - nub * 0.8f),
          cornerRadius = CornerRadius(1.5.dp.toPx()),
        )
      }
    } else {
      Canvas(Modifier.size(27.dp, 13.dp)) {
        val stroke = 1.dp.toPx()
        val body = Size(size.width - 3.dp.toPx(), size.height)
        drawRoundRect(
          color = ink.copy(alpha = 0.4f),
          topLeft = Offset(stroke / 2, stroke / 2),
          size = Size(body.width - stroke, body.height - stroke),
          cornerRadius = CornerRadius(4.dp.toPx()),
          style = Stroke(stroke),
        )
        val inset = 2.dp.toPx()
        drawRoundRect(
          color = ink,
          topLeft = Offset(inset, inset),
          size = Size((body.width - inset * 2) * 0.8f, body.height - inset * 2),
          cornerRadius = CornerRadius(2.5.dp.toPx()),
        )
        drawRoundRect(
          color = ink.copy(alpha = 0.4f),
          topLeft = Offset(body.width + 1.dp.toPx(), size.height * 0.33f),
          size = Size(1.6.dp.toPx(), size.height * 0.34f),
          cornerRadius = CornerRadius(1.dp.toPx()),
        )
      }
    }
  }
}

@Composable
private fun WifiGlyph(ink: Color) {
  Canvas(Modifier.size(16.dp, 12.dp)) {
    val center = Offset(size.width / 2, size.height)
    val stroke = 2.dp.toPx()
    for (ring in 1..2) {
      val radius = size.height * (0.38f + ring * 0.3f) - stroke / 2
      drawArc(
        color = ink,
        startAngle = 225f,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(stroke, cap = StrokeCap.Round),
      )
    }
    val dot = size.height * 0.38f
    drawArc(
      color = ink,
      startAngle = 225f,
      sweepAngle = 90f,
      useCenter = true,
      topLeft = Offset(center.x - dot, center.y - dot),
      size = Size(dot * 2, dot * 2),
    )
  }
}

/** One colored run of a terminal line. */
internal enum class TerminalInk { Default, Accent, Dim }

/** A line of terminal output in colored runs. */
internal class TerminalLine(val spans: List<Pair<String, TerminalInk>>) {
  constructor(text: String) : this(listOf(text to TerminalInk.Default))
}

/**
 * A dark terminal window [width] wide titled [title], as tall as [lines] need, with a block
 * cursor after the last line. It is dark in both themes: a step above a dark canvas, under a
 * [rim] that keeps its edge visible there, and a step lighter on a light one, so it does not
 * outweigh the app. Its traffic lights are muted, a window behind the app's.
 */
@Composable
internal fun TerminalWindow(
  title: String,
  lines: List<TerminalLine>,
  width: Dp,
  rim: Color,
  dark: Boolean,
  fontSize: Float,
  lineHeight: Float,
  modifier: Modifier = Modifier,
) {
  val colors = darkKetchColors()
  val shape = RoundedCornerShape(12.dp)
  val mono = KetchTheme.typography.mono.copy(
    fontSize = fontSize.sp,
    lineHeight = lineHeight.sp,
    color = colors.textPrimary,
  )
  val body = if (dark) colors.surface else colors.surfaceRaised
  val titleBar = if (dark) colors.surfaceRaised else colors.surfacePressed
  Column(
    modifier
      .width(width)
      .deviceShadow(shape, dark, lift = 0.8f)
      .clip(shape)
      .background(body, shape)
      .border(1.dp, rim, shape),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.fillMaxWidth().height(38.dp).background(titleBar),
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
      ) {
        for (light in TrafficLightColors) {
          val muted = light.copy(alpha = MUTED_LIGHT_ALPHA)
          Box(Modifier.size(12.dp).clip(CircleShape).background(muted))
        }
      }
      Text(
        text = title,
        style = KetchTheme.typography.label.copy(fontSize = 13.sp),
        color = colors.textTertiary,
      )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
      lines.forEachIndexed { index, line ->
        val text = buildAnnotatedString {
          for ((run, ink) in line.spans) {
            val color = when (ink) {
              TerminalInk.Default -> colors.textPrimary
              TerminalInk.Accent -> colors.accentText
              TerminalInk.Dim -> colors.textTertiary
            }
            withStyle(SpanStyle(color = color)) { append(run) }
          }
          if (index == lines.lastIndex) {
            withStyle(SpanStyle(color = colors.textSecondary)) { append(" █") }
          }
        }
        Text(text = text, style = mono, maxLines = 1, softWrap = false)
      }
    }
  }
}

/** The metal band of a phone of [kind] in [finish], a little lighter on a [dark] canvas. */
internal fun frameBrush(kind: PhoneKind, dark: Boolean, finish: PhoneFinish): Brush {
  if (finish == PhoneFinish.Silver) return Brush.verticalGradient(listOf(SilverTop, SilverBottom))
  if (finish == PhoneFinish.Clay) return Brush.verticalGradient(listOf(ClayTop, ClayBottom))
  val lift = if (dark) 0.06f else 0f
  val top = if (kind == PhoneKind.Ios) Color(0xFF4A4F59) else Color(0xFF3A3E46)
  val bottom = if (kind == PhoneKind.Ios) Color(0xFF2A2D33) else Color(0xFF23262B)
  return Brush.verticalGradient(listOf(lighten(top, lift), lighten(bottom, lift)))
}

/** The bright edge of a band in [finish], as light catches the metal. */
internal fun frameEdge(dark: Boolean, finish: PhoneFinish): Color = when {
  finish == PhoneFinish.Silver -> SilverEdge
  finish == PhoneFinish.Clay -> ClayEdge
  dark -> Color(0xFF6A717E)
  else -> Color(0xFF5A606B)
}

private fun lighten(color: Color, amount: Float): Color = Color(
  red = color.red + (1 - color.red) * amount,
  green = color.green + (1 - color.green) * amount,
  blue = color.blue + (1 - color.blue) * amount,
  alpha = color.alpha,
)

private val ShadowInk = Color(0xFF0F172A)
private val SilverTop = Color(0xFFE6E9ED)
private val SilverBottom = Color(0xFFB4BAC3)
private val SilverEdge = Color(0xFFF7F8FA)
private val ClayTop = Color(0xFFF9FAFB)
private val ClayBottom = Color(0xFFE3E6EB)
private val ClayBezel = Color(0xFFF1F3F6)
private val ClayEdge = Color(0xFFFFFFFF)
private val Bezel = Color(0xFF07080A)
private val Lens = Color(0xFF050506)
private const val DARK_SHADOW = 2.4f
private const val MUTED_LIGHT_ALPHA = 0.6f
