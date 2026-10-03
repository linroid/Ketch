package com.linroid.ketch.app.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.instance.DeviceScope
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.DiscoverRequest
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.clearMissingCaption
import com.linroid.ketch.app.ui.feedback.ActivityPopover
import com.linroid.ketch.app.ui.pulse.PulseSubtitle
import com.linroid.ketch.app.ui.sidebar.PennantCluster
import com.linroid.ketch.app.ui.sidebar.pennantHealth
import com.linroid.ketch.app.ui.sidebar.pennantName
import com.linroid.ketch.app.ui.sidebar.rememberDevices
import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.app.util.extractFilename
import com.linroid.ketch.app.util.links
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.device_any_in_sentence
import ketch.app.shared.generated.resources.downloads_clear_missing
import ketch.app.shared.generated.resources.shell_activity_unread
import ketch.app.shared.generated.resources.shell_clear_finished
import ketch.app.shared.generated.resources.shell_device_button
import ketch.app.shared.generated.resources.shell_device_button_back
import ketch.app.shared.generated.resources.shell_devices
import ketch.app.shared.generated.resources.shell_discover_query
import ketch.app.shared.generated.resources.shell_more
import ketch.app.shared.generated.resources.shell_search_close
import ketch.app.shared.generated.resources.shell_search_download_links_on
import ketch.app.shared.generated.resources.shell_search_download_on
import ketch.app.shared.generated.resources.shell_search_placeholder
import ketch.app.shared.generated.resources.shell_settings
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The phone's top bar as it scrolls away: while the content scrolls down the bar collapses, and
 * it comes back as soon as the content scrolls up. Lists inside the shell report their scrolling
 * through [nestedScrollConnection]; the Downloads chips can follow [collapsedFraction].
 */
@Stable
internal class PhoneChromeState {
  /** How far the bar has moved up, from 0 down to [heightOffsetLimit], in pixels. */
  var heightOffset: Float by mutableFloatStateOf(0f)
    private set

  /** How far the bar can move up: minus its height, in pixels. */
  var heightOffsetLimit: Float by mutableFloatStateOf(0f)

  /** How far the content has scrolled down since it was last at its top, in pixels. */
  var contentOffset: Float by mutableFloatStateOf(0f)
    private set

  /** Whether the bar may collapse; the search field keeps it in place. */
  var collapsible: Boolean by mutableStateOf(true)

  /** How collapsed the bar is, from 0 (shown) to 1 (gone). */
  val collapsedFraction: Float
    get() = if (heightOffsetLimit == 0f) 0f else heightOffset / heightOffsetLimit

  /** Brings the bar back and forgets how far the content scrolled, as on a new page. */
  fun expand() {
    heightOffset = 0f
    contentOffset = 0f
  }

  /** Receives the scrolling of the content under the bar. */
  val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
      if (!collapsible) return Offset.Zero
      val target = (heightOffset + available.y).coerceIn(heightOffsetLimit, 0f)
      val consumed = target - heightOffset
      heightOffset = target
      return Offset(0f, consumed)
    }

    override fun onPostScroll(
      consumed: Offset,
      available: Offset,
      source: NestedScrollSource,
    ): Offset {
      // Anything left over while scrolling up means the content reached its top.
      contentOffset = if (available.y > 0f) 0f else (contentOffset - consumed.y).coerceAtLeast(0f)
      return Offset.Zero
    }
  }
}

/** The phone's top bar of the shell around the screen below, or `null` on wider windows. */
internal val LocalPhoneChrome = staticCompositionLocalOf<PhoneChromeState?> { null }

/** Lays [content] out under the window's top, moved up by [chrome]'s offset and clipped. */
@Composable
internal fun CollapsingBar(chrome: PhoneChromeState, content: @Composable () -> Unit) {
  Box(
    Modifier
      .clipToBounds()
      .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val offset = chrome.heightOffset.roundToInt().coerceIn(-placeable.height, 0)
        layout(placeable.width, placeable.height + offset) { placeable.place(0, offset) }
      },
  ) {
    Box(Modifier.onSizeChanged { chrome.heightOffsetLimit = -it.height.toFloat() }) { content() }
  }
}

