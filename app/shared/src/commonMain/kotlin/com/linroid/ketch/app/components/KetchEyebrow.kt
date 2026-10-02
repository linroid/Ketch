package com.linroid.ketch.app.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText

/**
 * A group header, column header or card label: [text] in capitals, in the eyebrow style.
 *
 * @param color tertiary text by default; labels on the canvas, which tertiary text may not sit
 *   on, use secondary text.
 */
@Composable
fun KetchEyebrow(
  text: String,
  modifier: Modifier = Modifier,
  color: Color = KetchTheme.colors.textTertiary,
  maxLines: Int = Int.MAX_VALUE,
  overflow: TextOverflow = TextOverflow.Clip,
) {
  Text(
    text = eyebrowText(text),
    style = KetchTheme.typography.eyebrow,
    color = color,
    maxLines = maxLines,
    overflow = overflow,
    modifier = modifier,
  )
}
