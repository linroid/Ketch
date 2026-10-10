package com.linroid.ketch.app.ui.pulse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.api.ConnectionRoute
import com.linroid.ketch.app.components.CellPaint
import com.linroid.ketch.app.components.KetchCellGrid
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.durationText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.speedText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.ConnectionCell
import com.linroid.ketch.app.state.ConnectionCellKey
import com.linroid.ketch.app.state.ConnectionGridState
import com.linroid.ketch.app.state.TrafficDirection
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.KetchTrafficColors
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.pulse_connection_description
import ketch.app.shared.generated.resources.pulse_connection_direct
import ketch.app.shared.generated.resources.pulse_connection_incoming
import ketch.app.shared.generated.resources.pulse_connection_open_for
import ketch.app.shared.generated.resources.pulse_connection_outgoing
import ketch.app.shared.generated.resources.pulse_connection_peer_choking
import ketch.app.shared.generated.resources.pulse_connection_received
import ketch.app.shared.generated.resources.pulse_connection_secure
import ketch.app.shared.generated.resources.pulse_connection_sent
import ketch.app.shared.generated.resources.pulse_connection_upload_slot
import ketch.app.shared.generated.resources.pulse_connection_via_http_proxy
import ketch.app.shared.generated.resources.pulse_connection_via_socks_proxy
import ketch.app.shared.generated.resources.pulse_connections_grid_description
import ketch.app.shared.generated.resources.pulse_connections_legend_both
import ketch.app.shared.generated.resources.pulse_connections_legend_download
import ketch.app.shared.generated.resources.pulse_connections_legend_idle
import ketch.app.shared.generated.resources.pulse_connections_legend_upload
import ketch.app.shared.generated.resources.pulse_connections_more
import ketch.app.shared.generated.resources.pulse_connections_more_long
import ketch.app.shared.generated.resources.pulse_connections_rates
import ketch.app.shared.generated.resources.pulse_connections_summary
import ketch.app.shared.generated.resources.pulse_connections_tooltip

/** Connections the Pulse bar's strip and the phone's mini strip ask for. */
internal const val STRIP_LIMIT = 36

/** Connections the popover and the phone's sheet ask for. */
internal const val POPOVER_LIMIT = 512

/**
 * The live connections grid in the Pulse bar, with the popover it opens; nothing while no shown
 * device has a connection open. Its devices are asked for their connections only while it is
 * composed, which the bar does on the Downloads page.
 */
@Composable
internal fun ConnectionStripHost(state: AppState, barState: PulseBarState) {
  val grid by state.connectionGrid.state(STRIP_LIMIT).collectAsStateWithLifecycle()
  Box {
    ConnectionStrip(grid, onClick = { barState.connectionsOpen = !barState.connectionsOpen })
    ConnectionsPopover(
      state = state,
      expanded = barState.connectionsOpen,
      onDismissRequest = { barState.connectionsOpen = false },
    )
  }
}

/**
 * The Pulse bar's strip: three rows of up to twelve squares, oldest top left, then "+N" for the
 * connections past them. Pointing at a square names it in the tooltip; a click opens the
 * popover. Nothing shows while [grid] has no cells.
 */
@Composable
internal fun ConnectionStrip(grid: ConnectionGridState, onClick: () -> Unit) {
  if (grid.cells.isEmpty()) return
  val colors = KetchTheme.colors
  val cells = grid.cells.take(STRIP_LIMIT)
  val paints = remember(cells, colors) { cells.map { it.paint(colors) } }
  var hovered by remember { mutableStateOf<ConnectionCellKey?>(null) }
  val tip = cells.firstOrNull { it.key == hovered }?.tooltipText()
    ?: Res.string.pulse_connections_tooltip.text()
  KetchTooltip(text = tip.resolve()) {
    BarButton(onClick = onClick, description = grid.gridDescription().resolve()) {
      KetchCellGrid(
        cells = paints,
        cellSize = StripCell,
        gap = StripGap,
        rows = STRIP_ROWS,
        onHover = { index -> hovered = index?.let { cells.getOrNull(it)?.key } },
        // Clear of the button's rounded ends, which would clip the corner cells.
        modifier = Modifier.padding(horizontal = KetchTheme.spacing.s1),
      )
      val more = grid.total - cells.size
      if (more > 0) {
        Text(
          text = Res.string.pulse_connections_more.text(more).resolve(),
          style = KetchTheme.typography.numeralS,
          color = colors.textSecondary,
          maxLines = 1,
        )
      }
    }
  }
}

