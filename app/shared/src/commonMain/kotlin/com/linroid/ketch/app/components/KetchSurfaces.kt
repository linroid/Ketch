package com.linroid.ketch.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchTheme
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Color of a [KetchBadge]. */
enum class KetchBadgeTone {
  /** Sunken fill with secondary text. */
  Neutral,

  /** The completed status color. */
  Success,

  /** The paused status color, for conditions that need a look. */
  Warning,

  /** The failed status color on its soft fill. */
  Danger,

  /** The soft accent fill. */
  Accent,
}

/** Short status label in a 20 dp pill, such as "Offline". */
@Composable
fun KetchBadge(
  text: String,
  tone: KetchBadgeTone = KetchBadgeTone.Neutral,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val (fill, ink) = when (tone) {
    KetchBadgeTone.Neutral -> colors.surfaceSunken to colors.textSecondary
    KetchBadgeTone.Success -> colors.status.completed.soft to colors.status.completed.color
    KetchBadgeTone.Warning -> colors.status.paused.soft to colors.status.paused.color
    KetchBadgeTone.Danger -> colors.status.failed.soft to colors.status.failed.color
    KetchBadgeTone.Accent -> colors.accentSoft to colors.accentText
  }
  BadgePill(text = text, fill = fill, ink = ink, modifier = modifier)
}

/**
 * Count pill, such as the number of tasks in a tab.
 *
 * @param alert whether the count is of failures; it then reads white on `dangerFill`, never on
 *   the failed status color, which is light in the dark theme.
 */
@Composable
fun KetchCountBadge(
  count: Int,
  modifier: Modifier = Modifier,
  alert: Boolean = false,
) {
  val colors = KetchTheme.colors
  BadgePill(
    text = count.toString(),
    fill = if (alert) colors.dangerFill else colors.surfaceSunken,
    ink = if (alert) Color.White else colors.textSecondary,
    modifier = modifier,
  )
}

@Composable
private fun BadgePill(text: String, fill: Color, ink: Color, modifier: Modifier) {
  Box(
    modifier = modifier
      .heightIn(min = BadgeHeight)
      .widthIn(min = BadgeHeight)
      .background(fill, KetchTheme.shapes.badge)
      .padding(horizontal = KetchTheme.spacing.s2),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, style = KetchTheme.typography.numeralS, color = ink, maxLines = 1)
  }
}

/** Linear progress track; tasks move to `LaneStrip`, which shows their connections too. */
@Composable
fun KetchProgressBar(
  progress: Float,
  modifier: Modifier = Modifier,
  fillColor: Color = KetchTheme.colors.accent,
) {
  val shape = KetchTheme.shapes.progressBar
  val fraction by animateFloatAsState(
    targetValue = progress.coerceIn(0f, 1f),
    animationSpec = KetchTheme.motion.progressSpring,
  )
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(ProgressHeight)
      .background(KetchTheme.colors.surfaceSunken, shape),
  ) {
    Box(
      Modifier
        .fillMaxWidth(fraction)
        .fillMaxHeight()
        .background(fillColor, shape),
    )
  }
}

/**
 * Sidebar destination. The selected item sits on the translucent `sidebarItemSelected` pill,
 * never on the accent.
 *
 * @param trailing optional marker after the label, e.g. an unsaved dot.
 */
@Composable
fun KetchSidebarItem(
  label: String,
  icon: KetchIcon,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  trailing: (@Composable () -> Unit)? = null,
) {
  val colors = KetchTheme.colors
  val density = KetchTheme.density
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.sidebarItem
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val fill by animateColorAsState(
    targetValue = when {
      selected -> colors.sidebarItemSelected
      hovered -> colors.sidebarItemHover
      // Fades by alpha alone: Color.Transparent is transparent black, which flashes grey.
      else -> colors.sidebarItemHover.copy(alpha = 0f)
    },
    animationSpec = tween(KetchTheme.motion.micro),
  )
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.iconLabelGap),
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = spacing.s2, vertical = spacing.s0_5)
      .focusRing(focus.visible, shape, colors.focusRing)
      .height(density.sidebarItem)
      .background(fill, shape)
      .trackFocusVisibility(focus)
      .selectable(
        selected = selected,
        interactionSource = interactions,
        indication = null,
        role = Role.Tab,
        onClick = onClick,
      )
      .padding(horizontal = spacing.s2),
  ) {
    KetchIconImage(
      icon = icon,
      size = density.navGlyph,
      tint = if (selected) colors.accentText else colors.textSecondary,
    )
    Text(
      text = label,
      style = KetchTheme.typography.label,
      fontWeight = if (selected) FontWeight.SemiBold else null,
      color = if (selected) colors.textPrimary else colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    trailing?.invoke()
  }
}

/**
 * Small indeterminate spinner, shown by a control while its command is in flight and by a
 * loading button in place of its icon.
 */
@Composable
fun KetchSpinner(
  modifier: Modifier = Modifier,
  size: Dp = PendingSpinnerSize,
  color: Color = KetchTheme.colors.textTertiary,
) {
  CircularProgressIndicator(
    modifier = modifier.size(size),
    color = color,
    strokeWidth = SpinnerStroke,
    trackColor = Color.Transparent,
  )
}

