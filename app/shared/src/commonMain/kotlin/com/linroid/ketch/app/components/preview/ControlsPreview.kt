package com.linroid.ketch.app.components.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.ConnectionStepper
import com.linroid.ketch.app.components.KetchBadge
import com.linroid.ketch.app.components.KetchBadgeTone
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchCheckbox
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.components.KetchCountBadge
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenuPanel
import com.linroid.ketch.app.components.KetchPillGroup
import com.linroid.ketch.app.components.KetchPillItem
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.components.KetchSwitch
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.components.KetchToast
import com.linroid.ketch.app.components.KetchTooltipBubble
import com.linroid.ketch.app.components.KetchTriStateCheckbox
import com.linroid.ketch.app.components.PriorityGlyph
import com.linroid.ketch.app.components.SpeedLimitPicker
import com.linroid.ketch.app.components.StartTimePicker
import com.linroid.ketch.app.components.winningLimitCaption
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText
import com.linroid.ketch.app.ui.common.DialogPanel
import com.linroid.ketch.config.DensityMode

@Preview
@Composable
private fun ControlsLightCompactPreview() {
  ControlsPreview(darkTheme = false, density = DensityMode.Compact)
}

@Preview
@Composable
private fun ControlsDarkCompactPreview() {
  ControlsPreview(darkTheme = true, density = DensityMode.Compact)
}

@Preview
@Composable
private fun ControlsLightComfortablePreview() {
  ControlsPreview(darkTheme = false, density = DensityMode.Comfortable)
}

@Preview
@Composable
private fun ControlsDarkComfortablePreview() {
  ControlsPreview(darkTheme = true, density = DensityMode.Comfortable)
}

