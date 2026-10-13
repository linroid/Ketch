package com.linroid.ketch.app.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.ConnectionRoute
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.durationText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.DeviceScope
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.ConnectionGridState
import com.linroid.ketch.app.state.TrafficDirection
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.TableColumn
import com.linroid.ketch.app.ui.downloads.TablePadding
import com.linroid.ketch.app.ui.inspector.tabs.compactSizeText
import com.linroid.ketch.app.ui.inspector.tabs.compactSpeedText
import com.linroid.ketch.app.ui.list.rowDivider
import com.linroid.ketch.app.ui.pulse.SEGMENTED_SOURCES
import com.linroid.ketch.app.ui.pulse.summaryText
import com.linroid.ketch.app.ui.pulse.trafficLabel
import com.linroid.ketch.app.ui.shell.LocalKetchLayout
import com.linroid.ketch.app.ui.shell.ShellNavigation
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.connections_column_device
import ketch.app.shared.generated.resources.connections_column_down
import ketch.app.shared.generated.resources.connections_column_endpoint
import ketch.app.shared.generated.resources.connections_column_open
import ketch.app.shared.generated.resources.connections_column_peer
import ketch.app.shared.generated.resources.connections_column_protocol
import ketch.app.shared.generated.resources.connections_column_received
import ketch.app.shared.generated.resources.connections_column_route
import ketch.app.shared.generated.resources.connections_column_sent
import ketch.app.shared.generated.resources.connections_column_task
import ketch.app.shared.generated.resources.connections_column_up
import ketch.app.shared.generated.resources.connections_empty_body
import ketch.app.shared.generated.resources.connections_filter_all
import ketch.app.shared.generated.resources.connections_more
import ketch.app.shared.generated.resources.connections_no_match
import ketch.app.shared.generated.resources.connections_peer_choked
import ketch.app.shared.generated.resources.connections_peer_unchoked
import ketch.app.shared.generated.resources.connections_peer_uploading
import ketch.app.shared.generated.resources.connections_route_direct
import ketch.app.shared.generated.resources.connections_route_http_proxy
import ketch.app.shared.generated.resources.connections_route_socks_proxy
import ketch.app.shared.generated.resources.connections_search
import ketch.app.shared.generated.resources.connections_unsupported_body
import ketch.app.shared.generated.resources.connections_unsupported_title
import ketch.app.shared.generated.resources.downloads_table_sort_by
import ketch.app.shared.generated.resources.downloads_table_sorted_ascending
import ketch.app.shared.generated.resources.downloads_table_sorted_descending
import ketch.app.shared.generated.resources.pulse_connection_incoming
import ketch.app.shared.generated.resources.pulse_connection_peer_choking
import ketch.app.shared.generated.resources.pulse_connection_secure
import ketch.app.shared.generated.resources.pulse_connection_show_task
import ketch.app.shared.generated.resources.pulse_connection_upload_slot
import ketch.app.shared.generated.resources.pulse_connections_none
import ketch.app.shared.generated.resources.pulse_connections_unsupported
import ketch.app.shared.generated.resources.shell_destination_connections
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Connections page (`⌘7`): every live connection of the shown devices, the fastest
 * [PAGE_LIMIT] of them. Wide windows show a table whose headers sort it, narrow ones and phones
 * two-line rows; chips pick a protocol and the field searches endpoints, tasks, protocols and
 * devices. A row shows its task in the Downloads inspector.
 *
 * Devices stream their connections only while the page shows, and stop five seconds after it
 * leaves (see [com.linroid.ketch.app.state.ConnectionGridModel]).
 */
