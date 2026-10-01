package com.linroid.ketch.app.ui.downloads

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchPillGroup
import com.linroid.ketch.app.components.KetchPillItem
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.app.util.links
import com.linroid.ketch.config.DownloadsLayout

/**
 * The Downloads page header: the title with its count pill, the device chip when the sidebar
 * does not name the device, then the search field, the view and inspector toggles, the "⋯"
 * menu and Add. The search field shrinks to a button on cards narrower than
 * [SearchCollapseWidth], or [SearchWithDeviceWidth] next to the device chip; that button opens
 * it over the header. The list and table toggles show only where the table fits.
 *
 * @param page the page's state.
 * @param cardWidth width of the content card.
 * @param showDevice whether to show the device chip.
 * @param tableFits whether the table has room, so the view can be picked.
 * @param showsTable whether the page shows the table rather than list rows.
 */
@Composable
internal fun DownloadsHeader(
  page: DownloadsPage,
  cardWidth: Dp,
  showDevice: Boolean,
  tableFits: Boolean,
  showsTable: Boolean,
  modifier: Modifier = Modifier,
) {
  val state = page.state
  val spacing = KetchTheme.spacing
  val collapsed = cardWidth < if (showDevice) SearchWithDeviceWidth else SearchCollapseWidth
  LaunchedEffect(page) {
    state.focusSearchRequests.collect {
      page.searchOpen = true
      // The field may only now be composed; ask once it is.
      runCatching { page.searchFocus.requestFocus() }
    }
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier
      .fillMaxWidth()
      .height(spacing.pageHeaderHeight)
      .padding(horizontal = spacing.pageHeaderPadding),
  ) {
    if (collapsed && (page.searchOpen || state.searchQuery.isNotEmpty())) {
      SearchField(page, fill = true, modifier = Modifier.weight(1f))
      KetchIconButton(
        icon = KetchIcon.Close,
        contentDescription = "Close search",
        onClick = {
          state.searchQuery = ""
          page.searchOpen = false
        },
      )
      return@Row
    }
    Title(page)
    Box(Modifier.weight(1f)) {
      if (showDevice) DeviceChip(state)
    }
    if (collapsed) {
      KetchIconButton(
        command = KetchCommands.Search,
        onClick = { page.searchOpen = true },
        selected = state.searchQuery.isNotEmpty(),
      )
    } else {
      SearchField(page, fill = false)
    }
    if (tableFits) {
      KetchPillGroup(
        listOf(
          KetchPillItem(
            icon = KetchIcon.All,
            label = "List",
            onClick = { page.saveViewMode(DownloadsLayout.List) },
            selected = !showsTable,
          ),
          KetchPillItem(
            icon = KetchIcon.Columns,
            label = "Table",
            onClick = { page.saveViewMode(DownloadsLayout.Table) },
            selected = showsTable,
          ),
          KetchPillItem(
            command = KetchCommands.ToggleInspector,
            onClick = { state.updateInspectorOpen(!state.inspectorOpen) },
            selected = state.inspectorOpen,
          )
        )
      )
    } else {
      KetchIconButton(
        command = KetchCommands.ToggleInspector,
        onClick = { state.updateInspectorOpen(!state.inspectorOpen) },
        selected = state.inspectorOpen,
      )
    }
    OverflowMenu(page, showsTable)
    KetchButton(
      text = "Add",
      onClick = { state.requestAddDownload() },
      leadingIcon = KetchIcon.Plus,
      tooltip = KetchCommands.Add.label,
      shortcut = KetchCommands.Add.shortcutLabel(),
    )
  }
}

/** "Downloads" and its count pill: downloading and total, such as "2↓/14". */
@Composable
private fun Title(page: DownloadsPage) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val counts by page.state.taskList.counts.collectAsState()
  val total = counts[StatusFilter.All] ?: 0
  val downloading = counts[StatusFilter.Downloading] ?: 0
  Text(
    text = "Downloads",
    style = KetchTheme.typography.pageTitle,
    color = colors.textPrimary,
    maxLines = 1,
  )
  if (total > 0) {
    val text = if (downloading > 0) "$downloading↓/$total" else "$total"
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .height(spacing.s5)
        .background(colors.surfaceSunken, KetchTheme.shapes.full)
        .padding(horizontal = spacing.s2)
        .semantics {
          contentDescription = if (downloading > 0) {
            "$downloading downloading of $total"
          } else {
            "$total downloads"
          }
        },
    ) {
      Text(
        text = text,
        style = KetchTheme.typography.numeralS,
        color = colors.textSecondary,
        maxLines = 1,
      )
    }
  }
}

/** The active device as a chip that opens the device switcher. */
@Composable
private fun DeviceChip(state: AppState) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val active by state.activeInstance.collectAsState()
  val entry = active ?: return
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
      .widthIn(max = DeviceChipMaxWidth)
      .clip(shape)
      .background(colors.surface)
      .background(overlay)
      .border(1.dp, colors.borderStrong, shape)
      .trackFocusVisibility(focus)
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClickLabel = "Switch device",
        onClick = { state.showInstanceSelector = true },
      )
      .padding(start = spacing.s1, end = spacing.s2),
  ) {
    DevicePennant(
      deviceId = entry.deviceId,
      name = entry.label,
      size = DevicePennantDefaults.Small,
    )
    Text(
      text = entry.displayName,
      style = KetchTheme.typography.labelS,
      color = colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
    )
    KetchIconImage(KetchIcon.ChevronDown, size = spacing.s3, tint = colors.textTertiary)
  }
}

