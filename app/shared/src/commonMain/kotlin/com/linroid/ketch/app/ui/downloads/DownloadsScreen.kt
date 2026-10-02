package com.linroid.ketch.app.ui.downloads

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.ListArrangement
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.RowGroup
import com.linroid.ketch.app.state.SelectionState
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskListView
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.actions.ListActions
import com.linroid.ketch.app.ui.downloads.actions.RowActionDialogs
import com.linroid.ketch.app.ui.downloads.actions.SelectionBar
import com.linroid.ketch.app.ui.downloads.actions.SendConfirmationDialog
import com.linroid.ketch.app.ui.downloads.actions.isSelectionMode
import com.linroid.ketch.app.ui.downloads.actions.rememberListActions
import com.linroid.ketch.app.ui.list.DownloadList
import com.linroid.ketch.app.ui.list.GroupCollapse
import com.linroid.ketch.app.ui.shell.KetchLayout
import com.linroid.ketch.app.util.SearchQuery
import com.linroid.ketch.config.DownloadsLayout
import com.linroid.ketch.remote.ConnectionState

/** Width tiers of the window, which set how the app lays itself out. */
enum class LayoutTier {
  /** Phones, narrower than [KetchLayoutInfo.MediumWidth]. */
  Compact,

  /** Tablets and narrow windows. */
  Medium,

  /** Wide windows, from [KetchLayoutInfo.ExpandedWidth]. */
  Expanded,
}

/**
 * The room the window gives the app, which screens adapt to.
 *
 * @property tier width tier of the window.
 * @property windowWidth width of the window.
 */
@Immutable
data class KetchLayoutInfo(
  val tier: LayoutTier,
  val windowWidth: Dp,
) {
  companion object {
    /** Narrowest window of the [LayoutTier.Medium] tier. */
    val MediumWidth: Dp = 600.dp

    /** Narrowest window of the [LayoutTier.Expanded] tier. */
    val ExpandedWidth: Dp = 1024.dp

    /** Layout of a window [windowWidth] wide. */
    fun of(windowWidth: Dp): KetchLayoutInfo = KetchLayoutInfo(
      tier = when {
        windowWidth < MediumWidth -> LayoutTier.Compact
        windowWidth < ExpandedWidth -> LayoutTier.Medium
        else -> LayoutTier.Expanded
      },
      windowWidth = windowWidth,
    )
  }
}

/**
 * The Downloads page: the header with search and the view toggles, the status tabs (or the
 * selection bar), the search's facets, and the tasks of the shown device, or of every device
 * with their Device column and pennants, from [AppState.taskList], as a table where a pointer
 * has room for it and as two-line rows elsewhere. Before the first download it shows the
 * launchpad.
 *
 * The inspector docks beside the table on cards from [KetchLayout.DockedInspectorWidth], floats
 * over the list on narrower ones and opens in a bottom sheet on phones. On phones the status
 * tabs are chips that scroll away with the top bar.
 */
@Composable
fun DownloadsScreen(state: AppState, layout: KetchLayoutInfo, modifier: Modifier = Modifier) {
  CompositionLocalProvider(LocalAppState provides state) {
    val view by state.taskList.view.collectAsState()
    val actions = rememberListActions(view.rows, state)
    val page = remember(state, actions) { DownloadsPage(state, actions) }
    PageEffects(page, view)
    CompositionLocalProvider(LocalShownDevices provides rememberShownDevices(state)) {
      BoxWithConstraints(modifier) {
        if (layout.tier == LayoutTier.Compact) {
          PhoneDownloads(page, view)
        } else {
          WideDownloads(page, view, layout, cardWidth = maxWidth)
        }
      }
    }
    RowActionDialogs(actions.runner)
    SendConfirmationDialog(state)
  }
}

/**
 * What the Downloads page keeps while it is shown: the list's selection and actions, the groups
 * the user collapsed, the scroll position and the search field's focus, plus the preferences it
 * reads and saves in `UiPreferences`.
 */
@Stable
internal class DownloadsPage(val state: AppState, val actions: ListActions) {
  /** Groups the user collapsed or opened. */
  val collapse: GroupCollapse = GroupCollapse()

  /** Scroll position of the table or list. */
  val listState: LazyListState = LazyListState()

  /** Focuses the header's search field. */
  val searchFocus: FocusRequester = FocusRequester()