@Composable
fun ConnectionsScreen(state: AppState) {
  val grid by state.connectionGrid.state(PAGE_LIMIT).collectAsStateWithLifecycle()
  val scope by state.deviceScope.collectAsState()
  val phone = LocalKetchLayout.current.navigation == ShellNavigation.Phone
  var query by rememberSaveable { mutableStateOf("") }
  var source by rememberSaveable { mutableStateOf<String?>(null) }
  var sortName by rememberSaveable { mutableStateOf(ConnectionSort.Opened.name) }
  var descending by rememberSaveable { mutableStateOf(ConnectionArrangement().descending) }
  val arrangement = ConnectionArrangement(ConnectionSort.valueOf(sortName), descending)
  val deviceNames = grid.devices.associate { it.deviceId to it.name.resolve() }
  val all = remember(grid, deviceNames) { connectionRows(grid, deviceNames) }
  val filters = remember(all) { sourceFilters(all) }
  // A protocol whose connections all closed shows every connection again, and stays unpicked
  // when it comes back, rather than hiding the others without a click.
  val shownSource = source?.takeIf { picked -> filters.any { it.source == picked } }
  if (source != null && shownSource == null && all.isNotEmpty()) {
    LaunchedEffect(source) { source = null }
  }
  val rows = remember(all, shownSource, query, arrangement) {
    all.matching(shownSource, query).arranged(arrangement)
  }
  val onShow: (ConnectionRow) -> Unit = { row ->
    state.showConnections(row.cell.task, segments = row.connection.source in SEGMENTED_SOURCES)
  }
  val padding = KetchTheme.density.pagePadding
  Column(Modifier.fillMaxSize()) {
    if (!phone) PageHeader(grid)
    Toolbar(
      filters = filters,
      total = all.size,
      source = shownSource,
      onSource = { source = it },
      query = query,
      onQuery = { query = it },
      stacked = phone,
    )
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
      when {
        !grid.supported -> Message(
          title = stringResource(Res.string.connections_unsupported_title),
          body = stringResource(Res.string.connections_unsupported_body),
        )
        all.isEmpty() -> Message(
          title = stringResource(Res.string.pulse_connections_none),
          body = stringResource(Res.string.connections_empty_body),
        )
        rows.isEmpty() -> Message(title = stringResource(Res.string.connections_no_match))
        !phone && maxWidth >= TableColumn.TableMinWidth -> ConnectionTable(
          rows = rows,
          columns = fitColumns(
            available = maxWidth - padding * 2 - TablePadding * 2,
            devices = scope == DeviceScope.All,
            peers = all.any { it.connection.peer != null },
          ),
          arrangement = arrangement,
          onSort = { sort ->
            val next = arrangement.next(sort)
            sortName = next.sort.name
            descending = next.descending
          },
          onShow = onShow,
          modifier = Modifier.padding(horizontal = padding),
        )
        else -> ConnectionList(rows, showDevices = scope == DeviceScope.All, onShow = onShow)
      }
    }
    Footer(grid, listed = all.size)
  }
}

/** "Connections" and how many are open, with their speeds. */
@Composable
private fun PageHeader(grid: ConnectionGridState) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = KetchTheme.density.pagePadding)
      .padding(top = spacing.s3, bottom = spacing.s2),
  ) {
    Text(
      text = stringResource(Res.string.shell_destination_connections),
      style = KetchTheme.typography.pageTitle,
      color = colors.textPrimary,
    )
    Text(
      text = grid.summaryText().resolve(),
      style = KetchTheme.typography.bodyS,
      color = colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * The protocol chips and the search field: side by side, or the field above the chips when
 * [stacked], as on phones.
 */
@Composable
private fun Toolbar(
  filters: List<SourceFilter>,
  total: Int,
  source: String?,
  onSource: (String?) -> Unit,
  query: String,
  onQuery: (String) -> Unit,
  stacked: Boolean,
) {
  val spacing = KetchTheme.spacing
  val padding = KetchTheme.density.pagePadding
  val chips: @Composable RowScope.() -> Unit = {
    KetchChip(
      label = stringResource(Res.string.connections_filter_all),
      selected = source == null,
      onClick = { onSource(null) },
      count = total,
    )
    for (filter in filters) {
      KetchChip(
        label = filter.label,
        selected = source == filter.source,
        onClick = { onSource(if (source == filter.source) null else filter.source) },
        count = filter.count,
      )
    }
  }
  val field = @Composable { modifier: Modifier ->
    KetchTextField(
      value = query,
      onValueChange = onQuery,
      placeholder = stringResource(Res.string.connections_search),
      leadingIcon = KetchIcon.Search,
      modifier = modifier,
    )
  }
  if (stacked) {
    Column(
      verticalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier.fillMaxWidth().padding(padding),
    ) {
      field(Modifier.fillMaxWidth())
      Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.s1),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        content = chips,
      )
    }
  } else {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      modifier = Modifier.fillMaxWidth().padding(horizontal = padding).padding(bottom = spacing.s3),
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.s1),
        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        content = chips,
      )
      field(Modifier.width(SearchWidth))
    }
  }
}

