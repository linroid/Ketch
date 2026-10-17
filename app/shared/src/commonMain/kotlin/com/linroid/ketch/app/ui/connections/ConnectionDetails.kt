package com.linroid.ketch.app.ui.connections

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.inspector.tabs.compactSizeText
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.connections_column_client
import ketch.app.shared.generated.resources.connections_column_device
import ketch.app.shared.generated.resources.connections_column_down
import ketch.app.shared.generated.resources.connections_column_open
import ketch.app.shared.generated.resources.connections_column_peer
import ketch.app.shared.generated.resources.connections_column_task
import ketch.app.shared.generated.resources.connections_column_up
import ketch.app.shared.generated.resources.connections_detail_hint
import ketch.app.shared.generated.resources.connections_peer_interested
import ketch.app.shared.generated.resources.connections_peer_unchoked
import ketch.app.shared.generated.resources.pulse_connection_peer_choking
import ketch.app.shared.generated.resources.pulse_connection_received
import ketch.app.shared.generated.resources.pulse_connection_secure
import ketch.app.shared.generated.resources.pulse_connection_sent
import ketch.app.shared.generated.resources.pulse_connection_show_task
import ketch.app.shared.generated.resources.pulse_connection_upload_slot
import org.jetbrains.compose.resources.stringResource

/**
 * Everything the page knows about [row]'s connection, label by label: the hover card of a table
 * row, and what a list row shows once tapped open. Values follow the live samples.
 *
 * @param showDevice whether to name the device, as under All devices.
 * @param hint whether to say that the task name shows the task, as the card cannot be clicked.
 * @param endpoint whether to lead with the endpoint, which a list row shows already.
 */
@Composable
internal fun ConnectionDetails(
  row: ConnectionRow,
  showDevice: Boolean,
  hint: Boolean,
  modifier: Modifier = Modifier,
  endpoint: Boolean = true,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  val connection = row.connection
  Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
    if (endpoint) {
      Text(
        text = endpointText(row),
        style = type.monoS,
        color = colors.textPrimary,
      )
    }
    Text(
      text = summaryOf(row).resolve(),
      style = type.caption,
      color = colors.textSecondary,
      modifier = Modifier.padding(bottom = spacing.s1),
    )
    Detail(Res.string.connections_column_task.text(), row.taskName ?: connection.taskId)
    if (showDevice) Detail(Res.string.connections_column_device.text(), row.deviceName)
    row.client?.let { Detail(Res.string.connections_column_client.text(), it) }
    peerDetails(row)?.let { Detail(Res.string.connections_column_peer.text(), it.resolve()) }
    Detail(
      Res.string.connections_column_down.text(),
      transferText(
        rateText(connection.downloadBps),
        Res.string.pulse_connection_received.text(compactSizeText(connection.downloadedBytes)),
      ),
    )
    Detail(
      Res.string.connections_column_up.text(),
      transferText(
        rateText(connection.uploadBps),
        Res.string.pulse_connection_sent.text(compactSizeText(connection.uploadedBytes)),
      ),
    )
    Detail(Res.string.connections_column_open.text(), durationText(row.cell.age).resolve())
    if (hint) {
      Text(
        text = stringResource(Res.string.connections_detail_hint),
        style = type.caption,
        color = colors.textTertiary,
        modifier = Modifier.padding(top = spacing.s1),
      )
    }
  }
}

@Composable
private fun Detail(label: UiText, value: String) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  Row(horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2)) {
    Text(
      text = label.resolve(),
      style = type.caption,
      color = colors.textTertiary,
      maxLines = 1,
      modifier = Modifier.width(DetailLabelWidth),
    )
    Text(text = value, style = type.cell, color = colors.textPrimary)
  }
}

/** Its protocol, whether it is encrypted, and how it reaches its endpoint. */
private fun summaryOf(row: ConnectionRow): UiText = listOfNotNull(
  verbatim(row.protocol),
  Res.string.pulse_connection_secure.text().takeIf { row.connection.secure == true },
  routeText(row),
).joinText()

/** A torrent peer's wire and state in full; `null` for other connections. */
private fun peerDetails(row: ConnectionRow): UiText? = row.connection.peer?.let { peer ->
  listOfNotNull(
    verbatim(peer.wire),
    if (peer.peerChoking) {
      Res.string.pulse_connection_peer_choking.text()
    } else {
      Res.string.connections_peer_unchoked.text()
    },
    Res.string.connections_peer_interested.text().takeIf { peer.peerInterested },
    Res.string.pulse_connection_upload_slot.text().takeIf { peer.uploadSlot },
  ).joinText()
}

/** A speed, a dash for none, then what moved in all. */
@Composable
private fun transferText(rate: String?, total: UiText): String =
  listOf(verbatim(rate ?: Dash), total).joinText().resolve()

/**
 * The task cell of a table row: the one part of the row that shows the task, so it reads as a
 * link on hover or focus. It tells [onHover] while the pointer is on it, so the row's hover card
 * can make way for its own tooltip, and [onFocus] while it shows keyboard focus, so the card opens
 * for the keyboard too.
 */
@Composable
internal fun TaskLink(
  row: ConnectionRow,
  onShow: (ConnectionRow) -> Unit,
  onHover: (Boolean) -> Unit,
  onFocus: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  LaunchedEffect(hovered) { onHover(hovered) }
  LaunchedEffect(focus.visible) { onFocus(focus.visible) }
  val showTask = stringResource(Res.string.pulse_connection_show_task)
  val active = hovered || focus.visible
  KetchTooltip(text = showTask, modifier = modifier.fillMaxHeight()) {
    Box(
      contentAlignment = Alignment.CenterStart,
      modifier = Modifier
        .focusRing(focus.visible, KetchTheme.shapes.xs, colors.focusRing)
        .hoverable(interactions)
        .pointerHoverIcon(PointerIcon.Hand)
        .ketchClickable(
          interactions = interactions,
          focus = focus,
          onClickLabel = showTask,
          onClick = { onShow(row) },
        ),
    ) {
      Text(
        text = row.taskName ?: row.connection.taskId,
        style = KetchTheme.typography.cell,
        color = if (active) colors.accentText else colors.textSecondary,
        textDecoration = if (active) TextDecoration.Underline else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = CellPadding),
      )
    }
  }
}

private val DetailLabelWidth = 72.dp