/**
 * The search field: it filters as you type, grows while focused unless it [fill]s the header,
 * and ↩ on a pasted link opens the add sheet with it. ↓ moves into the list, and Esc clears the
 * field, then leaves it.
 */
@Composable
private fun SearchField(page: DownloadsPage, fill: Boolean, modifier: Modifier = Modifier) {
  val state = page.state
  val motion = KetchTheme.motion
  var focused by remember { mutableStateOf(false) }
  val query = state.searchQuery
  val width by animateDpAsState(
    targetValue = if (focused) SearchFocusedWidth else SearchWidth,
    animationSpec = tween(motion.short, easing = motion.easeStandard),
  )
  val hint = KetchCommands.Palette.shortcutLabel()
  if (fill) {
    // Opened over the header by its button or a shortcut: ready to type.
    LaunchedEffect(page) { runCatching { page.searchFocus.requestFocus() } }
  }
  KetchTextField(
    value = query,
    onValueChange = { state.searchQuery = it },
    placeholder = "Search or paste a link",
    leadingIcon = KetchIcon.Search,
    onFocusChange = { focused = it },
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions = KeyboardActions(onSearch = { addLinks(page) }),
    trailing = if (query.isEmpty() && !focused && hint != null) {
      {
        Text(
          text = hint,
          style = KetchTheme.typography.labelS,
          color = KetchTheme.colors.textTertiary,
          modifier = Modifier.padding(end = KetchTheme.spacing.s2),
        )
      }
    } else {
      null
    },
    modifier = modifier
      .then(if (fill) Modifier else Modifier.width(width))
      .focusRequester(page.searchFocus)
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
          Key.Enter, Key.NumPadEnter -> addLinks(page)
          Key.DirectionDown -> {
            page.actions.keyboard.focus()
            true
          }
          Key.Escape -> {
            if (query.isNotEmpty()) state.searchQuery = "" else page.actions.keyboard.focus()
            true
          }
          else -> false
        }
      },
  )
}

/** Opens the add sheet with the links typed or pasted in the search field, if there are any. */
private fun addLinks(page: DownloadsPage): Boolean {
  val state = page.state
  val text = state.searchQuery
  if (LinkParser.parseIntake(text).links().isEmpty()) return false
  state.searchQuery = ""
  state.openIntake(IntakeRequest(text = text))
  return true
}

/**
 * The "⋯" menu. Its items stay in place, disabled when they cannot run, so the menu never
 * changes shape: Pause all, Resume all, Retry failed, Clear finished, Select all, Copy all
 * links, the table's columns and its row density.
 */
@Composable
private fun OverflowMenu(page: DownloadsPage, showsTable: Boolean) {
  val state = page.state
  val actions = page.actions
  val rows by state.taskList.rows.collectAsState()
  var open by remember { mutableStateOf(false) }
  val visible = actions.rows
  Box {
    KetchIconButton(
      icon = KetchIcon.More,
      contentDescription = "More",
      onClick = { open = true },
      selected = open,
    )
    KetchMenu(expanded = open, onDismissRequest = { open = false }) {
      item(
        command = KetchCommands.PauseAll,
        onClick = { state.pauseAll() },
        enabled = rows.any { it.state.isPausable },
      )
      item(
        command = KetchCommands.ResumeAll,
        onClick = { state.resumeAll() },
        enabled = rows.any { it.state is DownloadState.Paused },
      )
      item(
        command = KetchCommands.RetryFailed,
        onClick = { state.retryFailed() },
        enabled = rows.any { it.state is DownloadState.Failed },
      )
      val finished = rows.count { it.state is DownloadState.Completed }
      item(
        label = clearFinishedLabel(finished),
        icon = KetchIcon.Trash,
        onClick = { state.clearCompleted() },
        enabled = finished > 0,
      )
      divider()
      item(
        command = KetchCommands.SelectAll,
        onClick = { actions.selectAll() },
        enabled = visible.isNotEmpty(),
      )
      item(
        label = "Copy all links",
        icon = KetchIcon.Copy,
        onClick = { copyLinks(page, visible) },
        enabled = visible.isNotEmpty(),
      )
      divider()
      submenu(label = "Columns", icon = KetchIcon.Columns, enabled = showsTable) {
        columnChooser(page.tableLayout(state.statusFilter)) {
          page.saveTableLayout(state.statusFilter, it)
        }
      }
      submenu(label = "Row density", icon = KetchIcon.Lanes, enabled = showsTable) {
        for (density in RowDensity.entries) {
          item(
            label = density.label,
            checked = page.rowDensity == density,
            onClick = { page.saveRowDensity(density) },
          )
        }
      }
    }
  }
}

private val DownloadState.isPausable: Boolean
  get() = this is DownloadState.Downloading || this is DownloadState.Queued

/** "Clear 4 finished", or "Clear finished" when there are none. */
internal fun clearFinishedLabel(count: Int): String =
  if (count > 0) "Clear $count finished" else "Clear finished"

private fun copyLinks(page: DownloadsPage, rows: List<TaskRow>) {
  page.actions.runner.run(RowAction.CopyLink, rows)
}

/** Narrowest card whose header keeps the search field open. */
internal val SearchCollapseWidth: Dp = 760.dp

/** Narrowest card whose header keeps the search field open beside the device chip. */
internal val SearchWithDeviceWidth: Dp = 900.dp

/** Narrowest card that shows the device chip while the sidebar names the device. */
internal val DeviceChipWidth: Dp = 900.dp

private val SearchWidth: Dp = 240.dp
private val SearchFocusedWidth: Dp = 320.dp
private val DeviceChipMaxWidth: Dp = 200.dp