/** A column of the table; the endpoint takes the room the others leave. */
internal enum class ConnectionColumn(
  val header: StringResource,
  val width: Dp,
  val sort: ConnectionSort,
  val numeric: Boolean = false,
) {
  Device(Res.string.connections_column_device, 112.dp, ConnectionSort.Device),
  Task(Res.string.connections_column_task, 160.dp, ConnectionSort.Task),
  Protocol(Res.string.connections_column_protocol, 104.dp, ConnectionSort.Protocol),
  Route(Res.string.connections_column_route, 96.dp, ConnectionSort.Route),
  Peer(Res.string.connections_column_peer, 136.dp, ConnectionSort.Peer),
  Down(Res.string.connections_column_down, 80.dp, ConnectionSort.Down, numeric = true),
  Up(Res.string.connections_column_up, 80.dp, ConnectionSort.Up, numeric = true),
  Received(Res.string.connections_column_received, 80.dp, ConnectionSort.Received, numeric = true),
  Sent(Res.string.connections_column_sent, 72.dp, ConnectionSort.Sent, numeric = true),
  Open(Res.string.connections_column_open, 72.dp, ConnectionSort.Opened, numeric = true),
}

/**
 * The columns that fit [available] beside the endpoint's [EndpointMinWidth], dropping the least
 * telling first. The device shows under All devices, the peer state while a torrent peer is
 * connected; speeds always show.
 */
internal fun fitColumns(available: Dp, devices: Boolean, peers: Boolean): List<ConnectionColumn> {
  val columns = ConnectionColumn.entries.filter {
    (it != ConnectionColumn.Device || devices) && (it != ConnectionColumn.Peer || peers)
  }.toMutableList()
  fun width() = LeadWidth + EndpointMinWidth + columns.fold(0.dp) { sum, it -> sum + it.width }
  for (column in HideOrder) {
    if (width() <= available) break
    columns -= column
  }
  return columns
}

@Composable
private fun ConnectionTable(
  rows: List<ConnectionRow>,
  columns: List<ConnectionColumn>,
  arrangement: ConnectionArrangement,
  onSort: (ConnectionSort) -> Unit,
  onShow: (ConnectionRow) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(modifier.fillMaxSize()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .height(spacing.tableHeaderHeight)
        .background(colors.surfaceSunken, KetchTheme.shapes.xs)
        .rowDivider(colors.hairline)
        .padding(horizontal = TablePadding),
    ) {
      Box(Modifier.width(LeadWidth))
      HeaderCell(
        label = stringResource(Res.string.connections_column_endpoint),
        sort = ConnectionSort.Endpoint,
        numeric = false,
        arrangement = arrangement,
        onSort = onSort,
        modifier = Modifier.weight(1f),
      )
      for (column in columns) {
        HeaderCell(
          label = stringResource(column.header),
          sort = column.sort,
          numeric = column.numeric,
          arrangement = arrangement,
          onSort = onSort,
          modifier = Modifier.width(column.width),
        )
      }
    }
    LazyColumn(Modifier.fillMaxSize()) {
      items(rows, key = { "${it.cell.key.deviceId}/${it.cell.key.id}" }) { row ->
        TableRow(row, columns, onShow)
      }
    }
  }
}