/**
 * The phone's mini strip after the summary line: three rows of up to eight squares, drawn from
 * [grid] without a tooltip; screen readers hear the summary line instead.
 */
@Composable
internal fun ConnectionMiniStrip(grid: ConnectionGridState, modifier: Modifier = Modifier) {
  if (grid.cells.isEmpty()) return
  val colors = KetchTheme.colors
  val cells = grid.cells.take(MINI_ROWS * MINI_COLUMNS)
  val paints = remember(cells, colors) { cells.map { it.paint(colors) } }
  KetchCellGrid(
    cells = paints,
    cellSize = MiniCell,
    gap = MiniGap,
    rows = MINI_ROWS,
    modifier = modifier,
  )
}

/** How this cell is drawn; see [trafficPaint]. */
internal fun ConnectionCell.paint(colors: KetchColors): CellPaint =
  trafficPaint(direction, level, colors)

/**
 * How a connection doing [direction] at [level] is drawn: downloads solid in the download ramp,
 * uploads a ring in the upload ramp around a faint fill, both split along the diagonal
 * (download lower left), and idle a hairline outline with no fill, so the state never rests on
 * color alone.
 */
internal fun trafficPaint(direction: TrafficDirection, level: Int, colors: KetchColors): CellPaint {
  val traffic = colors.traffic
  val step = (level - 1).coerceIn(0, KetchTrafficColors.LEVELS - 1)
  return when (direction) {
    TrafficDirection.Down -> CellPaint(fill = traffic.download[step])
    TrafficDirection.Up -> CellPaint(
      fill = traffic.uploadSoft,
      outline = traffic.upload[step],
      outlineWidth = UploadRing,
    )
    TrafficDirection.Both -> CellPaint(fill = traffic.download[step], split = traffic.upload[step])
    TrafficDirection.Idle -> CellPaint(fill = colors.surfaceSunken, outline = colors.hairline)
  }
}

/** What a connection doing [direction] is called in the legend and by screen readers. */
internal fun trafficLabel(direction: TrafficDirection): UiText = when (direction) {
  TrafficDirection.Down -> Res.string.pulse_connections_legend_download.text()
  TrafficDirection.Up -> Res.string.pulse_connections_legend_upload.text()
  TrafficDirection.Both -> Res.string.pulse_connections_legend_both.text()
  TrafficDirection.Idle -> Res.string.pulse_connections_legend_idle.text()
}

/**
 * [host] and [port] as `host:port`, with an IPv6 address in brackets; the host alone without a
 * port.
 */
internal fun endpointText(host: String, port: Int?): String {
  val shown = if (':' in host && !host.startsWith("[")) "[$host]" else host
  return if (port == null) shown else "$shown:$port"
}

/** Where this cell's connection goes, as `host:port`. */
internal val ConnectionCell.endpoint: String
  get() = endpointText(connection.host, connection.port)

/** "↓ 1.2 MB/s · ↑ 0 B/s" for [downloadBps] received and [uploadBps] sent. */
internal fun ratesText(downloadBps: Long, uploadBps: Long): UiText =
  Res.string.pulse_connections_rates.text(speedText(downloadBps), speedText(uploadBps))

/** The strip's tooltip for this cell: "example.org:443 · HTTP/2 · ↓ 1.2 MB/s · ↑ 0 B/s". */
internal fun ConnectionCell.tooltipText(): UiText = listOfNotNull(
  verbatim(endpoint),
  connection.protocol?.let(::verbatim),
  ratesText(connection.downloadBps, connection.uploadBps),
).joinText()

