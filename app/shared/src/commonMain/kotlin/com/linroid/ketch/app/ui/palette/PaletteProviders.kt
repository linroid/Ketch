package com.linroid.ketch.app.ui.palette

import androidx.compose.runtime.Immutable
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.i18n.SEPARATOR
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.CommandScope
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.SettingsCategory
import com.linroid.ketch.app.state.SpeedUnit
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.parseSpeedLimit
import com.linroid.ketch.app.state.speedLimitText
import com.linroid.ketch.app.state.taskActions
import com.linroid.ketch.app.util.IntakeItem
import com.linroid.ketch.app.util.LinkKind
import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.app.util.SearchQuery
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.app.util.links
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_review
import ketch.app.shared.generated.resources.count_downloads
import ketch.app.shared.generated.resources.device_switch_to
import ketch.app.shared.generated.resources.palette_add_with_options
import ketch.app.shared.generated.resources.palette_add_with_options_detail
import ketch.app.shared.generated.resources.palette_discover_detail
import ketch.app.shared.generated.resources.palette_download
import ketch.app.shared.generated.resources.palette_downloads_tab
import ketch.app.shared.generated.resources.palette_full_speed
import ketch.app.shared.generated.resources.palette_full_speed_already
import ketch.app.shared.generated.resources.palette_full_speed_cap
import ketch.app.shared.generated.resources.palette_full_speed_keywords
import ketch.app.shared.generated.resources.palette_full_speed_rules
import ketch.app.shared.generated.resources.palette_full_speed_slow_lane
import ketch.app.shared.generated.resources.palette_limit_to
import ketch.app.shared.generated.resources.palette_limit_while_off
import ketch.app.shared.generated.resources.palette_links
import ketch.app.shared.generated.resources.palette_matches
import ketch.app.shared.generated.resources.palette_none
import ketch.app.shared.generated.resources.palette_nothing_failed
import ketch.app.shared.generated.resources.palette_nothing_paused
import ketch.app.shared.generated.resources.palette_nothing_to_pause
import ketch.app.shared.generated.resources.palette_nothing_to_undo
import ketch.app.shared.generated.resources.palette_on_device
import ketch.app.shared.generated.resources.palette_pause_count
import ketch.app.shared.generated.resources.palette_resume_count
import ketch.app.shared.generated.resources.palette_retry_count
import ketch.app.shared.generated.resources.palette_settings_page
import ketch.app.shared.generated.resources.palette_show_in_downloads
import ketch.app.shared.generated.resources.palette_slow_lane_is_on
import ketch.app.shared.generated.resources.palette_slow_lane_now
import ketch.app.shared.generated.resources.palette_slow_lane_off
import ketch.app.shared.generated.resources.palette_slow_lane_on
import ketch.app.shared.generated.resources.palette_slow_lane_to
import ketch.app.shared.generated.resources.palette_slow_lane_turns_on
import ketch.app.shared.generated.resources.palette_verb_download
import ketch.app.shared.generated.resources.shell_discover_query
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * A device as the palette offers it.
 *
 * @property deviceId id of the device, as in its task keys.
 * @property name name to show, such as "This Mac" or "NAS-Basement".
 * @property number its place in the list of devices, from 1, which `⌥⌘1`… switch to.
 * @property active whether the app shows it now.
 * @property line what it is doing, such as "2 active · 6.4 MB/s" or "Offline".
 * @property reachable whether it can take a download now; an offline device, or one that needs
 *   a token, cannot.
 */
@Immutable
internal data class PaletteDevice(
  val deviceId: String,
  val name: String,
  val number: Int,
  val active: Boolean,
  val line: String,
  val reachable: Boolean = true,
)

/**
 * The speed of the active device.
 *
 * @property modes whether it has speed modes, so the Slow lane can be set; only the embedded
 *   device has them.
 * @property slowLane whether the Slow lane is in effect.
 * @property rules whether speed rules turn the Slow lane on and off.
 * @property slowLaneSpeed the Slow lane's speed, while the device has [modes].
 * @property cap its standing cap, which applies at full speed.
 */
