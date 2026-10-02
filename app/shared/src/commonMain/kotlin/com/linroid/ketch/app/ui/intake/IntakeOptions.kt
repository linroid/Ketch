package com.linroid.ketch.app.ui.intake

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.app.components.ConnectionStepper
import com.linroid.ketch.app.components.DISABLED_ALPHA
import com.linroid.ketch.app.components.DeviceOption
import com.linroid.ketch.app.components.DeviceTargetChip
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.components.PEER_LIMIT_STEP
import com.linroid.ketch.app.components.PeerLimitRange
import com.linroid.ketch.app.components.SpeedLimitPicker
import com.linroid.ketch.app.components.StartTimePicker
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.HeaderRow
import com.linroid.ketch.app.state.IntakeMode
import com.linroid.ketch.app.state.IntakeOptionValue
import com.linroid.ketch.app.state.IntakeSession
import com.linroid.ketch.app.state.IntakeSource
import com.linroid.ketch.app.state.UserAgentChoice
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.folderLabel
import com.linroid.ketch.app.state.formatSpace
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.state.toDeviceHealth
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.inspector.FirstThatFits
import com.linroid.ketch.app.util.priorityLabel

/**
 * The one line of options under the rows: where to save, and an Options pill that sums up the
 * speed limit, priority, start and connections ("Unlimited · Normal · Now · Auto") and opens a
 * popover holding all four. Values changed from their defaults show as chips after the pill
 * instead, such as "⚡ Urgent ✕", whose ✕ puts the default back.
 *
 * The line never wraps: as the room shrinks, Save to drops its label and then the free space,
 * the Options pill its summary and then its label, and last the line scrolls sideways.
 */
@Composable
internal fun OptionsRow(actions: IntakeActions) {
  val session = actions.session
  val values = session.optionValues
  // The box keeps the line at the start; the variant that fits is only as wide as it needs.
  Box(Modifier.fillMaxWidth()) {
    FirstThatFits(count = OPTION_LINE_VARIANTS) { variant ->
      OptionsLine(actions, values, OptionLineStyle.entries[variant])
    }
  }
  if (session.isSystemFolder && session.entries.any { it.isTorrent }) {
    NoticeLine(
      text = "Torrents save to the app folder: folders picked in Files only take links",
      icon = KetchIcon.Info,
    )
  }
}

/** How much one variant of the [OptionsRow] spells out, from the fullest to the most compact. */
private enum class OptionLineStyle(
  val saveToLabel: Boolean = false,
  val freeSpace: Boolean = false,
  val summary: Boolean = false,
  val optionsLabel: Boolean = true,
  val scrolls: Boolean = false,
) {
  Full(saveToLabel = true, freeSpace = true, summary = true),
  NoSaveToLabel(freeSpace = true, summary = true),
  NoFreeSpace(summary = true),
  NoSummary,
  IconOnly(optionsLabel = false),
  Scrolling(optionsLabel = false, scrolls = true),
}

private val OPTION_LINE_VARIANTS = OptionLineStyle.entries.size

