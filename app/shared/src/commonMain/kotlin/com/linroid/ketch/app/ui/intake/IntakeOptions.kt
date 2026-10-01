package com.linroid.ketch.app.ui.intake

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.app.components.ConnectionStepper
import com.linroid.ketch.app.components.DISABLED_ALPHA
import com.linroid.ketch.app.components.DeviceOption
import com.linroid.ketch.app.components.DeviceTargetChip
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.PEER_LIMIT_STEP
import com.linroid.ketch.app.components.PeerLimitRange
import com.linroid.ketch.app.components.SpeedLimitPicker
import com.linroid.ketch.app.components.StartTimeMenu
import com.linroid.ketch.app.components.connectionLabel
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.startTimeLabel
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.HeaderRow
import com.linroid.ketch.app.state.IntakeMode
import com.linroid.ketch.app.state.IntakeSession
import com.linroid.ketch.app.state.IntakeSource
import com.linroid.ketch.app.state.UserAgentChoice
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.folderLabel
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.toDeviceHealth
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.util.formatBytes
import com.linroid.ketch.app.util.priorityLabel
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/** The option pills under the rows: Save to, Speed, Priority, Start and Connections. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OptionPills(actions: IntakeActions) {
  val session = actions.session
  val spacing = KetchTheme.spacing
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
    itemVerticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth(),
  ) {
    if (session.mode == IntakeMode.Add) SaveToPill(actions)
    SpeedPill(session)
    PriorityPill(session)
    if (session.mode != IntakeMode.Retry) StartPill(session)
    ConnectionsPill(session)
    if (session.mode != IntakeMode.Edit) AdvancedToggle(session)
  }
  if (session.isSystemFolder && session.entries.any { it.isTorrent }) {
    NoticeLine(
      text = "Torrents save to the app folder: folders picked in Files only take links",
      icon = KetchIcon.Info,
    )
  }
}

/**
 * A 28 dp pill that names an option and its value and opens a menu to change it. A value other
 * than the default is tinted with the accent.
 */
@Composable
private fun OptionPill(
  label: String?,
  value: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: KetchIcon? = null,
  changed: Boolean = false,
  enabled: Boolean = true,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.full
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions, enabled)
  val focus = rememberFocusVisibility()
  val ink = if (changed) colors.accentText else colors.textPrimary
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = modifier
      .focusRing(focus.visible, shape, colors.focusRing)
      .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
      .height(KetchTheme.density.chip)
      .clip(shape)
      .background(if (changed) colors.accentSoft else colors.surface)
      .background(overlay)
      .border(
        width = IntakeSheetDefaults.Hairline,
        color = if (changed) colors.accentSoft else colors.borderStrong,
        shape = shape,
      )
      .trackFocusVisibility(focus)
      .clickable(
        interactionSource = interactions,
        indication = null,
        enabled = enabled,
        role = Role.DropdownList,
        onClick = onClick,
      )
      .padding(start = spacing.s3, end = spacing.s2),
  ) {
    if (icon != null) {
      KetchIconImage(
        icon = icon,
        size = KetchTheme.density.controlGlyph,
        tint = if (changed) colors.accentText else colors.textSecondary,
      )
    }
    if (label != null) {
      Text(
        text = "$label:",
        style = KetchTheme.typography.labelS,
        color = if (changed) colors.accentText else colors.textTertiary,
        maxLines = 1,
      )
    }
    Text(
      text = value,
      style = KetchTheme.typography.labelS,
      fontWeight = FontWeight.SemiBold,
      color = ink,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    KetchIconImage(KetchIcon.ChevronDown, size = spacing.s3, tint = colors.textSecondary)
  }
}