@Immutable
internal data class PaletteSpeed(
  val modes: Boolean = false,
  val slowLane: Boolean = false,
  val rules: Boolean = false,
  val slowLaneSpeed: SpeedLimit? = null,
  val cap: SpeedLimit = SpeedLimit.Unlimited,
)

/**
 * What the palette's rows are made from.
 *
 * @property query what was typed.
 * @property rows the tasks in scope, for the downloads and the live counts.
 * @property devices every device, in the app's order.
 * @property commands the global commands the window runs.
 * @property destinations the destinations the navigation offers.
 * @property settings the Settings pages offered.
 * @property speed the speed of the active device.
 * @property undoTitle what ⌘Z would do, such as "Undo pause all"; `null` when nothing.
 * @property canRun whether a task action can run here, such as opening a file.
 * @property now the current time, for tokens such as `added:today`.
 * @property timeZone the zone of those tokens.
 * @property platform whose chords the rows show.
 */
@Immutable
internal data class PaletteSource(
  val query: String,
  val rows: List<TaskRow> = emptyList(),
  val devices: List<PaletteDevice> = emptyList(),
  val commands: List<KetchCommand> = emptyList(),
  val destinations: List<AppDestination> = emptyList(),
  val settings: List<SettingsCategory> = emptyList(),
  val speed: PaletteSpeed = PaletteSpeed(),
  val undoTitle: UiText? = null,
  val canRun: (RowAction, TaskRow) -> Boolean = { _, _ -> true },
  val now: Instant = Instant.DISTANT_PAST,
  val timeZone: TimeZone = TimeZone.UTC,
  val platform: KeyboardPlatform = KeyboardPlatform.current,
) {
  /** The device the app shows, if any. */
  val activeDevice: PaletteDevice? get() = devices.firstOrNull { it.active }
}

/** Every row the palette offers for [source], in provider order; see [paletteResults]. */
internal suspend fun paletteItems(source: PaletteSource): List<PaletteItem> = buildList {
  val query = source.query.trim()
  val links = if (query.isEmpty()) emptyList() else LinkParser.parseIntake(query).links()
  addAll(linkItems(source, query, links))
  addAll(speedItems(source, query))
  addAll(commandItems(source))
  addAll(downloadItems(source, query))
  addAll(navigationItems(source))
  addAll(deviceItems(source))
  if (links.isEmpty()) addAll(fallbackItems(source, query))
}

/**
 * A typed speed such as "5m", "500k", "1.5 MB/s" or "2mbps"; `null` for anything else, a bare
 * number included, so typing a file name with digits never offers a speed.
 */
internal fun parsePaletteSpeed(text: String): SpeedLimit? {
  val match = SPEED.matchEntire(text.trim().lowercase()) ?: return null
  val (amount, unit) = match.destructured
  val speedUnit = if (unit == "k") SpeedUnit.KB else SpeedUnit.MB
  return parseSpeedLimit(amount.replace(',', '.'), speedUnit)
}

private suspend fun linkItems(
  source: PaletteSource,
  query: String,
  links: List<IntakeItem.Link>,
): List<PaletteItem> {
  if (links.isEmpty()) return emptyList()
  val urls = links.map { it.url }.distinct()
  // Plain links are added at once; magnets, torrents and links with headers need the sheet.
  val now = links.all { it.headers.isEmpty() && it.kind in QUICK_KINDS }
  val single = urls.singleOrNull()
  val name = single?.let(::linkName)
  val subject = name?.let(::verbatim) ?: Res.plurals.palette_links.text(urls.size)
  val icon = if (single != null && name != null) {
    PaletteIcon.File(name, single)
  } else {
    PaletteIcon.Glyph(KetchIcon.Link)
  }
  val title = Res.string.palette_download.text(subject).load()
  val verb = (if (now) Res.string.palette_verb_download else Res.string.action_review).text().load()
  // The active device first, then the others that can take the links, then the rest.
  val devices = source.devices.sortedWith(compareBy({ !it.active }, { !it.reachable }))
  val alternateKey = KetchCommands.PaletteAlternate.shortcutLabel(source.platform)
  return buildList {
    for (device in devices) {
      val place = Res.string.palette_on_device.text(device.name)
      add(
        PaletteItem(
          id = "",
          provider = PaletteProvider.Links,
          title = title,
          subtitle = if (device.reachable) {
            place.load()
          } else {
            listOf(place, verbatim(device.line)).joinText().load()
          },
          icon = icon,
          action = PaletteAction.Download(query, urls, device.deviceId, now),
          alternate = PaletteAction.AddWithOptions(query, device.deviceId),
          shortcut = if (device.active) null else deviceChord(device, source.platform),
          verb = verb,
          direct = true,
        )
      )
    }
    add(
      PaletteItem(
        id = "",
        provider = PaletteProvider.Links,
        title = Res.string.palette_add_with_options.text().load(),
        subtitle = Res.string.palette_add_with_options_detail.text().load(),
        icon = PaletteIcon.Glyph(KetchIcon.Filter),
        action = PaletteAction.AddWithOptions(query, source.activeDevice?.deviceId),
        shortcut = alternateKey,
        direct = true,
      )
    )
  }
}

