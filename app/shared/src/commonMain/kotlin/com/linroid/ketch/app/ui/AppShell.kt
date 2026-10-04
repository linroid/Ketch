package com.linroid.ketch.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.linroid.ketch.app.components.LocalDeviceTypes
import com.linroid.ketch.app.input.CommandScope
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.log.FileLogger
import com.linroid.ketch.app.platform.LocalDesktopHooks
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.platform.rememberFilePicker
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.connect.ConnectHost
import com.linroid.ketch.app.ui.connect.PairingApprovalHost
import com.linroid.ketch.app.ui.connect.ConnectLanding
import com.linroid.ketch.app.ui.devices.DevicesScreen
import com.linroid.ketch.app.ui.devices.rememberDeviceTypes
import com.linroid.ketch.app.ui.discover.DiscoverScreen
import com.linroid.ketch.app.ui.discover.DiscoverTopBarActions
import com.linroid.ketch.app.ui.discover.discoverMenu
import com.linroid.ketch.app.ui.discover.LocalDiscoverChrome
import com.linroid.ketch.app.ui.downloads.DownloadsScreen
import com.linroid.ketch.app.ui.downloads.LayoutTier
import com.linroid.ketch.app.ui.downloads.actions.compactSelectionBarHeight
import com.linroid.ketch.app.ui.downloads.actions.isSelectionMode
import com.linroid.ketch.app.ui.feedback.BannerHost
import com.linroid.ketch.app.ui.feedback.ToastHost
import com.linroid.ketch.app.ui.intake.IntakeHost
import com.linroid.ketch.app.ui.palette.CommandPalette
import com.linroid.ketch.app.ui.pulse.PulseBar
import com.linroid.ketch.app.ui.pulse.PulseSheet
import com.linroid.ketch.app.ui.settings.LocalFileLogger
import com.linroid.ketch.app.ui.settings.SettingsHost
import com.linroid.ketch.app.ui.shell.AddFab
import com.linroid.ketch.app.ui.shell.BOTTOM_BAR_MIN_DESTINATIONS
import com.linroid.ketch.app.ui.shell.BottomChrome
import com.linroid.ketch.app.ui.shell.DeviceSheet
import com.linroid.ketch.app.ui.shell.DeviceSwitcherPopover
import com.linroid.ketch.app.ui.shell.DropBerths
import com.linroid.ketch.app.ui.shell.KetchLayout
import com.linroid.ketch.app.ui.shell.LocalBottomChrome
import com.linroid.ketch.app.ui.shell.LocalHostShortcuts
import com.linroid.ketch.app.ui.shell.LocalKetchLayout
import com.linroid.ketch.app.ui.shell.LocalPhoneChrome
import com.linroid.ketch.app.ui.shell.NavRail
import com.linroid.ketch.app.ui.shell.PhoneBottomBar
import com.linroid.ketch.app.ui.shell.PhoneScaffold
import com.linroid.ketch.app.ui.shell.PhoneTopBar
import com.linroid.ketch.app.ui.shell.ShellCommands
import com.linroid.ketch.app.ui.shell.ShellNavigation
import com.linroid.ketch.app.ui.shell.ShellScaffold
import com.linroid.ketch.app.ui.shell.ShellState
import com.linroid.ketch.app.ui.shell.ShortcutHost
import com.linroid.ketch.app.ui.shell.ShortcutSheet
import com.linroid.ketch.app.ui.shell.dropFilesOn
import com.linroid.ketch.app.ui.shell.dropTextOn
import com.linroid.ketch.app.ui.shell.navBadges
import com.linroid.ketch.app.ui.shell.shortcutGroups
import com.linroid.ketch.app.ui.shell.switcherOffset
import com.linroid.ketch.app.ui.sidebar.Sidebar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter

/**
 * The app's window content: navigation, the destinations, Settings, the dialogs and the toasts,
 * all driven by [appState], which composables inside reach through [LocalAppState].
 *
 * Wide windows show the sidebar (or the rail) on the canvas wash beside the content card,
 * medium ones the rail, and phones a top bar with the Add button and, with three destinations
 * or more, a bottom bar; see [KetchLayout]. The global shortcuts work anywhere in it.
 *
 * @param openSettingsRequests emits when the platform asks to open Settings.
 * @param fileLogger the app's log files, offered on the About page; `null` when there are none.
 */
