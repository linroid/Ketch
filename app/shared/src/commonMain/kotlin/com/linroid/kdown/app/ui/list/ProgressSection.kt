package com.linroid.kdown.app.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.app.theme.LocalDownloadStateColors
import com.linroid.kdown.app.util.formatBytes
import com.linroid.kdown.app.util.formatEta

@Composable
fun ProgressSection(
  state: DownloadState,
  speedLimit: SpeedLimit,
) {
  val stateColors = LocalDownloadStateColors.current

  when (state) {
    is DownloadState.Downloading -> {
      val progress = state.progress
      val pct = (progress.percent * 100).coerceIn(0f, 100f)
      val colors = stateColors.downloading
      LinearProgressIndicator(
        progress = { progress.percent },
        modifier = Modifier.fillMaxWidth(),
        color = colors.foreground,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = buildString {
            append("${pct.toInt()}%")
            append(
              " \u00b7 ${formatBytes(progress.downloadedBytes)}"
            )
            append(" / ${formatBytes(progress.totalBytes)}")
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = buildString {
            if (progress.bytesPerSecond > 0) {
              append(
                "${formatBytes(progress.bytesPerSecond)}/s"
              )
              if (progress.totalBytes > 0) {
                val remaining = progress.totalBytes -
                  progress.downloadedBytes
                val eta =
                  remaining / progress.bytesPerSecond
                val etaStr = formatEta(eta)
                if (etaStr.isNotEmpty()) {
                  append(" \u00b7 $etaStr")
                }
              }
            }
            if (!speedLimit.isUnlimited) {
              append(" (limit: " + formatBytes(speedLimit.bytesPerSecond) + "/s)")
            }
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    is DownloadState.Paused -> {
      val progress = state.progress
      val colors = stateColors.paused
      if (progress.totalBytes > 0) {
        val pct =
          (progress.percent * 100).coerceIn(0f, 100f)
        LinearProgressIndicator(
          progress = { progress.percent },
          modifier = Modifier.fillMaxWidth(),
          color = colors.foreground,
          trackColor =
            MaterialTheme.colorScheme.surfaceVariant
        )
        Text(
          text = "Paused \u00b7 ${pct.toInt()}%" +
            " \u00b7 " + formatBytes(progress.downloadedBytes) +
            " / ${formatBytes(progress.totalBytes)}",
          style = MaterialTheme.typography.bodySmall,
          color = colors.foreground,
        )
      } else {
        Text(
          text = "Paused",
          style = MaterialTheme.typography.bodySmall,
          color = colors.foreground,
        )
      }
    }
    is DownloadState.Pending -> {
      val colors = stateColors.pending
      LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth(),
        color = colors.foreground,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
      )
      Text(
        text = "Preparing download\u2026",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    is DownloadState.Queued -> {
      Text(
        text = "Queued \u2014 waiting for download slot\u2026",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    is DownloadState.Scheduled -> {
      Text(
        text = "Scheduled \u2014 waiting for start time\u2026",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    is DownloadState.Completed -> {
      Text(
        text = "Download complete",
        style = MaterialTheme.typography.bodySmall,
        color = stateColors.completed.foreground,
      )
    }
    is DownloadState.Failed -> {
      Text(
        text = "Failed: ${state.error.message}",
        style = MaterialTheme.typography.bodySmall,
        color = stateColors.failed.foreground,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    is DownloadState.Canceled -> {
      Text(
        text = "Canceled",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    is DownloadState.Idle -> {}
  }
}