private suspend fun speedItems(source: PaletteSource, query: String): List<PaletteItem> {
  val limit = parsePaletteSpeed(query) ?: return emptyList()
  val speed = speedLimitText(limit)
  val cap = PaletteItem(
    id = "",
    provider = PaletteProvider.Speed,
    title = Res.string.palette_limit_to.text(speed).load(),
    subtitle = source.activeDevice?.name,
    icon = PaletteIcon.Glyph(KetchIcon.Speed),
    action = PaletteAction.SpeedCap(limit),
    direct = true,
  )
  if (!source.speed.modes) return listOf(cap)
  val now = source.speed.slowLaneSpeed
  val slowLane = PaletteItem(
    id = "",
    provider = PaletteProvider.Speed,
    title = Res.string.palette_slow_lane_to.text(speed).load(),
    subtitle = when {
      !source.speed.slowLane -> Res.string.palette_slow_lane_turns_on.text()
      now == null -> Res.string.palette_slow_lane_is_on.text()
      else -> Res.string.palette_slow_lane_now.text(speedLimitText(now))
    }.load(),
    icon = PaletteIcon.Glyph(KetchIcon.SlowLane),
    action = PaletteAction.SlowLane(limit),
    direct = true,
  )
  return listOf(slowLane, cap.copy(subtitle = Res.string.palette_limit_while_off.text().load()))
}

private suspend fun commandItems(source: PaletteSource): List<PaletteItem> {
  val bound = source.commands.toSet()
  val others = source.commands.filter {
    it.scope == CommandScope.Global && it !in COMMAND_ORDER && it !in NOT_COMMANDS &&
      !it.isTabOrDevice()
  }
  val items = (COMMAND_ORDER.filter { it in bound } + others)
    .filterNot { it == KetchCommands.SlowLane && !source.speed.modes }
    .map { commandItem(source, it, source.rows) }
    .toMutableList()
  // Full speed sits with the speed and queue commands.
  val anchor = items.indexOfLast { (it.action as? PaletteAction.Command)?.command in SPEED_ANCHORS }
  items.add(anchor + 1, fullSpeedItem(source))
  return items
}

private suspend fun commandItem(
  source: PaletteSource,
  command: KetchCommand,
  rows: List<TaskRow>,
): PaletteItem {
  val label = command.label.load()
  var title: UiText? = null
  var subtitle: UiText? = null
  when (command) {
    KetchCommands.PauseAll -> {
      val count = rows.count { it.state is DownloadState.Downloading || it.state.isQueued }
      if (count > 0) {
        title = Res.plurals.palette_pause_count.text(count)
      } else {
        subtitle = Res.string.palette_nothing_to_pause.text()
      }
    }
    KetchCommands.ResumeAll -> {
      val count = rows.count { it.state is DownloadState.Paused }
      if (count > 0) {
        title = Res.plurals.palette_resume_count.text(count)
      } else {
        subtitle = Res.string.palette_nothing_paused.text()
      }
    }
    KetchCommands.RetryFailed -> {
      val count = rows.count { it.state is DownloadState.Failed }
      if (count > 0) {
        title = Res.plurals.palette_retry_count.text(count)
      } else {
        subtitle = Res.string.palette_nothing_failed.text()
      }
    }
    KetchCommands.SlowLane -> {
      title = if (source.speed.slowLane) {
        Res.string.palette_slow_lane_off.text()
      } else {
        Res.string.palette_slow_lane_on.text()
      }
      subtitle = source.speed.slowLaneSpeed?.let(::speedLimitText)
    }
    KetchCommands.Undo -> {
      val undo = source.undoTitle
      if (undo != null) title = undo else subtitle = Res.string.palette_nothing_to_undo.text()
    }
  }
  val shown = title?.load() ?: label
  return PaletteItem(
    id = "command.${command.id}",
    provider = PaletteProvider.Commands,
    title = shown,
    subtitle = subtitle?.load(),
    icon = PaletteIcon.Glyph(command.icon ?: KetchIcon.Command),
    action = PaletteAction.Command(command),
    shortcut = command.shortcutLabel(source.platform),
    keywords = if (shown != label) listOf(label) else emptyList(),
  )
}

