package com.linroid.ketch.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
  val shape = RoundedCornerShape(10.dp)
  var focused by remember { mutableStateOf(false) }
  val textStyle = (if (mono) KetchTheme.typography.monoSmall else KetchTheme.typography.bodyMedium)
    .copy(color = colors.onBackground)
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    singleLine = true,
    textStyle = textStyle,
    cursorBrush = SolidColor(colors.primary),
    modifier = modifier
      .onFocusChanged { focused = it.isFocused }
      .defaultMinSize(minHeight = 44.dp)
      .clip(shape)
      .background(colors.surface)
      .border(if (focused) 2.dp else 1.dp, if (focused) colors.primary else colors.outlineVariant, shape),
    decorationBox = { inner ->
      Row(
        modifier = Modifier.padding(start = 14.dp, end = if (value.isEmpty()) 14.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (leadingIcon != null) KetchIconImage(leadingIcon, size = 18.dp, tint = colors.onSurfaceVariant)
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
          if (value.isEmpty()) Text(placeholder, style = textStyle, color = colors.onSurfaceDim)
          inner()
        }
        if (value.isNotEmpty()) KetchIconButton(
          icon = KetchIcon.Close,
          contentDescription = "Clear text",
          size = KetchButtonSize.Small,
          enabled = enabled,
          onClick = { onValueChange("") },
        )
      }
    },
  )
}