@Composable
private fun SaveToPill(actions: IntakeActions) {
  val session = actions.session
  var expanded by remember { mutableStateOf(false) }
  val folder = session.folder
  val default = session.defaultFolder
  val free = session.targetStatus?.system?.usableSpace?.takeIf { folder == null && it > 0 }
  val name = folderLabel(folder ?: default ?: "Downloads")
  val local = session.target is EmbeddedInstance && actions.picker.canPickFolder
  Box {
    OptionPill(
      label = "Save to",
      value = name + (free?.let { " · ${freeSpace(it)} free" } ?: ""),
      icon = KetchIcon.Folder,
      changed = folder != null,
      onClick = { expanded = true },
    )
    KetchMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      title = "Save to",
    ) {
      item(
        label = default?.let(::folderLabel) ?: "Default folder",
        caption = default,
        icon = KetchIcon.Folder,
        checked = folder == null,
        onClick = { session.folder = null },
      )
      val recent = session.recentFolders.filter { it !in session.pinnedFolders }
      if (recent.isNotEmpty()) {
        header("Recent")
        recent.forEach { path ->
          item(
            label = folderLabel(path),
            caption = path,
            checked = folder == path,
            onClick = { session.folder = path },
          )
        }
      }
      if (session.pinnedFolders.isNotEmpty()) {
        header("Pinned")
        session.pinnedFolders.forEach { path ->
          item(
            label = folderLabel(path),
            caption = path,
            icon = KetchIcon.Pennant,
            checked = folder == path,
            onClick = { session.folder = path },
          )
        }
      }
      if (folder != null) {
        if (folder in session.pinnedFolders) {
          item(label = "Unpin ${folderLabel(folder)}", onClick = { session.unpinFolder(folder) })
        } else {
          item(
            label = "Pin ${folderLabel(folder)}",
            icon = KetchIcon.Plus,
            onClick = { session.pinFolder(folder) },
          )
        }
      }
      divider()
      if (local) {
        item(label = "Choose folder…", icon = KetchIcon.Folder, onClick = actions::pickFolder)
      } else {
        custom { dismiss -> FolderField(session, dismiss) }
      }
    }
  }
}

/** A path typed for a device whose folders the app cannot browse, such as a remote one. */
@Composable
private fun FolderField(session: IntakeSession, dismiss: () -> Unit) {
  var path by remember { mutableStateOf(session.folder.orEmpty()) }
  val commit = {
    session.folder = path.trim().ifEmpty { null }
    dismiss()
  }
  KetchTextField(
    value = path,
    onValueChange = { path = it },
    label = "Folder on ${session.targetName()}",
    placeholder = session.defaultFolder.orEmpty(),
    mono = true,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { commit() }),
    modifier = Modifier
      .width(IntakeSheetDefaults.MenuFieldWidth)
      .padding(KetchTheme.spacing.s2),
  )
}

@Composable
private fun SpeedPill(session: IntakeSession) {
  var expanded by remember { mutableStateOf(false) }
  val limit = session.speedLimit
  Box {
    OptionPill(
      label = "Speed",
      value = if (limit.isUnlimited) "Unlimited" else formatSpeedLimit(limit),
      icon = KetchIcon.Speed,
      changed = !limit.isUnlimited,
      onClick = { expanded = true },
    )
    KetchMenu(expanded = expanded, onDismissRequest = { expanded = false }, title = "Speed limit") {
      custom {
        SpeedLimitPicker(
          value = session.speedLimit,
          onCommit = { session.speedLimit = it },
          modifier = Modifier
            .width(IntakeSheetDefaults.MenuFieldWidth)
            .padding(KetchTheme.spacing.s2),
        )
      }
    }
  }
}

@Composable
private fun PriorityPill(session: IntakeSession) {
  var expanded by remember { mutableStateOf(false) }
  val priority = session.priority
  Box {
    OptionPill(
      label = "Priority",
      value = (if (priority == DownloadPriority.URGENT) "⚡ " else "") + priorityLabel(priority),
      changed = priority != DownloadPriority.NORMAL,
      onClick = { expanded = true },
    )
    KetchMenu(expanded = expanded, onDismissRequest = { expanded = false }, title = "Priority") {
      for (option in PRIORITIES) {
        item(
          label = priorityLabel(option),
          caption = priorityCaption(option),
          icon = if (option == DownloadPriority.URGENT) KetchIcon.Bolt else null,
          checked = option == priority,
          onClick = { session.priority = option },
        )
      }
    }
  }
}

@Composable
private fun StartPill(session: IntakeSession) {
  var expanded by remember { mutableStateOf(false) }
  val schedule = session.schedule
  val scheduled = schedule != DownloadSchedule.Immediate
  Box {
    OptionPill(
      label = if (scheduled) null else "Start",
      value = if (scheduled) {
        startTimeLabel(schedule, Clock.System.now(), TimeZone.currentSystemDefault())
      } else {
        "Now"
      },
      icon = KetchIcon.Scheduled,
      changed = scheduled,
      onClick = { expanded = true },
    )
    StartTimeMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      value = schedule,
      onSelect = { session.schedule = it },
    )
  }
}