// Says what running it changes: the speed mode first, as [PaletteRunner] does, then the cap.
private suspend fun fullSpeedItem(source: PaletteSource): PaletteItem {
  val speed = source.speed
  val subtitle = when {
    speed.slowLane -> Res.string.palette_full_speed_slow_lane.text()
    speed.rules -> Res.string.palette_full_speed_rules.text()
    !speed.cap.isUnlimited -> Res.string.palette_full_speed_cap.text(speedLimitText(speed.cap))
    else -> Res.string.palette_full_speed_already.text()
  }
  // Other words for it, such as "unlimited", as the language lists them.
  val keywords = Res.string.palette_full_speed_keywords.text().load()
    .split(',', '，', '、')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
  return PaletteItem(
    id = "speed.full",
    provider = PaletteProvider.Commands,
    title = Res.string.palette_full_speed.text().load(),
    subtitle = subtitle.load(),
    icon = PaletteIcon.Glyph(KetchIcon.Bolt),
    action = PaletteAction.FullSpeed,
    keywords = keywords,
  )
}

private suspend fun downloadItems(source: PaletteSource, query: String): List<PaletteItem> {
  val search = SearchQuery.parse(query)
  val filter = SearchQuery(tokens = search.tokens)
  val manyDevices = source.rows.mapTo(HashSet()) { it.key.deviceId }.size > 1
  return source.rows
    .filter { filter.isEmpty || filter.matches(it, source.now, source.timeZone) }
    .sortedByDescending { it.createdAt }
    .map { row -> downloadItem(source, row, search, manyDevices) }
}

private suspend fun downloadItem(
  source: PaletteSource,
  row: TaskRow,
  search: SearchQuery,
  manyDevices: Boolean,
): PaletteItem {
  val content = row.content
  val primary = primaryAction(source, row)
  val reveal = RowAction.ShowInFolder.takeIf { source.canRun(it, row) }
  val alternate = reveal ?: RowAction.Details.takeIf { primary != RowAction.Details }
  val subtitle = listOf(
    (content.error?.title ?: content.statusText).load(),
    content.size.load(),
    if (row.state is DownloadState.Downloading) content.speed.load() else "",
    if (manyDevices) row.deviceName else ""
  ).filter { it.isNotBlank() && it != NO_VALUE }.joinToString(SEPARATOR)
  return PaletteItem(
    id = "task.${row.key.deviceId}/${row.key.taskId}",
    provider = PaletteProvider.Downloads,
    title = row.name,
    subtitle = subtitle,
    icon = PaletteIcon.File(row.name, row.request.url),
    action = PaletteAction.Task(row.key, primary),
    alternate = alternate?.let { PaletteAction.Task(row.key, it) },
    verb = primary.label.load(),
    keywords = listOfNotNull(row.host, row.refererHost),
    matchQuery = search.text,
    searchOnly = true,
  )
}

