package com.linroid.ketch.app.ui.inspector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.LaneStrip
import com.linroid.ketch.app.components.LaneStripDefaults
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.toDeviceHealth
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.actions.RowActionRunner
import com.linroid.ketch.app.ui.downloads.actions.command
import com.linroid.ketch.app.ui.downloads.actions.icon
import com.linroid.ketch.app.ui.downloads.actions.outputFile
import com.linroid.ketch.app.ui.downloads.actions.rowActionLabel
import com.linroid.ketch.app.ui.downloads.actions.sendEntries
import com.linroid.ketch.app.ui.downloads.actions.sendTargets
import com.linroid.ketch.app.ui.inspector.tabs.formatSize
import com.linroid.ketch.app.ui.inspector.tabs.middleEllipsis
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The device a task runs on, as the inspector names it.
 *
 * @property id the device's id, which picks its pennant hue.
 * @property name "This Mac" or the remote's name.
 * @property pennantName what its monogram is made from: the host name for this device.
 * @property health how well the app is connected to it.
 */
@Immutable
internal data class DeviceLabel(
  val id: String,
  val name: String,
  val pennantName: String,
  val health: DeviceHealth?,
)

/** The [DeviceLabel] of the device that runs [row]. */
@Composable
internal fun rememberDeviceLabel(state: AppState, row: TaskRow): DeviceLabel {
  val instances by state.instances.collectAsState()
  val entry = instances.firstOrNull { it.deviceId == row.key.deviceId }
  val health = when (entry) {
    is RemoteInstance -> entry.connectionState.collectAsState().value.toDeviceHealth()
    null -> null
    else -> DeviceHealth.Local()
  }
  return DeviceLabel(
    id = row.key.deviceId,
    name = entry?.displayName ?: row.device.name,
    pennantName = entry?.label ?: row.device.name,
    health = health,
  )
}

/**
 * The top of the inspector for [row]: its file chip and name, which copies itself when
 * clicked, its size, site and device, the lane strip with [highlight]ed and [stalled] lanes,
 * the metric line and the [reason] line.
 */
@Composable
internal fun TaskHeader(
  row: TaskRow,
  device: DeviceLabel,
  reason: InspectorReason?,
  highlight: Long?,
  stalled: Set<Long>,
  onReason: (ReasonAction) -> Unit,
  onCopyName: () -> Unit,
  onClose: () -> Unit,
) {
  val spacing = KetchTheme.spacing
  val completed = row.state is DownloadState.Completed
  // A download that completes while shown plays its strip's finish, then trades it for a check.
  var finished by remember(row.key) { mutableStateOf(completed) }
  var check by remember(row.key) { mutableStateOf(false) }
  LaunchedEffect(check) {
    if (check) {
      delay(CHECK_SHOWN)
      check = false
    }
  }
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s3)) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.s3)) {
      KetchFileTypeChip(
        fileName = row.name,
        sourceUrl = row.request.url,
        size = KetchFileTypeChipDefaults.LargeSize,
        showCheck = check,
      )
      Column(
        verticalArrangement = Arrangement.spacedBy(spacing.s0_5),
        modifier = Modifier.weight(1f).padding(top = spacing.s0_5),
      ) {
        TaskName(row.name, onCopyName)
        Subline(row, device)
      }
      KetchIconButton(
        icon = KetchIcon.Close,
        contentDescription = "Close inspector",
        onClick = onClose,
        size = KetchButtonSize.Small,
      )
    }
    if (row.hasLanes && (!finished || !completed)) {
      key(row.key) {
        LaneStrip(
          state = row.state,
          segments = row.segments,
          height = LaneStripDefaults.HeaderHeight,
          highlight = highlight,
          stalled = stalled,
          onCompletionShown = {
            if (!finished) check = true
            finished = true
          },
        )
      }
    }
    val parts = metricParts(row, Clock.System.now(), remember { TimeZone.currentSystemDefault() })
    if (parts.isNotEmpty()) MetricLine(parts)
    if (reason != null) ReasonLine(row, reason, onReason)
  }
}

/**
 * The download's [name] on at most two lines. It may break after any "-" or "_", which file
 * names use instead of spaces, so the first line stays full, but not at a dot, which keeps
 * versions and the extension whole. A name that still does not fit gives up characters from its
 * middle, so its extension stays in view, and shows whole in a tooltip. A click copies it.
 */
@Composable
private fun TaskName(name: String, onCopy: () -> Unit) {
  val colors = KetchTheme.colors
  val style = KetchTheme.typography.titleM
  val shape = KetchTheme.shapes.xs
  val focus = rememberFocusVisibility()
  BoxWithConstraints {
    val measurer = rememberTextMeasurer()
    val width = constraints.maxWidth
    val breakable = remember(name) { name.replace(NameSeparator) { it.value + ZERO_WIDTH_SPACE } }
    val shown = remember(breakable, width, style) {
      middleEllipsis(breakable) { candidate ->
        val fit = measurer.measure(
          text = candidate,
          style = style,
          maxLines = NAME_LINES,
          constraints = Constraints(maxWidth = width),
        )
        !fit.hasVisualOverflow
      }
    }
    val elided = shown != breakable
    KetchTooltip(text = name, enabled = elided) {
      Text(
        text = shown,
        style = style,
        color = colors.textPrimary,
        maxLines = NAME_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .focusRing(focus.visible, shape, colors.focusRing)
          .clip(shape)
          .trackFocusVisibility(focus)
          .clickable(onClickLabel = "Copy name", role = Role.Button, onClick = onCopy)
          .semantics { contentDescription = name },
      )
    }
  }
}

