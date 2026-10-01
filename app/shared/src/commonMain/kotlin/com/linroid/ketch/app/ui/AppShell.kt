package com.linroid.ketch.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.window.core.layout.WindowSizeClass
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.log.FileLogger
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.dialog.AddRemoteServerDialog
import com.linroid.ketch.app.ui.dialog.InstanceSelectorSheet
import com.linroid.ketch.app.ui.discover.DiscoverScreen
import com.linroid.ketch.app.ui.downloads.DownloadsScreen
import com.linroid.ketch.app.ui.downloads.KetchLayoutInfo
import com.linroid.ketch.app.ui.feedback.ToastHost
import com.linroid.ketch.app.ui.intake.IntakeHost
import com.linroid.ketch.app.ui.settings.LocalFileLogger
import com.linroid.ketch.app.ui.settings.SettingsHost
import com.linroid.ketch.app.ui.sidebar.SidebarNavigation
import com.linroid.ketch.app.ui.sidebar.SpeedStatusBar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter

/**
 * The app's window content: navigation, the destinations, Settings, the dialogs and the toasts,
 * all driven by [appState], which composables inside reach through [LocalAppState].
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
    LocalFileLogger provides fileLogger,
  ) {
    ShellContent(appState, openSettingsRequests)
  }
}

@Composable
private fun ShellContent(appState: AppState, openSettingsRequests: Flow<Unit>) {
  val instances by appState.instances.collectAsState()
  // Auto-show add-remote-server dialog when no instances
  // are configured (remote-only mode without auto-connect).
  LaunchedEffect(instances) {
    if (instances.isEmpty()) {
      appState.showAddRemoteDialog = true
    }
  }

  val activeInstance by appState.activeInstance.collectAsState()
  val connectionState by appState.connectionState.collectAsState()
  val serverState by appState.serverState.collectAsState()
  val counts by appState.taskList.counts.collectAsState()
  val pulse by appState.pulse.state.collectAsState()

  // A recreated Android activity builds a new controller; the list's tab and search come back.
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

  // Use the full sidebar when it fits; otherwise keep the destinations in
  // the bottom bar instead of a sparse rail that squeezes the content.
  var destinationName by rememberSaveable {
    mutableStateOf(AppDestination.Downloads.name)
  }
  val destinations = AppDestination.visible(appState.aiSettings.available)
  val destination = AppDestination.valueOf(destinationName)
    .takeIf { it in destinations && it != AppDestination.Settings }
    ?: AppDestination.Downloads
  LaunchedEffect(destination) {
    // Keep the saved value in step when a destination disappears.
    if (destination.name != destinationName) {
      destinationName = destination.name
    }
  }
  // Settings opens over the current destination: as a dialog on wide
  // windows and as a full page on narrow ones. One flag drives both, so
  // resizing across the breakpoint switches between them.
  var settingsOpen by rememberSaveable { mutableStateOf(false) }
  val settingsRequest = appState.settingsRequest
  LaunchedEffect(settingsRequest) {
    if (settingsRequest != null) settingsOpen = true
  }
  LaunchedEffect(openSettingsRequests) {
    openSettingsRequests.collect { appState.openSettings() }
  }
  val closeSettings = {
    settingsOpen = false
    appState.closeSettings()
  }
  val discoverRequest = appState.discoverRequest
  LaunchedEffect(discoverRequest) {
    if (discoverRequest != null) {
      destinationName = AppDestination.Discover.name
      closeSettings()
      appState.discoverRequestHandled()
    }
  }
  LaunchedEffect(appState) {
    appState.downloadsRequests.collect {
      destinationName = AppDestination.Downloads.name
      closeSettings()
    }
  }
  // The device may have changed its settings while the app was in the background.
  val windowInfo = LocalWindowInfo.current
  LaunchedEffect(windowInfo, appState) {
    snapshotFlow { windowInfo.isWindowFocused }
      .drop(1)
      .filter { it }
      .collect { appState.onWindowFocused() }
  }
  val windowWidth = with(LocalDensity.current) { windowInfo.containerSize.width.toDp() }
  val layout = remember(windowWidth) { KetchLayoutInfo.of(windowWidth) }
  val showSidebar = currentWindowAdaptiveInfoV2().windowSizeClass
    .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
  val navLayoutType = if (showSidebar) {
    NavigationSuiteType.None
  } else {
    NavigationSuiteType.NavigationBar
  }

  FileDropTarget(
    onDrop = { files ->
      // Show the list the new task will appear in.
      destinationName = AppDestination.Downloads.name
      closeSettings()
      appState.addDroppedFiles(files)
    },
    modifier = Modifier.fillMaxSize(),
  ) {
    NavigationSuiteScaffold(
      navigationSuiteItems = {
        destinations.forEach { entry ->
          val selected = if (settingsOpen) {
            entry == AppDestination.Settings
          } else {
            entry == destination
          }
          item(
            label = { Text(entry.label) },
            selected = selected,
            onClick = {
              if (entry == AppDestination.Settings) {
                appState.openSettings()
              } else {
                destinationName = entry.name
                closeSettings()
              }
            },
            icon = {
              KetchIconImage(
                icon = entry.icon,
                size = KetchTheme.density.navGlyph,
                tint = if (selected) KetchTheme.colors.accent else KetchTheme.colors.textSecondary,
              )
            },
          )
        }
      },
      layoutType = navLayoutType,
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f)) {
          if (showSidebar) {
            SidebarNavigation(
              selectedFilter = appState.statusFilter,
              destination = destination,
              showDiscovery = AppDestination.Discover in destinations,
              onDestinationSelect = { destinationName = it.name },
              onOpenSettings = { appState.openSettings() },
              taskCounts = counts,
              onFilterSelect = { selected ->
                destinationName = AppDestination.Downloads.name
                appState.statusFilter = selected
              },
              activeInstance = activeInstance,
              connectionState = connectionState,
              onInstanceClick = {
                appState.showInstanceSelector = true
              },
            )
          }

          Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when {
              settingsOpen && !showSidebar ->
                SettingsHost(appState, settingsRequest, onClose = closeSettings)
              destination == AppDestination.Discover -> DiscoverScreen(appState)
              else -> DownloadsScreen(appState, layout, Modifier.fillMaxSize())
            }
            ToastHost(
              messages = appState.messages,
              modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = KetchTheme.spacing.s4)
                .padding(bottom = KetchTheme.spacing.s2),
            )
          }
        }

        SpeedStatusBar(
          activeDownloads = pulse.counts.downloading,
          totalSpeed = pulse.totalSpeed,
          instanceLabel = activeInstance?.label,
          connectionState = connectionState,
          onInstanceClick = {
            appState.showInstanceSelector = true
          },
        )
      }
    }
  }

  if (settingsOpen && showSidebar) {
    SettingsHost(appState, settingsRequest, onClose = closeSettings)
  }

  IntakeHost(appState)

  if (appState.showInstanceSelector) {
    InstanceSelectorSheet(
      instanceManager = appState.instanceManager,
      activeInstance = activeInstance,
      switchingInstance = appState.switchingInstance,
      serverState = serverState,
      onSelectInstance = { instance ->
        appState.switchInstance(instance)
      },
      onRemoveInstance = { instance ->
        appState.removeInstance(instance)
      },
      onAddRemoteServer = {
        appState.showInstanceSelector = false
        appState.showAddRemoteDialog = true
      },
      onDismiss = {
        appState.showInstanceSelector = false
      },
    )
  }

  if (appState.showAddRemoteDialog) {
    val unauthorized = appState.unauthorizedInstance
    AddRemoteServerDialog(
      onDismiss = {
        appState.resetDiscovery()
        appState.showAddRemoteDialog = false
        appState.unauthorizedInstance = null
      },
      discoveryState = appState.discoveryState,
      onDiscover = { port ->
        appState.discoverRemoteServers(port)
      },
      onStopDiscovery = {
        appState.stopDiscovery()
      },
      onAdd = { host, port, token ->
        appState.resetDiscovery()
        appState.showAddRemoteDialog = false
        if (unauthorized != null) {
          appState.reconnectWithToken(unauthorized, token ?: "")
        } else {
          appState.addRemoteServer(host, port, token)
        }
      },
      initialHost = unauthorized?.host ?: "",
      initialPort = unauthorized?.port?.toString()
        ?: "8642",
      authRequired = unauthorized != null,
    )
  }
}