/** Every control of the component library on one page, in one theme and density. */
@Composable
internal fun ControlsPreview(darkTheme: Boolean, density: DensityMode) {
  KetchTheme(darkTheme = darkTheme, density = density, reduceMotion = true) {
    ControlsGallery()
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsGallery() {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s4),
    modifier = Modifier
      .width(GalleryWidth)
      .background(colors.surface)
      .padding(spacing.s6),
  ) {
    Section("Buttons") {
      KetchButtonVariant.entries.forEach { variant ->
        KetchButton(text = variant.name, variant = variant, onClick = {})
      }
      KetchButton(text = "Add", leadingIcon = KetchIcon.Plus, shortcut = "⌘N", onClick = {})
      KetchButton(text = "Adding", leadingIcon = KetchIcon.Plus, loading = true, onClick = {})
      KetchButton(text = "Disabled", enabled = false, onClick = {})
      KetchButtonSize.entries.forEach { size ->
        KetchButton(
          text = size.name,
          size = size,
          variant = KetchButtonVariant.Secondary,
          onClick = {},
        )
      }
    }
    Section("Icon buttons and pill group") {
      KetchIconButton(command = KetchCommands.PauseAll, onClick = {})
      KetchIconButton(command = KetchCommands.ResumeAll, onClick = {}, enabled = false)
      KetchIconButton(command = KetchCommands.ToggleSidebar, onClick = {}, selected = true)
      KetchPillGroup(
        items = listOf(
          KetchPillItem(icon = KetchIcon.Filter, label = "List", onClick = {}),
          KetchPillItem(icon = KetchIcon.Columns, label = "Table", onClick = {}, selected = true),
        ),
      )
    }
    Section("Segmented") {
      var filter by remember { mutableStateOf(StatusFilter.All) }
      KetchSegmented(
        options = StatusFilter.entries,
        selected = filter,
        onSelect = { filter = it },
        label = { it.label },
        count = { if (it == StatusFilter.Failed) 2 else it.ordinal * 3 },
        alert = { it == StatusFilter.Failed },
      )
    }
    Section("Chips") {
      var selected by remember { mutableStateOf(true) }
      KetchChip(
        label = "Video",
        selected = selected,
        count = 12,
        onClick = { selected = !selected },
      )
      KetchChip(label = "Archive", selected = false, count = 3, onClick = {})
      KetchChip(label = "host:github.com", selected = false, onClick = {}, onRemove = {})
      KetchChip(
        label = "Sort: Smart",
        selected = false,
        trailingIcon = KetchIcon.ChevronDown,
        onClick = {},
      )
      KetchChip(label = "Disabled", selected = false, enabled = false, onClick = {})
    }
    Section("Text fields") {
      var link by remember { mutableStateOf("https://releases.ubuntu.com/24.04/") }
      KetchTextField(
        value = link,
        onValueChange = { link = it },
        label = "Link",
        mono = true,
        leadingIcon = KetchIcon.Link,
        modifier = Modifier.width(FieldWidth),
      )
      KetchTextField(
        value = "",
        onValueChange = {},
        label = "Folder",
        placeholder = "~/Downloads",
        onPaste = {},
        modifier = Modifier.width(FieldWidth),
      )
      KetchTextField(
        value = "8080x",
        onValueChange = {},
        label = "Port",
        error = "Enter a port from 1 to 65535.",
        modifier = Modifier.width(FieldWidth),
      )
      KetchTextField(
        value = "https://example.com/a.iso\nmagnet:?xt=urn:btih:3f2a91c0",
        onValueChange = {},
        placeholder = "Paste links, magnets or a cURL command, one per line",
        mono = true,
        maxLines = 8,
        modifier = Modifier.width(FieldWidth),
      )
    }
    Section("Checkboxes and switches") {
      var checked by remember { mutableStateOf(true) }
      KetchCheckbox(
        checked = checked,
        onCheckedChange = { checked = it },
        label = "Use as Slow lane",
      )
      KetchCheckbox(checked = false, onCheckedChange = {})
      KetchTriStateCheckbox(state = ToggleableState.Indeterminate, onClick = {})
      KetchCheckbox(checked = true, onCheckedChange = {}, enabled = false)
      var on by remember { mutableStateOf(true) }
      KetchSwitch(checked = on, onCheckedChange = { on = it }, label = "Open at login")
      KetchSwitch(checked = false, onCheckedChange = {})
    }
    Section("Badges and priority") {
      KetchCountBadge(count = 14)
      KetchCountBadge(count = 2, alert = true)
      KetchBadgeTone.entries.forEach { KetchBadge(text = it.name, tone = it) }
      DownloadPriority.entries.forEach { PriorityGlyph(it) }
    }
    Section("Menu and tooltip") {
      KetchMenuPanel {
        item(command = KetchCommands.PauseAll, onClick = {})
        item(command = KetchCommands.ResumeAll, onClick = {}, enabled = false)
        submenu(label = "Speed limit", icon = KetchIcon.Speed) {}
        item(label = "Normal", onClick = {}, checked = true, caption = "Default order")
        divider()
        item(label = "Remove from list", onClick = {}, icon = KetchIcon.Trash, destructive = true)
      }
      KetchTooltipBubble(text = "Pause all", shortcut = KetchCommands.PauseAll.shortcutLabel())
    }
    Section("Dialog") {
      DialogPanel(
        title = { Text("Remove 3 downloads?") },
        confirmButton = {
          KetchButton(text = "Remove", variant = KetchButtonVariant.Danger, onClick = {})
        },
        dismissButton = {
          KetchButton(text = "Cancel", variant = KetchButtonVariant.Secondary, onClick = {})
        },
      ) {
        Text(
          text = "Their files stay in the Downloads folder.",
          style = KetchTheme.typography.body,
          color = KetchTheme.colors.textSecondary,
        )
        KetchCheckbox(checked = false, onCheckedChange = {}, label = "Also move the files to Trash")
      }
    }
    Section("Toasts") {
      KetchToast(
        title = "Removed 5 downloads",
        level = MessageLevel.Success,
        actions = listOf(MessageAction("Undo") {}),
        duration = null,
        onDismiss = {},
      )
      KetchToast(
        title = "Couldn't set speed limit on NAS-Basement",
        detail = "Connection lost",
        level = MessageLevel.Error,
        actions = listOf(MessageAction("Try again") {}),
        onDismiss = {},
      )
    }
    Section("Pickers") {
      var limit by remember { mutableStateOf(SpeedLimit.mbps(2)) }
      SpeedLimitPicker(
        value = limit,
        onCommit = { limit = it },
        caption = winningLimitCaption(limit, SpeedLimit.mbps(1), "Slow lane"),
      )
      var schedule by remember { mutableStateOf<DownloadSchedule>(DownloadSchedule.Immediate) }
      StartTimePicker(value = schedule, onSelect = { schedule = it })
      var connections by remember { mutableStateOf(0) }
      ConnectionStepper(value = connections, onCommit = { connections = it }, autoValue = 4)
      ConnectionStepper(value = 8, onCommit = {}, pending = true)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  val spacing = KetchTheme.spacing
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
    Text(
      text = eyebrowText(title),
      style = KetchTheme.typography.eyebrow,
      color = KetchTheme.colors.textTertiary,
    )
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      verticalArrangement = Arrangement.spacedBy(spacing.s3),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
  }
}

private val GalleryWidth = 720.dp
private val FieldWidth = 280.dp