// ↩ opens a finished file and otherwise toggles the task as Space does in the list: it pauses a
// running or queued task, resumes a paused one and fixes a failed one. Anything else, such as a
// scheduled task, shows in the inspector.
private fun primaryAction(source: PaletteSource, row: TaskRow): RowAction {
  val menu = taskActions(row.request, row.state, row.device, stalled = row.isStalled).menu
  fun offered(action: RowAction) = action.takeIf { it in menu && source.canRun(it, row) }
  val action = when (row.state) {
    is DownloadState.Completed -> offered(RowAction.Open)
    is DownloadState.Downloading, DownloadState.Queued -> offered(RowAction.Pause)
    is DownloadState.Paused -> offered(RowAction.Resume)
    // The row's own fix comes first, such as Edit link for a link the server turned away; a
    // copy is no fix, so a gone file retries as Space does.
    is DownloadState.Failed, DownloadState.Canceled ->
      row.content.primary?.takeIf { it !in COPIES && source.canRun(it, row) }
        ?: offered(RowAction.Retry)
        ?: offered(RowAction.DownloadAgain)
    is DownloadState.Scheduled -> null
  }
  return action ?: RowAction.Details
}

private suspend fun navigationItems(source: PaletteSource): List<PaletteItem> = buildList {
  val counts = StatusFilter.counts(source.rows.map { it.state })
  for (filter in StatusFilter.entries) {
    val command = KetchCommands.tab(filter)
    val count = counts.getValue(filter)
    add(
      PaletteItem(
        id = "tab.${filter.name}",
        provider = PaletteProvider.Navigation,
        title = if (filter == StatusFilter.All) {
          AppDestination.Downloads.label
        } else {
          Res.string.palette_downloads_tab.text(filter.label)
        }.load(),
        subtitle = if (count == 0) {
          Res.string.palette_none.text()
        } else {
          Res.plurals.count_downloads.text(count)
        }.load(),
        icon = PaletteIcon.Glyph(command.icon ?: KetchIcon.All),
        action = PaletteAction.Command(command),
        shortcut = command.shortcutLabel(source.platform),
      )
    )
  }
  for (destination in source.destinations) {
    if (destination == AppDestination.Downloads) continue
    add(
      PaletteItem(
        id = "destination.${destination.name}",
        provider = PaletteProvider.Navigation,
        title = destination.label.load(),
        icon = PaletteIcon.Glyph(destination.icon),
        action = PaletteAction.Command(destination.command),
        shortcut = destination.command.shortcutLabel(source.platform),
      )
    )
  }
  for (category in source.settings) {
    val title = category.titleText.load()
    add(
      PaletteItem(
        id = "settings.${category.page.name}",
        provider = PaletteProvider.Navigation,
        title = Res.string.palette_settings_page.text(category.titleText).load(),
        subtitle = category.descriptionText.load(),
        icon = PaletteIcon.Glyph(category.icon),
        action = PaletteAction.Settings(category.page),
        // "/speed" jumps to the page named Speed, as the language names it.
        keywords = listOf("/${title.lowercase()}", title),
      )
    )
  }
}

private suspend fun deviceItems(source: PaletteSource): List<PaletteItem> {
  if (source.devices.size < 2) return emptyList()
  return buildList {
    if (KetchCommands.AllDevices in source.commands) {
      add(
        PaletteItem(
          id = "command.${KetchCommands.AllDevices.id}",
          provider = PaletteProvider.Devices,
          title = KetchCommands.AllDevices.label.load(),
          icon = PaletteIcon.Glyph(KetchIcon.Fleet),
          action = PaletteAction.Command(KetchCommands.AllDevices),
          shortcut = KetchCommands.AllDevices.shortcutLabel(source.platform),
        )
      )
    }
    for (device in source.devices.filterNot { it.active }) {
      add(
        PaletteItem(
          id = "device.${device.deviceId}",
          provider = PaletteProvider.Devices,
          title = Res.string.device_switch_to.text(device.name).load(),
          subtitle = device.line,
          icon = PaletteIcon.Device(device.deviceId, device.name),
          action = PaletteAction.SwitchDevice(device.deviceId),
          shortcut = deviceChord(device, source.platform),
        )
      )
    }
    for (device in source.devices) {
      for (verb in BatchVerb.entries) {
        add(
          PaletteItem(
            id = "device.${verb.name.lowercase()}.${device.deviceId}",
            provider = PaletteProvider.Devices,
            title = verb.title.text(device.name).load(),
            subtitle = device.line,
            icon = PaletteIcon.Glyph(verb.icon),
            action = PaletteAction.DeviceBatch(device.deviceId, verb),
            searchOnly = true,
          )
        )
      }
    }
  }
}