@Composable
private fun HeaderCell(
  label: String,
  sort: ConnectionSort,
  numeric: Boolean,
  arrangement: ConnectionArrangement,
  onSort: (ConnectionSort) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val active = arrangement.sort == sort
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val ink = if (active || hovered) colors.textPrimary else colors.textTertiary
  val sortBy = stringResource(Res.string.downloads_table_sort_by, label)
  val sorted = if (arrangement.descending) {
    stringResource(Res.string.downloads_table_sorted_descending, label)
  } else {
    stringResource(Res.string.downloads_table_sorted_ascending, label)
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(
      spacing.s1,
      if (numeric) Alignment.End else Alignment.Start,
    ),
    modifier = modifier
      .fillMaxHeight()
      .focusRing(focus.visible, KetchTheme.shapes.xs, colors.focusRing, gap = -spacing.s0_5)
      .hoverable(interactions)
      .ketchClickable(
        interactions = interactions,
        focus = focus,
        onClickLabel = sortBy,
        onClick = { onSort(sort) },
      )
      .semantics { if (active) contentDescription = sorted }
      .padding(horizontal = CellPadding),
  ) {
    if (numeric && active) SortChevron(arrangement.descending, ink)
    KetchEyebrow(label, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (!numeric && active) SortChevron(arrangement.descending, ink)
  }
}

@Composable
private fun SortChevron(descending: Boolean, tint: Color) {
  KetchIconImage(
    icon = if (descending) KetchIcon.ChevronDown else KetchIcon.ChevronUp,
    size = KetchTheme.spacing.s3,
    tint = tint,
  )
}

@Composable
private fun TableRow(
  row: ConnectionRow,
  columns: List<ConnectionColumn>,
  onShow: (ConnectionRow) -> Unit,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val connection = row.connection
  RowFrame(row, onShow, Modifier.height(KetchTheme.density.tableRow)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxSize().padding(horizontal = TablePadding),
    ) {
      TrafficSwatch(row, Modifier.width(LeadWidth))
      Text(
        text = row.endpoint,
        style = type.monoS,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(horizontal = CellPadding),
      )
      for (column in columns) {
        val modifier = Modifier.width(column.width).padding(horizontal = CellPadding)
        when (column) {
          ConnectionColumn.Device -> TextCell(row.deviceName, modifier)
          ConnectionColumn.Task -> TextCell(row.taskName ?: connection.taskId, modifier)
          ConnectionColumn.Protocol -> ProtocolCell(row, modifier)
          ConnectionColumn.Route -> TextCell(routeText(row)?.resolve(), modifier)
          ConnectionColumn.Peer -> PeerCell(row, modifier)
          ConnectionColumn.Down -> NumberCell(
            text = rateText(connection.downloadBps),
            modifier = modifier,
            color = KetchTheme.colors.traffic.download.last(),
          )
          ConnectionColumn.Up -> NumberCell(
            text = rateText(connection.uploadBps),
            modifier = modifier,
            color = KetchTheme.colors.traffic.upload.last(),
          )
          ConnectionColumn.Received ->
            NumberCell(compactSizeText(connection.downloadedBytes).resolve(), modifier)
          ConnectionColumn.Sent ->
            NumberCell(compactSizeText(connection.uploadedBytes).resolve(), modifier)
          ConnectionColumn.Open -> NumberCell(durationText(row.cell.age).resolve(), modifier)
        }
      }
    }
  }
}

/** A row that shows its task when clicked, with the hover fill rows have. */
@Composable
private fun RowFrame(
  row: ConnectionRow,
  onShow: (ConnectionRow) -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val colors = KetchTheme.colors
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  val gap = -KetchTheme.spacing.s0_5
  Box(
    modifier
      .fillMaxWidth()
      .background(if (hovered) colors.surfaceHover else Color.Transparent)
      .rowDivider(colors.divider)
      .focusRing(focus.visible, KetchTheme.shapes.xs, colors.focusRing, gap = gap)
      .hoverable(interactions)
      .ketchClickable(
        interactions = interactions,
        focus = focus,
        onClickLabel = stringResource(Res.string.pulse_connection_show_task),
        onClick = { onShow(row) },
      ),
  ) {
    content()
  }
}

/**
 * Which way the connection's bytes move, by shape and color alike: ↓ in the download color, ↑ in
 * the upload color, both for both, and a dot while idle; named in its tooltip.
 */
