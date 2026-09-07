package com.linroid.ketch.app.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.formatBytes

/** Responsive toolbar with search and download actions. */
@Composable
fun KetchToolbar(
  title: String,
  downloadCount: Int,
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  bandwidthBytesPerSec: Long,
  globalCapBytesPerSec: Long?,
  hasActiveDownloads: Boolean,
  hasPausedDownloads: Boolean,
  hasCompletedDownloads: Boolean,
  onPauseAll: () -> Unit,
  onResumeAll: () -> Unit,
  onClearCompleted: () -> Unit,
  onAddClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography

  BoxWithConstraints(modifier = modifier.fillMaxWidth().background(colors.background)) {
    val wide = maxWidth >= 1000.dp
    Column {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 88.dp)
          .background(colors.background)
          .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = title,
            style = type.displayMedium,
            color = colors.onBackground,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
          )
          Text(
            text = "$downloadCount ${if (downloadCount == 1) "download" else "downloads"}",
            style = type.bodySmall,
            color = colors.onSurfaceVariant,
          )
        }

        if (wide) BandwidthReadout(
          bandwidthBytesPerSec = bandwidthBytesPerSec,
          globalCapBytesPerSec = globalCapBytesPerSec,
        )

        if (wide) KetchTextField(
          value = searchQuery, onValueChange = onSearchQueryChange,
          placeholder = "Search downloads…", leadingIcon = KetchIcon.Search,
          modifier = Modifier.width(240.dp),
        )

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
      if (!wide) KetchTextField(
        value = searchQuery, onValueChange = onSearchQueryChange,
        placeholder = "Search downloads…", leadingIcon = KetchIcon.Search,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
      )
    }
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
