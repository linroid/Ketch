package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchMenuScope
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.formatBytes
import kotlin.time.Clock
import kotlinx.datetime.TimeZone

/** What a [BarVerb] does when clicked. */
internal enum class BarVerbKind {
  /** Runs the verb's action on its rows. */
  Run,

  /** Opens the priorities. */
  Priority,

  /** Opens the speed limits. */
  Speed,

  /** Opens the devices to send to. */
  SendTo,

  /** Opens the connection counts. */
  Connections,

  /** Opens the start times. */
  StartLater,

  /** Asks to remove the rows, with a box for their files. */
  RemoveDialog,
}

/**
 * A verb of the selection bar.
 *
 * @property action the row action it stands for.
 * @property kind what clicking it does.
 * @property label button text, such as "Pause" or "Copy links".
 * @property rows the selected rows it applies to.
 * @property counted whether the button shows how many rows it applies to, as in "Pause 2".
 */
@Immutable
internal data class BarVerb(
  val action: RowAction,
  val kind: BarVerbKind,
  val label: String,
  val rows: List<TaskRow>,
  val counted: Boolean = false,
) {
  /** How many selected rows it applies to. */
  val count: Int get() = rows.size

  /** Its tooltip over a selection of [total] rows, such as "Pause 2 of 3 selected". */
  fun tooltip(total: Int): String = when {
    counted -> "$label $count of $total selected"
    kind == BarVerbKind.RemoveDialog -> "Remove ${downloads(count)}…"
    action == RowAction.CopyLink -> if (count == 1) "Copy 1 link" else "Copy $count links"
    count < total -> "$label · $count of $total selected"
    else -> label
  }
}

/**
 * The verbs of the selection bar for a selection whose [batch] actions are given: first those
 * the bar shows while they fit, in order, then those only its "⋯" lists. Only verbs that apply
 * to a selected row are present.
 *
 * @param canSend whether there is another device to send to.
 * @param revealLabel "Show in Finder" and the like.
 */
internal fun barVerbs(
  batch: List<BatchAction>,
  canSend: Boolean,
  revealLabel: String? = null,
): Pair<List<BarVerb>, List<BarVerb>> {
  val byAction = batch.associate { it.action to it.rows }
  fun verb(action: RowAction, kind: BarVerbKind, label: String, counted: Boolean = false) =
    byAction[action]?.let { BarVerb(action, kind, label, it, counted) }

  val bar = listOfNotNull(
    verb(RowAction.Pause, BarVerbKind.Run, "Pause", counted = true),
    verb(RowAction.Resume, BarVerbKind.Run, "Resume", counted = true),
    verb(RowAction.Retry, BarVerbKind.Run, "Retry", counted = true),
    verb(RowAction.Priority, BarVerbKind.Priority, "Priority"),
    verb(RowAction.SpeedLimit, BarVerbKind.Speed, "Speed"),
    verb(RowAction.SendTo, BarVerbKind.SendTo, "Send to").takeIf { canSend },
    verb(RowAction.CopyLink, BarVerbKind.Run, "Copy links"),
    verb(RowAction.Remove, BarVerbKind.RemoveDialog, "Remove…"),
  )
  val more = listOfNotNull(
    verb(RowAction.StartNow, BarVerbKind.Run, "Start now", counted = true),
    verb(RowAction.Open, BarVerbKind.Run, "Open", counted = true),
    verb(RowAction.ShowInFolder, BarVerbKind.Run, revealLabel ?: "Show in folder", true),
    verb(RowAction.Connections, BarVerbKind.Connections, "Connections"),
    verb(RowAction.StartLater, BarVerbKind.StartLater, "Start later"),
    verb(RowAction.CopyPath, BarVerbKind.Run, "Copy file paths", counted = true),
    verb(RowAction.DownloadAgain, BarVerbKind.Run, "Download again", counted = true),
    verb(RowAction.StopAndDiscard, BarVerbKind.Run, "Discard progress…", counted = true),
    verb(RowAction.Remove, BarVerbKind.Run, "Remove from list"),
  )
  return bar to more
}

/** "3 selected · 2.40 GB": how many rows are selected and their known total size. */
internal fun selectionSummary(rows: List<TaskRow>): String {
  val bytes = rows.sumOf { it.sizeBytes ?: 0L }
  return listOfNotNull("${rows.size} selected", formatBytes(bytes).takeIf { bytes > 0 })
    .joinToString(" · ")
}

/**
 * The bar that takes the place of the status tabs while two or more rows are selected: what is
 * selected, then the verbs that apply with how many rows each applies to ("Pause 2"). Verbs that
 * do not fit move into "⋯". Results come back as one toast, such as "Paused 2 · 1 already
 * finished".
 *
 * With [compact] it is the two-line bar phones show at the bottom instead of the add button,
 * with Select all and the first verbs as labelled icons.
 *
 * @param rows the selected rows on screen, in display order.
 * @param onClear clears the selection.
 * @param onSelectAll selects every visible row; `null` hides Select all.
 */
