package com.linroid.ketch.app.ui.devices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.platform.DragExitEffect
import com.linroid.ketch.app.platform.DroppedFile
import com.linroid.ketch.app.platform.rememberFileDropReader
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.launch

/**
 * Takes links, magnets, `.torrent` files and lists of links dragged in from other applications
 * over [content], such as a device card, and shows "[label]" while a drag hovers; drops that
 * reach it never reach the window's own drop target. Takes nothing while not [enabled].
 *
 * @param shape the outline of [content], which the hover overlay follows.
 */
@Composable
internal fun DeviceDropTarget(
  label: String,
  enabled: Boolean,
  shape: Shape,
  onDropFiles: (List<DroppedFile>) -> Unit,
  onDropText: (String) -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  val reader = rememberFileDropReader()
  val scope = rememberCoroutineScope()
  val currentOnDropFiles by rememberUpdatedState(onDropFiles)
  val currentOnDropText by rememberUpdatedState(onDropText)
  val currentEnabled by rememberUpdatedState(enabled)
  var hovering by remember { mutableStateOf(false) }
  val target = remember(reader, scope) {
    object : DragAndDropTarget {
      override fun onEntered(event: DragAndDropEvent) {
        hovering = true
      }

      override fun onExited(event: DragAndDropEvent) {
        hovering = false
      }

      override fun onEnded(event: DragAndDropEvent) {
        hovering = false
      }

      override fun onDrop(event: DragAndDropEvent): Boolean {
        hovering = false
        val files = reader.files(event)
        if (files.isNotEmpty()) {
          currentOnDropFiles(files)
          return true
        }
        val text = reader.text(event) ?: return false
        scope.launch { currentOnDropText(text()) }
        return true
      }
    }
  }
  DragExitEffect { hovering = false }
  val fade = tween<Float>(KetchTheme.motion.short)
  Box(
    modifier = modifier.dragAndDropTarget(
      shouldStartDragAndDrop = { currentEnabled && reader.accepts(it) },
      target = target,
    ),
  ) {
    content()
    AnimatedVisibility(
      visible = hovering,
      enter = fadeIn(fade),
      exit = fadeOut(fade),
      modifier = Modifier.matchParentSize(),
    ) {
      DeviceDropOverlay(label, shape)
    }
  }
}

/** The berth a device card shows while a drag hovers it. */
@Composable
private fun DeviceDropOverlay(label: String, shape: Shape) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(spacing.s2, Alignment.CenterVertically),
    modifier = Modifier
      .fillMaxSize()
      .background(colors.accentSoft, shape)
      .dashedBorder(colors.accent, shape)
      .padding(spacing.s5),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(spacing.s10).background(colors.accent, KetchTheme.shapes.full),
    ) {
      KetchIconImage(KetchIcon.Drop, size = spacing.s5, tint = colors.onAccent)
    }
    Text(
      text = label,
      style = KetchTheme.typography.bodyStrong,
      color = colors.textPrimary,
      textAlign = TextAlign.Center,
    )
  }
}

/** A dashed [color] line just inside the edge of [shape]. */
internal fun Modifier.dashedBorder(color: Color, shape: Shape): Modifier = drawWithCache {
  val width = DashWidth.toPx()
  val dash = DashLength.toPx()
  val dashes = PathEffect.dashPathEffect(floatArrayOf(dash, dash))
  val stroke = Stroke(width = width, pathEffect = dashes)
  val inset = Size(size.width - width, size.height - width)
  val outline = shape.createOutline(inset, layoutDirection, this)
  onDrawBehind {
    translate(width / 2, width / 2) { drawOutline(outline, color, style = stroke) }
  }
}

private val DashWidth: Dp = 1.5.dp
private val DashLength: Dp = 6.dp
