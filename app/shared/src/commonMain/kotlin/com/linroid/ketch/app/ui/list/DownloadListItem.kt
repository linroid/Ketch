package com.linroid.ketch.app.ui.list

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.ConnectionRange
import com.linroid.ketch.app.components.ConnectionStepper
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchProgressBar
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.components.PEER_LIMIT_STEP
import com.linroid.ketch.app.components.PeerLimitRange
import com.linroid.ketch.app.components.PriorityGlyph
import com.linroid.ketch.app.components.StatusDot
import com.linroid.ketch.app.components.statusColor
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.common.AdaptiveModal
import com.linroid.ketch.app.ui.common.PriorityIcon
import com.linroid.ketch.app.ui.common.PriorityPanel
import com.linroid.ketch.app.ui.common.ScheduleIcon
import com.linroid.ketch.app.ui.common.SchedulePanel
import com.linroid.ketch.app.ui.common.SpeedLimitIcon
import com.linroid.ketch.app.ui.common.SpeedLimitPanel
import com.linroid.ketch.app.ui.common.TaskSettingsIcon
import com.linroid.ketch.app.ui.common.TaskSettingsPanel
import com.linroid.ketch.app.ui.dialog.RemoveDownloadDialog
import com.linroid.ketch.app.ui.downloads.KetchLayoutInfo
import com.linroid.ketch.app.util.RowContent
import com.linroid.ketch.app.util.priorityLabel
import com.linroid.ketch.app.util.speedLimitLabel

/** The panels under an expanded row, each opened by the [RowAction] that changes it. */
private enum class ExpandedPanel(val action: RowAction?) {
  None(null),
  SpeedLimit(RowAction.SpeedLimit),
  Connections(RowAction.Connections),
  Priority(RowAction.Priority),
  Schedule(RowAction.StartLater),
  Details(RowAction.Details),
}

/**
 * One download of the list: a card with the name, progress and reason, and the action most
 * likely needed now. Clicking it shows the details, the controls the task offers and a menu of
 * its other actions; every action runs through [commands].
 */
@Composable
internal fun DownloadListItem(
  row: TaskRow,
  commands: RowCommands,
  modifier: Modifier = Modifier,
) {
  val pending by LocalAppState.current.pending.collectAsState()
  var expanded by remember { mutableStateOf(false) }
  var panel by remember { mutableStateOf(ExpandedPanel.None) }
  var confirmingDelete by remember { mutableStateOf(false) }
  var confirmingDiscard by remember { mutableStateOf(false) }
  val terminal = row.state.isTerminal
  LaunchedEffect(terminal) {
    if (terminal) {
      panel = ExpandedPanel.None
      // A task that finished or failed meanwhile has no progress left to discard.
      confirmingDiscard = false
    }
  }

  val colors = KetchTheme.colors
  val trailing = commands.trailing(row)
  Column(
    modifier = modifier
      .fillMaxWidth()
      .ketchSurface(
        level = KetchElevationLevel.E0,
        shape = KetchTheme.shapes.md,
        fill = colors.surface,
        border = if (expanded) colors.borderStrong else colors.hairline,
      ),
  ) {
    DownloadRow(
      row = row,
      trailing = trailing,
      busy = RowCommands.isBusy(row, pending),
      expanded = expanded,
      onToggle = { expanded = !expanded },
      onAction = { commands.run(it, row) },
    )

    AnimatedVisibility(
      visible = expanded,
      enter = expandVertically() + fadeIn(),
      exit = shrinkVertically() + fadeOut(),
    ) {
      Column {
        DownloadExpandedPanel(row)
        ActionsRow(
          row = row,
          commands = commands,
          trailing = trailing,
          panel = panel,
          onPanelChange = { panel = it },
          onDelete = { confirmingDelete = true },
          onDiscard = { confirmingDiscard = true },
        )
        AnimatedContent(
          targetState = panel,
          transitionSpec = {
            (expandVertically() + fadeIn()) togetherWith (shrinkVertically() + fadeOut())
          },
          label = "row-panel",
        ) { shown ->
          PanelContent(shown, row, commands, pending)
        }
      }
    }
  }

  if (confirmingDelete) {
    RemoveDownloadDialog(
      fileName = row.name,
      deviceName = row.device.name,
      totalBytes = row.sizeBytes,
      onDismiss = { confirmingDelete = false },
      onConfirm = { deleteFiles -> commands.remove(row, deleteFiles) },
    )
  }
  if (confirmingDiscard) {
    DiscardProgressDialog(
      fileName = row.name,
      onDismiss = { confirmingDiscard = false },
      onConfirm = { commands.run(RowAction.StopAndDiscard, row) },
    )
  }
}

