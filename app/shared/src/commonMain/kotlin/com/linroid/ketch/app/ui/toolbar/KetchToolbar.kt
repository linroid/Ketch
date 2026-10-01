package com.linroid.ketch.app.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.downloads.KetchLayoutInfo
import com.linroid.ketch.app.util.formatBytes

/**
 * Header of the Downloads page: the title and count, the speed readout and search on wide
 * windows, the bulk actions and Add.
 *
 * @param downloadCount downloads listed under the current tab and search.
 * @param globalCapBytesPerSec the device's speed limit, or `null` when unlimited.
 * @param bulkActions what the bulk actions can act on.
 */
@Composable
fun KetchToolbar(
  title: String,
  downloadCount: Int,
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  bandwidthBytesPerSec: Long,
  globalCapBytesPerSec: Long?,
  bulkActions: BulkActions,
  onPauseAll: () -> Unit,
  onResumeAll: () -> Unit,
  onRetryFailed: () -> Unit,
  onClearCompleted: () -> Unit,
  onAddClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing

  BoxWithConstraints(modifier = modifier.fillMaxWidth().background(colors.canvas)) {
    val wide = maxWidth >= KetchLayoutInfo.ExpandedWidth
    Column {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = spacing.s6, vertical = spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
          Text(
            text = title,
            style = type.pageTitle,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "$downloadCount ${if (downloadCount == 1) "download" else "downloads"}",
            style = type.caption,
            color = colors.textSecondary,
          )
        }

        if (wide) {
          BandwidthReadout(
            bandwidthBytesPerSec = bandwidthBytesPerSec,
            globalCapBytesPerSec = globalCapBytesPerSec,
          )
          KetchTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = "Search downloads…",
            leadingIcon = KetchIcon.Search,
            modifier = Modifier.width(SearchFieldWidth),
          )
        }

        BatchActionBar(
          actions = bulkActions,
          onPauseAll = onPauseAll,
          onResumeAll = onResumeAll,
          onRetryFailed = onRetryFailed,
          onClearCompleted = onClearCompleted,
        )

        KetchButton(text = "Add download", onClick = onAddClick, leadingIcon = KetchIcon.Plus)
      }
      if (!wide) {
        KetchTextField(
          value = searchQuery,
          onValueChange = onSearchQueryChange,
          placeholder = "Search downloads…",
          leadingIcon = KetchIcon.Search,
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.s6, end = spacing.s6, bottom = spacing.s4),
        )
      }
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
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.sm
  val capLabel = globalCapBytesPerSec?.let { "/ ${formatBytes(it)}/s" } ?: "/ ∞"
  val capFraction = if (globalCapBytesPerSec != null && globalCapBytesPerSec > 0) {
    (bandwidthBytesPerSec.toFloat() / globalCapBytesPerSec).coerceIn(0f, 1f)
  } else {
    0f
  }
  val nearCap = capFraction > NEAR_CAP
  val fillColor = if (nearCap) colors.status.paused.color else colors.accent

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .height(KetchTheme.density.buttonLarge)
      .ketchSurface(KetchElevationLevel.E0, shape, colors.canvas, border = colors.borderStrong)
      .padding(horizontal = spacing.s3),
  ) {
    KetchIconImage(
      icon = KetchIcon.Speed,
      size = KetchTheme.density.controlGlyph,
      tint = colors.textSecondary,
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s1),
      ) {
        Text(
          text = "${formatBytes(bandwidthBytesPerSec)}/s",
          style = type.numeral,
          color = colors.textPrimary,
        )
        Text(text = capLabel, style = type.numeralS, color = colors.textTertiary)
      }
      Box(
        Modifier
          .width(CapBarWidth)
          .height(spacing.s0_5)
          .background(colors.hairline, KetchTheme.shapes.progressBar),
      ) {
        if (capFraction > 0f) {
          Box(
            Modifier
              .fillMaxWidth(capFraction)
              .fillMaxHeight()
              .background(fillColor, KetchTheme.shapes.progressBar),
          )
        }
      }
    }
  }
}

/** Share of the cap from which the readout turns amber. */
private const val NEAR_CAP = 0.9f

/** Width of the search field on wide windows (§4.7.1). */
private val SearchFieldWidth = 240.dp

/** Width of the readout's cap bar. */
private val CapBarWidth = 112.dp