@Composable
private fun OptionsLine(
  actions: IntakeActions,
  values: List<IntakeOptionValue>,
  style: OptionLineStyle,
) {
  val session = actions.session
  var optionsOpen by remember { mutableStateOf(false) }
  val changed = values.filter { it.changed }
  // The pill sums up only a sheet without changes; otherwise the chips say what changed.
  val summary = if (style.summary && changed.isEmpty()) {
    values.joinToString(SEPARATOR) { it.text }
  } else {
    ""
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
    modifier = if (style.scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier,
  ) {
    if (session.mode == IntakeMode.Add) {
      SaveToPill(actions = actions, label = style.saveToLabel, showFree = style.freeSpace)
    }
    Box {
      OptionPill(
        label = "Options".takeIf { style.optionsLabel },
        value = summary,
        icon = KetchIcon.Lanes,
        description = "Options",
        onClick = { optionsOpen = true },
      )
      OptionsMenu(session, expanded = optionsOpen, onDismiss = { optionsOpen = false })
    }
    for (value in changed) {
      ValueChip(
        text = value.text,
        onClick = { optionsOpen = true },
        onRemove = { session.resetOption(value.option) },
        removeLabel = "Reset ${value.option.name.lowercase()}",
      )
    }
    HeadersChip(session)
  }
}

/** A quiet chip that tells headers are sent while the Advanced section is closed. */
@Composable
private fun HeadersChip(session: IntakeSession) {
  if (session.mode == IntakeMode.Edit || session.advancedOpen) return
  val count = session.headers.toMap().size
  if (count == 0) return
  ValueChip(
    text = if (count == 1) "1 header" else "$count headers",
    onClick = { session.updateAdvancedOpen(true) },
    onRemove = null,
  )
}

/**
 * The Options popover: the four controls, and the Advanced section's switch, which a retry and
 * an add offer. On touch it is a bottom sheet.
 */
@Composable
private fun OptionsMenu(session: IntakeSession, expanded: Boolean, onDismiss: () -> Unit) {
  KetchMenu(expanded = expanded, onDismissRequest = onDismiss, title = "Options") {
    custom {
      OptionsPanel(
        session = session,
        modifier = Modifier
          .width(IntakeSheetDefaults.OptionsWidth)
          .padding(horizontal = KetchTheme.spacing.s3, vertical = KetchTheme.spacing.s2),
      )
    }
    if (session.mode != IntakeMode.Edit) {
      divider()
      item(
        label = if (session.advancedOpen) "Hide advanced options" else "Advanced options…",
        caption = "File name, referer, cookies and other headers",
        icon = KetchIcon.Settings,
        onClick = { session.updateAdvancedOpen(!session.advancedOpen) },
      )
    }
  }
}

/**
 * Speed limit, priority, start and connections of every download in the sheet. The Options
 * popover holds it, and the sheet that edits a task shows it in place.
 */
@Composable
internal fun OptionsPanel(session: IntakeSession, modifier: Modifier = Modifier) {
  val spacing = KetchTheme.spacing
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s4), modifier = modifier) {
    OptionField("Speed limit") {
      SpeedLimitPicker(
        value = session.speedLimit,
        onCommit = { session.speedLimit = it },
        modifier = Modifier.fillMaxWidth(),
      )
    }
    OptionField("Priority", caption = priorityCaption(session.priority)) {
      KetchSegmented(
        options = PRIORITIES,
        selected = session.priority,
        onSelect = { session.priority = it },
        label = ::priorityLabel,
        icon = { priority -> KetchIcon.Bolt.takeIf { priority == DownloadPriority.URGENT } },
      )
    }
    if (session.mode != IntakeMode.Retry) {
      OptionField("Start") {
        StartTimePicker(value = session.schedule, onSelect = { session.schedule = it })
      }
    }
    ConnectionsField(session)
  }
}

@Composable
private fun ConnectionsField(session: IntakeSession) {
  val torrents = session.torrentsOnly
  val connections = session.connections
  val max = session.maxConnections ?: IntakeSession.MAX_CONNECTIONS
  val single = !torrents && max <= 1
  val auto = if (torrents) null else session.autoConnections?.coerceAtMost(max)
  val caption = when {
    single -> "This server allows 1 connection"
    torrents -> "Auto lets the device decide"
    auto != null -> "Auto uses the device's setting: $auto"
    else -> null
  }
  OptionField(if (torrents) "Peer limit" else "Connections", caption = caption) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
    ) {
      ConnectionStepper(
        value = if (single) 1 else connections,
        onCommit = { session.connections = it },
        autoValue = auto,
        range = if (torrents) PeerLimitRange else 1..max,
        step = if (torrents) PEER_LIMIT_STEP else 1,
        enabled = !single,
        noun = if (torrents) "peers" else "connections",
        disabledReason = "This server allows 1 connection".takeIf { single },
      )
      if (connections != 0 && !single) {
        KetchButton(
          text = "Auto",
          onClick = { session.connections = 0 },
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
        )
      }
    }
  }
}

/** An eyebrow [label] over a control, with an optional [caption] under it. */
@Composable
private fun OptionField(
  label: String,
  caption: String? = null,
  content: @Composable () -> Unit,
) {
  val colors = KetchTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2)) {
    KetchEyebrow(label)
    content()
    if (caption != null) {
      Text(text = caption, style = KetchTheme.typography.caption, color = colors.textSecondary)
    }
  }
}

/**
 * A 28 dp pill that names an option and its value and opens a menu to change it. A value other
 * than the default is tinted with the accent. Without a [value] the [label] reads as one.
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
  description: String? = null,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.full
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions, enabled)
  val focus = rememberFocusVisibility()
  val ink = if (changed) colors.accentText else colors.textPrimary
  val text = value.ifEmpty { label.orEmpty() }
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
      .semantics { if (text.isEmpty() && description != null) contentDescription = description }
      .ketchClickable(interactions, enabled = enabled, role = Role.DropdownList, onClick = onClick)
      .padding(start = if (text.isEmpty()) spacing.s2 else spacing.s3, end = spacing.s2),
  ) {
    if (icon != null) {
      KetchIconImage(
        icon = icon,
        size = KetchTheme.density.controlGlyph,
        tint = if (changed) colors.accentText else colors.textSecondary,
      )
    }
    if (label != null && value.isNotEmpty()) {
      Text(
        text = "$label:",
        style = KetchTheme.typography.labelS,
        color = if (changed) colors.accentText else colors.textTertiary,
        maxLines = 1,
      )
    }
    if (text.isNotEmpty()) {
      Text(
        text = text,
        style = KetchTheme.typography.labelS,
        fontWeight = FontWeight.SemiBold,
        color = ink,
        maxLines = 1,
      )
    }
    KetchIconImage(KetchIcon.ChevronDown, size = spacing.s3, tint = colors.textSecondary)
  }
}

/**
 * A changed option after the Options pill, such as "⚡ Urgent", on the accent tint: a click
 * opens the popover, and its ✕, when there is [onRemove], puts the default back.
 */
