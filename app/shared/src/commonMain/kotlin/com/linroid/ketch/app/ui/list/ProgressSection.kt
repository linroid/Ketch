package com.linroid.ketch.app.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.KetchProgressBar
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.LocalDownloadStateColors
import com.linroid.ketch.app.util.formatBytes
import com.linroid.ketch.app.util.formatEta

@Composable
fun ProgressSection(
  state: DownloadState,
  speedLimit: SpeedLimit,
) {
  val stateColors = LocalDownloadStateColors.current
  val type = KetchTheme.typography
  val colors = KetchTheme.colors

  when (state) {
    is DownloadState.Downloading -> {
      val progress = state.progress
      val pct = (progress.percent * 100).coerceIn(0f, 100f)
      val active = stateColors.downloading
      KetchProgressBar(
        progress = progress.percent,
        modifier = Modifier.fillMaxWidth(),
        fillColor = active.foreground,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = buildString {
            append("${pct.toInt()}%")
            append(" · ${formatBytes(progress.downloadedBytes)}")
            append(" / ${formatBytes(progress.totalBytes)}")
          },
          style = type.monoSmall,
          color = colors.onSurfaceVariant,
        )
        Text(
          text = buildString {
            if (progress.bytesPerSecond > 0) {
              append("${formatBytes(progress.bytesPerSecond)}/s")
              if (progress.totalBytes > 0) {
                val remaining = progress.totalBytes - progress.downloadedBytes
                val eta = remaining / progress.bytesPerSecond
                val etaStr = formatEta(eta)
                if (etaStr.isNotEmpty()) {
                  append(" · $etaStr")
                }
              }
            }
            if (!speedLimit.isUnlimited) {
              append(" (limit: ${formatBytes(speedLimit.bytesPerSecond)}/s)")
            }
          },
          style = type.monoSmall,
          color = colors.onSurfaceVariant,
        )
      }
    }
    is DownloadState.Paused -> {
      val progress = state.progress
      val pausedColors = stateColors.paused
      if (progress.totalBytes > 0) {
        val pct = (progress.percent * 100).coerceIn(0f, 100f)
        KetchProgressBar(
          progress = progress.percent,
          modifier = Modifier.fillMaxWidth(),
          fillColor = pausedColors.foreground,
        )
        Text(
          text = "Paused · ${pct.toInt()}% · " +
            "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}",
          style = type.bodySmall,
          color = pausedColors.foreground,
        )
      } else {
        Text(
          text = "Paused",
          style = type.bodySmall,
          color = pausedColors.foreground,
        )
      }
    }
    is DownloadState.Queued -> {
      Text(
        text = "Queued — waiting for download slot…",
        style = type.bodySmall,
        color = colors.onSurfaceVariant,
      )
    }
    is DownloadState.Scheduled -> {
      Text(
        text = "Scheduled — waiting for start time…",
        style = type.bodySmall,
        color = colors.onSurfaceVariant,
      )
    }
    is DownloadState.Completed -> {
      Text(
        text = "Download complete",
        style = type.bodySmall,
        color = stateColors.completed.foreground,
      )
    }
    is DownloadState.Failed -> {
      Text(
        text = "Failed: ${state.error.message}",
        style = type.bodySmall,
        color = stateColors.failed.foreground,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    is DownloadState.Canceled -> {
      Text(
        text = "Canceled",
        style = type.bodySmall,
        color = colors.onSurfaceVariant,
      )
    }
  }
}