/**
 * The phone's top bar: the active device's pennant (tap for the devices, long-press to go back
 * to the one before), the page title with the Pulse subtitle (tap for the Pulse sheet), search
 * and the overflow menu. Search turns the bar into a field that filters the downloads, adds a
 * pasted link, or searches Discover for plain text.
 *
 * @param onShow shows a destination; the overflow lists Devices when [showsBottomBar] is off.
 * @param onOpenSettings opens Settings, which phones show as a page.
 */
@Composable
internal fun PhoneTopBar(
  shell: ShellState,
  title: String,
  showsBottomBar: Boolean,
  onShow: (AppDestination) -> Unit,
  onOpenSettings: () -> Unit,
) {
  val state = shell.app
  if (shell.searchOpen) {
    Box {
      SearchBar(shell)
      // ⌘J reaches Activity while the search shows too.
      ActivityPopover(
        state = state,
        expanded = shell.pulseBar.activityOpen,
        onDismissRequest = { shell.pulseBar.activityOpen = false },
      )
    }
    return
  }
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    // The pennant's ring lines up with the content's 16 dp edge, the ⋮ glyph with the other.
    modifier = Modifier
      .fillMaxWidth()
      .height(BarHeight)
      .padding(start = spacing.s3, end = spacing.s1),
  ) {
    DeviceButton(shell)
    Column(Modifier.weight(1f).padding(start = spacing.s1)) {
      Text(
        text = title,
        style = KetchTheme.typography.pageTitle,
        color = KetchTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      PulseSubtitle(state, onClick = { shell.pulseSheetOpen = true })
    }
    KetchIconButton(
      icon = KetchIcon.Search,
      onClick = { shell.focusSearch() },
      contentDescription = KetchCommands.Search.label.resolve(),
    )
    Overflow(shell, showsBottomBar, onShow, onOpenSettings)
  }
}

/**
 * The active device's pennant in its health ring, with its unseen failures, or every device's
 * pennants while they all show: a tap opens the devices, and a long press goes back to the
 * device that was active before.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceButton(shell: ShellState) {
  val state = shell.app
  // Read here, so the device's live numbers recompose the button and not the whole bar.
  val devices = rememberDevices(state)
  val active by state.activeInstance.collectAsState()
  val scope by state.deviceScope.collectAsState()
  val all = scope == DeviceScope.All && devices.size >= DeviceScope.MIN_DEVICES
  val device = devices.firstOrNull { it.deviceId == active?.deviceId }
  val target = KetchTheme.density.iconButtonTarget
  val name = if (all) KetchCommands.AllDevices.label else device?.name
  val description = name?.let { stringResource(Res.string.shell_device_button, it.resolve()) }
    ?: stringResource(Res.string.shell_devices)
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .sizeIn(minWidth = target, minHeight = target)
      .clip(KetchTheme.shapes.full)
      .semantics { contentDescription = description }
      .combinedClickable(
        role = Role.Button,
        onLongClickLabel = stringResource(Res.string.shell_device_button_back),
        onLongClick = {
          devices.firstOrNull { it.deviceId == shell.previousDeviceId }
            ?.let { state.switchInstance(it.entry) }
        },
        onClick = { state.showInstanceSelector = true },
      ),
  ) {
    if (all) {
      PennantCluster(devices)
    } else if (device == null) {
      KetchIconImage(KetchIcon.Devices, tint = KetchTheme.colors.textSecondary)
    } else {
      DevicePennant(
        deviceId = device.deviceId,
        name = device.pennantName,
        size = DevicePennantDefaults.Large,
        health = pennantHealth(device),
        failures = device.unseenFailures,
      )
    }
  }
}

/**
 * The ⋮ menu: queue commands, clearing finished downloads or those whose files are gone,
 * Activity, Devices without a bottom bar, and Settings.
 */
