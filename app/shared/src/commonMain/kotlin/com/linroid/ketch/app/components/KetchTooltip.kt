package com.linroid.ketch.app.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Shows [text] in a small inverse bubble under [content] after the pointer rests on it for
 * half a second, or while it is long-pressed on touch screens.
 *
 * @param shortcut chord that runs the same action, such as "⇧⌘P", shown dimmed after [text].
 * @param enabled whether the tooltip shows at all.
 */
@Composable
fun KetchTooltip(
  text: String,
  modifier: Modifier = Modifier,
  shortcut: String? = null,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  val gap = with(LocalDensity.current) { TooltipGap.roundToPx() }
  HoverPopup(
    enabled = enabled && text.isNotEmpty(),
    delayMillis = TOOLTIP_DELAY_MILLIS,
    positionProvider = { remember(gap) { TooltipPositionProvider(gap) } },
    popup = { KetchTooltipBubble(text, shortcut) },
    modifier = modifier,
    content = content,
  )
}

/**
 * Shows [card], a raised surface of details, under [content] where the pointer rests on it for
 * a moment, or while it is long-pressed on touch screens. Unlike [KetchTooltip], it opens at the
 * pointer rather than centered, as [content] may be as wide as a table row, and it never takes
 * clicks or focus: what it shows must also be reachable another way.
 *
 * @param enabled whether hovering shows the card, such as off while a control inside [content]
 *   is hovered, which shows its own tooltip.
 * @param open shows the card whatever the pointer does, such as while a control inside [content]
 *   has keyboard focus, so the details are not for pointers only.
 */
@Composable
fun KetchHoverCard(
  card: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  open: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colors = KetchTheme.colors
  val gap = with(LocalDensity.current) { TooltipGap.roundToPx() }
  HoverPopup(
    enabled = enabled,
    open = open,
    delayMillis = HOVER_CARD_DELAY_MILLIS,
    positionProvider = { pointerX ->
      remember(gap, pointerX) { PointerPositionProvider(gap, pointerX) }
    },
    popup = {
      Box(
        Modifier
          .widthIn(max = HoverCardMaxWidth)
          .ketchSurface(
            KetchElevationLevel.E3,
            KetchTheme.shapes.menu,
            colors.surfaceRaised,
            colors.hairline,
          )
          .padding(HoverCardPadding),
      ) {
        card()
      }
    },
    modifier = modifier,
    content = content,
  )
}

/**
 * [content], with [popup] shown after the pointer rests on it for [delayMillis] or while it is
 * long-pressed on touch screens, or while [open], placed by the provider [positionProvider] makes from where the
 * pointer was, in pixels from [content]'s left edge, as the popup opened.
 */
@Composable
private fun HoverPopup(
  enabled: Boolean,
  delayMillis: Long,
  open: Boolean = false,
  positionProvider: @Composable (pointerX: Int) -> PopupPositionProvider,
  popup: @Composable () -> Unit,
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  var hovered by remember { mutableStateOf(false) }
  var suppressed by remember { mutableStateOf(false) }
  var hoverShown by remember { mutableStateOf(false) }
  var touchShown by remember { mutableStateOf(false) }
  // Where the pointer is, read only as the popup opens, so moving never recomposes.
  val pointer = remember { PointerX() }
  var openedAt by remember { mutableIntStateOf(0) }
  LaunchedEffect(hovered, suppressed) {
    hoverShown = false
    if (hovered && !suppressed) {
      delay(delayMillis)
      openedAt = pointer.x
      hoverShown = true
    }
  }
  Box(
    propagateMinConstraints = true,
    modifier = modifier
      .pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.firstOrNull()?.let { pointer.x = it.position.x.roundToInt() }
            when (event.type) {
              PointerEventType.Enter -> hovered = true
              PointerEventType.Exit -> {
                hovered = false
                suppressed = false
              }
              PointerEventType.Press -> suppressed = true
            }
          }
        }
      }
      .pointerInput(Unit) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          if (down.type != PointerType.Touch) return@awaitEachGesture
          var released = false
          withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation(PointerEventPass.Final)
            released = true
          }
          if (released) return@awaitEachGesture
          openedAt = down.position.x.roundToInt()
          touchShown = true
          // The release would also click the control under the finger.
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) {
              event.changes.forEach { it.consume() }
              break
            }
          }
          touchShown = false
        }
      },
  ) {
    content()
    if (open || enabled && (touchShown || hoverShown && !suppressed)) {
      Popup(
        popupPositionProvider = positionProvider(openedAt),
        properties = PopupProperties(focusable = false),
      ) {
        popup()
      }
    }
  }
}

private class PointerX {
  var x: Int = 0
}

/**
 * [content] under a [KetchTooltip] of [text], or on its own when [text] is `null`. [modifier]
 * goes to the outermost of the two, and [content] receives what it should apply.
 */
@Composable
internal fun OptionalTooltip(
  text: String?,
  modifier: Modifier = Modifier,
  shortcut: String? = null,
  content: @Composable (Modifier) -> Unit,
) {
  if (text == null) {
    content(modifier)
  } else {
    KetchTooltip(text = text, modifier = modifier, shortcut = shortcut) { content(Modifier) }
  }
}

/** The bubble of a [KetchTooltip], without the popup that positions it. */
@Composable
internal fun KetchTooltipBubble(text: String, shortcut: String?, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .widthIn(max = TooltipMaxWidth)
      .ketchSurface(KetchElevationLevel.E3, KetchTheme.shapes.sm, colors.inverseSurface)
      .padding(horizontal = TooltipPaddingH, vertical = TooltipPaddingV),
  ) {
    // A long text wraps instead of squeezing the shortcut after it.
    Text(
      text = text,
      style = type.caption,
      color = colors.inverseOnSurface,
      modifier = Modifier.weight(1f, fill = false),
    )
    if (shortcut != null) {
      Spacer(Modifier.width(KetchTheme.spacing.s3))
      Text(
        text = shortcut,
        style = type.caption,
        color = colors.inverseOnSurface.copy(alpha = SHORTCUT_ALPHA),
      )
    }
  }
}

/** Centers the tooltip under its anchor, or above it when there is no room below. */
private class TooltipPositionProvider(private val gap: Int) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
    val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxX)
    val below = anchorBounds.bottom + gap
    val y = if (below + popupContentSize.height <= windowSize.height) {
      below
    } else {
      anchorBounds.top - gap - popupContentSize.height
    }
    return IntOffset(x, y.coerceAtLeast(0))
  }
}

/**
 * Opens a [KetchHoverCard] under its anchor, starting a little left of [pointerX] (pixels from
 * the anchor's left edge), or above the anchor when there is no room below.
 */
private class PointerPositionProvider(
  private val gap: Int,
  private val pointerX: Int,
) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
    val x = (anchorBounds.left + pointerX - gap * 2).coerceIn(0, maxX)
    val below = anchorBounds.bottom + gap
    val y = if (below + popupContentSize.height <= windowSize.height) {
      below
    } else {
      anchorBounds.top - gap - popupContentSize.height
    }
    return IntOffset(x, y.coerceAtLeast(0))
  }
}

private const val TOOLTIP_DELAY_MILLIS = 500L
private const val HOVER_CARD_DELAY_MILLIS = 600L
private const val SHORTCUT_ALPHA = 0.6f
private val TooltipGap = 6.dp
private val TooltipPaddingH = 10.dp
private val TooltipPaddingV = 6.dp
private val TooltipMaxWidth = 320.dp
private val HoverCardMaxWidth = 360.dp
private val HoverCardPadding = 12.dp