@Composable
fun AppShell(
  appState: AppState,
  openSettingsRequests: Flow<Unit> = emptyFlow(),
  fileLogger: FileLogger? = null,
) {
  CompositionLocalProvider(
    LocalAppState provides appState,
    LocalClock provides appState.clock,
    LocalFileLogger provides fileLogger,
    LocalDeviceTypes provides rememberDeviceTypes(appState),
  ) {
    ShellContent(appState, openSettingsRequests)
  }
}

@Composable
private fun ShellContent(appState: AppState, openSettingsRequests: Flow<Unit>) {
  val activeInstance by appState.activeInstance.collectAsState()
  RestoreListState(appState)

  var savedDestination by rememberSaveable { mutableStateOf(AppDestination.Downloads.name) }
  var savedSettingsOpen by rememberSaveable { mutableStateOf(false) }
  val shell = remember(appState) {
    ShellState(
      app = appState,
      destination = AppDestination.entries.firstOrNull { it.name == savedDestination }
        ?: AppDestination.Downloads,
      settingsOpen = savedSettingsOpen,
    )
  }
  LaunchedEffect(shell) {
    snapshotFlow { shell.destination to shell.settingsOpen }.collect { (destination, open) ->
      savedDestination = destination.name
      savedSettingsOpen = open
    }
  }

  val destinations = AppDestination.visible(appState.aiSettings.offered)
  val windowInfo = LocalWindowInfo.current
  val windowWidth = with(LocalDensity.current) { windowInfo.containerSize.width.toDp() }
  val layout = KetchLayout.of(windowWidth, appState.appSettings.ui.sidebarCollapsed)
  SideEffect {
    shell.destinations = destinations
    shell.layout = layout
  }
  LaunchedEffect(destinations) {
    // Discover goes away when discovery is switched off.
    if (shell.destination !in destinations) shell.show(AppDestination.Downloads)
  }
  ShellRequests(appState, shell, openSettingsRequests)
  val backLeadsHome = isMobilePlatform || layout.navigation == ShellNavigation.Phone
  if (backLeadsHome && shell.destination != AppDestination.Downloads && !shell.settingsOpen) {
    // Back leads home to Downloads; Settings and the phone's search handle it first.
    NavigationBackHandler(
      state = rememberNavigationEventState(NavigationEventInfo.None),
      onBackCompleted = { shell.show(AppDestination.Downloads) },
    )
  }
  LaunchedEffect(windowInfo, appState) {
    // The device may have changed its settings while the app was in the background.
    snapshotFlow { windowInfo.isWindowFocused }
      .drop(1)
      .filter { it }
      .collect { appState.onWindowFocused() }
  }

  val bottomChrome = remember { BottomChrome() }
  val scope = rememberCoroutineScope()
  val clipboard = rememberSystemClipboard()
  val files = rememberFilePicker()
  val commands = remember(shell, scope, clipboard, files) {
    ShellCommands(shell, scope, clipboard, files)
  }
  LaunchedEffect(clipboard, commands) {
    // The web pastes through the browser's event, since a key press cannot read the clipboard.
    clipboard.pasteEvents.collect(commands::paste)
  }
  val shellCommand = appState.shellCommand
  LaunchedEffect(shellCommand, commands) {
    // Asked for from outside the window's content, such as the macOS menu bar.
    if (shellCommand != null) {
      commands.run(shellCommand)
      appState.shellCommandHandled()
    }
  }
  // A drop away from the berths and devices adds where a paste would.
  val windowDrop = remember { DropHoverState() }
  FileDropTarget(
    onDrop = { dropped ->
      val target = appState.quickAddTarget()
      if (target != null) {
        appState.dropFilesOn(target, dropped)
      } else {
        appState.addDroppedFiles(dropped)
      }
    },
    onDropText = { text ->
      val target = appState.quickAddTarget()
      if (target != null) appState.dropTextOn(target, text) else appState.addDroppedText(text)
    },
    hover = windowDrop,
    modifier = Modifier.fillMaxSize(),
  ) {
    ShortcutHost(
      onCommand = commands::run,
      overlay = when {
        appState.showAddDialog -> CommandScope.Intake
        shell.paletteOpen -> CommandScope.Palette
        else -> null
      },
      // Discover's page keys, such as ⇧⌘H, work while nothing on the page has the keyboard.
      page = CommandScope.Discover.takeIf {
        shell.destination == AppDestination.Discover && !shell.settingsOpen
      },
      modifier = Modifier.fillMaxSize(),
    ) {
      CompositionLocalProvider(
        LocalKetchLayout provides layout,
        LocalPhoneChrome provides shell.chrome.takeIf {
          layout.navigation == ShellNavigation.Phone
        },
        LocalWindowDrop provides windowDrop,
        LocalBottomChrome provides bottomChrome,
        LocalDiscoverChrome provides shell.discover,
      ) {
        if (activeInstance == null) {
          // No device to show yet, as in the web app before one is connected.
          ConnectLanding(appState)
        } else if (layout.navigation == ShellNavigation.Phone) {
          PhoneShell(shell, commands, destinations, windowDrop)
        } else {
          WideShell(shell, destinations, layout, windowDrop)
        }
      }
    }
  }

  IntakeHost(appState)
  if (shell.paletteOpen) {
    CommandPalette(
      state = appState,
      onDismiss = { shell.paletteOpen = false },
      onCommand = commands::run,
      canRun = commands::binds,
      destinations = destinations,
      initialQuery = appState.searchQuery,
    )
  }
  if (shell.shortcutsOpen) {
    val hostShortcuts = LocalHostShortcuts.current
    val discover = AppDestination.Discover in destinations
    val groups = remember(commands, hostShortcuts, discover) {
      shortcutGroups(KeyboardPlatform.current) { command ->
        // Discover's keys show only where the window offers Discover.
        if (command == KetchCommands.Discover && !discover) return@shortcutGroups false
        commands.binds(command) || command in hostShortcuts || command.isWindowCommand()
      }
    }
    ShortcutSheet(groups, onDismissRequest = { shell.shortcutsOpen = false })
  }
  if (shell.pulseSheetOpen) {
    PulseSheet(appState, onDismissRequest = { shell.pulseSheetOpen = false })
  }

  val switcherAsked = appState.showInstanceSelector
  if (switcherAsked && activeInstance == null) {
    // The connect page lists the devices itself.
    LaunchedEffect(Unit) { appState.showInstanceSelector = false }
  } else if (switcherAsked && layout.navigation == ShellNavigation.Phone) {
    DeviceSheet(
      state = appState,
      onDismissRequest = { appState.showInstanceSelector = false },
      onShowOverview = { shell.show(AppDestination.Devices) },
    )
  }
  ConnectHost(appState)
  PairingApprovalHost(appState)
}

