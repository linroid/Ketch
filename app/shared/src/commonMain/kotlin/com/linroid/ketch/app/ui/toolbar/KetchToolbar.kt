package com.linroid.ketch.app.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.formatBytes

/**
 * Top toolbar for the desktop hero view.
 *
 * Layout (left → right):
 *  - View title
 *  - Bandwidth readout (live MB/s + horizontal cap meter)
 *  - Search hint pill
 *  - AI discovery icon button
 *  - Batch action buttons (pause/resume/clear all)
 *  - Primary "Add download" button
 *
 * 60dp tall, no bottom border — relies on tonal contrast vs. the body.
 */
@Composable
fun KetchToolbar(
  title: String,
  bandwidthBytesPerSec: Long,
  globalCapBytesPerSec: Long?,
  hasActiveDownloads: Boolean,
  hasPausedDownloads: Boolean,
  hasCompletedDownloads: Boolean,
  onPauseAll: () -> Unit,
  onResumeAll: () -> Unit,
  onClearCompleted: () -> Unit,
  onAiDiscoverClick: () -> Unit,
  onAddClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography

  Row(
    modifier = modifier
      .fillMaxWidth()
      .height(60.dp)
      .background(colors.surface)
      .padding(horizontal = 20.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text(
      text = title,
      style = type.displayMedium.copy(fontWeight = FontWeight.SemiBold),
      color = colors.onBackground,
    )

    Spacer(Modifier.weight(1f))

    BandwidthReadout(
      bandwidthBytesPerSec = bandwidthBytesPerSec,
      globalCapBytesPerSec = globalCapBytesPerSec,
    )

    SearchHint()

    KetchIconButton(icon = KetchIcon.Ai, onClick = onAiDiscoverClick)

    BatchActionBar(
      hasActiveDownloads = hasActiveDownloads,
      hasPausedDownloads = hasPausedDownloads,
      hasCompletedDownloads = hasCompletedDownloads,
      onPauseAll = onPauseAll,
      onResumeAll = onResumeAll,
      onClearCompleted = onClearCompleted,
    )

    KetchButton(
      text = "Add download",
      onClick = onAddClick,
      leadingIcon = KetchIcon.Plus,
    )
  }
}

@Composable
private fun BandwidthReadout(
  bandwidthBytesPerSec: Long,
  globalCapBytesPerSec: Long?,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val capLabel = globalCapBytesPerSec?.let { "/ ${formatBytes(it)}/s" } ?: "/ ∞"
  val capFraction = if (globalCapBytesPerSec != null && globalCapBytesPerSec > 0) {
    (bandwidthBytesPerSec.toFloat() / globalCapBytesPerSec).coerceIn(0f, 1f)
  } else {
    0f
  }
  val nearCap = capFraction > 0.9f
  val fillColor = if (nearCap) colors.warning else colors.primary

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    modifier = Modifier
      .height(36.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(colors.background)
      .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
      .padding(horizontal = 12.dp),
  ) {
    KetchIconImage(icon = KetchIcon.Speed, size = 13.dp, tint = colors.onSurfaceVariant)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "${formatBytes(bandwidthBytesPerSec)}/s",
          style = type.monoSmall.copy(fontWeight = FontWeight.SemiBold),
          color = colors.onBackground,
        )
        Spacer(Modifier.width(4.dp))
        Text(
          text = capLabel,
          style = type.monoXSmall,
          color = colors.onSurfaceDim,
        )
      }
      Box(
        Modifier
          .width(110.dp)
          .height(3.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(colors.outlineVariant),
      ) {
        if (capFraction > 0f) {
          Box(
            Modifier
              .fillMaxWidth(capFraction)
              .fillMaxHeight()
              .background(fillColor),
          )
        }
      }
    }
  }
}

@Composable
private fun SearchHint() {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier
      .height(36.dp)
      .widthIn(min = 220.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(colors.background)
      .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
      .padding(horizontal = 12.dp),
  ) {
    KetchIconImage(icon = KetchIcon.Search, size = 14.dp, tint = colors.onSurfaceDim)
    Text(
      text = "Search downloads…",
      style = type.bodyMedium,
      color = colors.onSurfaceDim,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = "⌘K",
      style = type.monoXSmall,
      color = colors.onSurfaceDim,
      modifier = Modifier
        .clip(RoundedCornerShape(3.dp))
        .background(colors.outlineVariant)
        .padding(horizontal = 4.dp, vertical = 1.dp),
    )
  }
}
