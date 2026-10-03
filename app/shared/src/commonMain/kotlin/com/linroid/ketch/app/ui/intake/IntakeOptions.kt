package com.linroid.ketch.app.ui.intake

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.components.PEER_LIMIT_STEP
import com.linroid.ketch.app.components.PeerLimitRange
import com.linroid.ketch.app.components.SpeedLimitPicker
import com.linroid.ketch.app.components.StartTimePicker
import com.linroid.ketch.app.components.StepperCount
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.priorityText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
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
import com.linroid.ketch.app.state.IntakeHeaders
import com.linroid.ketch.app.state.IntakeMode
import com.linroid.ketch.app.state.IntakeOption
import com.linroid.ketch.app.state.IntakeOptionValue
import com.linroid.ketch.app.state.IntakeSession
import com.linroid.ketch.app.state.IntakeSource
import com.linroid.ketch.app.state.UserAgentChoice
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.folderLabel
import com.linroid.ketch.app.state.formatSpace
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.state.toDeviceHealth
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.inspector.FirstThatFits
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.device_active_downloads
import ketch.app.shared.generated.resources.device_free_space
import ketch.app.shared.generated.resources.intake_add_header
import ketch.app.shared.generated.resources.intake_advanced
import ketch.app.shared.generated.resources.intake_advanced_caption
import ketch.app.shared.generated.resources.intake_advanced_hide
import ketch.app.shared.generated.resources.intake_advanced_show
import ketch.app.shared.generated.resources.intake_also_sent
import ketch.app.shared.generated.resources.intake_auto_device
import ketch.app.shared.generated.resources.intake_auto_setting
import ketch.app.shared.generated.resources.intake_choose_folder
import ketch.app.shared.generated.resources.intake_connections
import ketch.app.shared.generated.resources.intake_default_folder
import ketch.app.shared.generated.resources.intake_file_name
import ketch.app.shared.generated.resources.intake_folder_downloads
import ketch.app.shared.generated.resources.intake_folder_on
import ketch.app.shared.generated.resources.intake_header_name
import ketch.app.shared.generated.resources.intake_header_value
import ketch.app.shared.generated.resources.intake_headers_count
import ketch.app.shared.generated.resources.intake_headers_saved
import ketch.app.shared.generated.resources.intake_hide
import ketch.app.shared.generated.resources.intake_option_auto
import ketch.app.shared.generated.resources.intake_options
import ketch.app.shared.generated.resources.intake_paste_curl
import ketch.app.shared.generated.resources.intake_peer_limit
import ketch.app.shared.generated.resources.intake_pill_label
import ketch.app.shared.generated.resources.intake_pin
import ketch.app.shared.generated.resources.intake_pinned
import ketch.app.shared.generated.resources.intake_priority
import ketch.app.shared.generated.resources.intake_priority_high_caption
import ketch.app.shared.generated.resources.intake_priority_low_caption
import ketch.app.shared.generated.resources.intake_priority_normal_caption
import ketch.app.shared.generated.resources.intake_priority_urgent_caption
import ketch.app.shared.generated.resources.intake_recent
import ketch.app.shared.generated.resources.intake_remove_header
import ketch.app.shared.generated.resources.intake_remove_named
import ketch.app.shared.generated.resources.intake_reset_connections
import ketch.app.shared.generated.resources.intake_reset_priority
import ketch.app.shared.generated.resources.intake_reset_speed
import ketch.app.shared.generated.resources.intake_reset_start
import ketch.app.shared.generated.resources.intake_save_to
import ketch.app.shared.generated.resources.intake_server_file_name
import ketch.app.shared.generated.resources.intake_single_connection
import ketch.app.shared.generated.resources.intake_speed_limit
import ketch.app.shared.generated.resources.intake_start
import ketch.app.shared.generated.resources.intake_torrents_app_folder
import ketch.app.shared.generated.resources.intake_unpin
import ketch.app.shared.generated.resources.pulse_slow_lane
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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
      text = stringResource(Res.string.intake_torrents_app_folder),
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
    values.map { it.text }.joinText().resolve()
  } else {
    ""
  }
  val options = stringResource(Res.string.intake_options)
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
        label = options.takeIf { style.optionsLabel },
        value = summary,
        icon = KetchIcon.Lanes,
        description = options,
        onClick = { optionsOpen = true },
      )
      OptionsMenu(session, expanded = optionsOpen, onDismiss = { optionsOpen = false })
    }
    for (value in changed) {
      ValueChip(
        text = value.text.resolve(),
        onClick = { optionsOpen = true },
        onRemove = { session.resetOption(value.option) },
        removeLabel = stringResource(resetLabel(value.option)),
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
    text = pluralStringResource(Res.plurals.intake_headers_count, count, count),
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
  KetchMenu(
    expanded = expanded,
    onDismissRequest = onDismiss,
    title = stringResource(Res.string.intake_options),
  ) {
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
        label = if (session.advancedOpen) {
          Res.string.intake_advanced_hide.text()
        } else {
          Res.string.intake_advanced_show.text()
        },
        caption = Res.string.intake_advanced_caption.text(),
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
    OptionField(stringResource(Res.string.intake_speed_limit)) {
      SpeedLimitPicker(
        value = session.speedLimit,
        onCommit = { session.speedLimit = it },
        modifier = Modifier.fillMaxWidth(),
      )
    }
    OptionField(
      label = stringResource(Res.string.intake_priority),
      caption = stringResource(priorityCaption(session.priority)),
    ) {
      KetchSegmented(
        options = PRIORITIES,
        selected = session.priority,
        onSelect = { session.priority = it },
        label = { priorityText(it).resolve() },
        icon = { priority -> KetchIcon.Bolt.takeIf { priority == DownloadPriority.URGENT } },
      )
    }
    if (session.mode != IntakeMode.Retry) {
      OptionField(stringResource(Res.string.intake_start)) {
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
  val singleReason = stringResource(Res.string.intake_single_connection)
  val caption = when {
    single -> singleReason
    torrents -> stringResource(Res.string.intake_auto_device)
    auto != null -> stringResource(Res.string.intake_auto_setting, auto)
    else -> null
  }
  val label = if (torrents) Res.string.intake_peer_limit else Res.string.intake_connections
  OptionField(stringResource(label), caption = caption) {
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
        counts = if (torrents) StepperCount.Peers else StepperCount.Connections,
        disabledReason = singleReason.takeIf { single },
      )
      if (connections != 0 && !single) {
        KetchButton(
          text = stringResource(Res.string.intake_option_auto),
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
    Text(
      text = eyebrowText(label),
      style = KetchTheme.typography.eyebrow,
      color = colors.textTertiary,
    )
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
      .clickable(
        interactionSource = interactions,
        indication = null,
        enabled = enabled,
        role = Role.DropdownList,
        onClick = onClick,
      )
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
        text = stringResource(Res.string.intake_pill_label, label),
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
  removeLabel: String = stringResource(Res.string.intake_remove_named, text),
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
      .trackFocusVisibility(focus)
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClick = onClick,
      )
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
          .clickable(
            interactionSource = removeInteractions,
            indication = null,
            role = Role.Button,
            onClick = onRemove,
          ),
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
  val name = folderLabel(folder ?: default ?: stringResource(Res.string.intake_folder_downloads))
  val saveTo = stringResource(Res.string.intake_save_to)
  val local = session.target is EmbeddedInstance && actions.picker.canPickFolder
  Box(modifier) {
    OptionPill(
      label = saveTo.takeIf { label },
      value = listOfNotNull(
        verbatim(name),
        free?.takeIf { showFree }?.let { Res.string.device_free_space.text(freeSpace(it)) },
      ).joinText().resolve(),
      icon = KetchIcon.Folder,
      changed = folder != null,
      onClick = { expanded = true },
    )
    KetchMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      title = saveTo,
    ) {
      item(
        label = default?.let { verbatim(folderLabel(it)) }
          ?: Res.string.intake_default_folder.text(),
        caption = default?.let(::verbatim),
        icon = KetchIcon.Folder,
        checked = folder == null,
        onClick = { session.folder = null },
      )
      val recent = session.recentFolders.filter { it !in session.pinnedFolders }
      if (recent.isNotEmpty()) {
        header(Res.string.intake_recent.text())
        recent.forEach { path ->
          item(
            label = verbatim(folderLabel(path)),
            caption = verbatim(path),
            checked = folder == path,
            onClick = { session.folder = path },
          )
        }
      }
      if (session.pinnedFolders.isNotEmpty()) {
        header(Res.string.intake_pinned.text())
        session.pinnedFolders.forEach { path ->
          item(
            label = verbatim(folderLabel(path)),
            caption = verbatim(path),
            icon = KetchIcon.Pennant,
            checked = folder == path,
            onClick = { session.folder = path },
          )
        }
      }
      if (folder != null) {
        if (folder in session.pinnedFolders) {
          item(
            label = Res.string.intake_unpin.text(folderLabel(folder)),
            onClick = { session.unpinFolder(folder) },
          )
        } else {
          item(
            label = Res.string.intake_pin.text(folderLabel(folder)),
            icon = KetchIcon.Plus,
            onClick = { session.pinFolder(folder) },
          )
        }
      }
      divider()
      if (local) {
        item(
          label = Res.string.intake_choose_folder.text(),
          icon = KetchIcon.Folder,
          onClick = actions::pickFolder,
        )
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
    label = stringResource(Res.string.intake_folder_on, session.targetName().resolve()),
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
    modifier = Modifier
      .fillMaxWidth()
      .ketchSurface(KetchElevationLevel.E0, KetchTheme.shapes.lg, colors.surface, colors.hairline)
      .padding(spacing.s3),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = eyebrowText(stringResource(Res.string.intake_advanced)),
        style = KetchTheme.typography.eyebrow,
        color = colors.textTertiary,
        modifier = Modifier.weight(1f),
      )
      KetchButton(
        text = stringResource(Res.string.intake_hide),
        onClick = { session.updateAdvancedOpen(false) },
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
      )
    }
    val single = session.single?.takeIf { it.source is IntakeSource.Link && !it.isTorrent }
    val own = (single?.source as? IntakeSource.Link)?.headers.orEmpty()
    if (own.isNotEmpty()) {
      Text(
        text = stringResource(Res.string.intake_also_sent, own.keys.joinToString(", ")),
        style = KetchTheme.typography.caption,
        color = colors.textSecondary,
      )
    }
    if (single != null) {
      KetchTextField(
        value = single.fileName,
        onValueChange = { single.fileName = it },
        label = stringResource(Res.string.intake_file_name),
        placeholder = single.name,
        enabled = !session.isSystemFolder,
        modifier = Modifier.fillMaxWidth(),
      )
      if (session.isSystemFolder) {
        Text(
          text = stringResource(Res.string.intake_server_file_name),
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
      label = IntakeHeaders.REFERER,
      placeholder = "https://",
      mono = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
      Text(
        text = IntakeHeaders.USER_AGENT,
        style = KetchTheme.typography.labelS,
        color = colors.textSecondary,
      )
      KetchSegmented(
        options = UserAgentChoice.entries,
        selected = headers.userAgent,
        onSelect = {
          headers.userAgent = it
          changed()
        },
        label = { it.label.resolve() },
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
      label = IntakeHeaders.COOKIE,
      placeholder = "name=value; other=value",
      multiline = true,
    )
    SecretField(
      value = headers.authorization,
      onValueChange = {
        headers.authorization = it
        changed()
      },
      label = IntakeHeaders.AUTHORIZATION,
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
        text = stringResource(Res.string.intake_add_header),
        onClick = { headers.extra += HeaderRow() },
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
        leadingIcon = KetchIcon.Plus,
      )
      KetchButton(
        text = stringResource(Res.string.intake_paste_curl),
        onClick = { actions.pasteCurl() },
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
        leadingIcon = KetchIcon.Copy,
      )
    }
    Text(
      text = stringResource(Res.string.intake_headers_saved),
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
      placeholder = stringResource(Res.string.intake_header_name),
      clearable = false,
      modifier = Modifier.weight(HEADER_NAME_WEIGHT),
    )
    KetchTextField(
      value = row.value,
      onValueChange = {
        row.value = it
        onChange()
      },
      placeholder = stringResource(Res.string.intake_header_value),
      mono = true,
      modifier = Modifier.weight(1f),
    )
    KetchIconButton(
      icon = KetchIcon.Close,
      contentDescription = stringResource(Res.string.intake_remove_header),
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
internal fun targetSummary(presence: DevicePresence?, free: Long? = null): UiText? {
  val usable = free ?: presence?.disk?.usableBytes?.takeIf { it > 0 }
  val active = presence?.counts?.downloading ?: 0
  return listOfNotNull(
    usable?.let { Res.string.device_free_space.text(freeSpace(it)) },
    Res.string.device_active_downloads.text(active).takeIf { active > 0 },
    Res.string.pulse_slow_lane.text().takeIf { presence?.speedMode?.isSlowLane == true },
  ).takeIf { it.isNotEmpty() }?.joinText()
}

private const val MAX_TARGET_SHORTCUTS = 9

/** Free space as the Pulse bar says it, such as "412 GB", "3.1 GB" or "1.6 TB". */
internal fun freeSpace(bytes: Long): UiText = formatSpace(bytes)

private fun priorityCaption(priority: DownloadPriority): StringResource = when (priority) {
  DownloadPriority.LOW -> Res.string.intake_priority_low_caption
  DownloadPriority.NORMAL -> Res.string.intake_priority_normal_caption
  DownloadPriority.HIGH -> Res.string.intake_priority_high_caption
  DownloadPriority.URGENT -> Res.string.intake_priority_urgent_caption
}

/** What the ✕ of [option]'s chip does: "Reset speed". */
private fun resetLabel(option: IntakeOption): StringResource = when (option) {
  IntakeOption.Speed -> Res.string.intake_reset_speed
  IntakeOption.Priority -> Res.string.intake_reset_priority
  IntakeOption.Start -> Res.string.intake_reset_start
  IntakeOption.Connections -> Res.string.intake_reset_connections
}

private val PRIORITIES = listOf(
  DownloadPriority.LOW,
  DownloadPriority.NORMAL,
  DownloadPriority.HIGH,
  DownloadPriority.URGENT,
)

private const val SECRET_LINES = 3
private const val HEADER_NAME_WEIGHT = 0.4f
