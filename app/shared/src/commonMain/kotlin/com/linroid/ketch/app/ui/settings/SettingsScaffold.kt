package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchCard
import com.linroid.ketch.app.theme.KetchTheme

/** Card wrapper shared by every settings section. */
@Composable
fun SettingsCard(
  title: String,
  description: String,
  compact: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  KetchCard(
    modifier = modifier.fillMaxWidth(),
    padding = if (compact) 16.dp else 24.dp,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = title,
          style = KetchTheme.typography.bodyLarge,
          color = KetchTheme.colors.onBackground,
        )
        Text(
          text = description,
          style = KetchTheme.typography.bodySmall,
          color = KetchTheme.colors.onSurfaceVariant,
        )
      }
      content()
    }
  }
}

/** Small caps-style label above a group of controls. */
@Composable
fun SettingsFieldLabel(text: String) {
  Text(
    text = text,
    style = KetchTheme.typography.labelSmall,
    color = KetchTheme.colors.onSurfaceDim,
  )
}

/** Footnote under a section, e.g. when a change needs a restart. */
@Composable
fun SettingsHint(text: String) {
  Text(
    text = text,
    style = KetchTheme.typography.bodySmall,
    color = KetchTheme.colors.onSurfaceDim,
  )
}

/** Single-line text field with the page's shape and spacing. */
@Composable
fun SettingsTextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  placeholder: String = "",
  supportingText: String = "",
  enabled: Boolean = true,
  numeric: Boolean = false,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    placeholder = if (placeholder.isBlank()) null else {
      { Text(placeholder) }
    },
    supportingText = if (supportingText.isBlank()) null else {
      { Text(supportingText) }
    },
    singleLine = true,
    enabled = enabled,
    keyboardOptions = if (numeric) {
      KeyboardOptions(keyboardType = KeyboardType.Number)
    } else {
      KeyboardOptions.Default
    },
    shape = RoundedCornerShape(12.dp),
    modifier = modifier.fillMaxWidth(),
  )
}
