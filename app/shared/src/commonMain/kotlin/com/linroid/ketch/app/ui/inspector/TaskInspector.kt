package com.linroid.ketch.app.ui.inspector

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.actions.RowActionDialogs
import com.linroid.ketch.app.ui.downloads.actions.RowActionRunner
import com.linroid.ketch.app.ui.downloads.actions.rememberRowActionRunner
import com.linroid.ketch.app.ui.inspector.tabs.ActivityTab
import com.linroid.ketch.app.ui.inspector.tabs.ConnectionsTab
import com.linroid.ketch.app.ui.inspector.tabs.FilesTab
import com.linroid.ketch.app.ui.inspector.tabs.InspectorTab
import com.linroid.ketch.app.ui.inspector.tabs.count
import com.linroid.ketch.app.ui.inspector.tabs.rememberInspectorTabs
import com.linroid.ketch.app.ui.pulse.switchSpeedMode
import com.linroid.ketch.config.SpeedLimitMode

/** Where the task inspector shows. */
enum class InspectorPlacement {
  /** Beside the list, on wide windows. */
  Docked,

  /** Floating over the list. */
  Overlay,

  /** In a bottom sheet, on phones. */
  Sheet,
}

/**
 * The inspector's content; the docked, overlay and sheet containers belong to the Downloads
 * page, which places this inside them. It scrolls on its own, and the Files tab's list fills its
 * height: scrolling the list scrolls the header away before the list moves.
 *
 * With two or more rows selected it sums them up and offers their shared Controls. Otherwise it
 * shows the task of [taskKey]: its header with the lane strip, metric and reason lines, the
 * action bar, and the tabs: Overview (problem card, Controls and Details), Connections or Files,
 * and Activity. With neither, it keeps showing what it showed last, while its container goes
 * away.
 *
 * @param placement the container this is shown in.
 * @param onClose closes the inspector.
 */
@Composable
fun TaskInspector(
  state: AppState,
  taskKey: TaskKey?,
  placement: InspectorPlacement,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  CompositionLocalProvider(LocalAppState provides state) {
    InspectorContent(state, taskKey, placement, onClose, rememberRowActionRunner(), modifier)
  }
}

/** [TaskInspector] running its actions through [runner]. */
@Composable
internal fun InspectorContent(
  state: AppState,
  taskKey: TaskKey?,
  placement: InspectorPlacement,
  onClose: () -> Unit,
  runner: RowActionRunner,
  modifier: Modifier = Modifier,
) {
  val rows by state.taskList.rows.collectAsState()
  val pending by state.pending.collectAsState()
  val selectedKeys = state.selectedKeys
  val selection = remember(rows, selectedKeys) {
    if (selectedKeys.size < 2) emptyList() else rows.filter { it.key in selectedKeys }
  }
  val row = remember(rows, taskKey) { taskKey?.let { key -> rows.firstOrNull { it.key == key } } }
  val last = remember { LastShown() }
  val shown = when {
    selection.size >= 2 -> Shown.Selection(selection)
    row != null -> Shown.Task(row)
    else -> last.value
  }
  SideEffect { last.value = shown }
  val spacing = KetchTheme.spacing
  val padding = if (placement == InspectorPlacement.Sheet) {
    // The sheet's drag handle already leaves room above.
    val page = KetchTheme.density.pagePadding
    PaddingValues(start = page, top = spacing.s1, end = page, bottom = page)
  } else {
    PaddingValues(spacing.s4)
  }
  // The tab stays as other downloads are inspected; the scroll position starts over.
  var tab by rememberSaveable { mutableStateOf(InspectorTab.Overview) }
  val shows = when (shown) {
    is Shown.Selection -> "selection"
    is Shown.Task -> shown.row.key
    null -> null
  }
  key(shows) {
    val scroll = rememberScrollState()
    val headerFirst = remember(scroll) { HeaderFirst(scroll) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
      // The room inside the padding, which a tab with a long list fills once scrolled to.
      val room = if (constraints.hasBoundedHeight) {
        val inset = padding.calculateTopPadding() + padding.calculateBottomPadding()
        (maxHeight - inset).coerceAtLeast(0.dp)
      } else {
        Dp.Unspecified
      }
      Column(
        verticalArrangement = Arrangement.spacedBy(spacing.s4),
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(scroll)
          .padding(padding),
      ) {
        when (shown) {
          is Shown.Selection -> SelectionSummary(state, shown.rows, runner, pending, onClose)
          is Shown.Task -> {
            val tabFill = TabFill(room, Modifier.nestedScroll(headerFirst))
            TaskView(state, shown.row, runner, pending, tab, { tab = it }, tabFill, onClose)
          }
          null -> Unit
        }
      }
    }
  }
  RowActionDialogs(runner)
}

