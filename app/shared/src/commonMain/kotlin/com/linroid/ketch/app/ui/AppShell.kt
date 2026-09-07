package com.linroid.ketch.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchCard
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.linroid.ketch.app.state.AiDiscoverDraft
import com.linroid.ketch.app.ui.dialog.AddDownloadDialog
import com.linroid.ketch.app.ui.dialog.AddRemoteServerDialog
import com.linroid.ketch.app.ui.dialog.InstanceSelectorSheet
import com.linroid.ketch.app.util.matchesSearch
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

  var searchQuery by rememberSaveable { mutableStateOf("") }
  val filteredTasks by remember(appState) {
    derivedStateOf {
      val query = searchQuery.trim()
      sortedTasks.filter { task ->
        val state = taskStates[task.taskId]
        val matchesStatus = appState.statusFilter == StatusFilter.All ||
          (state != null && appState.statusFilter.matches(state))
        matchesStatus && task.request.matchesSearch(query)
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

  // Use the full sidebar when it fits; otherwise keep two destinations in
  // the bottom bar instead of a sparse rail that squeezes the content.
  var discoverySelected by rememberSaveable { mutableStateOf(false) }
  val aiDraft = remember { AiDiscoverDraft() }
  val adaptiveInfo = currentWindowAdaptiveInfo()
  val isExpanded = adaptiveInfo.windowSizeClass
    .isWidthAtLeastBreakpoint(
      WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
    )
  val navLayoutType = if (isExpanded) {
    NavigationSuiteType.None
  } else {
    NavigationSuiteType.NavigationBar
  }

  NavigationSuiteScaffold(
    navigationSuiteItems = {
      item(
        label = { Text("Downloads") },
        selected = !discoverySelected,
        onClick = { discoverySelected = false },
        icon = {
          KetchIconImage(
            icon = KetchIcon.Active, size = 24.dp,
            tint = if (!discoverySelected) KetchTheme.colors.primary
              else KetchTheme.colors.onSurfaceVariant,
          )
        },
      )
      item(
        label = { Text("Discover") },
        selected = discoverySelected,
        onClick = { discoverySelected = true },
        icon = {
          KetchIconImage(
            icon = KetchIcon.Ai,
            size = 24.dp,
            tint = if (discoverySelected) KetchTheme.colors.primary
              else KetchTheme.colors.onSurfaceVariant,
          )
        },
      )
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
              discoverySelected = discoverySelected,
              onDiscoverySelect = { discoverySelected = true },
              taskCounts = taskCounts,
              onFilterSelect = { selected ->
                discoverySelected = false
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
            if (discoverySelected) {
              AiDiscoveryPage(
                state = appState.aiDiscoverState,
                draft = aiDraft,
                available = embeddedAiProvider != null,
                onAddDirect = { appState.requestAddDownload() },
                onCancelSearch = { appState.resetAiDiscover() },
                onDiscover = { query, sites -> appState.aiDiscover(query, sites) },
                onDownloadSelected = { candidates ->
                  appState.aiDownloadSelected(candidates)
                  aiDraft.selected = emptySet()
                  discoverySelected = false
                  appState.statusFilter = StatusFilter.All
                },
              )
            } else {
              if (isExpanded) {
                KetchToolbar(
                  title = if (appState.statusFilter == StatusFilter.All) "Downloads" else appState.statusFilter.label,
                  downloadCount = filteredTasks.size,
                  searchQuery = searchQuery,
                  onSearchQueryChange = { searchQuery = it },
                  bandwidthBytesPerSec = totalSpeed,
                  globalCapBytesPerSec = null,
                  hasActiveDownloads = hasActive,
                  hasPausedDownloads = hasPaused,
                  hasCompletedDownloads = hasCompleted,
                  onPauseAll = { appState.pauseAll() },
                  onResumeAll = { appState.resumeAll() },
                  onClearCompleted = { appState.clearCompleted() },
                  onAddClick = { appState.requestAddDownload() },
                )
              } else {
                TopAppBar(
                  title = {
                    Text(
                      text = if (appState.statusFilter == StatusFilter.All) "Downloads" else appState.statusFilter.label,
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.SemiBold,
                    )
                  },
                  actions = {
                    com.linroid.ketch.app.components.KetchIconButton(
                      icon = KetchIcon.Plus,
                      contentDescription = "Add download",
                      onClick = { appState.requestAddDownload() },
                      tint = KetchTheme.colors.primary,
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

              if (!isExpanded) {
                com.linroid.ketch.app.components.KetchTextField(
                  value = searchQuery, onValueChange = { searchQuery = it },
                  placeholder = "Search downloads…", leadingIcon = KetchIcon.Search,
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
              }

              if (!isExpanded) {
                DownloadFilters(
                  selected = appState.statusFilter,
                  counts = taskCounts,
                  onSelect = { appState.statusFilter = it },
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
                onAddDownload = { appState.requestAddDownload() },
                isEmpty = sortedTasks.isEmpty() &&
                  appState.errorMessage == null,
                isFilterEmpty = filteredTasks.isEmpty() &&
                  sortedTasks.isNotEmpty(),
                selectedFilter = appState.statusFilter,
                onShowAllDownloads = { appState.statusFilter = StatusFilter.All },
                onClearSearch = { searchQuery = "" },
                searchQuery = searchQuery,
                bottomPadding = 24.dp,
                scope = scope,
                modifier = Modifier.weight(1f),
              )
            }
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