  /** Whether the search field is open on a card too narrow to keep it open. */
  var searchOpen: Boolean by mutableStateOf(false)

  /** Whether the pointer is over the list, which holds its order still. */
  var hovering: Boolean by mutableStateOf(false)

  /** How the user wants the downloads laid out. */
  val viewMode: DownloadsLayout get() = state.appSettings.ui.layout

  /** How tall the table's rows are. */
  val rowDensity: RowDensity
    get() = RowDensity.fromId(state.appSettings.ui.table[RowDensity.ROWS_KEY])

  /** The columns of the [filter] tab's table. */
  fun tableLayout(filter: StatusFilter): TableLayout =
    TableLayout.decode(state.appSettings.ui.table[filter.name])

  /** The sort order and grouping saved for the [filter] tab. */
  fun arrangementOf(filter: StatusFilter): ListArrangement =
    ListArrangement.decode(state.appSettings.ui.sort[filter.name], filter)

  /** Saves [layout] as the columns of the [filter] tab's table. */
  fun saveTableLayout(filter: StatusFilter, layout: TableLayout) {
    state.appSettings.saveUi { it.copy(table = it.table + (filter.name to layout.encode())) }
  }

  /** Saves the table's row density. */
  fun saveRowDensity(density: RowDensity) {
    state.appSettings.saveUi { it.copy(table = it.table + (RowDensity.ROWS_KEY to density.id)) }
  }

  /** Saves how the downloads are laid out. */
  fun saveViewMode(mode: DownloadsLayout) {
    state.appSettings.saveUi { it.copy(layout = mode) }
  }

  /** Shows the current tab in [arrangement] and saves it for that tab. */
  fun arrange(arrangement: ListArrangement) {
    val filter = state.statusFilter
    state.listArrangement = arrangement
    state.appSettings.saveUi { it.copy(sort = it.sort + (filter.name to arrangement.encode())) }
  }

  /**
   * Retries [rows]: one row runs its own fix, such as Retry or Download again, and several retry
   * together, starting over those whose progress cannot be reused.
   */
  fun retry(rows: List<TaskRow>) {
    val runner = actions.runner
    val row = rows.singleOrNull()
    if (row != null) {
      runner.primary(row)?.let { runner.run(it, listOf(row)) }
    } else if (rows.isNotEmpty()) {
      runner.run(RowAction.Retry, rows)
    }
  }
}

/**
 * Keeps the page in step with the list: each tab shows its saved order, a new tab or search
 * starts at the top, removed tasks leave the selection and the inspector, clearing the selection
 * returns the inspector to its overview, and the order holds still while the user works in the
 * list.
 */
@Composable
private fun PageEffects(page: DownloadsPage, view: TaskListView) {
  val state = page.state
  val actions = page.actions
  val filter = state.statusFilter
  val rows by state.taskList.rows.collectAsState()
  val tasks by state.tasks.collectAsState()
  LaunchedEffect(filter) { state.listArrangement = page.arrangementOf(filter) }
  LaunchedEffect(filter, view.query) { page.listState.scrollToItem(0) }
  LaunchedEffect(rows, tasks) {
    // Rows trail the tasks by a moment; prune only once they caught up.
    if (rows.size != tasks.size) return@LaunchedEffect
    val existing = rows.mapTo(HashSet()) { it.key }
    actions.selection.prune(existing)
    if (state.inspectedTask?.let { it !in existing } == true) state.inspect(null)
  }
  LaunchedEffect(state) {
    var selected = state.selectedKeys.isNotEmpty()
    snapshotFlow { state.selectedKeys.isNotEmpty() }.collect { now ->
      if (selected && !now) state.inspect(null)
      selected = now
    }
  }
  val pointer = KetchTheme.density == KetchDensity.Compact
  LaunchedEffect(state, pointer) {
    if (!pointer) return@LaunchedEffect
    // A row shown from elsewhere, such as the Activity popover or the menu bar, is selected too,
    // so it stands out and scrolls into view as a clicked row does.
    snapshotFlow { state.inspectedTask }.collect { key ->
      if (key != null && actions.selection.count == 0 && key in actions.visibleKeys) {
        actions.selection.update(SelectionState().select(key))
      }
    }
  }
  LaunchedEffect(page) {
    snapshotFlow {
      val keyboard = actions.keyboard
      page.hovering || actions.menu.isOpen || keyboard.hasFocus && keyboard.focusVisible
    }.collect { state.listFrozen = it }
  }
  DisposableEffect(page) { onDispose { state.listFrozen = false } }
}