@Composable
private fun Overflow(
  shell: ShellState,
  showsBottomBar: Boolean,
  onShow: (AppDestination) -> Unit,
  onOpenSettings: () -> Unit,
) {
  val state = shell.app
  val pulse by state.pulse.state.collectAsState()
  val unread by state.messages.unreadCount.collectAsState()
  val files = rememberFileActions()
  var open by remember { mutableStateOf(false) }
  val counts = pulse.counts
  Box {
    KetchIconButton(
      icon = KetchIcon.More,
      onClick = { open = true },
      contentDescription = stringResource(Res.string.shell_more),
    )
    KetchMenu(expanded = open, onDismissRequest = { open = false }) {
      item(
        command = KetchCommands.PauseAll,
        onClick = { state.pauseAll() },
        enabled = counts.downloading + counts.waiting > 0,
      )
      item(
        command = KetchCommands.ResumeAll,
        onClick = { state.resumeAll() },
        enabled = counts.paused > 0,
      )
      item(
        command = KetchCommands.RetryFailed,
        onClick = { state.retryFailed() },
        enabled = pulse.failures > 0,
      )
      item(
        label = Res.string.shell_clear_finished.text(),
        onClick = { state.clearCompleted() },
        icon = KetchIcon.Done,
        enabled = counts.done > 0,
      )
      if (files != null) {
        item(
          label = Res.string.downloads_clear_missing.text(),
          onClick = { state.clearMissing(files) },
          icon = KetchIcon.Warning,
          caption = clearMissingCaption,
          enabled = counts.done > 0,
        )
      }
      divider()
      item(
        label = KetchCommands.Activity.label,
        onClick = { shell.pulseBar.activityOpen = true },
        icon = KetchCommands.Activity.icon,
        caption = if (unread > 0) Res.plurals.shell_activity_unread.text(unread) else null,
      )
      if (!showsBottomBar && AppDestination.Devices in shell.destinations) {
        item(
          label = AppDestination.Devices.label,
          onClick = { onShow(AppDestination.Devices) },
          icon = AppDestination.Devices.icon,
        )
      }
      item(
        label = Res.string.shell_settings.text(),
        onClick = onOpenSettings,
        icon = KetchIcon.Settings,
      )
    }
    ActivityPopover(
      state = state,
      expanded = shell.pulseBar.activityOpen,
      onDismissRequest = { shell.pulseBar.activityOpen = false },
    )
  }
}

/**
 * The top bar as a search field: typing filters the downloads; a link offers to download it on
 * the active device, and plain text to search Discover for it. Back closes it and clears the
 * search.
 */
@Composable
private fun SearchBar(shell: ShellState) {
  val state = shell.app
  val spacing = KetchTheme.spacing
  val focus = remember { FocusRequester() }
  val active by state.activeInstance.collectAsState()
  val query = state.searchQuery
  val close = shell::closeSearch
  NavigationBackHandler(
    state = rememberNavigationEventState(NavigationEventInfo.None),
    onBackCompleted = close,
  )
  LaunchedEffect(focus) {
    // A search kept from a wider window or an earlier activity opens without the keyboard.
    if (query.isEmpty()) runCatching { focus.requestFocus() }
  }
  DisposableEffect(shell.chrome) {
    // The field stays in place while the results scroll under it.
    shell.chrome.expand()
    shell.chrome.collapsible = false
    onDispose { shell.chrome.collapsible = true }
  }
  val suggestion = remember(query, shell.destinations) {
    searchSuggestion(query, discover = AppDestination.Discover in shell.destinations)
  }
  val focusManager = LocalFocusManager.current
  val run = { chosen: SearchSuggestion ->
    close()
    when (chosen) {
      is SearchSuggestion.Download -> if (chosen.withOptions) {
        state.openIntake(IntakeRequest(text = query))
      } else {
        state.quickAdd(chosen.urls)
      }
      is SearchSuggestion.Discover -> state.openDiscover(DiscoverRequest(chosen.query))
    }
  }
  Column(Modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s1),
      modifier = Modifier
        .fillMaxWidth()
        .height(BarHeight)
        .padding(start = spacing.s1, end = spacing.s4),
    ) {
      KetchIconButton(
        icon = KetchIcon.ChevronLeft,
        onClick = close,
        contentDescription = stringResource(Res.string.shell_search_close),
      )
      KetchTextField(
        value = query,
        onValueChange = { state.searchQuery = it },
        placeholder = stringResource(Res.string.shell_search_placeholder),
        leadingIcon = KetchIcon.Search,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
          onSearch = {
            // A link downloads as its suggestion offers; other text only filters the list.
            val download = suggestion as? SearchSuggestion.Download
            if (download != null) run(download) else focusManager.clearFocus()
          },
        ),
        modifier = Modifier.weight(1f).focusRequester(focus),
      )
    }
    if (suggestion != null) {
      val device = active?.displayName ?: Res.string.device_any_in_sentence.text()
      SuggestionRow(
        icon = suggestion.icon,
        text = suggestion.label(device).resolve(),
        detail = suggestion.detail,
        onClick = { run(suggestion) },
      )
    }
  }
}