@Composable
private fun TrafficSwatch(row: ConnectionRow, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val label = trafficLabel(row.cell.direction).resolve()
  Box(modifier, contentAlignment = Alignment.Center) {
    KetchTooltip(text = label) {
      Row(Modifier.semantics { contentDescription = label }) {
        val direction = row.cell.direction
        if (direction == TrafficDirection.Down || direction == TrafficDirection.Both) {
          Arrow(DownArrow, colors.traffic.download.last())
        }
        if (direction == TrafficDirection.Up || direction == TrafficDirection.Both) {
          Arrow(UpArrow, colors.traffic.upload.last())
        }
        if (direction == TrafficDirection.Idle) Arrow(IdleDot, colors.textTertiary)
      }
    }
  }
}

@Composable
private fun Arrow(glyph: String, color: Color) {
  Text(text = glyph, style = KetchTheme.typography.numeral, color = color, maxLines = 1)
}

@Composable
private fun TextCell(text: String?, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  Text(
    text = text ?: Dash,
    style = KetchTheme.typography.cell,
    color = if (text == null) colors.textTertiary else colors.textSecondary,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
  )
}

@Composable
private fun NumberCell(
  text: String?,
  modifier: Modifier = Modifier,
  color: Color = KetchTheme.colors.textPrimary,
) {
  Text(
    text = text ?: Dash,
    style = KetchTheme.typography.numeral,
    color = if (text == null) KetchTheme.colors.textTertiary else color,
    textAlign = TextAlign.End,
    maxLines = 1,
    overflow = TextOverflow.Clip,
    modifier = modifier,
  )
}

/** The protocol, with a shield when the connection is encrypted. */
@Composable
private fun ProtocolCell(row: ConnectionRow, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
    modifier = modifier,
  ) {
    Text(
      text = row.protocol,
      style = KetchTheme.typography.cell,
      color = colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
    )
    if (row.connection.secure == true) {
      val encrypted = stringResource(Res.string.pulse_connection_secure)
      KetchTooltip(text = encrypted) {
        KetchIconImage(
          icon = KetchIcon.Shield,
          size = KetchTheme.spacing.s3,
          tint = colors.textTertiary,
          modifier = Modifier.semantics { contentDescription = encrypted },
        )
      }
    }
  }
}

/** A torrent peer's state, in full in its tooltip. */
@Composable
private fun PeerCell(row: ConnectionRow, modifier: Modifier = Modifier) {
  val peer = row.connection.peer ?: return TextCell(null, modifier)
  val details = listOfNotNull(
    Res.string.pulse_connection_peer_choking.text().takeIf { peer.peerChoking },
    Res.string.pulse_connection_upload_slot.text().takeIf { peer.uploadSlot },
  )
  Box(modifier) {
    KetchTooltip(text = details.joinText().resolve(), enabled = details.isNotEmpty()) {
      TextCell(peerText(row)?.resolve())
    }
  }
}

/** Two-line rows, for narrow windows and phones. */
@Composable
private fun ConnectionList(
  rows: List<ConnectionRow>,
  showDevices: Boolean,
  onShow: (ConnectionRow) -> Unit,
) {
  LazyColumn(Modifier.fillMaxSize()) {
    items(rows, key = { "${it.cell.key.deviceId}/${it.cell.key.id}" }) { row ->
      ListRow(row, showDevices, onShow)
    }
  }
}

@Composable
private fun ListRow(row: ConnectionRow, showDevices: Boolean, onShow: (ConnectionRow) -> Unit) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  val connection = row.connection
  val details = listOfNotNull(
    verbatim(row.deviceName).takeIf { showDevices },
    verbatim(row.taskName ?: connection.taskId),
    verbatim(row.protocol),
    routeText(row),
    peerText(row),
  ).joinText()
  RowFrame(row, onShow, Modifier.heightIn(min = KetchTheme.density.tableRow)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = KetchTheme.density.pagePadding, vertical = spacing.s2),
    ) {
      TrafficSwatch(row, Modifier.width(LeadWidth))
      Column(Modifier.weight(1f)) {
        Text(
          text = row.endpoint,
          style = type.monoS,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = details.resolve(),
          style = type.caption,
          color = colors.textTertiary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        val down = rateText(connection.downloadBps)
        val up = rateText(connection.uploadBps)
        Text(
          text = "$DownArrow ${down ?: Dash}",
          style = type.numeral,
          color = if (down == null) colors.textTertiary else colors.traffic.download.last(),
          maxLines = 1,
        )
        Text(
          text = "$UpArrow ${up ?: Dash}",
          style = type.numeralS,
          color = if (up == null) colors.textTertiary else colors.traffic.upload.last(),
          maxLines = 1,
        )
      }
    }
  }
}