@Composable
private fun ConnectionsPill(session: IntakeSession) {
  var expanded by remember { mutableStateOf(false) }
  val torrents = session.entries.isNotEmpty() && session.entries.all { it.isTorrent }
  val connections = session.connections
  val max = session.maxConnections ?: IntakeSession.MAX_CONNECTIONS
  val single = !torrents && max <= 1
  val auto = if (torrents) null else session.autoConnections?.coerceAtMost(max)
  val pill = @Composable {
    Box {
      OptionPill(
        label = if (torrents) "Peer limit" else "Connections",
        value = if (single) "1" else connectionLabel(connections, auto),
        icon = KetchIcon.Lanes,
        changed = connections != 0,
        enabled = !single,
        onClick = { expanded = true },
      )
      KetchMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        title = if (torrents) "Peer limit" else "Connections",
      ) {
        item(
          label = "Auto",
          caption = auto?.let { "The device's setting: $it" },
          checked = connections == 0,
          onClick = { session.connections = 0 },
        )
        custom {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s3),
            modifier = Modifier.padding(KetchTheme.spacing.s2),
          ) {
            Text(
              text = if (torrents) "Peers" else "Connections",
              style = KetchTheme.typography.label,
              color = KetchTheme.colors.textSecondary,
            )
            ConnectionStepper(
              value = connections,
              onCommit = { session.connections = it },
              autoValue = auto,
              range = if (torrents) PeerLimitRange else 1..max,
              step = if (torrents) PEER_LIMIT_STEP else 1,
              noun = if (torrents) "peers" else "connections",
            )
          }
        }
      }
    }
  }
  if (single) {
    KetchTooltip(text = "This server allows 1 connection") { pill() }
  } else {
    pill()
  }
}

@Composable
private fun AdvancedToggle(session: IntakeSession) {
  KetchButton(
    text = "Advanced",
    onClick = { session.updateAdvancedOpen(!session.advancedOpen) },
    variant = KetchButtonVariant.Ghost,
    size = KetchButtonSize.Small,
    leadingIcon = if (session.advancedOpen) KetchIcon.ChevronDown else KetchIcon.Chevron,
  )
}

/** The pill that picks the device downloads go to; shown with two devices or more. */
@Composable
internal fun TargetChip(session: IntakeSession, instances: List<InstanceEntry>) {
  val options = instances.map { entry ->
    key(entry.deviceId) {
      val health = when (entry) {
        is RemoteInstance -> entry.connectionState.collectAsState().value.toDeviceHealth()
        else -> DeviceHealth.Local()
      }
      val summary = if (entry == session.target) {
        session.targetStatus?.system?.usableSpace?.takeIf { it > 0 }
          ?.let { "${freeSpace(it)} free" }
      } else {
        null
      }
      DeviceOption(
        id = entry.deviceId,
        name = if (entry is EmbeddedInstance) localDeviceNoun() else entry.label,
        health = health,
        pennantName = entry.label,
        summary = summary,
      )
    }
  }
  DeviceTargetChip(
    selectedId = session.target?.deviceId.orEmpty(),
    options = options,
    onSelect = { option ->
      instances.firstOrNull { it.deviceId == option.id }?.let(session::selectTarget)
    },
  )
}

/**
 * Advanced: the file name of a single link, and request headers sent with every link: Referer,
 * User-Agent, Cookie, Authorization and any other.
 */
