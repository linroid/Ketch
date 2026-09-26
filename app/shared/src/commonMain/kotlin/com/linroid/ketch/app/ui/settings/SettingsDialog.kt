package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchSidebarItem
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.SettingsEdits
import com.linroid.ketch.app.state.SettingsSection
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal

/**
 * Settings as a panel over the app, for wide windows: a section list on
 * the left and the selected section on the right. Narrow windows use
 * [SettingsPage] instead.
 *
 * Unsaved edits survive switching sections. Closing — the close button,
 * Esc or a click outside — asks first while any section still has them.
 *
 * @param sections sections to list, in order.
 * @param onDismiss close the settings.
 * @param section renders one section; see [SettingsSectionCard].
 */
@Composable
fun SettingsDialog(
  sections: List<SettingsSection>,
  onDismiss: () -> Unit,
  section: @Composable (
    section: SettingsSection,
    compact: Boolean,
    onUnsavedChange: (Boolean) -> Unit,
  ) -> Unit,
) {
  val colors = KetchTheme.colors
  val edits = remember { SettingsEdits() }
  // Keeps each section's draft and scroll position while another is shown.
  val drafts = rememberSaveableStateHolder()
  var selectedName by rememberSaveable { mutableStateOf(sections.first().name) }
  val selected = sections.firstOrNull { it.name == selectedName } ?: sections.first()
  val requestClose = {
    if (edits.requestClose()) onDismiss()
  }

  Dialog(
    onDismissRequest = requestClose,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    val shape = RoundedCornerShape(16.dp)
    Row(
      modifier = Modifier
        .padding(32.dp)
        .widthIn(max = 880.dp)
        .heightIn(max = 720.dp)
        .fillMaxSize()
        .clip(shape)
        .background(colors.background)
        .border(1.dp, colors.outline, shape),
    ) {
      SettingsNavigation(
        sections = sections,
        selected = selected,
        edits = edits,
        onSelect = { selectedName = it.name },
      )
      VerticalDivider(color = colors.outlineVariant)
      Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          KetchIconButton(
            icon = KetchIcon.Close,
            onClick = requestClose,
            contentDescription = "Close settings",
          )
        }
        drafts.SaveableStateProvider(selected.name) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(start = 32.dp, end = 32.dp, bottom = 32.dp),
          ) {
            section(selected, false) { edits.report(selected, it) }
          }
        }
      }
    }

    if (edits.confirmingDiscard) {
      DiscardEditsDialog(
        sections = edits.unsavedSections,
        onKeepEditing = edits::keepEditing,
        onDiscard = {
          edits.discard()
          onDismiss()
        },
      )
    }
  }
}

@Composable
private fun SettingsNavigation(
  sections: List<SettingsSection>,
  selected: SettingsSection,
  edits: SettingsEdits,
  onSelect: (SettingsSection) -> Unit,
) {
  val colors = KetchTheme.colors
  Column(
    modifier = Modifier
      .width(220.dp)
      .fillMaxHeight()
      .background(colors.surfaceVariant)
      .padding(vertical = 20.dp),
  ) {
    Text(
      text = "Settings",
      style = KetchTheme.typography.displaySmall,
      color = colors.onBackground,
      modifier = Modifier.padding(horizontal = 22.dp),
    )
    Spacer(Modifier.size(16.dp))
    sections.forEach { entry ->
      KetchSidebarItem(
        label = entry.label,
        icon = entry.icon,
        selected = entry == selected,
        onClick = { onSelect(entry) },
        trailing = if (edits.isUnsaved(entry)) {
          { UnsavedDot() }
        } else {
          null
        },
      )
    }
    Spacer(Modifier.weight(1f))
    Text(
      text = "Saved to this device's config file.",
      style = KetchTheme.typography.bodySmall,
      color = colors.onSurfaceDim,
      modifier = Modifier.padding(horizontal = 22.dp),
    )
  }
}

@Composable
private fun UnsavedDot() {
  Box(
    modifier = Modifier
      .size(8.dp)
      .clip(CircleShape)
      .background(KetchTheme.colors.warning)
      .semantics { contentDescription = "Unsaved changes" },
  )
}

@Composable
private fun DiscardEditsDialog(
  sections: List<SettingsSection>,
  onKeepEditing: () -> Unit,
  onDiscard: () -> Unit,
) {
  val names = sections.map { it.label }
  val list = if (names.size <= 1) {
    names.joinToString()
  } else {
    names.dropLast(1).joinToString() + " and " + names.last()
  }
  AdaptiveModal(
    onDismissRequest = onKeepEditing,
    title = { Text("Discard unsaved changes?") },
    confirmButton = {
      KetchButton(
        text = "Discard",
        onClick = onDiscard,
        variant = KetchButtonVariant.Danger,
      )
    },
    dismissButton = {
      KetchButton(
        text = "Keep editing",
        onClick = onKeepEditing,
        variant = KetchButtonVariant.Ghost,
      )
    },
  ) {
    Text(
      text = "Your changes to $list haven't been saved.",
      style = KetchTheme.typography.bodyMedium,
      color = KetchTheme.colors.onSurfaceVariant,
    )
  }
}