/** What the content area under the tabs shows. */
private enum class PageContent {
  /** Nothing yet: rows trail the tasks for a moment. */
  Blank,

  /** Placeholder rows while a device sends its downloads. */
  Loading,

  /** The first-run launchpad of a device without downloads. */
  Launchpad,

  /** A remote device without downloads. */
  RemoteEmpty,

  /** A remote device that cannot be reached and has sent no downloads yet. */
  Offline,

  /** Every device shows, and none has downloads. */
  FleetEmpty,

  /** A tab or search that matches nothing. */
  Empty,

  /** The table or the list. */
  Rows;

  /** Whether the device has no downloads to show at all, so the tabs and inspector stay away. */
  val isBare: Boolean
    get() = this == Launchpad || this == RemoteEmpty || this == Offline || this == FleetEmpty
}

@Composable
private fun pageContent(state: AppState, view: TaskListView): PageContent {
  val rows by state.taskList.rows.collectAsState()
  val tasks by state.tasks.collectAsState()
  val active by state.activeInstance.collectAsState()
  val connection by state.connectionState.collectAsState()
  val shown = LocalShownDevices.current
  val remote = active is RemoteInstance
  return when {
    tasks.isEmpty() && rows.isEmpty() -> when {
      shown.several -> PageContent.FleetEmpty
      !remote -> PageContent.Launchpad
      connection == ConnectionState.Connecting -> PageContent.Loading
      connection is ConnectionState.Disconnected ||
        connection == ConnectionState.Unauthorized -> PageContent.Offline
      else -> PageContent.RemoteEmpty
    }
    rows.isEmpty() -> PageContent.Blank
    view.rows.isNotEmpty() -> PageContent.Rows
    // The view trails the rows for a moment after they first arrive.
    view.query.isEmpty && rows.any { view.filter.matches(it.state) } -> PageContent.Blank
    else -> PageContent.Empty
  }
}

@Composable
private fun WideDownloads(
  page: DownloadsPage,
  view: TaskListView,
  layout: KetchLayoutInfo,
  cardWidth: Dp,
) {
  val state = page.state
  val spacing = KetchTheme.spacing
  val ui = state.appSettings.ui
  val instances by state.instances.collectAsState()
  val content = pageContent(state, view)
  val firstRun = content.isBare
  val docked = cardWidth >= KetchLayout.DockedInspectorWidth
  var draggedWidth by remember { mutableStateOf<Dp?>(null) }
  val inspectorWidth = (draggedWidth ?: ui.inspectorWidth.dp)
    .coerceIn(spacing.inspectorMinWidth, spacing.inspectorMaxWidth)
  val dockedOpen = docked && state.inspectorOpen && !firstRun
  val tableWidth = if (dockedOpen) cardWidth - inspectorWidth - HairlineWidth else cardWidth
  val pointer = KetchTheme.density == KetchDensity.Compact
  val tableFits = pointer && tableWidth >= TableColumn.TableMinWidth
  val showsTable = tableFits && page.viewMode != DownloadsLayout.List
  // The sidebar lists the devices; on narrow cards the search field needs the room more.
  val sidebar = layout.tier == LayoutTier.Expanded && !ui.sidebarCollapsed
  val showDevice = (instances.size >= 2 || !sidebar) && (!sidebar || cardWidth >= DeviceChipWidth)
  val hover = remember { MutableInteractionSource() }
  val hovering by hover.collectIsHoveredAsState()
  LaunchedEffect(hovering) { page.hovering = hovering }
  Column(Modifier.fillMaxSize()) {
    DownloadsHeader(
      page = page,
      cardWidth = cardWidth,
      showDevice = showDevice,
      tableFits = tableFits,
      showsTable = showsTable,
      hasRows = !firstRun,
    )
    if (!firstRun) TabArea(page, view, showsTable)
    if (!firstRun && !view.query.isEmpty) {
      val onTab = rowsOnTab(state, view.filter)
      FacetRow(
        query = view.query,
        rows = onTab,
        matched = view.matched,
        total = view.total,
        onQueryChange = { state.searchQuery = it.format() },
      )
    }
    Row(Modifier.weight(1f).fillMaxWidth()) {
      Box(Modifier.weight(1f).fillMaxHeight().hoverable(hover)) {
        PageBody(page, view, content, showsTable, phone = false)
        if (!docked) {
          OverlayInspector(
            state = state,
            taskKey = state.inspectedTask,
            visible = state.inspectorOpen && !firstRun &&
              (state.inspectedTask != null || state.selectedKeys.size >= 2),
            onClose = { state.updateInspectorOpen(false) },
          )
        }
      }
      if (dockedOpen) {
        DockedInspector(
          state = state,
          taskKey = state.inspectedTask,
          width = inspectorWidth,
          onResize = { draggedWidth = it },
          onResizeEnd = {
            val width = inspectorWidth.value.toInt()
            state.appSettings.saveUi { it.copy(inspectorWidth = width) }
            draggedWidth = null
          },
          onClose = { state.updateInspectorOpen(false) },
        )
      }
    }
  }
}