/** Asks before discarding a task's progress, which cannot be resumed afterwards. */
@Composable
private fun DiscardProgressDialog(fileName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
  AdaptiveModal(
    onDismissRequest = onDismiss,
    title = { Text("Stop and discard progress?") },
    dismissButton = {
      KetchButton(text = "Keep", variant = KetchButtonVariant.Secondary, onClick = onDismiss)
    },
    confirmButton = {
      KetchButton(
        text = "Discard progress",
        variant = KetchButtonVariant.Danger,
        onClick = {
          onConfirm()
          onDismiss()
        },
      )
    },
  ) {
    Text(
      text = "$fileName can't be resumed after this.",
      style = KetchTheme.typography.body,
      color = KetchTheme.colors.textSecondary,
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadRow(
  row: TaskRow,
  trailing: RowAction?,
  busy: Boolean,
  expanded: Boolean,
  onToggle: () -> Unit,
  onAction: (RowAction) -> Unit,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  val content = row.content
  val running = row.state is DownloadState.Downloading
  val metric = listOf(content.speed, content.time).filter { running && it.isNotEmpty() }
  val size = knownSize(content)
  val limit = speedLimitLabel(row.state, row.request.speedLimit)
  val toggleLabel = if (expanded) "Hide download details" else "Show download details"

  BoxWithConstraints {
    val compact = maxWidth < KetchLayoutInfo.MediumWidth
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(if (compact) spacing.s2 else spacing.s3),
      modifier = Modifier
        .fillMaxWidth()
        .clickable(onClickLabel = toggleLabel, onClick = onToggle)
        .heightIn(min = KetchTheme.density.listRowWithLanes)
        .padding(horizontal = spacing.s4, vertical = spacing.s3),
    ) {
      KetchFileTypeChip(row.name, sourceUrl = row.request.url)

      Column(
        verticalArrangement = Arrangement.spacedBy(spacing.s1),
        modifier = Modifier.weight(1f),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
          Text(
            text = row.name,
            style = type.bodyStrong,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          PriorityGlyph(row.request.priority)
        }
        if (row.state is DownloadState.Downloading || row.state is DownloadState.Paused) {
          KetchProgressBar(
            progress = content.progress ?: 0f,
            fillColor = colors.statusColor(content.status),
          )
        }
        if (compact) {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.s2),
            verticalArrangement = Arrangement.spacedBy(spacing.s1),
            itemVerticalAlignment = Alignment.CenterVertically,
          ) {
            StatusDot(content.status, label = content.statusText)
            // One item per part, so lines wrap between parts rather than inside them.
            val parts = listOfNotNull(size) + metric + listOfNotNull(limit)
            parts.forEachIndexed { i, part ->
              CaptionText(if (i < parts.lastIndex) "$part ·" else part)
            }
          }
          if (!running) ReasonText(content)
        } else {
          ReasonText(content, prefix = listOfNotNull(size), suffix = listOfNotNull(limit))
        }
      }

      if (!compact) {
        if (metric.isNotEmpty()) {
          Text(
            text = metric.joinToString(" · "),
            style = type.numeral,
            color = colors.textSecondary,
            maxLines = 1,
          )
        }
        StatusDot(content.status, label = content.statusText)
      }

      if (trailing != null) {
        RowActionButton(
          action = trailing,
          busy = busy,
          iconOnly = compact || trailing in GlyphActions,
          onClick = { onAction(trailing) },
        )
      } else {
        KetchIconButton(
          icon = if (expanded) KetchIcon.ChevronDown else KetchIcon.Chevron,
          contentDescription = toggleLabel,
          onClick = onToggle,
        )
      }
    }
  }
}

/**
 * Why a task is where it is, from [RowContent.detail], between [prefix] and [suffix] parts. A
 * failure's title is drawn in the failed color, then its short hint.
 */
