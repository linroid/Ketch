package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchAccent
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.darkKetchColors
import com.linroid.ketch.app.theme.lightKetchColors

/**
 * Accent palette picker. Taps apply immediately — a colour choice you
 * cannot see until you press Save is no choice at all.
 *
 * @param accent currently selected palette.
 * @param onSelect persist and apply the palette.
 */
@Composable
fun AppearanceSettingsCard(
  accent: KetchAccent,
  compact: Boolean,
  onSelect: (KetchAccent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val dark = KetchTheme.colors.isDark
  SettingsCard(
    title = "Appearance",
    description = "Accent colour used across the app. " +
      "Light and dark follow your system setting.",
    compact = compact,
    modifier = modifier,
  ) {
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      KetchAccent.entries.forEach { option ->
        val swatch = if (dark) {
          darkKetchColors(option).primary
        } else {
          lightKetchColors(option).primary
        }
        FilterChip(
          selected = accent == option,
          onClick = { onSelect(option) },
          leadingIcon = {
            Box(
              modifier = Modifier.size(12.dp)
                .clip(CircleShape)
                .background(swatch),
            )
          },
          label = {
            Text(
              option.displayName,
              style = KetchTheme.typography.labelSmall,
            )
          },
        )
      }
    }
  }
}
