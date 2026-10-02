package com.linroid.ketch.app.ui.pulse

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface

/** Edge of its anchor a [PulsePopover] lines up with. */
internal enum class PopoverAlignment { Start, End }

/**
 * A raised panel opened from the Pulse bar, such as the speed mode or Activity popover.
 *
 * With a pointer it opens above its anchor, the layout it is placed in, lined up with the
 * anchor's [alignment] edge, or below it when there is no room above; Esc and a click outside
 * close it. On touch it is a bottom sheet headed by [title].
 *
 * @param width width of the panel with a pointer; the sheet takes the window's width.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PulsePopover(
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  width: Dp,
  modifier: Modifier = Modifier,
  alignment: PopoverAlignment = PopoverAlignment.Start,
  title: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  if (!expanded) return
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  if (KetchTheme.density == KetchDensity.Comfortable) {
    val sheetState = rememberBottomSheetState(
      initialValue = SheetValue.Hidden,
      enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
      onDismissRequest = onDismissRequest,
      sheetState = sheetState,
      shape = KetchTheme.shapes.sheetTop,
      containerColor = colors.surfaceRaised,
      contentColor = colors.textPrimary,
      tonalElevation = 0.dp,
      scrimColor = colors.scrim,
    ) {
      Column(
        modifier = modifier
          .fillMaxWidth()
          .padding(horizontal = KetchTheme.density.pagePadding)
          .padding(bottom = spacing.s4),
      ) {
        if (title != null) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = spacing.s12),
          ) {
            Text(text = title, style = KetchTheme.typography.titleM, color = colors.textPrimary)
          }
        }
        content()
      }
    }
    return
  }
  val gap = with(LocalDensity.current) { spacing.s2.roundToPx() }
  Popup(
    popupPositionProvider = remember(gap, alignment) { AbovePositionProvider(gap, alignment) },
    onDismissRequest = onDismissRequest,
    properties = PopupProperties(focusable = true),
  ) {
    val focus = remember { FocusRequester() }
    val appear = remember { Animatable(0f) }
    val motion = KetchTheme.motion
    LaunchedEffect(Unit) {
      focus.requestFocus()
      appear.animateTo(1f, tween(motion.short, easing = motion.easeDecelerate))
    }
    Column(
      modifier = modifier
        .graphicsLayer {
          alpha = appear.value
          val scale = APPEAR_SCALE + (1f - APPEAR_SCALE) * appear.value
          scaleX = scale
          scaleY = scale
        }
        .onPreviewKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
            onDismissRequest()
            true
          } else {
            false
          }
        }
        .focusRequester(focus)
        .focusable()
        .width(width)
        .ketchSurface(
          KetchElevationLevel.E3,
          KetchTheme.shapes.menu,
          colors.surfaceRaised,
          colors.hairline
        )
        .padding(spacing.s4),
      content = content,
    )
  }
}

/** Opens above the anchor, or below it when there is no room above, inside the window. */
private class AbovePositionProvider(
  private val gap: Int,
  private val alignment: PopoverAlignment,
) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val ltr = layoutDirection == LayoutDirection.Ltr
    val startEdge = (alignment == PopoverAlignment.Start) == ltr
    val x = if (startEdge) anchorBounds.left else anchorBounds.right - popupContentSize.width
    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
    val above = anchorBounds.top - gap - popupContentSize.height
    val below = anchorBounds.bottom + gap
    val y = when {
      above >= 0 -> above
      below + popupContentSize.height <= windowSize.height -> below
      else -> 0
    }
    return IntOffset(x.coerceIn(0, maxX), y)
  }
}

private const val APPEAR_SCALE = 0.96f