@Composable
internal fun SelectionBar(
  rows: List<TaskRow>,
  runner: RowActionRunner,
  onClear: () -> Unit,
  modifier: Modifier = Modifier,
  onSelectAll: (() -> Unit)? = null,
  compact: Boolean = false,
) {
  val instances by runner.state.instances.collectAsState()
  val devices = remember(instances, rows) { sendTargets(instances, rows) }
  val batch = remember(rows) { runner.batch(rows) }
  val (bar, more) = barVerbs(batch, devices.isNotEmpty(), runner.files?.revealLabel)
  val context = RowMenuContext(
    revealLabel = runner.files?.revealLabel,
    devices = devices,
    now = Clock.System.now(),
    zone = TimeZone.currentSystemDefault(),
    urgentVictim = if (rows.isEmpty()) null else urgentVictim(rows, runner),
  )
  if (compact) {
    CompactSelectionBar(rows, bar + more, runner, context, onClear, onSelectAll, modifier)
  } else {
    WideSelectionBar(rows, bar, more, runner, context, onClear, onSelectAll, modifier)
  }
}

@Composable
private fun WideSelectionBar(
  rows: List<TaskRow>,
  bar: List<BarVerb>,
  more: List<BarVerb>,
  runner: RowActionRunner,
  context: RowMenuContext,
  onClear: () -> Unit,
  onSelectAll: (() -> Unit)?,
  modifier: Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val moreWidth = KetchTheme.density.iconButtonTarget
  val gap = spacing.s1
  SubcomposeLayout(
    modifier
      .fillMaxWidth()
      .height(spacing.tabRowHeight)
      .background(colors.surfaceRaised)
      .drawBehind {
        val y = size.height - density / 2
        drawLine(colors.hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = density)
      }
      .padding(horizontal = spacing.s2),
  ) { constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val lead = subcompose("lead") {
      SelectionLead(rows, onClear)
    }.map { it.measure(loose) }
    val buttons = bar.mapIndexed { index, verb ->
      subcompose("verb$index") { VerbButton(verb, rows, runner, context) }
        .map { it.measure(loose) }
    }
    val gapPx = gap.roundToPx()
    val moreWidthPx = moreWidth.roundToPx()
    val leadWidth = lead.sumOf { it.width } + spacing.s3.roundToPx()
    var used = leadWidth
    var shown = 0
    for ((index, button) in buttons.withIndex()) {
      val width = button.sumOf { it.width } + gapPx
      val rest = bar.size - index - 1 + more.size
      val needsMore = rest > 0
      val limit = constraints.maxWidth - if (needsMore) moreWidthPx + gapPx else 0
      if (used + width > limit) break
      used += width
      shown++
    }
    val overflow = bar.drop(shown) + more
    val moreButton = if (overflow.isNotEmpty() || onSelectAll != null) {
      subcompose("more") { MoreVerbs(overflow, rows, runner, context, onSelectAll) }
        .map { it.measure(loose) }
    } else {
      emptyList()
    }
    val height = constraints.maxHeight.takeIf { it != Constraints.Infinity }
      ?: (lead + buttons.flatten()).maxOfOrNull { it.height } ?: 0
    layout(constraints.maxWidth, height) {
      var x = 0
      for (placeable in lead) {
        placeable.place(x, (height - placeable.height) / 2)
        x += placeable.width
      }
      x += spacing.s3.roundToPx()
      for (button in buttons.take(shown)) {
        for (placeable in button) {
          placeable.place(x, (height - placeable.height) / 2)
          x += placeable.width
        }
        x += gapPx
      }
      for (placeable in moreButton) placeable.place(x, (height - placeable.height) / 2)
    }
  }
}

/** "✕ 3 selected · 2.40 GB │". */
@Composable
private fun SelectionLead(rows: List<TaskRow>, onClear: () -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s1),
  ) {
    KetchIconButton(
      command = KetchCommands.ClearSelection,
      icon = KetchIcon.Close,
      onClick = onClear,
    )
    Text(
      text = "${rows.size} selected",
      style = KetchTheme.typography.label,
      color = colors.textPrimary,
      maxLines = 1,
    )
    val bytes = rows.sumOf { it.sizeBytes ?: 0L }
    if (bytes > 0) {
      Text(
        text = "· ${formatBytes(bytes)}",
        style = KetchTheme.typography.numeralS,
        color = colors.textSecondary,
        maxLines = 1,
      )
    }
    Spacer(Modifier.width(spacing.s2))
    Box(
      Modifier.size(width = spacing.s0_5, height = spacing.s5).drawBehind {
        val x = size.width / 2
        drawLine(colors.divider, Offset(x, 0f), Offset(x, size.height), strokeWidth = density)
      },
    )
  }
}