/**
 * How a tab with a long list, such as Files, fills the inspector: at most [height] tall, and
 * with [modifier] on it so scrolling the list scrolls the header away first.
 */
private class TabFill(val height: Dp, val modifier: Modifier)

/**
 * Scrolls the inspector forward before a list inside it, so the header goes away and the list
 * fills the inspector before it scrolls itself; scrolling back, the list returns to its top first.
 */
private class HeaderFirst(private val scroll: ScrollState) : NestedScrollConnection {
  override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
    if (available.y >= 0f) return Offset.Zero
    return Offset(0f, -scroll.dispatchRawDelta(-available.y))
  }
}

/** What the inspector shows. */
private sealed interface Shown {
  /** One download. */
  data class Task(val row: TaskRow) : Shown

  /** Two or more selected downloads. */
  data class Selection(val rows: List<TaskRow>) : Shown
}

/** What the inspector showed last, which it keeps while it goes away. */
private class LastShown {
  var value: Shown? = null
}

@Composable
private fun TaskView(
  state: AppState,
  row: TaskRow,
  runner: RowActionRunner,
  pending: Set<Pair<TaskKey, String>>,
  tab: InspectorTab,
  onTab: (InspectorTab) -> Unit,
  tabFill: TabFill,
  onClose: () -> Unit,
) {
  val pulse by state.pulse.state.collectAsState()
  val rates by state.speedHistory.rates.collectAsState()
  val instances by state.instances.collectAsState()
  val device = rememberDeviceLabel(state, row)
  val copier = rememberCopier(state)
  val cap = pulse.devices.firstOrNull { it.deviceId == row.key.deviceId }?.cap
    ?: SpeedLimit.Unlimited
  val slowLane = row.key.deviceId == LOCAL_DEVICE_ID && pulse.mode.isSlowLane
  val reason = inspectorReason(row, slowLane, cap, runner.isFileMissing(row))
  val stalled = remember(rates, row.key) {
    rates[row.key].orEmpty().filter { it.stalledFor != null }.mapTo(HashSet()) { it.start }
  }
  var highlight by remember { mutableStateOf<Long?>(null) }
  LaunchedEffect(row.state is DownloadState.Completed) { runner.checkFile(row) }

  TaskHeader(
    row = row,
    device = device,
    reason = reason,
    highlight = highlight,
    stalled = stalled,
    onReason = { action -> runReason(state, row, runner, action) },
    onCopyName = {
      copier.copy(row.name, "name") { state.messages.post(MessageLevel.Success, "Copied the name") }
    },
    onClose = onClose,
  )
  ActionBar(state, row, runner, instances)
  val tabs = rememberInspectorTabs(state, row)
  val shown = if (tab in tabs) tab else InspectorTab.Overview
  if (tabs.size > 1) {
    KetchSegmented(
      options = tabs,
      selected = shown,
      onSelect = onTab,
      label = { it.title },
      count = { it.count(row) },
    )
  }
  when (shown) {
    InspectorTab.Overview -> Overview(state, row, device, runner, pending, copier)
    InspectorTab.Connections -> ConnectionsTab(state, row, onHighlight = { highlight = it })
    InspectorTab.Files -> FilesTab(row, tabFill.modifier, maxHeight = tabFill.height)
    InspectorTab.Activity -> ActivityTab(state, row)
  }
}

/** The Overview tab: the problem of a failed download, its Controls and its Details. */
@Composable
private fun Overview(
  state: AppState,
  row: TaskRow,
  device: DeviceLabel,
  runner: RowActionRunner,
  pending: Set<Pair<TaskKey, String>>,
  copier: Copier,
) {
  val error = row.content.error
  if (row.state is DownloadState.Failed && error != null) {
    ProblemCard(row, error, runner, inBar = setOfNotNull(runner.primary(row)))
  }
  if (row.state.hasControls) InspectorControls(state, listOf(row), runner, pending)
  TaskDetails(state, row, device, runner, copier)
}

private fun runReason(
  state: AppState,
  row: TaskRow,
  runner: RowActionRunner,
  action: ReasonAction,
) {
  when (action) {
    ReasonAction.Reconnect -> runner.run(RowAction.Reconnect, listOf(row))
    ReasonAction.FullSpeed -> state.switchSpeedMode(SpeedLimitMode.Full)
    ReasonAction.RemoveLimit -> runner.setSpeedLimit(listOf(row), SpeedLimit.Unlimited)
    ReasonAction.SpeedSettings -> {
      state.openSettings(SettingsTarget(SettingsTarget.Page.Speed, row.key.deviceId))
    }
  }
}