/** The status tabs, or the selection bar in their place while two or more rows are selected. */
@Composable
private fun TabArea(page: DownloadsPage, view: TaskListView, showsTable: Boolean) {
  val state = page.state
  val actions = page.actions
  val counts by state.taskList.counts.collectAsState()
  val selected = actions.selectedRows
  val motion = KetchTheme.motion
  Crossfade(
    targetState = selected.size >= 2,
    animationSpec = tween(motion.short, easing = motion.easeStandard),
    label = "tabArea",
  ) { selecting ->
    if (selecting) {
      SelectionBar(
        rows = actions.selectedRows,
        runner = actions.runner,
        onClear = actions.selection::clear,
        onSelectAll = actions::selectAll,
      )
    } else {
      StatusTabs(
        selected = view.filter,
        counts = counts,
        onSelect = { state.statusFilter = it },
      ) {
        TabAction(
          filter = view.filter,
          counts = counts,
          needsLink = view.rows.count { it.content.error?.primary == RowAction.EditLink },
          onClearFinished = { state.clearCompleted() },
          onRetryAll = { page.retry(view.rows.filter { it.state.needsAttention }) },
        )
        ArrangementMenu(
          arrangement = view.arrangement,
          table = showsTable,
          onChange = page::arrange,
        )
      }
    }
  }
}

/** The content under the tabs: the rows, or what stands in for them. */
@Composable
private fun PageBody(
  page: DownloadsPage,
  view: TaskListView,
  content: PageContent,
  showsTable: Boolean,
  phone: Boolean,
) {
  val state = page.state
  val spacing = KetchTheme.spacing
  val density = KetchTheme.density
  val active by state.activeInstance.collectAsState()
  val deviceName = active?.displayName ?: localDeviceNoun()
  val bottom = if (phone) KetchLayout.FabClearance else spacing.s4
  val onAction: (EmptyAction) -> Unit = { action ->
    when (action) {
      EmptyAction.ClearSearch -> state.searchQuery = ""
      EmptyAction.ShowAll -> state.statusFilter = StatusFilter.All
      EmptyAction.Add -> state.openIntake()
      EmptyAction.AddLinks -> {
        val text = state.searchQuery
        state.searchQuery = ""
        state.openIntake(IntakeRequest(text = text))
      }
    }
  }
  when (content) {
    PageContent.Blank -> Box(Modifier.fillMaxSize())
    PageContent.Loading -> SkeletonRows(
      rowHeight = if (showsTable) density.tableRow else density.listRow,
      chip = if (showsTable) KetchFileTypeChipDefaults.TableSize else {
        KetchFileTypeChipDefaults.ListSize
      },
    )
    PageContent.Launchpad -> Launchpad(state, phone)
    PageContent.FleetEmpty -> {
      // The add sheet opens on the device links last went to, as quick add does.
      val target = state.quickAddTarget()?.displayName ?: deviceName
      EmptyMessage(fleetEmptyCopy(target), onAction)
    }
    PageContent.RemoteEmpty -> EmptyMessage(remoteEmptyCopy(deviceName), onAction)
    PageContent.Offline -> {
      val connection by state.connectionState.collectAsState()
      val unauthorized = connection == ConnectionState.Unauthorized
      EmptyMessage(offlineCopy(deviceName, unauthorized), onAction)
    }
    PageContent.Empty -> EmptyMessage(
      copy = emptyCopy(
        filter = view.filter,
        query = state.searchQuery,
        deviceName = deviceName,
        slots = state.instanceSettings.download?.maxConcurrentDownloads
          .takeUnless { LocalShownDevices.current.several },
      ),
      onAction = onAction,
    )
    PageContent.Rows -> if (showsTable) {
      val filter = view.filter
      val devices = LocalShownDevices.current
      DownloadTable(
        view = view,
        actions = page.actions,
        collapse = page.collapse,
        listState = page.listState,
        layout = page.tableLayout(filter),
        onLayoutChange = { page.saveTableLayout(filter, it) },
        autoColumns = if (devices.several) setOf(TableColumn.Device) else emptySet(),
        onSort = { key -> page.arrange(nextArrangement(view.arrangement, key)) },
        onAddToken = { token ->
          state.searchQuery = (SearchQuery.parse(state.searchQuery) + token).format()
        },
        rowHeight = if (page.rowDensity == RowDensity.Compact) {
          spacing.tableRowCompact
        } else {
          density.tableRow
        },
        modifier = Modifier.fillMaxSize(),
        groupAction = { group -> RetryGroup(page, group) },
      )
    } else {
      DownloadList(
        view = view,
        actions = page.actions,
        collapse = page.collapse,
        listState = page.listState,
        bottomPadding = bottom,
        modifier = Modifier.fillMaxSize(),
        groupAction = { group -> RetryGroup(page, group) },
      )
    }
  }
}