@Composable
private fun ReasonText(
  content: RowContent,
  prefix: List<String> = emptyList(),
  suffix: List<String> = emptyList(),
) {
  val colors = KetchTheme.colors
  val error = content.error
  val text = buildAnnotatedString {
    prefix.forEach { append(it + SEPARATOR) }
    if (error != null) {
      withStyle(SpanStyle(color = colors.status.failed.color)) { append(error.title) }
      error.shortHint?.let { append(SEPARATOR + it) }
    } else {
      append(content.detail)
    }
    suffix.forEach { append(SEPARATOR + it) }
  }
  Text(
    text = text,
    style = KetchTheme.typography.caption,
    color = colors.textSecondary,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun CaptionText(text: String) {
  Text(
    text = text,
    style = KetchTheme.typography.caption,
    color = KetchTheme.colors.textSecondary,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/** The row's trailing button, with a spinner while a command on the task is in flight. */
@Composable
private fun RowActionButton(
  action: RowAction,
  busy: Boolean,
  iconOnly: Boolean,
  onClick: () -> Unit,
) {
  if (!iconOnly) {
    KetchButton(
      text = action.label,
      onClick = onClick,
      variant = KetchButtonVariant.Secondary,
      size = KetchButtonSize.Small,
      leadingIcon = action.icon,
      loading = busy,
    )
    return
  }
  if (busy) {
    Box(Modifier.size(KetchTheme.density.iconButtonTarget), contentAlignment = Alignment.Center) {
      KetchSpinner()
    }
  } else {
    KetchIconButton(icon = action.icon, contentDescription = action.label, onClick = onClick)
  }
}

/**
 * The buttons under an expanded row: one per panel the task offers, then Remove from list and a
 * "⋯" menu of the task's other actions. Rescheduling only shows where the device supports it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsRow(
  row: TaskRow,
  commands: RowCommands,
  trailing: RowAction?,
  panel: ExpandedPanel,
  onPanelChange: (ExpandedPanel) -> Unit,
  onDelete: () -> Unit,
  onDiscard: () -> Unit,
) {
  val spacing = KetchTheme.spacing
  val request = row.request
  val menu = commands.menu(row)
  val panels = ExpandedPanel.entries.filter { it.action != null && it.action in menu }
  val more = menu.filter { action ->
    action != trailing && action != RowAction.Remove && action != RowAction.SendTo &&
      ExpandedPanel.entries.none { it.action == action }
  }
  fun toggle(target: ExpandedPanel) {
    onPanelChange(if (panel == target) ExpandedPanel.None else target)
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = spacing.s3, vertical = spacing.s2),
  ) {
    FlowRow(
      modifier = Modifier.weight(1f),
      horizontalArrangement = Arrangement.spacedBy(spacing.s1),
      verticalArrangement = Arrangement.spacedBy(spacing.s1),
    ) {
      panels.forEach { target ->
        val selected = panel == target
        when (target) {
          ExpandedPanel.SpeedLimit -> SpeedLimitIcon(
            label = if (request.speedLimit.isUnlimited) {
              "Speed limit"
            } else {
              formatSpeedLimit(request.speedLimit)
            },
            active = !request.speedLimit.isUnlimited,
            selected = selected,
            onClick = { toggle(target) },
          )
          ExpandedPanel.Connections -> KetchButton(
            text = if (row.isTorrent) "Peers" else "Connections",
            leadingIcon = KetchIcon.Lanes,
            variant = if (selected) KetchButtonVariant.Secondary else KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
            onClick = { toggle(target) },
          )
          ExpandedPanel.Priority -> PriorityIcon(
            label = if (request.priority == DownloadPriority.NORMAL) {
              "Priority"
            } else {
              "${priorityLabel(request.priority)} priority"
            },
            active = request.priority != DownloadPriority.NORMAL,
            selected = selected,
            onClick = { toggle(target) },
          )
          ExpandedPanel.Schedule -> ScheduleIcon(
            label = RowAction.StartLater.label,
            selected = selected,
            onClick = { toggle(target) },
          )
          ExpandedPanel.Details -> TaskSettingsIcon(
            selected = selected,
            onClick = { toggle(target) },
          )
          ExpandedPanel.None -> Unit
        }
      }
    }
    if (RowAction.Remove in menu) {
      KetchIconButton(
        icon = RowAction.Remove.icon,
        contentDescription = RowAction.Remove.label,
        onClick = { commands.run(RowAction.Remove, row) },
      )
    }
    if (more.isNotEmpty()) {
      MoreMenu(
        actions = more,
        onAction = { action ->
          when (action) {
            RowAction.RemoveAndDelete, RowAction.RemoveAndTrash -> onDelete()
            RowAction.StopAndDiscard -> onDiscard()
            else -> commands.run(action, row)
          }
        },
      )
    }
  }
}

@Composable
private fun MoreMenu(actions: List<RowAction>, onAction: (RowAction) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    KetchIconButton(
      icon = KetchIcon.More,
      contentDescription = "More actions",
      onClick = { open = true },
    )
    KetchMenu(expanded = open, onDismissRequest = { open = false }) {
      actions.forEachIndexed { index, action ->
        if (action.destructive && actions.getOrNull(index - 1)?.destructive == false) divider()
        item(
          label = action.label,
          icon = action.icon,
          destructive = action.destructive,
          onClick = { onAction(action) },
        )
      }
    }
  }
}

@Composable
private fun PanelContent(
  panel: ExpandedPanel,
  row: TaskRow,
  commands: RowCommands,
  pending: Set<Pair<TaskKey, String>>,
) {
  val spacing = KetchTheme.spacing
  val modifier = Modifier
    .fillMaxWidth()
    .padding(start = spacing.s4, end = spacing.s4, bottom = spacing.s3)
  val request = row.request
  fun isPending(label: String) = (row.key to label) in pending
  when (panel) {
    ExpandedPanel.SpeedLimit -> SpeedLimitPanel(
      value = request.speedLimit,
      onCommit = { commands.setSpeedLimit(row, it) },
      pending = isPending(RowCommands.speedLimitLabel(row)),
      modifier = modifier,
    )
    ExpandedPanel.Connections -> Box(modifier) {
      ConnectionStepper(
        value = request.connections,
        onCommit = { commands.setConnections(row, it) },
        range = if (row.isTorrent) PeerLimitRange else ConnectionRange,
        step = if (row.isTorrent) PEER_LIMIT_STEP else 1,
        pending = isPending(RowCommands.connectionsLabel(row)),
        noun = if (row.isTorrent) "peers" else "connections",
      )
    }
    ExpandedPanel.Priority -> PriorityPanel(
      value = request.priority,
      onSelect = { commands.setPriority(row, it) },
      pending = isPending(RowCommands.priorityLabel(row)),
      modifier = modifier,
    )
    ExpandedPanel.Schedule -> SchedulePanel(
      value = request.schedule,
      onSelect = { commands.reschedule(row, it) },
      pending = isPending(RowCommands.rescheduleLabel(row)),
      modifier = modifier,
    )
    ExpandedPanel.Details -> TaskSettingsPanel(
      row = row,
      onCopyLink = { commands.run(RowAction.CopyLink, row) },
      modifier = modifier,
    )
    ExpandedPanel.None -> Unit
  }
}

/** The row's size, or `null` while it is unknown. */
private fun knownSize(content: RowContent): String? =
  content.size.takeIf { it.isNotBlank() && it != UNKNOWN_SIZE }

/** Glyph of [this] action on buttons and in menus. */
internal val RowAction.icon: KetchIcon
  get() = when (this) {
    RowAction.Pause -> KetchIcon.Pause
    RowAction.Resume, RowAction.StartNow -> KetchIcon.Play
    RowAction.Reconnect,
    RowAction.Retry,
    is RowAction.RetryWithConnections,
    RowAction.DownloadAgain -> KetchIcon.Retry
    RowAction.SpeedLimit -> KetchIcon.Speed
    RowAction.Connections -> KetchIcon.Lanes
    RowAction.Priority -> KetchIcon.Bolt
    RowAction.StartLater -> KetchIcon.Scheduled
    RowAction.SendTo -> KetchIcon.Devices
    RowAction.CopyLink, RowAction.EditLink -> KetchIcon.Link
    RowAction.Details -> KetchIcon.Info
    RowAction.Open, RowAction.OpenSourcePage -> KetchIcon.Open
    RowAction.ShowInFolder -> KetchIcon.Reveal
    RowAction.CopyPath, RowAction.CopyError, RowAction.CopyDetails -> KetchIcon.Copy
    RowAction.RetryWithOptions, RowAction.EnterCredentials -> KetchIcon.Settings
    RowAction.FindAnotherSource -> KetchIcon.Discover
    RowAction.StopAndDiscard -> KetchIcon.Stop
    RowAction.Remove, RowAction.RemoveAndTrash, RowAction.RemoveAndDelete -> KetchIcon.Trash
  }

/** Actions whose glyph says enough on its own, so their button never carries a label. */
private val GlyphActions: Set<RowAction> = setOf(
  RowAction.Pause,
  RowAction.Resume,
  RowAction.Reconnect,
  RowAction.StartNow,
  RowAction.Open,
)

private const val SEPARATOR = " · "

/** What [RowContent.size] reads while the size is unknown. */
private const val UNKNOWN_SIZE = "–"