/** One verb of the wide bar; dropdown verbs open their choices under the button. */
@Composable
private fun VerbButton(
  verb: BarVerb,
  selected: List<TaskRow>,
  runner: RowActionRunner,
  context: RowMenuContext,
) {
  var open by remember { mutableStateOf(false) }
  val dropdown = verb.kind != BarVerbKind.Run && verb.kind != BarVerbKind.RemoveDialog
  val shortcut = verb.action.command?.shortcutLabel(KeyboardPlatform.current)
  Box {
    BarButton(
      icon = verb.action.icon,
      label = verb.label,
      count = verb.count.takeIf { verb.counted },
      dropdown = dropdown,
      tooltip = verb.tooltip(selected.size),
      shortcut = shortcut,
      onClick = { if (dropdown) open = true else runVerb(verb, selected, runner) },
    )
    if (dropdown) {
      KetchMenu(expanded = open, onDismissRequest = { open = false }, title = verb.label) {
        verbChoices(verb, runner, context)
      }
    }
  }
}

/** The "⋯" of the wide bar, with the verbs that did not fit and those it always holds. */
@Composable
private fun MoreVerbs(
  verbs: List<BarVerb>,
  selected: List<TaskRow>,
  runner: RowActionRunner,
  context: RowMenuContext,
  onSelectAll: (() -> Unit)?,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    KetchIconButton(
      icon = KetchIcon.More,
      contentDescription = "More actions",
      onClick = { open = true },
      selected = open,
    )
    KetchMenu(
      expanded = open,
      onDismissRequest = { open = false },
      title = "${selected.size} selected",
    ) {
      moreEntries(verbs, selected, runner, context, onSelectAll)
    }
  }
}

/** Adds [verbs] to a "⋯" menu: actions as items, choices as submenus, destructive ones last. */
private fun KetchMenuScope.moreEntries(
  verbs: List<BarVerb>,
  selected: List<TaskRow>,
  runner: RowActionRunner,
  context: RowMenuContext,
  onSelectAll: (() -> Unit)?,
) {
  var destructive = false
  for (verb in verbs) {
    if (verb.action.destructive && !destructive) {
      divider()
      destructive = true
    }
    val label = if (verb.counted) "${verb.label} (${verb.count})" else verb.label
    when (verb.kind) {
      BarVerbKind.Run, BarVerbKind.RemoveDialog -> item(
        label = label,
        onClick = { runVerb(verb, selected, runner) },
        icon = verb.action.icon,
        shortcut = verb.action.command?.shortcutLabel(KeyboardPlatform.current),
        destructive = verb.action.destructive,
      )
      else -> submenu(label, verb.action.icon) { verbChoices(verb, runner, context) }
    }
  }
  if (onSelectAll != null) {
    divider()
    item(command = KetchCommands.SelectAll, onClick = onSelectAll)
  }
}

/** Adds the choices of a dropdown [verb]. */
private fun KetchMenuScope.verbChoices(
  verb: BarVerb,
  runner: RowActionRunner,
  context: RowMenuContext,
) {
  when (verb.kind) {
    BarVerbKind.Priority -> priorityEntries(verb.rows, runner, context.urgentVictim)
    BarVerbKind.Speed -> speedEntries(verb.rows, runner)
    BarVerbKind.SendTo -> sendEntries(verb.rows, runner, context.devices)
    BarVerbKind.Connections -> {
      connectionEntries(verb.rows, runner, peers = verb.rows.all { it.isTorrent })
    }
    BarVerbKind.StartLater -> startLaterEntries(verb.rows, runner, context)
    BarVerbKind.Run, BarVerbKind.RemoveDialog -> Unit
  }
}

/** Runs [verb] on the [selected] rows; batches report the rows it left alone. */
private fun runVerb(verb: BarVerb, selected: List<TaskRow>, runner: RowActionRunner) {
  when (verb.kind) {
    BarVerbKind.RemoveDialog -> runner.requestRemove(verb.rows, withFiles = false)
    else -> runner.run(verb.action, selected)
  }
}

/**
 * A 28 dp ghost button of the selection bar: a glyph, the label, an optional count in numerals
 * and a chevron for dropdowns.
 */