/** Sizes of dialogs, which `AdaptiveModal` draws. */
object KetchDialogDefaults {
  /** Widest a dialog grows. */
  val MaxWidth: Dp = 520.dp

  /** Widest the intake sheet grows. */
  val IntakeMaxWidth: Dp = 640.dp

  /** Narrowest a dialog gets while the window has room. */
  val MinWidth: Dp = 320.dp
}

/** Hover or press overlay of a control: 8% or 12% black, or white in the dark theme. */
internal fun KetchColors.interactionOverlay(hovered: Boolean, pressed: Boolean): Color {
  val alpha = when {
    pressed -> PRESS_OVERLAY_ALPHA
    hovered -> HOVER_OVERLAY_ALPHA
    else -> 0f
  }
  return (if (isDark) Color.White else Color.Black).copy(alpha = alpha)
}

/** Color of the keyboard focus ring. */
internal val KetchColors.focusRing: Color get() = accent.copy(alpha = FOCUS_RING_ALPHA)

/** The animated hover and press overlay of a control using [interactions]. */
@Composable
internal fun rememberInteractionOverlay(
  interactions: MutableInteractionSource,
  enabled: Boolean = true,
): Color {
  val hovered by interactions.collectIsHoveredAsState()
  val pressed by interactions.collectIsPressedAsState()
  val overlay by animateColorAsState(
    targetValue = KetchTheme.colors.interactionOverlay(enabled && hovered, enabled && pressed),
    animationSpec = tween(KetchTheme.motion.micro),
  )
  return overlay
}

/** The animated scale of a control that shrinks slightly while pressed. */
@Composable
internal fun rememberPressScale(interactions: MutableInteractionSource): Float {
  val pressed by interactions.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (pressed) PRESSED_SCALE else 1f,
    animationSpec = tween(KetchTheme.motion.micro),
  )
  return scale
}

/**
 * Whether a control's focus came from the keyboard. Clicking a control focuses it too, and
 * only keyboard focus shows a ring, like the web's `:focus-visible`.
 */
@Stable
internal class FocusVisibility {
  var visible: Boolean by mutableStateOf(false)
    private set

  private var lastPointerDown: TimeMark? = null

  fun onPointerDown() {
    lastPointerDown = TimeSource.Monotonic.markNow()
    visible = false
  }

  fun onFocusChanged(focused: Boolean) {
    val fromPointer = lastPointerDown?.let { it.elapsedNow() < PointerFocusWindow } ?: false
    visible = focused && !fromPointer
  }
}

@Composable
internal fun rememberFocusVisibility(): FocusVisibility = remember { FocusVisibility() }

/**
 * Feeds [state] with pointer presses and the focus of the focusable that follows in the chain,
 * such as `clickable`.
 */
internal fun Modifier.trackFocusVisibility(state: FocusVisibility): Modifier = this
  .pointerInput(state) {
    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.type == PointerEventType.Press) state.onPointerDown()
      }
    }
  }
  .onFocusChanged { state.onFocusChanged(it.isFocused) }

/**
 * A click without the platform's indication, for controls that draw their own hover and press
 * overlay from [interactions]. With [focus], it also tracks whether its focus came from the
 * keyboard, as [trackFocusVisibility] does.
 */
internal fun Modifier.ketchClickable(
  interactions: MutableInteractionSource,
  focus: FocusVisibility? = null,
  enabled: Boolean = true,
  role: Role? = Role.Button,
  onClickLabel: String? = null,
  onClick: () -> Unit,
): Modifier = (if (focus != null) trackFocusVisibility(focus) else this)
  .clickable(interactions, indication = null, enabled, onClickLabel, role, onClick)

/**
 * Draws a ring of [width] around the shape, [gap] outside the bounds. Place it before any clip.
 */
internal fun Modifier.focusRing(
  visible: Boolean,
  shape: Shape,
  color: Color,
  gap: Dp = FocusRingGap,
  width: Dp = FocusRingWidth,
): Modifier = drawWithContent {
  drawContent()
  if (!visible) return@drawWithContent
  val stroke = width.toPx()
  val inset = gap.toPx() + stroke / 2
  val outline = shape.createOutline(
    Size(size.width + inset * 2, size.height + inset * 2),
    layoutDirection,
    this,
  )
  translate(-inset, -inset) { drawOutline(outline, color, style = Stroke(stroke)) }
}

internal const val HOVER_OVERLAY_ALPHA = 0.08f
internal const val PRESS_OVERLAY_ALPHA = 0.12f
internal const val PRESSED_SCALE = 0.98f
internal const val DISABLED_ALPHA = 0.4f
internal const val FOCUS_RING_ALPHA = 0.5f
internal val PendingSpinnerSize = 12.dp

private val PointerFocusWindow = 500.milliseconds
private val FocusRingGap = 2.dp
private val FocusRingWidth = 2.dp
private val SpinnerStroke = 1.5.dp
private val BadgeHeight = 20.dp
private val ProgressHeight = 4.dp