/** Keeps the Downloads tab and search across an Android activity recreation. */
@Composable
private fun RestoreListState(appState: AppState) {
  var savedFilter by rememberSaveable { mutableStateOf(appState.statusFilter.name) }
  var savedQuery by rememberSaveable { mutableStateOf(appState.searchQuery) }
  LaunchedEffect(appState) {
    appState.statusFilter = StatusFilter.entries.firstOrNull { it.name == savedFilter }
      ?: appState.statusFilter
    appState.searchQuery = savedQuery
    snapshotFlow { appState.statusFilter to appState.searchQuery }.collect { (filter, query) ->
      savedFilter = filter.name
      savedQuery = query
    }
  }
}

/** Follows what the app and the platform ask the shell to show. */
@Composable
private fun ShellRequests(appState: AppState, shell: ShellState, openSettingsRequests: Flow<Unit>) {
  val settingsRequest = appState.settingsRequest
  // Desktop shows Settings in a window of its own, which takes the requests.
  val settingsInWindow = LocalDesktopHooks.current.isSupported
  LaunchedEffect(settingsRequest, settingsInWindow) {
    if (settingsRequest != null && !settingsInWindow) shell.openSettings()
  }
  LaunchedEffect(openSettingsRequests) {
    openSettingsRequests.collect { appState.openSettings() }
  }
  val shortcutsRequested = appState.shortcutsRequested
  LaunchedEffect(shortcutsRequested) {
    if (shortcutsRequested) {
      shell.shortcutsOpen = true
      appState.shortcutsShown()
    }
  }
  val discoverRequest = appState.discoverRequest
  LaunchedEffect(discoverRequest) {
    if (discoverRequest != null) {
      shell.show(AppDestination.Discover)
      appState.discoverRequestHandled()
    }
  }
  LaunchedEffect(appState) {
    appState.downloadsRequests.collect { shell.show(AppDestination.Downloads) }
  }
  LaunchedEffect(appState) {
    var last: String? = null
    appState.activeInstance.collect { entry ->
      val id = entry?.deviceId
      if (last != null && last != id) shell.previousDeviceId = last
      last = id
    }
  }
  LaunchedEffect(appState) {
    appState.focusSearchRequests.collect { shell.focusSearch() }
  }
  val searchFocus = shell.searchFocusRequests
  LaunchedEffect(searchFocus) {
    if (searchFocus == 0) return@LaunchedEffect
    // Asks again once the Downloads page, which owns the field, is on screen.
    withFrameNanos {}
    appState.requestSearchFocus()
  }
}