/** What a screen reader says for this cell, of the task named [taskName]. */
internal fun ConnectionCell.description(taskName: String?): UiText =
  Res.string.pulse_connection_description.text(
    verbatim(taskName ?: task.taskId),
    verbatim(endpoint),
    trafficLabel(direction),
    speedText(connection.downloadBps),
    speedText(connection.uploadBps),
  )

/** What a screen reader says for [cells]: how many there are, download and upload. */
internal fun gridDescription(cells: List<ConnectionCell>): UiText {
  val down = cells.count { it.direction == TrafficDirection.Down || it.direction == Both }
  val up = cells.count { it.direction == TrafficDirection.Up || it.direction == Both }
  return Res.plurals.pulse_connections_grid_description.text(cells.size, cells.size, down, up)
}

/**
 * [gridDescription] of the grid's cells, then how many more connections it has no cells for,
 * whose directions are not known.
 */
internal fun ConnectionGridState.gridDescription(): UiText {
  val shown = Res.plurals.pulse_connections_grid_description
    .text(cells.size, cells.size, downloading, uploading)
  if (overflow == 0) return shown
  return listOf(shown, Res.plurals.pulse_connections_more_long.text(overflow, overflow))
    .joinText()
}

/** "42 connections · ↓ 3.2 MB/s · ↑ 120 KB/s". */
internal fun ConnectionGridState.summaryText(): UiText = listOf(
  Res.plurals.pulse_connections_summary.text(total),
  ratesText(downloadBps, uploadBps),
).joinText()

/**
 * The lines of a connection's details.
 *
 * @property endpoint where it goes, as `host:port`.
 * @property transport its protocol, and whether it is encrypted.
 * @property route which side opened it and how it reaches its endpoint.
 * @property peer a torrent peer's wire and choke state; `null` for other connections.
 * @property rates its speeds now.
 * @property totals what it received and sent, and how long it has been open.
 */
internal data class ConnectionDetails(
  val endpoint: String,
  val transport: UiText,
  val route: UiText,
  val peer: UiText?,
  val rates: UiText,
  val totals: UiText,
)

/** The details panel's lines for this cell. */
internal fun ConnectionCell.details(): ConnectionDetails {
  val connection = connection
  val transport = listOfNotNull(
    verbatim(connection.protocol ?: connection.source.uppercase()),
    Res.string.pulse_connection_secure.text().takeIf { connection.secure == true },
  ).joinText()
  val direction = when (connection.direction) {
    ConnectionDirection.OUTGOING -> Res.string.pulse_connection_outgoing.text()
    ConnectionDirection.INCOMING -> Res.string.pulse_connection_incoming.text()
  }
  val route = when (connection.route) {
    ConnectionRoute.DIRECT -> Res.string.pulse_connection_direct.text()
    ConnectionRoute.HTTP_PROXY -> Res.string.pulse_connection_via_http_proxy.text()
    ConnectionRoute.SOCKS5_PROXY -> Res.string.pulse_connection_via_socks_proxy.text()
    ConnectionRoute.UNKNOWN -> null
  }
  val peer = connection.peer?.let { peer ->
    listOfNotNull(
      verbatim(peer.wire),
      Res.string.pulse_connection_peer_choking.text().takeIf { peer.peerChoking },
      Res.string.pulse_connection_upload_slot.text().takeIf { peer.uploadSlot },
    ).joinText()
  }
  return ConnectionDetails(
    endpoint = endpoint,
    transport = transport,
    route = listOfNotNull(direction, route).joinText(),
    peer = peer,
    rates = ratesText(connection.downloadBps, connection.uploadBps),
    totals = listOf(
      Res.string.pulse_connection_received.text(sizeText(connection.downloadedBytes)),
      Res.string.pulse_connection_sent.text(sizeText(connection.uploadedBytes)),
      Res.string.pulse_connection_open_for.text(durationText(age)),
    ).joinText(),
  )
}

private val Both = TrafficDirection.Both
private const val STRIP_ROWS = 3
private const val MINI_ROWS = 3
private const val MINI_COLUMNS = 8
private val StripCell = 6.dp
private val StripGap = 2.dp
private val MiniCell = 4.dp
private val MiniGap = 1.5.dp
private val UploadRing = 1.5.dp
