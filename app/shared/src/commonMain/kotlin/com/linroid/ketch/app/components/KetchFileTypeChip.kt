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
  size: Dp = 26.dp,
) {
  val ext = fileName.substringAfterLast('.', "").lowercase()
  val palette = KetchTheme.colors.segments
  val color: Color = when (ext) {
    "iso" -> palette[0]
    "zip", "7z", "rar" -> palette[3]
    "xz", "gz", "tar", "bz2" -> palette[2]
    "parquet", "csv", "json" -> palette[4]
    "safetensors", "ckpt", "bin", "pt", "h5" -> palette[5]
    "mp4", "mkv", "webm", "mov", "avi" -> palette[1]
    "mp3", "flac", "wav", "ogg", "m4a" -> palette[6]
    "pdf", "epub", "djvu" -> palette[7]
    else -> KetchTheme.colors.onSurfaceDim
  }
  val label = ext.take(3).ifBlank { "·" }

  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(size)
      .clip(RoundedCornerShape(6.dp))
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