private suspend fun fallbackItems(source: PaletteSource, query: String): List<PaletteItem> {
  if (query.isEmpty() || query.startsWith("/")) return emptyList()
  val search = SearchQuery.parse(query)
  return buildList {
    val matches = source.rows.count { search.matches(it, source.now, source.timeZone) }
    if (matches > 0) {
      add(
        PaletteItem(
          id = "",
          provider = PaletteProvider.Fallback,
          title = Res.string.palette_show_in_downloads.text(query).load(),
          subtitle = Res.plurals.palette_matches.text(matches).load(),
          icon = PaletteIcon.Glyph(KetchIcon.Search),
          action = PaletteAction.Search(query),
        )
      )
    }
    // Search tokens such as is:failed and a typed speed mean nothing to Discover.
    val plainText = search.tokens.isEmpty() && parsePaletteSpeed(query) == null
    if (AppDestination.Discover in source.destinations && plainText) {
      add(
        PaletteItem(
          id = "",
          provider = PaletteProvider.Fallback,
          title = Res.string.shell_discover_query.text(query).load(),
          subtitle = Res.string.palette_discover_detail.text().load(),
          icon = PaletteIcon.Glyph(KetchIcon.Discover),
          action = PaletteAction.Discover(query),
          shortcut = KetchCommands.PaletteDiscover.shortcutLabel(source.platform),
        )
      )
    }
  }
}

private fun deviceChord(device: PaletteDevice, platform: KeyboardPlatform): String? =
  KetchCommands.deviceOrNull(device.number)?.shortcutLabel(platform)

private fun linkName(url: String): String = displayName(DownloadRequest(url))

private fun KetchCommand.isTabOrDevice(): Boolean =
  KetchCommands.tabFilter(this) != null || KetchCommands.deviceNumber(this) != null

private val DownloadState.isQueued: Boolean
  get() = this is DownloadState.Queued

private val BatchVerb.icon: KetchIcon
  get() = when (this) {
    BatchVerb.Pause -> KetchIcon.Pause
    BatchVerb.Resume -> KetchIcon.Play
    BatchVerb.Retry -> KetchIcon.Retry
  }

/** Commands in the order the palette lists them; any other bound command follows. */
private val COMMAND_ORDER: List<KetchCommand> = listOf(
  KetchCommands.Add,
  KetchCommands.AddClipboardLink,
  KetchCommands.OpenTorrent,
  KetchCommands.PauseAll,
  KetchCommands.ResumeAll,
  KetchCommands.RetryFailed,
  KetchCommands.SlowLane,
  KetchCommands.Undo,
  KetchCommands.Search,
  KetchCommands.ShowDetails,
  KetchCommands.ToggleSidebar,
  KetchCommands.Activity,
  KetchCommands.SwitchDevice,
  KetchCommands.Settings,
  KetchCommands.Shortcuts
)

/** Commands Full speed follows: the last of these the window runs. */
private val SPEED_ANCHORS: Set<KetchCommand> = setOf(
  KetchCommands.PauseAll,
  KetchCommands.ResumeAll,
  KetchCommands.RetryFailed,
  KetchCommands.SlowLane
)

/**
 * Commands listed elsewhere, or that make no sense from the palette, such as Paste links: in the
 * palette `⌘V` pastes into its field, which offers the links.
 */
private val NOT_COMMANDS: Set<KetchCommand> = setOf(
  KetchCommands.Palette,
  KetchCommands.PasteLinks,
  KetchCommands.Discover,
  KetchCommands.Devices,
  KetchCommands.AllDevices
)

/** Row actions that only copy something, which ↩ on a failed download never runs. */
private val COPIES: Set<RowAction> = setOf(
  RowAction.CopyLink,
  RowAction.CopyPath,
  RowAction.CopyError,
  RowAction.CopyDetails
)

/** Kinds of link added without the add sheet, like a quick add. */
private val QUICK_KINDS: Set<LinkKind> = setOf(LinkKind.Http, LinkKind.Ftp)

private const val NO_VALUE = "–"
private val SPEED = Regex("""(\d+(?:[.,]\d+)?)\s*([km])(?:i?b)?(?:/s|ps)?""")
