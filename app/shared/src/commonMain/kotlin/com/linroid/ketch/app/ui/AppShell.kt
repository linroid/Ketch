package com.linroid.ketch.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchCard
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AiDiscoveryProvider
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.ui.dialog.AddDownloadDialog
import com.linroid.ketch.app.ui.dialog.AddRemoteServerDialog
import com.linroid.ketch.app.ui.dialog.AiDiscoverDialog
import com.linroid.ketch.app.ui.dialog.InstanceSelectorSheet
import com.linroid.ketch.app.ui.list.DownloadList
import com.linroid.ketch.app.ui.sidebar.SidebarNavigation
import com.linroid.ketch.app.ui.sidebar.SpeedStatusBar
import com.linroid.ketch.app.ui.sidebar.filterIcon
import com.linroid.ketch.app.ui.toolbar.BatchActionBar
import com.linroid.ketch.app.ui.toolbar.KetchToolbar
import com.linroid.ketch.app.ui.toolbar.countTasksByFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
  instanceManager: InstanceManager,
  embeddedAiProvider: AiDiscoveryProvider? = null,
) {
  val scope = rememberCoroutineScope()
  val appState = remember(instanceManager) {
    AppState(instanceManager, scope, embeddedAiProvider)
  }

  val instances by appState.instances.collectAsState()
  // Auto-show add-remote-server dialog when no instances
  // are configured (remote-only mode without auto-connect).
  LaunchedEffect(instances) {
    if (instances.isEmpty()) {
      appState.showAddRemoteDialog = true
    }
  }

  val sortedTasks by appState.sortedTasks.collectAsState()
  val activeInstance by appState.activeInstance.collectAsState()
  val connectionState by appState.connectionState.collectAsState()
  val serverState by appState.serverState.collectAsState()

  // Collect all task states for filtering/counts
  val taskStates = remember {
    mutableStateMapOf<String, DownloadState>()
  }
  val currentTaskIds =
    sortedTasks.map { it.taskId }.toSet()
  taskStates.keys.removeAll { it !in currentTaskIds }
  sortedTasks.forEach { task ->
    val state by task.state.collectAsState()
    taskStates[task.taskId] = state
  }

  val filteredTasks by remember {
    derivedStateOf {
      if (appState.statusFilter == StatusFilter.All) {
        sortedTasks
      } else {
        sortedTasks.filter { task ->
          val state = taskStates[task.taskId]
          state != null &&
            appState.statusFilter.matches(state)
        }
      }
    }
  }

  val taskCounts by remember {
    derivedStateOf {
      StatusFilter.entries.associateWith { filter ->
        countTasksByFilter(filter, taskStates)
      }
    }
  }

  val hasActive by remember {
    derivedStateOf {
      taskStates.values.any { it.isActive }
    }
  }
  val hasPaused by remember {
    derivedStateOf {
      taskStates.values.any {
        it is DownloadState.Paused
      }
    }
  }
  val hasCompleted by remember(taskStates) {
    derivedStateOf {
      taskStates.values.any {
        it is DownloadState.Completed
      }
    }
  }

  val activeDownloadCount by remember(taskStates) {
    derivedStateOf {
      taskStates.values.count { it.isActive }
    }
  }
  val totalSpeed by remember(taskStates) {
    derivedStateOf {
      taskStates.values.sumOf { state ->
        if (state is DownloadState.Downloading) {
          state.progress.bytesPerSecond
        } else {
          0L
        }
      }
    }
  }

  // Determine layout type: None for Expanded (custom sidebar),
  // scaffold handles Compact/Medium automatically.
  val adaptiveInfo = currentWindowAdaptiveInfo()
  val isExpanded = adaptiveInfo.windowSizeClass
    .isWidthAtLeastBreakpoint(
      WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
    )
  val navLayoutType = if (isExpanded) {
    NavigationSuiteType.None
  } else {
    NavigationSuiteScaffoldDefaults
      .calculateFromAdaptiveInfo(adaptiveInfo)
  }

  NavigationSuiteScaffold(
    navigationSuiteItems = {
      StatusFilter.entries.forEach { filter ->
        val count = taskCounts[filter] ?: 0
        item(
          selected = appState.statusFilter == filter,
          onClick = { appState.statusFilter = filter },
          icon = {
            if (count > 0 &&
              filter != StatusFilter.All
            ) {
              BadgedBox(
                badge = {
                  Badge { Text(count.toString()) }
                },
              ) {
                KetchIconImage(
                  icon = filterIcon(filter),
                  size = 24.dp,
                  tint = KetchTheme.colors.onSurfaceVariant,
                )
              }
            } else {
              KetchIconImage(
                icon = filterIcon(filter),
                size = 24.dp,
                tint = KetchTheme.colors.onSurfaceVariant,
              )
            }
          },
        )
      }
    },
    layoutType = navLayoutType,
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        // Expanded: sidebar + content side by side
        Row(modifier = Modifier.weight(1f)) {
          if (isExpanded) {
            SidebarNavigation(
              selectedFilter = appState.statusFilter,
              taskCounts = taskCounts,
              onFilterSelect = { selected ->
                appState.statusFilter = selected
              },
              activeInstance = activeInstance,
              connectionState = connectionState,
              onInstanceClick = {
                appState.showInstanceSelector = true
              },
            )
          }

          // Content area
          Column(modifier = Modifier.weight(1f)) {
            if (isExpanded) {
              KetchToolbar(
                title = appState.statusFilter.label,
                bandwidthBytesPerSec = totalSpeed,
                globalCapBytesPerSec = null,
                hasActiveDownloads = hasActive,
                hasPausedDownloads = hasPaused,
                hasCompletedDownloads = hasCompleted,
                onPauseAll = { appState.pauseAll() },
                onResumeAll = { appState.resumeAll() },
                onClearCompleted = { appState.clearCompleted() },
                onAiDiscoverClick = {
                  appState.showAiDiscoverDialog = true
                },
                onAddClick = { appState.requestAddDownload() },
              )
            } else {
              TopAppBar(
                title = {
                  Text(
                    text = appState.statusFilter.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                  )
                },
                actions = {
                  KetchIconButton(
                    icon = KetchIcon.Ai,
                    onClick = {
                      appState.showAiDiscoverDialog = true
                    },
                  )
                  BatchActionBar(
                    hasActiveDownloads = hasActive,
                    hasPausedDownloads = hasPaused,
                    hasCompletedDownloads = hasCompleted,
                    onPauseAll = { appState.pauseAll() },
                    onResumeAll = { appState.resumeAll() },
                    onClearCompleted = {
                      appState.clearCompleted()
                    },
                  )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                  containerColor = MaterialTheme.colorScheme.surface,
                ),
              )
            }

            // Error banner
            if (appState.errorMessage != null) {
              KetchCard(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp),
                padding = 0.dp,
              ) {
                Row(
                  modifier = Modifier.padding(16.dp),
                  verticalAlignment =
                    Alignment.CenterVertically,
                  horizontalArrangement =
                    Arrangement.spacedBy(12.dp),
                ) {
                  Text(
                    text = appState.errorMessage ?: "",
                    style = KetchTheme.typography.bodySmall,
                    color = KetchTheme.colors.error,
                    modifier = Modifier.weight(1f),
                  )
                  KetchButton(
                    text = "Dismiss",
                    onClick = { appState.dismissError() },
                    variant =
                      com.linroid.ketch.app.components
                        .KetchButtonVariant.Ghost,
                    size = KetchButtonSize.Small,
                  )
                }
              }
            }

            // Download list
            DownloadList(
              tasks = filteredTasks,
              isEmpty = sortedTasks.isEmpty() &&
                appState.errorMessage == null,
              isFilterEmpty = filteredTasks.isEmpty() &&
                sortedTasks.isNotEmpty(),
              selectedFilter = appState.statusFilter,
              scope = scope,
              modifier = Modifier.weight(1f),
            )
          }
        }

        // Bottom speed status bar
        SpeedStatusBar(
          activeDownloads = activeDownloadCount,
          totalSpeed = totalSpeed,
          instanceLabel = activeInstance?.label,
          connectionState = connectionState,
          onInstanceClick = {
            appState.showInstanceSelector = true
          },
        )
      }

      // FAB-style primary action for Compact/Medium layouts.
      // (Sidebar has its own "New Task" button on Expanded.)
      if (!isExpanded) {
        KetchButton(
          text = "New Task",
          onClick = { appState.requestAddDownload() },
          leadingIcon = KetchIcon.Plus,
          size = KetchButtonSize.Large,
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 72.dp),
        )
      }
    }
  }

  // Dialogs
  if (appState.showAddDialog) {
    AddDownloadDialog(
      resolveState = appState.resolveState,
      onResolveUrl = { appState.resolveUrl(it) },
      onResetResolve = { appState.resetResolveState() },
      onDismiss = { appState.showAddDialog = false },
      onDownload = { url, fileName, speedLimit,
                     priority, schedule,
                     resolvedUrl, selectedFileIds ->
        appState.showAddDialog = false
        appState.dismissError()
        appState.startDownload(
          url, fileName, speedLimit, priority,
          schedule, resolvedUrl, selectedFileIds,
        )
      },
    )
  }

  if (appState.showInstanceSelector) {
    InstanceSelectorSheet(
      instanceManager = instanceManager,
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
        appState.showAddRemoteDialog = true
      },
      onDismiss = {
        appState.showInstanceSelector = false
      },
    )
  }

  if (appState.showAiDiscoverDialog) {
    AiDiscoverDialog(
      state = appState.aiDiscoverState,
      onDiscover = { query, sites ->
        appState.aiDiscover(query, sites)
      },
      onDownloadSelected = { candidates ->
        appState.aiDownloadSelected(candidates)
      },
      onDismiss = {
        appState.resetAiDiscover()
        appState.showAiDiscoverDialog = false
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
          appState.reconnectWithToken(
            unauthorized, token ?: "",
          )
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