/**
 * "5.7 GB · releases.ubuntu.com · (LM) This Mac"; the device moves to a line of its own when
 * the site leaves no room for it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Subline(row: TaskRow, device: DeviceLabel) {
  val spacing = KetchTheme.spacing
  val text = listOfNotNull(row.sizeBytes?.let(::formatSize), row.sourceHost).joinToString(" · ")
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    verticalArrangement = Arrangement.spacedBy(spacing.s0_5),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    if (text.isNotEmpty()) {
      Text(
        text = text,
        style = KetchTheme.typography.caption,
        color = KetchTheme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    DeviceName(device)
  }
}

/** The device's pennant with its health ring, and its name. */
@Composable
internal fun DeviceName(device: DeviceLabel, modifier: Modifier = Modifier) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
    modifier = modifier,
  ) {
    DevicePennant(
      deviceId = device.id,
      name = device.pennantName,
      size = DevicePennantDefaults.XSmall,
      health = device.health,
    )
    Text(
      text = device.name,
      style = KetchTheme.typography.caption,
      color = KetchTheme.colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * The metric line: the share and speed in the primary color, the rest secondary, breaking only
 * between parts.
 */
@Composable
private fun MetricLine(parts: List<String>) {
  val colors = KetchTheme.colors
  val text = buildAnnotatedString {
    parts.forEachIndexed { index, part ->
      if (index > 0) append(" · ")
      val strong = part.endsWith("%") || part.endsWith("/s")
      withStyle(SpanStyle(color = if (strong) colors.textPrimary else colors.textSecondary)) {
        append(keepPartsTogether(part))
      }
    }
  }
  Text(text = text, style = KetchTheme.typography.numeral, color = colors.textTertiary)
}

/** The reason line, with its chip at the end. */
@Composable
private fun ReasonLine(row: TaskRow, reason: InspectorReason, onReason: (ReasonAction) -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val tint = if (reason.warning) colors.status.paused.color else colors.textTertiary
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier.fillMaxWidth(),
  ) {
    KetchIconImage(icon = reasonIcon(row, reason), size = spacing.s4, tint = tint)
    Text(
      text = reason.text,
      style = KetchTheme.typography.labelS,
      color = if (reason.warning) colors.status.paused.color else colors.textSecondary,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    val action = reason.action
    if (action != null) {
      KetchButton(
        text = action.label,
        onClick = { onReason(action) },
        variant = KetchButtonVariant.Secondary,
        size = KetchButtonSize.Small,
      )
    }
  }
}

private fun reasonIcon(row: TaskRow, reason: InspectorReason): KetchIcon = when {
  reason.warning -> KetchIcon.Warning
  reason.action == ReasonAction.FullSpeed -> KetchIcon.SlowLane
  reason.action != null -> KetchIcon.Speed
  row.state is DownloadState.Queued -> KetchIcon.Queued
  row.state is DownloadState.Scheduled -> KetchIcon.Scheduled
  row.state is DownloadState.Paused -> KetchIcon.Pause
  else -> KetchIcon.Info
}

/**
 * The action bar: the one or two things to do now for [row]'s state, Send to while it
 * downloads, then "⋯" with the rest of its actions, leaving out those the Controls set.
 *
 * @param instances the devices; the others are offered in Send to, with their health when a menu
 *   opens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionBar(
  state: AppState,
  row: TaskRow,
  runner: RowActionRunner,
  instances: List<InstanceEntry>,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val files = runner.files
  val missing = runner.isFileMissing(row)
  val remote = row.device.capabilities.isRemote
  val buttons = barActions(row, runner, missing)
  val canSend = instances.any { it.deviceId != row.key.deviceId }
  val sendInBar = row.state is DownloadState.Downloading && canSend
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    if (row.state is DownloadState.Completed && remote) {
      Text(
        text = row.content.detail,
        style = KetchTheme.typography.labelS,
        color = colors.textSecondary,
        modifier = Modifier.padding(end = spacing.s1),
      )
    }
    buttons.forEachIndexed { index, action ->
      KetchButton(
        text = rowActionLabel(action, files?.revealLabel),
        onClick = { runner.run(action, listOf(row)) },
        variant = if (index == 0 && action != RowAction.Pause) {
          KetchButtonVariant.Primary
        } else {
          KetchButtonVariant.Secondary
        },
        leadingIcon = action.icon,
        shortcut = action.command?.shortcutLabel(KeyboardPlatform.current),
      )
    }
    val path = row.outputFile
    if (files?.revealLabel == null && files?.canShare == true && path != null && !missing &&
      !remote && row.state is DownloadState.Completed
    ) {
      KetchButton(
        text = "Share",
        onClick = { state.runTaskCommand(row.task, "share ${row.name}") { files.share(path) } },
        variant = KetchButtonVariant.Secondary,
        leadingIcon = KetchIcon.Open,
      )
    }
    if (sendInBar) SendToButton(row, runner, instances)
    val inBar = if (sendInBar) buttons + RowAction.SendTo else buttons
    MoreButton(row, runner, instances.takeIf { canSend }, exclude = inBar.toSet() + InControls)
  }
}

/** The buttons of [row]'s state, primary first. */
private fun barActions(row: TaskRow, runner: RowActionRunner, missing: Boolean): List<RowAction> {
  val canRun = { action: RowAction -> runner.commands.canRun(action, row) }
  val actions = when (row.state) {
    is DownloadState.Downloading -> listOf(RowAction.Pause)
    is DownloadState.Paused -> listOf(RowAction.Resume)
    is DownloadState.Queued -> listOf(RowAction.StartNow)
    is DownloadState.Scheduled -> {
      listOfNotNull(RowAction.StartNow.takeIf { row.device.capabilities.canReschedule })
    }
    is DownloadState.Failed -> listOfNotNull(runner.primary(row))
    is DownloadState.Completed -> when {
      missing -> listOf(RowAction.DownloadAgain)
      row.device.capabilities.isRemote -> listOf(RowAction.CopyPath)
      else -> listOf(RowAction.Open, RowAction.ShowInFolder)
    }
    is DownloadState.Canceled -> listOf(RowAction.DownloadAgain)
  }
  return actions.filter(canRun)
}

/** Actions the Controls set, or that open the inspector itself, which "⋯" leaves out. */
private val InControls = setOf(
  RowAction.SpeedLimit,
  RowAction.Connections,
  RowAction.Priority,
  RowAction.StartLater,
  RowAction.Details,
)

@Composable
private fun SendToButton(row: TaskRow, runner: RowActionRunner, instances: List<InstanceEntry>) {
  var open by remember { mutableStateOf(false) }
  Box {
    KetchButton(
      text = "Send to",
      onClick = { open = true },
      variant = KetchButtonVariant.Secondary,
      leadingIcon = KetchIcon.Devices,
    )
    KetchMenu(expanded = open, onDismissRequest = { open = false }, title = "Send to") {
      sendEntries(listOf(row), runner, sendTargets(instances, listOf(row)))
    }
  }
}

/**
 * "⋯" with the actions of [row] that are not [exclude]d, destructive ones last. Send to lists the
 * other [instances]; it is left out when there are none (`null`).
 */
@Composable
private fun MoreButton(
  row: TaskRow,
  runner: RowActionRunner,
  instances: List<InstanceEntry>?,
  exclude: Set<RowAction>,
) {
  var open by remember { mutableStateOf(false) }
  val revealLabel = runner.files?.revealLabel
  Box {
    KetchIconButton(
      icon = KetchIcon.More,
      contentDescription = "More actions",
      onClick = { open = true },
    )
    KetchMenu(expanded = open, onDismissRequest = { open = false }, title = row.name) {
      if (runner.isFileMissing(row)) {
        item(
          label = "File moved or deleted",
          onClick = {},
          icon = KetchIcon.Warning,
          enabled = false,
        )
      }
      var destructive = false
      for (action in runner.menu(row).filter { it !in exclude }) {
        if (action.destructive && !destructive) {
          divider()
          destructive = true
        }
        if (action == RowAction.SendTo) {
          if (instances != null) {
            submenu(action.label, action.icon) {
              sendEntries(listOf(row), runner, sendTargets(instances, listOf(row)))
            }
          }
          continue
        }
        item(
          label = rowActionLabel(action, revealLabel),
          onClick = { runner.run(action, listOf(row)) },
          icon = action.icon,
          shortcut = action.command?.shortcutLabel(KeyboardPlatform.current),
          destructive = action.destructive,
        )
      }
    }
  }
}

/**
 * Whether [this] row has a strip worth drawing: segments, or a download running or paused,
 * whose strip shows its progress or that its size is unknown.
 */
private val TaskRow.hasLanes: Boolean
  get() = segments.isNotEmpty() || state is DownloadState.Downloading ||
    state is DownloadState.Paused

/** [text] with its " · "-separated parts kept whole, so it only wraps between them. */
internal fun keepPartsTogether(text: String): String =
  text.split(SEPARATOR).joinToString(SEPARATOR) { it.replace(' ', NO_BREAK) }

private const val NAME_LINES = 2
private const val ZERO_WIDTH_SPACE = "\u200B"
private val NameSeparator = Regex("[-_]")
private const val SEPARATOR = " · "
private const val NO_BREAK = '\u00A0'
private val CHECK_SHOWN = 1.2.seconds
