package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton

/**
 * Instance name shown in the instance picker and advertised on the
 * local network.
 *
 * @param name saved name; blank means the platform default.
 * @param defaultName label the app falls back to.
 * @param onSave persist the edited name.
 * @param onUnsavedChange told whether the field differs from [name].
 */
@Composable
fun GeneralSettingsCard(
  name: String,
  defaultName: String,
  compact: Boolean,
  onSave: (String) -> Unit,
  modifier: Modifier = Modifier,
  onUnsavedChange: (Boolean) -> Unit = {},
) {
  var edited by rememberSaveable(name) { mutableStateOf(name) }
  val changed = edited.trim() != name.trim()
  ReportUnsaved(changed, onUnsavedChange)
  SettingsCard(
    title = "This device",
    description = "How this instance appears to you and to other devices.",
    compact = compact,
    modifier = modifier,
  ) {
    SettingsTextField(
      value = edited,
      onValueChange = { edited = it },
      label = "Device name",
      placeholder = defaultName,
      supportingText = "Leave empty to use $defaultName.",
    )
    SettingsHint(
      "The name is picked up the next time the app and its server start.",
    )
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
      KetchButton(
        text = "Save",
        onClick = { onSave(edited) },
        enabled = changed,
      )
    }
  }
}