/**
 * The sidebar or the rail beside the content card, with the banners, the Pulse bar, the device
 * switcher under the page header and, while links are dragged over the window, the drop berths.
 */
@Composable
private fun WideShell(
  shell: ShellState,
  destinations: List<AppDestination>,
  layout: KetchLayout,
  windowDrop: DropHoverState,
) {
  val appState = shell.app
  val spacing = KetchTheme.spacing
  ShellScaffold(
    layout = layout,
    navigation = {
      if (layout.navigation == ShellNavigation.Sidebar) {
        Sidebar(shell, destinations)
      } else {
        NavRail(shell, destinations, showSidebarToggle = layout.tier == LayoutTier.Expanded)
      }
    },
    top = { BannerHost(appState) },
    bottom = { PulseBar(appState, barState = shell.pulseBar) },
    overlay = {
      // Above the controls a page keeps at the bottom, such as Discover's composer.
      val clearance = LocalBottomChrome.current?.height ?: 0.dp
      ToastHost(
        messages = appState.messages,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(horizontal = spacing.s4)
          .padding(bottom = clearance + spacing.s2),
      )
      if (appState.showInstanceSelector) {
        // Under the page header, by the device chip.
        Box(Modifier.align(Alignment.TopStart)) {
          DeviceSwitcherPopover(
            state = appState,
            onDismissRequest = { appState.showInstanceSelector = false },
            offset = switcherOffset(spacing.pageHeaderPadding, spacing.pageHeaderHeight),
          )
        }
      }
    },
    cover = {
      // Device cards take drops on the Devices page themselves. On Downloads the page header
      // stays uncovered, so its Add button can take the drop too.
      if (shell.destination != AppDestination.Devices || shell.settingsOpen) {
        val header = shell.destination == AppDestination.Downloads && !shell.settingsOpen
        DropBerths(
          state = appState,
          hover = windowDrop,
          modifier = if (header) Modifier.padding(top = spacing.pageHeaderHeight) else Modifier,
        )
      }
    },
  ) {
    // Desktop shows Settings in a window of its own; elsewhere it takes the card's place.
    if (shell.settingsOpen) {
      SettingsHost(appState, appState.settingsRequest, onClose = shell::closeSettings)
    } else {
      Destination(shell.destination, appState, layout)
    }
  }
}

/**
 * The phone's top bar, content, Add button, with enough destinations a bottom bar and, while
 * links are dragged over the window, the drop berths.
 */