/** What the table leaves out: slower connections past the limit, and devices that cannot say. */
@Composable
private fun Footer(grid: ConnectionGridState, listed: Int) {
  val more = (grid.total - listed).coerceAtLeast(0)
  val unsupported = grid.unsupported.size.takeIf { grid.supported && it > 0 }
  if (more == 0 && unsupported == null) return
  val lines = listOfNotNull(
    Res.plurals.connections_more.text(more).takeIf { more > 0 },
    unsupported?.let { Res.plurals.pulse_connections_unsupported.text(it) },
  )
  Text(
    text = lines.joinText().resolve(),
    style = KetchTheme.typography.caption,
    color = KetchTheme.colors.textTertiary,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = KetchTheme.density.pagePadding, vertical = KetchTheme.spacing.s2),
  )
}

/** A title, and a sentence under it, in the middle of the page. */
@Composable
private fun Message(title: String, body: String? = null) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(spacing.s2, Alignment.CenterVertically),
    modifier = Modifier.fillMaxSize().padding(KetchTheme.density.pagePadding),
  ) {
    KetchIconImage(KetchIcon.Lanes, size = spacing.s8, tint = colors.textTertiary)
    Text(
      text = title,
      style = KetchTheme.typography.titleM,
      color = colors.textPrimary,
      textAlign = TextAlign.Center,
    )
    if (body != null) {
      Text(
        text = body,
        style = KetchTheme.typography.bodyS,
        color = colors.textSecondary,
        textAlign = TextAlign.Center,
      )
    }
  }
}

/** How it reaches its endpoint, or that the peer connected in; `null` when unknown. */
private fun routeText(row: ConnectionRow): UiText? = when {
  row.incoming -> Res.string.pulse_connection_incoming.text()
  else -> when (row.connection.route) {
    ConnectionRoute.DIRECT -> Res.string.connections_route_direct.text()
    ConnectionRoute.HTTP_PROXY -> Res.string.connections_route_http_proxy.text()
    ConnectionRoute.SOCKS5_PROXY -> Res.string.connections_route_socks_proxy.text()
    ConnectionRoute.UNKNOWN -> null
  }
}

/** A torrent peer's wire and whether it chokes us or we upload to it; `null` for others. */
private fun peerText(row: ConnectionRow): UiText? = row.connection.peer?.let { peer ->
  listOfNotNull(
    verbatim(peer.wire),
    if (peer.peerChoking) {
      Res.string.connections_peer_choked.text()
    } else {
      Res.string.connections_peer_unchoked.text()
    },
    Res.string.connections_peer_uploading.text().takeIf { peer.uploadSlot },
  ).joinText()
}

/** A speed, or `null` for none, which shows as a dash. */
@Composable
private fun rateText(bytesPerSecond: Long): String? =
  bytesPerSecond.takeIf { it > 0 }?.let { compactSpeedText(it).resolve() }

/** The most connections the page lists; the slowest past it are counted under the list. */
private const val PAGE_LIMIT = ActiveConnections.MAX_LIMIT

/** Columns dropped first when the window is narrow. */
private val HideOrder = listOf(
  ConnectionColumn.Peer,
  ConnectionColumn.Route,
  ConnectionColumn.Sent,
  ConnectionColumn.Received,
  ConnectionColumn.Device,
  ConnectionColumn.Open,
  ConnectionColumn.Protocol,
  ConnectionColumn.Task,
)

private val LeadWidth = 24.dp
private val EndpointMinWidth = 180.dp
private val CellPadding = 4.dp
private val SearchWidth = 280.dp
private const val Dash = "–"
private const val DownArrow = "↓"
private const val UpArrow = "↑"
private const val IdleDot = "·"
