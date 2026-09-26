package com.linroid.ketch.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.platform.DragExitEffect
import com.linroid.ketch.app.platform.DroppedFile
import com.linroid.ketch.app.platform.rememberFileDropReader
import com.linroid.ketch.app.theme.KetchTheme

/**
 * Accepts files dragged in from other applications anywhere over [content],
 * showing a drop hint while a drag hovers. A [compact] hint fits small areas
 * such as a dialog body.
 */
@Composable
internal fun FileDropTarget(
  onDrop: (List<DroppedFile>) -> Unit,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  content: @Composable BoxScope.() -> Unit,
) {
  val reader = rememberFileDropReader()
  val currentOnDrop by rememberUpdatedState(onDrop)
  var hovering by remember { mutableStateOf(false) }
  val target = remember(reader) {
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
        if (files.isEmpty()) return false
        currentOnDrop(files)
        return true
      }
    }
  }
  DragExitEffect { hovering = false }
  Box(
    modifier = modifier.dragAndDropTarget(
      shouldStartDragAndDrop = { reader.hasFiles(it) },
      target = target,
    ),
  ) {
    content()
    AnimatedVisibility(
      visible = hovering,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier.matchParentSize(),
    ) {
      FileDropHint(compact)
    }
  }
}

@Composable
private fun FileDropHint(compact: Boolean) {
  val colors = KetchTheme.colors
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(colors.background.copy(alpha = 0.96f))
      .padding(if (compact) 4.dp else 16.dp)
      .drawBehind {
        val stroke = 2.dp.toPx()
        val dash = 8.dp.toPx()
        drawRoundRect(
          color = colors.primary,
          cornerRadius = CornerRadius(16.dp.toPx()),
          style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
          ),
        )
      },
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier.padding(if (compact) 12.dp else 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      val iconSize = if (compact) 40.dp else 64.dp
      Box(
        modifier = Modifier
          .size(iconSize)
          .clip(CircleShape)
          .background(colors.primaryContainer),
        contentAlignment = Alignment.Center,
      ) {
        KetchIconImage(icon = KetchIcon.Active, size = iconSize / 2, tint = colors.primary)
      }
      if (!compact) Spacer(Modifier.height(4.dp))
      Text(
        text = "Drop a .torrent file",
        style = KetchTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        color = colors.onBackground,
        textAlign = TextAlign.Center,
      )
      if (!compact) {
        Text(
          text = "Choose files and options before the download starts.",
          style = KetchTheme.typography.bodyMedium,
          color = colors.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}
