package com.linroid.ketch.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.input.CommandScope
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal

/**
 * One line of the shortcut sheet.
 *
 * @property label what the keys do.
 * @property keys the chords, as the platform prints them; a range of devices reads "⌥⌘1–9".
 */
internal data class ShortcutLine(val label: String, val keys: List<String>)

/** A titled group of the shortcut sheet. */
internal data class ShortcutGroup(val title: String, val lines: List<ShortcutLine>)

/**
 * The shortcut sheet's groups on [platform], from [KetchCommands]: the global commands the
 * window runs ([runs]), the Downloads list's keys and the add sheet's. A command without a chord
 * here is left out, and the nine device chords of a group read as one line.
 *
 * @param runs whether a global command does something in this window.
 */
internal fun shortcutGroups(
  platform: KeyboardPlatform,
  runs: (KetchCommand) -> Boolean,
): List<ShortcutGroup> {
  fun lines(commands: List<KetchCommand>, devices: List<KetchCommand>, label: String) =
    buildList {
      for (command in commands) {
        if (command in devices.drop(1)) continue
        val chords = command.chords(platform)
        if (chords.isEmpty()) continue
        if (command == devices.firstOrNull()) {
          val first = chords.first().label(platform)
          add(ShortcutLine(label, listOf("$first–${devices.size}")))
        } else {
          add(ShortcutLine(lineLabel(command), chords.map { it.label(platform) }))
        }
      }
    }
  val global = KetchCommands.all.filter { it.scope == CommandScope.Global && runs(it) }
  val list = KetchCommands.all.filter { it.scope == CommandScope.List }
  val intake = KetchCommands.all.filter { it.scope == CommandScope.Intake }
  val deviceCommands = (1..DEVICE_SHORTCUTS).map(KetchCommands::device)
  val targetCommands = (1..DEVICE_SHORTCUTS).map(KetchCommands::intakeTarget)
  return listOf(
    ShortcutGroup("General", lines(global, deviceCommands, "Switch to device 1–9")),
    ShortcutGroup("Downloads list", lines(list, emptyList(), "")),
    ShortcutGroup("Add sheet", lines(intake, targetCommands, "Add to device 1–9")),
  ).filter { it.lines.isNotEmpty() }
}

// A tab command is named after its tab alone, such as "Failed".
private fun lineLabel(command: KetchCommand): String =
  if (StatusFilter.entries.any { KetchCommands.tab(it) == command }) {
    "${command.label} tab"
  } else {
    command.label
  }

/**
 * The keyboard shortcut sheet (`⌘/`): every chord of [groups], two columns to a group where the
 * window has room.
 */
@Composable
internal fun ShortcutSheet(groups: List<ShortcutGroup>, onDismissRequest: () -> Unit) {
  val typography = KetchTheme.typography
  AdaptiveModal(
    onDismissRequest = onDismissRequest,
    title = { Text("Keyboard shortcuts", style = typography.titleL) },
    confirmButton = { KetchButton(text = "Done", onClick = onDismissRequest) },
    maxWidth = SheetMaxWidth,
  ) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val columns = if (maxWidth >= TwoColumnWidth) 2 else 1
      Column(
        verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.sectionGap),
        modifier = Modifier
          .heightIn(max = SheetMaxHeight)
          .verticalScroll(rememberScrollState()),
      ) {
        for (group in groups) ShortcutGroupView(group, columns)
      }
    }
  }
}

@Composable
private fun ShortcutGroupView(group: ShortcutGroup, columns: Int) {
  val spacing = KetchTheme.spacing
  val rows = remember(group, columns) { group.lines.chunked(columns) }
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
    KetchEyebrow(group.title, Modifier.padding(bottom = spacing.s1))
    for (row in rows) {
      Row(horizontalArrangement = Arrangement.spacedBy(spacing.s6)) {
        for (line in row) ShortcutLineView(line, Modifier.weight(1f))
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
      }
    }
  }
}

@Composable
private fun ShortcutLineView(line: ShortcutLine, modifier: Modifier) {
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier
      // Lines are read, not tapped, so they keep their height on touch screens too.
      .heightIn(min = KeyCapHeight + spacing.s2)
      .clearAndSetSemantics { contentDescription = "${line.label}, ${line.keys.joinToString()}" },
  ) {
    Text(
      text = line.label,
      style = KetchTheme.typography.bodyS,
      color = KetchTheme.colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    for (key in line.keys) KeyCap(key)
  }
}

/** A key or chord on a sunken cap, such as "⌘K", in the shortcut sheet and the palette. */
@Composable
internal fun KeyCap(text: String) {
  val colors = KetchTheme.colors
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .heightIn(min = KeyCapHeight)
      .background(colors.surfaceSunken, KetchTheme.shapes.xs)
      .padding(horizontal = KetchTheme.spacing.s2),
  ) {
    Text(text = text, style = KetchTheme.typography.labelS, color = colors.textSecondary)
  }
}

private const val DEVICE_SHORTCUTS = 9

private val SheetMaxWidth: Dp = 720.dp
private val SheetMaxHeight: Dp = 560.dp
private val TwoColumnWidth: Dp = 560.dp
private val KeyCapHeight: Dp = 20.dp
