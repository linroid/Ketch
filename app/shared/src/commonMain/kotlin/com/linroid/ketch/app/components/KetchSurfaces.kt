package com.linroid.ketch.app.components

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme

/** Surface container — 10dp card with 1dp hairline border. */
@Composable
fun KetchCard(
  modifier: Modifier = Modifier,
  padding: Dp = 16.dp,
  content: @Composable () -> Unit,
) {
  val shape = RoundedCornerShape(10.dp)
  Box(
    modifier = modifier
      .clip(shape)
      .background(KetchTheme.colors.surface)
      .border(1.dp, KetchTheme.colors.outline, shape)
      .padding(padding),
  ) { content() }
}

enum class KetchBadgeTone { Neutral, Success, Warning, Danger, Accent }

/** Small chip for statuses, counts, eyebrows. */
@Composable
fun KetchBadge(
  text: String,
  tone: KetchBadgeTone = KetchBadgeTone.Neutral,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val (bg, fg) = when (tone) {
    KetchBadgeTone.Neutral -> colors.outlineVariant to colors.onSurfaceDim
    KetchBadgeTone.Success -> colors.success.copy(alpha = 0.12f) to colors.success
    KetchBadgeTone.Warning -> colors.warning.copy(alpha = 0.16f) to colors.warning
    KetchBadgeTone.Danger  -> colors.error.copy(alpha = 0.14f)   to colors.error
    KetchBadgeTone.Accent  -> colors.primaryContainer            to colors.onPrimaryContainer
  }
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(3.dp))
      .background(bg)
      .padding(horizontal = 6.dp, vertical = 2.dp),
  ) {
    Text(text, style = KetchTheme.typography.monoXSmall.copy(color = fg))
  }
}

/** Linear progress bar — 3dp tall, 2dp radius. */
@Composable
fun KetchProgressBar(
  progress: Float,
  modifier: Modifier = Modifier,
  trackColor: Color = KetchTheme.colors.outlineVariant,
  fillColor: Color = KetchTheme.colors.primary,
) {
  val shape = RoundedCornerShape(2.dp)
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(3.dp)
      .clip(shape)
      .background(trackColor),
  ) {
    Box(
      Modifier
        .fillMaxWidth(progress.coerceIn(0f, 1f))
        .height(3.dp)
        .background(fillColor),
    )
  }
}

/** Sidebar destination with selected, hover, and keyboard-focus feedback. */
@Composable
fun KetchSidebarItem(
  label: String,
  icon: KetchIcon,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  count: Int? = null,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val shape = RoundedCornerShape(8.dp)
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focused by interactions.collectIsFocusedAsState()

  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 2.dp)
      .height(44.dp)
      .clip(shape)
      .background(if (selected) colors.primaryContainer else if (hovered) colors.surfaceHover else Color.Transparent)
      .let { if (focused) it.border(2.dp, colors.primary, shape) else it }
      .hoverable(interactions)
      .selectable(selected = selected, role = Role.Tab, interactionSource = interactions,
        indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight()
        .padding(horizontal = 14.dp),
    ) {
      KetchIconImage(
        icon = icon,
        size = 17.dp,
        tint = if (selected) colors.primary else colors.onSurfaceVariant,
      )
      Text(
        text = label,
        style = type.bodyLarge.copy(
          color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
          fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        ),
        modifier = Modifier.weight(1f),
      )
      if (count != null) {
        Spacer(Modifier.width(4.dp))
        Text(
          text = count.toString(),
          style = type.monoXSmall.copy(
            color = if (selected) colors.onSurfaceVariant else colors.onSurfaceDim,
          ),
        )
      }
    }
  }
}