/** "Retry all (2)" on the header of a group of failed and canceled downloads. */
@Composable
private fun RowScope.RetryGroup(page: DownloadsPage, group: RowGroup) {
  if (group.rows.isEmpty() || !group.rows.all { it.state.needsAttention }) return
  TextAction("Retry all (${group.rows.size})", onClick = { page.retry(group.rows) })
}

/** Whether a task in this state failed or was canceled. */
private val DownloadState.needsAttention: Boolean
  get() = this is DownloadState.Failed || this is DownloadState.Canceled

@Composable
private fun rowsOnTab(state: AppState, filter: StatusFilter): List<TaskRow> {
  val rows by state.taskList.rows.collectAsState()
  return remember(rows, filter) { rows.filter { filter.matches(it.state) } }
}

/**
 * The phone's Downloads page under the shell's top bar: the status chips, which scroll away
 * with the bar, then the rows. A tap shows a row in the inspector's sheet; with rows selected
 * the selection bar sits at the bottom.
 */
@Composable
private fun PhoneDownloads(page: DownloadsPage, view: TaskListView) {
  val state = page.state
  val actions = page.actions
  val counts by state.taskList.counts.collectAsState()
  val content = pageContent(state, view)
  val chips = remember { CollapsingChips() }
  val selected = actions.selectedRows
  // With a pointer, as in a narrow browser window, a click selects the row it shows.
  val pointer = KetchTheme.density == KetchDensity.Compact
  val selecting = isSelectionMode(actions.selection.count, pointer)
  Box(Modifier.fillMaxSize().nestedScroll(chips.connection)) {
    Column(Modifier.fillMaxSize()) {
      if (!content.isBare) {
        chips.Bar {
          StatusChips(
            selected = view.filter,
            counts = counts,
            onSelect = { state.statusFilter = it },
          )
        }
      }
      if (content == PageContent.Rows) ClipboardChip(state)
      if (view.query.tokens.isNotEmpty()) {
        FacetRow(
          query = view.query,
          rows = rowsOnTab(state, view.filter),
          matched = view.matched,
          total = view.total,
          onQueryChange = { state.searchQuery = it.format() },
        )
      }
      Box(Modifier.weight(1f).fillMaxWidth()) {
        PageBody(page, view, content, showsTable = false, phone = true)
      }
      if (selecting && selected.isNotEmpty()) {
        SelectionBar(
          rows = selected,
          runner = actions.runner,
          onClear = actions.selection::clear,
          onSelectAll = actions::selectAll,
          compact = true,
        )
      }
    }
  }
  val inspected = state.inspectedTask
  if (inspected != null && !selecting) {
    SheetInspector(state, inspected, onClose = { state.inspect(null) })
  }
}

/** Width of the 1 dp lines that frame the page's parts. */
internal val HairlineWidth: Dp = 1.dp