@Composable
private fun PhoneShell(
  shell: ShellState,
  commands: ShellCommands,
  destinations: List<AppDestination>,
  windowDrop: DropHoverState,
) {
  val appState = shell.app
  val spacing = KetchTheme.spacing
  if (shell.settingsOpen) {
    // Settings covers the whole shell as a page with its own way back.
    Box(
      Modifier
        .fillMaxSize()
        .background(KetchTheme.colors.surface)
        .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
      SettingsHost(appState, appState.settingsRequest, onClose = shell::closeSettings)
    }
    return
  }
  LaunchedEffect(shell) {
    // A search typed on a wider window, restored after Android recreated the activity, or set
    // from elsewhere shows in the search field, so it never filters the list out of sight.
    snapshotFlow {
      appState.searchQuery.isNotEmpty() && shell.destination == AppDestination.Downloads
    }.collect { searching -> if (searching) shell.searchOpen = true }
  }
  val showsBottomBar = destinations.size >= BOTTOM_BAR_MIN_DESTINATIONS
  val downloads = shell.destination == AppDestination.Downloads
  // The selection bar takes the bottom of the Downloads page while rows are selected.
  val pointer = KetchTheme.density == KetchDensity.Compact
  val selecting = isSelectionMode(appState.selectedKeys.size, pointer)
  val clearance = with(LocalDensity.current) { KetchLayout.FabClearance.toPx() }
  // Read through a derived state, so scrolling recomposes only when the button changes shape.
  val fabExpanded by remember(shell, clearance) {
    derivedStateOf { shell.chrome.contentOffset < clearance }
  }
  // The button leaves with the top bar while the list scrolls down, so it never sits over the
  // actions of the rows passing under it, and comes back as soon as the list scrolls up.
  val fabShown by remember(shell) {
    derivedStateOf { shell.chrome.collapsedFraction < FAB_HIDE_FRACTION }
  }
  PhoneScaffold(
    chrome = shell.chrome,
    topBar = {
      // Discover's own buttons take the place of search there.
      val discover = shell.destination == AppDestination.Discover
      PhoneTopBar(
        shell = shell,
        showsBottomBar = showsBottomBar,
        actions = if (discover) {
          { DiscoverTopBarActions(appState, shell.discover) }
        } else {
          null
        },
        menu = if (discover) {
          { discoverMenu(appState) }
        } else {
          null
        },
      )
    },
    banners = { BannerHost(appState) },
    bottomBar = if (showsBottomBar) {
      {
        PhoneBottomBar(
          destinations = destinations,
          selected = shell.destination,
          onSelect = { shell.show(it) },
          badges = navBadges(appState),
        )
      }
    } else {
      null
    },
    floating = {
      val clearance = LocalBottomChrome.current?.height ?: 0.dp
      ToastHost(
        messages = appState.messages,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(horizontal = spacing.s4)
          .padding(
            bottom = when {
              !downloads -> clearance + spacing.s2
              selecting -> compactSelectionBarHeight + spacing.s2
              else -> KetchLayout.FabClearance
            },
          ),
      )
      val motion = KetchTheme.motion
      AnimatedVisibility(
        visible = downloads && !selecting && fabShown,
        enter = slideInVertically(tween(motion.short, easing = motion.easeDecelerate)) { it } +
          fadeIn(tween(motion.short)),
        exit = slideOutVertically(tween(motion.short, easing = motion.easeAccelerate)) { it } +
          fadeOut(tween(motion.short)),
        modifier = Modifier.align(Alignment.BottomEnd).padding(spacing.s4),
      ) {
        AddFab(
          expanded = fabExpanded,
          onClick = { appState.openIntake() },
          onLongClick = { commands.run(KetchCommands.AddClipboardLink) },
        )
      }
      if (shell.destination != AppDestination.Devices) DropBerths(appState, windowDrop)
    },
  ) {
    Destination(shell.destination, appState, LocalKetchLayout.current)
  }
}

@Composable
private fun Destination(
  destination: AppDestination,
  appState: AppState,
  layout: KetchLayout,
) {
  when (destination) {
    AppDestination.Downloads -> DownloadsScreen(appState, layout.info, Modifier.fillMaxSize())
    AppDestination.Discover -> DiscoverScreen(appState)
    AppDestination.Devices -> DevicesScreen(appState)
  }
}

/** Whether the host, not the shell, runs [this]: the window commands of desktop apps. */
private fun KetchCommand.isWindowCommand(): Boolean =
  !isMobilePlatform && this in WindowCommands

private val WindowCommands =
  setOf(KetchCommands.CloseWindow, KetchCommands.Minimize, KetchCommands.Quit)

// How far the top bar collapses before the phone's Add button leaves with it.
private const val FAB_HIDE_FRACTION = 0.5f