@Composable
internal fun AdvancedSection(actions: IntakeActions) {
  val session = actions.session
  val headers = session.headers
  val spacing = KetchTheme.spacing
  val colors = KetchTheme.colors
  val changed = { session.onHeadersChanged() }
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      .ketchSurface(KetchElevationLevel.E0, KetchTheme.shapes.lg, colors.surface, colors.hairline)
      .padding(spacing.s3),
  ) {
    val single = session.single?.takeIf { it.source is IntakeSource.Link && !it.isTorrent }
    val own = (single?.source as? IntakeSource.Link)?.headers.orEmpty()
    if (own.isNotEmpty()) {
      Text(
        text = "Also sent with this link: ${own.keys.joinToString(", ")}",
        style = KetchTheme.typography.caption,
        color = colors.textSecondary,
      )
    }
    if (single != null) {
      KetchTextField(
        value = single.fileName,
        onValueChange = { single.fileName = it },
        label = "File name",
        placeholder = single.resolved?.suggestedFileName ?: single.name,
        enabled = !session.isSystemFolder,
        modifier = Modifier.fillMaxWidth(),
      )
      if (session.isSystemFolder) {
        Text(
          text = "Uses the server's file name",
          style = KetchTheme.typography.caption,
          color = colors.textTertiary,
        )
      }
    }
    KetchTextField(
      value = headers.referer,
      onValueChange = {
        headers.referer = it
        changed()
      },
      label = "Referer",
      placeholder = "https://",
      mono = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
      Text("User-Agent", style = KetchTheme.typography.labelS, color = colors.textSecondary)
      KetchSegmented(
        options = UserAgentChoice.entries,
        selected = headers.userAgent,
        onSelect = {
          headers.userAgent = it
          changed()
        },
        label = { it.label },
      )
      if (headers.userAgent == UserAgentChoice.Custom) {
        KetchTextField(
          value = headers.customUserAgent,
          onValueChange = {
            headers.customUserAgent = it
            changed()
          },
          placeholder = "Mozilla/5.0 …",
          mono = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
    SecretField(
      value = headers.cookie,
      onValueChange = {
        headers.cookie = it
        changed()
      },
      label = "Cookie",
      placeholder = "name=value; other=value",
      multiline = true,
    )
    SecretField(
      value = headers.authorization,
      onValueChange = {
        headers.authorization = it
        changed()
      },
      label = "Authorization",
      placeholder = "Bearer …",
    )
    for (row in headers.extra.toList()) {
      ExtraHeader(
        row = row,
        onChange = changed,
        onRemove = {
          headers.extra.remove(row)
          changed()
        },
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
      KetchButton(
        text = "Add header",
        onClick = { headers.extra += HeaderRow() },
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
        leadingIcon = KetchIcon.Plus,
      )
      KetchButton(
        text = "Paste cURL",
        onClick = { actions.pasteCurl() },
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
        leadingIcon = KetchIcon.Copy,
      )
    }
    Text(
      text = "Headers are saved with the task and visible to devices connected to this one.",
      style = KetchTheme.typography.caption,
      color = colors.textTertiary,
    )
  }
}

/** A field whose value is masked while it does not have the focus, such as a cookie. */
@Composable
private fun SecretField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  placeholder: String,
  multiline: Boolean = false,
) {
  var focused by remember { mutableStateOf(false) }
  KetchTextField(
    value = value,
    onValueChange = onValueChange,
    label = label,
    placeholder = placeholder,
    mono = true,
    maxLines = if (multiline) SECRET_LINES else 1,
    onFocusChange = { focused = it },
    visualTransformation = if (focused) {
      VisualTransformation.None
    } else {
      PasswordVisualTransformation()
    },
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun ExtraHeader(row: HeaderRow, onChange: () -> Unit, onRemove: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
  ) {
    KetchTextField(
      value = row.name,
      onValueChange = {
        row.name = it
        onChange()
      },
      placeholder = "Header",
      clearable = false,
      modifier = Modifier.weight(HEADER_NAME_WEIGHT),
    )
    KetchTextField(
      value = row.value,
      onValueChange = {
        row.value = it
        onChange()
      },
      placeholder = "Value",
      mono = true,
      modifier = Modifier.weight(1f),
    )
    KetchIconButton(
      icon = KetchIcon.Close,
      contentDescription = "Remove header",
      size = KetchButtonSize.Small,
      onClick = onRemove,
    )
  }
}

/** Free space in whole gigabytes once there are ten or more, such as "412 GB". */
internal fun freeSpace(bytes: Long): String =
  if (bytes >= 10 * GIB) "${bytes / GIB} GB" else formatBytes(bytes)

private fun priorityCaption(priority: DownloadPriority): String = when (priority) {
  DownloadPriority.LOW -> "Runs when nothing else is waiting"
  DownloadPriority.NORMAL -> "Default order"
  DownloadPriority.HIGH -> "Ahead of Normal and Low"
  DownloadPriority.URGENT -> "Jumps the queue; may pause a lower-priority download"
}

private val PRIORITIES = listOf(
  DownloadPriority.LOW,
  DownloadPriority.NORMAL,
  DownloadPriority.HIGH,
  DownloadPriority.URGENT,
)

private const val GIB = 1L shl 30
private const val SECRET_LINES = 3
private const val HEADER_NAME_WEIGHT = 0.4f
