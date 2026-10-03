package com.linroid.ketch.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The frame of a wide window: [navigation] on the canvas wash at the start, and the content
 * card filling the rest, with [top] (the banners) over [content] and [bottom] (the Pulse bar)
 * under it. [overlay] floats over the content, such as the toasts, and [cover] over the whole
 * card, such as the drop berths.
 *
 * The card floats with an 8 dp inset, rounded corners and a hairline, except on windows of a
 * [full-bleed][KetchLayout.fullBleed] layout, where it fills its area beside a hairline. On
 * tablets the wash runs under the system bars and the rest stays clear of them.
 */
@Composable
internal fun ShellScaffold(
  layout: KetchLayout,
  navigation: @Composable () -> Unit,
  top: @Composable () -> Unit,
  bottom: @Composable () -> Unit,
  overlay: @Composable BoxScope.() -> Unit,
  modifier: Modifier = Modifier,
  cover: @Composable BoxScope.() -> Unit = {},
  content: @Composable () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    modifier
      .fillMaxSize()
      .canvasWash(colors)
      .windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    navigation()
    val card = if (layout.fullBleed) {
      Modifier.background(colors.surface)
    } else {
      Modifier
        .padding(top = spacing.cardInset, end = spacing.cardInset, bottom = spacing.cardInset)
        .ketchSurface(
          level = KetchElevationLevel.E1,
          shape = KetchTheme.shapes.card,
          fill = colors.surface,
          border = colors.hairline,
        )
    }
    if (layout.fullBleed) {
      Spacer(Modifier.width(HairlineWidth).fillMaxHeight().background(colors.hairline))
    }
    Box(Modifier.weight(1f).fillMaxHeight().then(card)) {
      Column(Modifier.fillMaxSize()) {
        top()
        Box(Modifier.weight(1f).fillMaxWidth()) {
          content()
          overlay()
        }
        bottom()
      }
      cover()
    }
  }
}

/**
 * The frame of a phone: the full-bleed surface with the wash fading out under [topBar], the
 * [banners] and [content] below it, and [bottomBar] when there is one. [floating] sits over the
 * content above the bottom bar, such as the Add button and the toasts.
 *
 * [chrome] collapses the top bar while the content scrolls down and brings it back as soon as
 * it scrolls up. The window's safe-area insets pad the bars, and the content no longer sees
 * them.
 */
@Composable
internal fun PhoneScaffold(
  chrome: PhoneChromeState,
  topBar: @Composable () -> Unit,
  banners: @Composable () -> Unit,
  bottomBar: (@Composable () -> Unit)?,
  floating: @Composable BoxScope.() -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val colors = KetchTheme.colors
  val safe = WindowInsets.safeDrawing
  val topInsets = safe.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
  Column(modifier.fillMaxSize().background(colors.surface)) {
    // The wash fades out by the bar's bottom edge, so the list below meets it without a seam.
    Box(Modifier.phoneWash(colors).windowInsetsPadding(topInsets)) {
      CollapsingBar(chrome) { topBar() }
    }
    banners()
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .consumeWindowInsets(topInsets)
        .windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal))
        .then(
          if (bottomBar == null) {
            Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Bottom))
          } else {
            Modifier
          },
        )
        .nestedScroll(chrome.nestedScrollConnection),
    ) {
      content()
      floating()
    }
    bottomBar?.invoke()
  }
}

/**
 * Draws the window's canvas: the 160° wash over the canvas color, and the warm ember glow of
 * [emberRadius] from the bottom-left corner, where the sidebar ends.
 */
internal fun Modifier.canvasWash(colors: KetchColors, emberRadius: Dp = EmberRadius): Modifier =
  drawWithCache {
    val wash = colors.wash
    val angle = WASH_ANGLE_DEGREES * PI / 180
    val direction = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
    // As in CSS, the gradient line is as long as the box is deep along it.
    val length = abs(size.width * direction.x) + abs(size.height * direction.y)
    val center = Offset(size.width / 2, size.height / 2)
    val linear = Brush.linearGradient(
      0f to wash.start,
      0.5f to wash.mid,
      1f to wash.end,
      start = center - direction * (length / 2),
      end = center + direction * (length / 2),
    )
    val ember = Brush.radialGradient(
      colors = listOf(wash.ember, wash.ember.copy(alpha = 0f)),
      center = Offset(0f, size.height),
      radius = emberRadius.toPx(),
    )
    onDrawBehind {
      drawRect(colors.canvas)
      drawRect(linear)
      drawRect(ember)
    }
  }

/** The wash's first stop fading out into the phone's surface down this element's height. */
private fun Modifier.phoneWash(colors: KetchColors): Modifier = drawWithCache {
  val fade = Brush.verticalGradient(colors = listOf(colors.wash.start, colors.surface))
  onDrawBehind { drawRect(fade) }
}

private const val WASH_ANGLE_DEGREES = 160.0

private val HairlineWidth = 1.dp

// Radius of the wash's ember glow.
private val EmberRadius = 520.dp