@Composable
private fun ValueChip(
  text: String,
  onClick: () -> Unit,
  onRemove: (() -> Unit)?,
  removeLabel: String = "Remove $text",
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.full
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = Modifier
      .focusRing(focus.visible, shape, colors.focusRing)
      .height(KetchTheme.density.chip)
      .clip(shape)
      .background(colors.accentSoft)
      .background(overlay)
      .ketchClickable(interactions, focus, onClick = onClick)
      .padding(start = spacing.s3, end = if (onRemove != null) spacing.s1 else spacing.s3),
  ) {
    Text(
      text = text,
      style = KetchTheme.typography.labelS,
      fontWeight = FontWeight.SemiBold,
      color = colors.accentText,
      maxLines = 1,
    )
    if (onRemove != null) {
      val removeInteractions = remember { MutableInteractionSource() }
      val removeOverlay = rememberInteractionOverlay(removeInteractions)
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .size(spacing.s5)
          .clip(shape)
          .background(removeOverlay)
          .semantics { contentDescription = removeLabel }
          .ketchClickable(removeInteractions, onClick = onRemove),
      ) {
        KetchIconImage(KetchIcon.Close, size = spacing.s3, tint = colors.accentText)
      }
    }
  }
}

@Composable
private fun SaveToPill(
  actions: IntakeActions,
  label: Boolean,
  showFree: Boolean,
  modifier: Modifier = Modifier,
) {
  val session = actions.session
  var expanded by remember { mutableStateOf(false) }
  val folder = session.folder
  val default = session.defaultFolder
  val free = session.targetStatus?.system?.usableSpace?.takeIf { folder == null && it > 0 }
  val name = folderLabel(folder ?: default ?: "Downloads")
  val local = session.target is EmbeddedInstance && actions.picker.canPickFolder
  Box(modifier) {
    OptionPill(
      label = "Save to".takeIf { label },
      value = name + (free?.takeIf { showFree }?.let { " · ${freeSpace(it)} free" } ?: ""),
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

/**
 * The pill that picks the device downloads go to; shown with two devices or more. Each device
 * in its menu says what it is doing, such as "1.8 TB free · 2 active · Slow lane", and the
 * chord that picks it while the sheet is open.
 */
@Composable
internal fun TargetChip(session: IntakeSession, instances: List<InstanceEntry>) {
  val presence by session.presence.collectAsState()
  val options = instances.mapIndexed { index, entry ->
    key(entry.deviceId) {
      val health = when (entry) {
        is RemoteInstance -> entry.connectionState.collectAsState().value.toDeviceHealth()
        else -> DeviceHealth.Local()
      }
      val free = if (entry == session.target) {
        session.targetStatus?.system?.usableSpace?.takeIf { it > 0 }
      } else {
        null
      }
      DeviceOption(
        id = entry.deviceId,
        name = entry.displayName,
        health = health,
        pennantName = entry.label,
        summary = targetSummary(presence.firstOrNull { it.deviceId == entry.deviceId }, free),
        shortcut = (index + 1).takeIf { it <= MAX_TARGET_SHORTCUTS }
          ?.let { KetchCommands.intakeTarget(it).shortcutLabel() },
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
    modifier = Modifier.intakeCard().padding(spacing.s3),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      KetchEyebrow("Advanced", Modifier.weight(1f))
      KetchButton(
        text = "Hide",
        onClick = { session.updateAdvancedOpen(false) },
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
      )
    }
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
        placeholder = single.name,
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

/**
 * What a device in the target menu is doing: "1.8 TB free · 2 active · Slow lane", from its
 * [presence], with [free] bytes when the sheet read them more recently; `null` when nothing is
 * known yet.
 */
internal fun targetSummary(presence: DevicePresence?, free: Long? = null): String? {
  val usable = free ?: presence?.disk?.usableBytes?.takeIf { it > 0 }
  val active = presence?.counts?.downloading ?: 0
  return listOfNotNull(
    usable?.let { "${freeSpace(it)} free" },
    "$active active".takeIf { active > 0 },
    "Slow lane".takeIf { presence?.speedMode?.isSlowLane == true },
  ).joinToString(" · ").ifEmpty { null }
}

private const val MAX_TARGET_SHORTCUTS = 9

/** Free space as the Pulse bar says it, such as "412 GB", "3.1 GB" or "1.6 TB". */
internal fun freeSpace(bytes: Long): String = formatSpace(bytes)

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

private const val SECRET_LINES = 3
private const val SEPARATOR = " · "
private const val HEADER_NAME_WEIGHT = 0.4f