@Composable
private fun BarButton(
  icon: KetchIcon,
  label: String,
  count: Int?,
  dropdown: Boolean,
  tooltip: String,
  shortcut: String?,
  onClick: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val density = KetchTheme.density
  val shape = KetchTheme.shapes.sm
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  KetchTooltip(text = tooltip, shortcut = shortcut) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s1),
      modifier = Modifier
        .focusRing(focus.visible, shape, colors.focusRing)
        .heightIn(min = density.buttonSmall)
        .clip(shape)
        .background(overlay)
        .trackFocusVisibility(focus)
        .clickable(
          interactionSource = interactions,
          indication = null,
          role = if (dropdown) Role.DropdownList else Role.Button,
          onClick = onClick,
        )
        .padding(horizontal = spacing.s2),
    ) {
      KetchIconImage(icon, size = density.controlGlyph, tint = colors.textSecondary)
      Text(
        text = label,
        style = KetchTheme.typography.label,
        color = colors.textPrimary,
        maxLines = 1,
      )
      if (count != null) {
        Text(
          text = count.toString(),
          style = KetchTheme.typography.numeralS,
          color = colors.textTertiary,
          maxLines = 1,
        )
      }
      if (dropdown) {
        KetchIconImage(KetchIcon.ChevronDown, size = spacing.s3, tint = colors.textTertiary)
      }
    }
  }
}

/**
 * Height of the phone form of the selection bar ([SelectionBar] with `compact`), which things
 * floating over the bottom of the page keep clear of.
 */
internal val compactSelectionBarHeight: Dp
  @Composable get() {
    val target = KetchTheme.density.iconButtonTarget
    val spacing = KetchTheme.spacing
    return target + (target + spacing.s4) + spacing.s1 * 2
  }

/** The phone form: what is selected and Select all, over labelled icons for the first verbs. */
@Composable
private fun CompactSelectionBar(
  rows: List<TaskRow>,
  verbs: List<BarVerb>,
  runner: RowActionRunner,
  context: RowMenuContext,
  onClear: () -> Unit,
  onSelectAll: (() -> Unit)?,
  modifier: Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val total = rows.size
  val direct = verbs.filter { it.kind == BarVerbKind.Run || it.kind == BarVerbKind.RemoveDialog }
  val shown = (direct.filter { it.kind == BarVerbKind.Run }.take(COMPACT_VERBS - 1) +
    direct.filter { it.kind == BarVerbKind.RemoveDialog })
  val overflow = verbs - shown.toSet()
  Column(
    modifier
      .fillMaxWidth()
      .background(colors.surfaceRaised)
      .drawBehind {
        drawLine(colors.hairline, Offset.Zero, Offset(size.width, 0f), strokeWidth = density)
      }
      .padding(horizontal = spacing.s2, vertical = spacing.s1),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      KetchIconButton(
        icon = KetchIcon.Close,
        contentDescription = "Clear selection",
        onClick = onClear,
      )
      Text(
        text = selectionSummary(rows),
        style = KetchTheme.typography.bodyStrong,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(start = spacing.s1),
      )
      if (onSelectAll != null) {
        TextAction(text = "Select all", onClick = onSelectAll)
      }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
      for (verb in shown) {
        LabelledVerb(
          icon = verb.action.icon,
          label = if (verb.counted) "${verb.label} ${verb.count}" else verb.label,
          description = verb.tooltip(total),
          onClick = { runVerb(verb, rows, runner) },
          modifier = Modifier.weight(1f),
        )
      }
      if (overflow.isNotEmpty()) {
        var open by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
          LabelledVerb(
            icon = KetchIcon.More,
            label = "More",
            description = "More actions",
            onClick = { open = true },
          )
          KetchMenu(
            expanded = open,
            onDismissRequest = { open = false },
            title = "$total selected",
          ) {
            moreEntries(overflow, rows, runner, context, onSelectAll = null)
          }
        }
      }
    }
  }
}

/** An icon over its label, the width of its slot, for the phone's selection bar. */
@Composable
private fun LabelledVerb(
  icon: KetchIcon,
  label: String,
  description: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val shape = KetchTheme.shapes.md
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = KetchTheme.density.iconButtonTarget + spacing.s4)
      .clip(shape)
      .background(overlay)
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClickLabel = description,
        onClick = onClick,
      )
      .padding(vertical = spacing.s2),
  ) {
    KetchIconImage(icon, size = KetchTheme.density.controlGlyph, tint = colors.textSecondary)
    Text(
      text = label,
      style = KetchTheme.typography.labelS,
      color = colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** A borderless text button in the accent, such as Select all. */
@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
  val spacing = KetchTheme.spacing
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val shape = KetchTheme.shapes.sm
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .heightIn(min = KetchTheme.density.iconButtonTarget)
      .widthIn(min = KetchTheme.density.iconButtonTarget)
      .clip(shape)
      .background(overlay)
      .clickable(interactionSource = interactions, indication = null, role = Role.Button) {
        onClick()
      }
      .padding(horizontal = spacing.s3),
  ) {
    Text(
      text = text,
      style = KetchTheme.typography.label,
      color = KetchTheme.colors.accentText,
      maxLines = 1,
    )
  }
}

/** Verbs the phone bar shows as icons before "More", Remove included. */
private const val COMPACT_VERBS = 3
