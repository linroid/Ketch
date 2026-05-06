package com.linroid.ketch.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme

/**
 * Ketch single-line text field. 36dp tall, 8dp radius, 1dp border.
 * Cursor uses the accent color.
 */
@Composable
fun KetchTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "",
  leadingIcon: KetchIcon? = null,
  mono: Boolean = false,
  enabled: Boolean = true,
) {
  val colors = KetchTheme.colors
  val shape = RoundedCornerShape(8.dp)
  val textStyle: TextStyle =
    (if (mono) KetchTheme.typography.monoSmall else KetchTheme.typography.bodyMedium)
      .copy(color = colors.onBackground)

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = modifier
      .defaultMinSize(minHeight = 36.dp)
      .clip(shape)
      .background(colors.surface)
      .border(1.dp, colors.outline, shape)
      .padding(horizontal = 12.dp),
  ) {
    if (leadingIcon != null) {
      KetchIconImage(leadingIcon, size = 14.dp, tint = colors.onSurfaceDim)
    }
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      enabled = enabled,
      textStyle = textStyle,
      cursorBrush = SolidColor(colors.primary),
      modifier = Modifier.defaultMinSize(minWidth = 120.dp),
      decorationBox = { inner ->
        if (value.isEmpty() && placeholder.isNotEmpty()) {
          Text(placeholder, style = textStyle.copy(color = colors.onSurfaceDim))
        }
        inner()
      },
    )
  }
}
