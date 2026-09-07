package com.linroid.ketch.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchTheme

/**
 * Small file-type tile rendered to the left of a download row. Uses the
 * accent-segment palette so different extensions remain visually distinct
 * even on long lists.
 */
@Composable
fun KetchFileTypeChip(
  fileName: String,
  modifier: Modifier = Modifier,
  size: Dp = 36.dp,
) {
  val ext = fileName.substringAfterLast('.', "").lowercase()
  val colors = KetchTheme.colors
  val color = when (ext) {
    "pdf", "epub", "djvu" -> colors.error
    "mp4", "mkv", "webm", "mov", "avi", "mp3", "flac", "wav" -> colors.success
    "zip", "7z", "rar", "iso", "gz", "tar", "xz" -> colors.primary
    else -> colors.onSurfaceVariant
  }
  val label = ext.take(3).ifBlank { "·" }

  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(size)
      .clip(RoundedCornerShape(10.dp))
      .background(color.copy(alpha = 0.13f)),
  ) {
    Text(
      text = label.uppercase(),
      style = KetchTheme.typography.monoXSmall.copy(
        color = color,
        fontWeight = FontWeight.Bold,
      ),
    )
  }
}