/** What the phone's search offers to do with its text besides filtering. */
internal sealed interface SearchSuggestion {
  /** Glyph of the suggestion's row. */
  val icon: KetchIcon

  /** Text of the suggestion's row, naming the [device] downloads go to. */
  fun label(device: UiText): UiText

  /** Second line of the suggestion's row, such as the file a link downloads. */
  val detail: String?

  /**
   * Downloads the links in the text; [withOptions] opens the add sheet instead, for links that
   * carry headers.
   */
  data class Download(val urls: List<String>, val withOptions: Boolean) : SearchSuggestion {
    override val icon: KetchIcon get() = KetchIcon.Active

    override fun label(device: UiText): UiText = when (urls.size) {
      1 -> Res.string.shell_search_download_on.text(device)
      else -> Res.plurals.shell_search_download_links_on.text(urls.size, urls.size, device)
    }

    override val detail: String?
      get() = urls.singleOrNull()?.let(::extractFilename)
  }

  /** Searches Discover for [query]. */
  data class Discover(val query: String) : SearchSuggestion {
    override val icon: KetchIcon get() = KetchIcon.Discover

    override fun label(device: UiText): UiText = Res.string.shell_discover_query.text(query)

    override val detail: String? get() = null
  }
}

/**
 * What the phone's search offers for [query]: downloading its links, or searching Discover
 * for plain text while [discover] is offered; `null` for blank text.
 */
internal fun searchSuggestion(query: String, discover: Boolean): SearchSuggestion? {
  val text = query.trim()
  if (text.isEmpty()) return null
  val links = LinkParser.parseIntake(text).links()
  return when {
    links.isNotEmpty() -> SearchSuggestion.Download(
      urls = links.map { it.url }.distinct(),
      withOptions = links.any { it.headers.isNotEmpty() },
    )
    discover -> SearchSuggestion.Discover(text.replace(Whitespace, " "))
    else -> null
  }
}

@Composable
private fun SuggestionRow(icon: KetchIcon, text: String, detail: String?, onClick: () -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = spacing.s3, vertical = spacing.s1)
      .clip(KetchTheme.shapes.md)
      .background(colors.accentSoft)
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        role = Role.Button,
        onClick = onClick,
      )
      .heightIn(min = KetchTheme.density.menuItem)
      .padding(horizontal = spacing.s3),
  ) {
    KetchIconImage(icon, size = KetchTheme.density.controlGlyph, tint = colors.accentText)
    Column(Modifier.padding(vertical = spacing.s2)) {
      Text(
        text = text,
        style = KetchTheme.typography.label,
        color = colors.accentText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (detail != null) {
        Text(
          text = detail,
          style = KetchTheme.typography.caption,
          color = colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.MiddleEllipsis,
        )
      }
    }
  }
}

private val Whitespace = Regex("""\s+""")

/** Height of the phone's top bar, without the status bar. */
private val BarHeight: Dp = 64.dp
