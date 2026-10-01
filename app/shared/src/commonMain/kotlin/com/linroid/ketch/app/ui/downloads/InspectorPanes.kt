package com.linroid.ketch.app.ui.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.inspector.InspectorPlacement
import com.linroid.ketch.app.ui.inspector.TaskInspector

/**
 * The inspector docked beside the table: a 1 dp divider whose 6 dp handle drags the width
 * between [KetchSpacing.inspectorMinWidth][com.linroid.ketch.app.theme.KetchSpacing] and its
 * maximum, then the inspector on the card's surface.
 *
 * @param width the inspector's width.
 * @param onResize the width while it is dragged.
 * @param onResizeEnd saves the width once the drag ends.
 */
@Composable
internal fun DockedInspector(
  state: AppState,
  taskKey: TaskKey?,
  width: Dp,
  onResize: (Dp) -> Unit,
  onResizeEnd: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val dragged by interactions.collectIsDraggedAsState()
  val line = if (hovered || dragged) colors.borderStrong else colors.hairline
  Row(modifier.fillMaxHeight()) {
    Box(Modifier.width(HairlineWidth).fillMaxHeight().drawBehind { drawRect(line) })
    Box(Modifier.width(width).fillMaxHeight().background(colors.surface)) {
      TaskInspector(state, taskKey, InspectorPlacement.Docked, onClose)
      ResizeHandle(
        width = width,
        interactions = interactions,
        onResize = onResize,
        onResizeEnd = onResizeEnd,
        modifier = Modifier.align(Alignment.CenterStart),
      )
    }
  }
}

/**
 * The 6 dp handle along the inspector's leading edge that drags its width; dragging toward the
 * table widens it.
 */
@Composable
private fun ResizeHandle(
  width: Dp,
  interactions: MutableInteractionSource,
  onResize: (Dp) -> Unit,
  onResizeEnd: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val spacing = KetchTheme.spacing
  val density = LocalDensity.current
  val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  var start by remember { mutableStateOf(width) }
  var moved by remember { mutableStateOf(0f) }
  Box(
    modifier = modifier
      .width(InspectorHandleWidth)
      .fillMaxHeight()
      .hoverable(interactions)
      .draggable(
        state = rememberDraggableState { delta ->
          moved += if (rtl) delta else -delta
          val next = start + with(density) { moved.toDp() }
          onResize(next.coerceIn(spacing.inspectorMinWidth, spacing.inspectorMaxWidth))
        },
        orientation = Orientation.Horizontal,
        interactionSource = interactions,
        onDragStarted = {
          start = width
          moved = 0f
        },
        onDragStopped = { onResizeEnd() },
      )
      .semantics { contentDescription = "Resize the inspector" },
  )
}

/**
 * The inspector floating over the list on medium cards: a raised card inset 8 dp from the top,
 * end and bottom, that slides in from 24 dp to the side. Esc closes it.
 */
@Composable
internal fun BoxScope.OverlayInspector(
  state: AppState,
  taskKey: TaskKey?,
  visible: Boolean,
  onClose: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val motion = KetchTheme.motion
  val shift = with(LocalDensity.current) { spacing.s6.roundToPx() }
  AnimatedVisibility(
    visible = visible,
    enter = slideInHorizontally(tween(motion.medium, easing = motion.easeDecelerate)) { shift } +
      fadeIn(tween(motion.medium, easing = motion.easeDecelerate)),
    exit = slideOutHorizontally(tween(motion.longExit, easing = motion.easeAccelerate)) { shift } +
      fadeOut(tween(motion.longExit, easing = motion.easeAccelerate)),
    modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight(),
  ) {
    Box(
      modifier = Modifier
        .padding(spacing.s2)
        .width(OverlayInspectorWidth)
        .fillMaxHeight()
        .ketchSurface(
          level = KetchElevationLevel.E3,
          shape = KetchTheme.shapes.lg,
          fill = colors.surfaceRaised,
          border = colors.hairline,
        )
        .onKeyEvent { event ->
          val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
          if (escape) onClose()
          escape
        },
    ) {
      TaskInspector(state, taskKey, InspectorPlacement.Overlay, onClose)
    }
  }
}

/**
 * The inspector in a bottom sheet on phones. It opens half way, showing the header, actions and
 * Controls, and drags up for the tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetInspector(state: AppState, taskKey: TaskKey, onClose: () -> Unit) {
  val colors = KetchTheme.colors
  val sheet = rememberBottomSheetState(initialValue = SheetValue.Hidden)
  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = sheet,
    shape = KetchTheme.shapes.sheetTop,
    containerColor = colors.surfaceRaised,
    contentColor = colors.textPrimary,
    scrimColor = colors.scrim,
  ) {
    Box(Modifier.fillMaxSize()) {
      TaskInspector(state, taskKey, InspectorPlacement.Sheet, onClose)
    }
  }
}

/** Width of the inspector floating over the list. */
private val OverlayInspectorWidth: Dp = 340.dp

/** Width of the handle that drags the docked inspector's width. */
private val InspectorHandleWidth: Dp = 6.dp
