package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.theme.KetchAccent
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.darkKetchColors
import com.linroid.ketch.app.theme.lightKetchColors
import com.linroid.ketch.config.ThemeMode

/**
 * Device name and appearance.
 *
 * @param systemDeviceName name the device goes by when none is set, or
 *   `null` when there is no local instance to name (the web app).
 */
@Composable
fun GeneralSettings(
  appSettings: AppSettingsController,
  systemDeviceName: String?,
) {
  Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
    if (systemDeviceName != null) {
      SettingsGroup(title = "Device") {
        SettingsRow(
          title = "Device name",
          description = "Shown in the instance picker and to other devices on your " +
            "network. Leave empty to use the system name. Applies after a restart.",
        ) {
          SettingsTextInput(
            value = appSettings.config.name.orEmpty(),
            onCommit = { appSettings.saveName(it) },
            placeholder = systemDeviceName,
          )
        }
      }
    }

    SettingsGroup(title = "Appearance") {
      SettingsRow(
        title = "Theme",
        trailing = {
          SettingsSegmented(
            value = appSettings.themeMode,
            options = ThemeMode.entries,
            label = { it.label },
            onSelect = { appSettings.saveThemeMode(it) },
          )
        },
      )
      SettingsRow(
        title = "Accent colour",
        description = appSettings.accent.displayName,
        trailing = {
          AccentSwatches(
            selected = appSettings.accent,
            onSelect = { appSettings.saveAccent(it) },
          )
        },
      )
    }
  }
}

private val ThemeMode.label: String
  get() = when (this) {
    ThemeMode.System -> "System"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
  }

@Composable
private fun AccentSwatches(
  selected: KetchAccent,
  onSelect: (KetchAccent) -> Unit,
) {
  val dark = KetchTheme.colors.isDark
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    KetchAccent.entries.forEach { accent ->
      val color = if (dark) darkKetchColors(accent).primary else lightKetchColors(accent).primary
      val isSelected = accent == selected
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(32.dp)
          .clip(CircleShape)
          .border(2.dp, if (isSelected) color else Color.Transparent, CircleShape)
          .selectable(
            selected = isSelected,
            role = Role.RadioButton,
            onClick = { onSelect(accent) },
          )
          .semantics { contentDescription = accent.displayName },
      ) {
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier.size(22.dp).clip(CircleShape).background(color),
        ) {
          if (isSelected) {
            KetchIconImage(
              icon = KetchIcon.Check,
              size = 14.dp,
              tint = if (dark) KetchTheme.colors.background else Color.White,
            )
          }
        }
      }
    }
  }
}
