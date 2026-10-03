package com.linroid.ketch.app.ui.inspector

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.connectionText
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.durationText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.speedText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.actions.RowActionRunner
import com.linroid.ketch.app.ui.inspector.tabs.middleEllipsis
import com.linroid.ketch.app.util.averageSpeed
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.inspector_copied
import ketch.app.shared.generated.resources.inspector_copy_detail
import ketch.app.shared.generated.resources.inspector_copy_failed_added
import ketch.app.shared.generated.resources.inspector_copy_failed_average
import ketch.app.shared.generated.resources.inspector_copy_failed_captured
import ketch.app.shared.generated.resources.inspector_copy_failed_connections
import ketch.app.shared.generated.resources.inspector_copy_failed_device
import ketch.app.shared.generated.resources.inspector_copy_failed_headers
import ketch.app.shared.generated.resources.inspector_copy_failed_link
import ketch.app.shared.generated.resources.inspector_copy_failed_path
import ketch.app.shared.generated.resources.inspector_copy_failed_property
import ketch.app.shared.generated.resources.inspector_copy_failed_size
import ketch.app.shared.generated.resources.inspector_copy_failed_source
import ketch.app.shared.generated.resources.inspector_copy_failed_task_id
import ketch.app.shared.generated.resources.inspector_copy_failed_time
import ketch.app.shared.generated.resources.inspector_detail_added
import ketch.app.shared.generated.resources.inspector_detail_average
import ketch.app.shared.generated.resources.inspector_detail_captured
import ketch.app.shared.generated.resources.inspector_detail_connections
import ketch.app.shared.generated.resources.inspector_detail_device
import ketch.app.shared.generated.resources.inspector_detail_headers
import ketch.app.shared.generated.resources.inspector_detail_link
import ketch.app.shared.generated.resources.inspector_detail_path
import ketch.app.shared.generated.resources.inspector_detail_property
import ketch.app.shared.generated.resources.inspector_detail_size
import ketch.app.shared.generated.resources.inspector_detail_source
import ketch.app.shared.generated.resources.inspector_detail_task_id
import ketch.app.shared.generated.resources.inspector_detail_time
import ketch.app.shared.generated.resources.inspector_hide_full_link
import ketch.app.shared.generated.resources.inspector_section_advanced
import ketch.app.shared.generated.resources.inspector_section_details
import ketch.app.shared.generated.resources.inspector_show_full_link
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Copies text to the clipboard, posting an error when the system refuses. */
@Stable
internal class Copier(
  private val clipboard: SystemClipboard?,
  private val scope: CoroutineScope,
  private val state: AppState,
) {
  private val log = KetchLogger("Inspector")

  /** Whether there is a clipboard to copy to. */
  val enabled: Boolean get() = clipboard != null

  /**
   * Copies [text] and runs [onCopied] once it is copied; when the system refuses, posts
   * [failure], such as "Couldn't copy the link".
   */
  fun copy(text: String, failure: UiText, onCopied: () -> Unit = {}) {
    val clipboard = clipboard ?: return
    scope.launch {
      catchingUnlessCancelled { clipboard.writeText(text) }
        .onSuccess { onCopied() }
        .onFailure { e ->
          log.w { "Couldn't copy to the clipboard: ${e.describeCauses()}" }
          state.messages.post(MessageLevel.Error, failure, cause = e)
        }
    }
  }
}

/** A [Copier] for the app's clipboard. */
@Composable
internal fun rememberCopier(state: AppState): Copier {
  val clipboard = rememberSystemClipboard()
  val scope = rememberCoroutineScope()
  return remember(clipboard, scope, state) { Copier(clipboard, scope, state) }
}

/**
 * The DETAILS of [row], each row copying its value when clicked (long-pressed on touch), which
 * then reads "Copied" for a moment: Source, Link (the query behind "Show full link"), Saved to,
 * Size, Added, Time spent and Avg speed once completed, Connections, Device, Captured, and
 * under Advanced the task id, the names of its headers and its properties.
 */
@Composable
internal fun TaskDetails(
  state: AppState,
  row: TaskRow,
  device: DeviceLabel,
  runner: RowActionRunner,
  copier: Copier,
  modifier: Modifier = Modifier,
) {
  val request = row.request
  val completed = row.state as? DownloadState.Completed
  InspectorSection(stringResource(Res.string.inspector_section_details), modifier) {
    DetailRow(Detail.Source, copier, sourceLabel(request, row.isTorrent).resolve())
    LinkRow(request.url, copier)
    val path = row.outputPath
    if (path != null) {
      val reveal = !row.device.capabilities.isRemote &&
        runner.commands.canRun(RowAction.ShowInFolder, row)
      DetailRow(
        detail = Detail.SavedTo,
        copier = copier,
        copy = path,
        mono = true,
        trailing = if (reveal) {
          {
            KetchIconButton(
              icon = KetchIcon.Reveal,
              contentDescription =
                (runner.files?.revealLabel ?: RowAction.ShowInFolder.label).resolve(),
              onClick = { runner.run(RowAction.ShowInFolder, listOf(row)) },
              size = KetchButtonSize.Small,
            )
          }
        } else {
          null
        },
      ) { MiddleText(path, KetchTheme.typography.monoS, KetchTheme.colors.textPrimary) }
    }
    row.sizeBytes?.let { DetailRow(Detail.Size, copier, sizeText(it).resolve()) }
    val zone = remember { TimeZone.currentSystemDefault() }
    val added = addedDetail(row, LocalClock.current.now(), zone).resolve()
    DetailRow(Detail.Added, copier, added)
    val time = completed?.downloadTime
    if (completed != null && time != null) {
      DetailRow(Detail.TimeSpent, copier, durationText(time).resolve())
      val total = completed.totalBytes
      val average = total?.let { averageSpeed(it, time) }
      if (average != null) DetailRow(Detail.AverageSpeed, copier, speedText(average).resolve())
    }
    // While the Controls show, they show the connections too.
    if (!row.state.hasControls && !row.isTorrent) {
      val auto = autoConnectionsOf(state, listOf(row))
      val connections = connectionText(request.connections, auto).resolve()
      DetailRow(Detail.Connections, copier, connections)
    }
    DetailRow(Detail.Device, copier, copy = device.name) {
      // The pennant is taller than a line; it overhangs the row's padding instead.
      Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier.height(KetchTheme.spacing.s4).wrapContentHeight(unbounded = true),
      ) {
        DeviceName(
          device = device,
          style = KetchTheme.typography.bodyS,
          color = KetchTheme.colors.textPrimary,
        )
      }
    }
    capturedText(request)?.let { DetailRow(Detail.Captured, copier, it.resolve()) }
    Advanced(row, copier)
  }
}

/** The link: host in a heavier weight and the path shortened in the middle on one line. */
@Composable
private fun LinkRow(url: String, copier: Copier) {
  val parts = remember(url) { linkParts(url) }
  var full by remember(url) { mutableStateOf(false) }
  DetailRow(Detail.Link, copier, copy = url) {
    Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1)) {
      if (full) {
        Text(
          text = parts.full,
          style = KetchTheme.typography.monoS,
          color = KetchTheme.colors.textPrimary,
        )
      } else {
        LinkText(parts)
      }
      if (parts.query != null) {
        val label = if (full) {
          Res.string.inspector_hide_full_link
        } else {
          Res.string.inspector_show_full_link
        }
        TextLink(stringResource(label)) { full = !full }
      }
    }
  }
}

@Composable
private fun LinkText(parts: LinkParts) {
  val style = KetchTheme.typography.bodyS.copy(color = KetchTheme.colors.textPrimary)
  val host = parts.host
  fun text(path: String): AnnotatedString = buildAnnotatedString {
    if (host != null) withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(host) }
    append(path)
  }
  val path = parts.path.ifEmpty { if (host == null) "" else "/" }
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val measurer = rememberTextMeasurer()
    val width = with(LocalDensity.current) { maxWidth.roundToPx() }
    val shown = remember(parts, width, style) {
      middleEllipsis(path) { candidate ->
        measurer.measure(text(candidate), style, maxLines = 1).size.width <= width
      }
    }
    Text(text = text(shown), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/** Task id, header names (values never show) and properties, folded until opened. */
@Composable
private fun Advanced(row: TaskRow, copier: Copier) {
  var open by remember(row.key) { mutableStateOf(false) }
  Disclosure(stringResource(Res.string.inspector_section_advanced), open) { open = !open }
  if (!open) return
  val request = row.request
  DetailRow(Detail.TaskId, copier, row.key.taskId, mono = true)
  if (request.headers.isNotEmpty()) {
    val names = request.headers.keys.sortedBy { it.lowercase() }.joinToString(", ")
    DetailRow(Detail.Headers, copier, names)
  }
  for ((name, value) in request.properties.entries.sortedBy { it.key }) {
    DetailRow(Detail.Property, copier, "$name = $value", mono = true)
  }
}

/** A detail of the inspector: its [label], and what a failed copy of it says. */
private enum class Detail(val label: StringResource, val copyFailed: StringResource) {
  Source(Res.string.inspector_detail_source, Res.string.inspector_copy_failed_source),
  Link(Res.string.inspector_detail_link, Res.string.inspector_copy_failed_link),
  SavedTo(Res.string.inspector_detail_path, Res.string.inspector_copy_failed_path),
  Size(Res.string.inspector_detail_size, Res.string.inspector_copy_failed_size),
  Added(Res.string.inspector_detail_added, Res.string.inspector_copy_failed_added),
  TimeSpent(Res.string.inspector_detail_time, Res.string.inspector_copy_failed_time),
  AverageSpeed(Res.string.inspector_detail_average, Res.string.inspector_copy_failed_average),
  Connections(
    Res.string.inspector_detail_connections,
    Res.string.inspector_copy_failed_connections,
  ),
  Device(Res.string.inspector_detail_device, Res.string.inspector_copy_failed_device),
  Captured(Res.string.inspector_detail_captured, Res.string.inspector_copy_failed_captured),
  TaskId(Res.string.inspector_detail_task_id, Res.string.inspector_copy_failed_task_id),
  Headers(Res.string.inspector_detail_headers, Res.string.inspector_copy_failed_headers),
  Property(Res.string.inspector_detail_property, Res.string.inspector_copy_failed_property),
}

@Composable
private fun DetailRow(detail: Detail, copier: Copier, value: String, mono: Boolean = false) {
  DetailRow(detail = detail, copier = copier, copy = value, mono = mono) {
    Text(
      text = value,
      style = if (mono) KetchTheme.typography.monoS else KetchTheme.typography.bodyS,
      color = KetchTheme.colors.textPrimary,
    )
  }
}

/**
 * One detail: its label in the label column, then the value, which [copy] puts on the clipboard
 * on a click, or a long press on touch. While the pointer is over it a copy glyph shows at its
 * end, over the value, and after a copy the value reads "Copied" for a moment.
 *
 * @param trailing a button after the value, such as Show in Finder; the copy glyph then stays
 *   hidden.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailRow(
  detail: Detail,
  copier: Copier,
  copy: String?,
  mono: Boolean = false,
  trailing: (@Composable () -> Unit)? = null,
  value: @Composable () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.xs
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  val copyable = copy != null && copier.enabled
  val overlay = rememberInteractionOverlay(interactions, enabled = copyable)
  val focus = rememberFocusVisibility()
  var copies by remember { mutableIntStateOf(0) }
  val copied = rememberFlash(copies)
  val touch = KetchTheme.density == KetchDensity.Comfortable
  val label = stringResource(detail.label)
  val copyIt: () -> Unit = {
    if (copy != null) copier.copy(copy, detail.copyFailed.text()) { copies++ }
  }
  val copyLabel = stringResource(Res.string.inspector_copy_detail, label)
  val clickable = if (copyable) {
    Modifier
      .focusRing(focus.visible, shape, colors.focusRing)
      .clip(shape)
      .background(overlay)
      .trackFocusVisibility(focus)
      .combinedClickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClickLabel = copyLabel,
        onLongClickLabel = copyLabel,
        onLongClick = copyIt,
        onClick = if (touch) ({}) else copyIt,
      )
  } else {
    Modifier
  }
  Box(Modifier.bleed(spacing.s1).fillMaxWidth().then(clickable)) {
    Row(
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = spacing.s6)
        .padding(horizontal = spacing.s1, vertical = spacing.s1),
    ) {
      Text(
        text = label,
        style = KetchTheme.typography.caption,
        color = colors.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .width(InspectorLabelWidth - spacing.s2)
          .padding(top = spacing.s0_5 / 2),
      )
      Box(Modifier.weight(1f)) {
        if (copied) {
          Text(
            text = stringResource(Res.string.inspector_copied),
            style = if (mono) KetchTheme.typography.monoS else KetchTheme.typography.bodyS,
            color = colors.accentText,
          )
        } else {
          value()
        }
      }
      if (trailing != null) {
        // The button may be taller than a line; it overhangs the row's padding instead.
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier.height(spacing.s4).wrapContentHeight(unbounded = true),
        ) { trailing() }
      }
    }
    if (copyable && trailing == null && !touch && (hovered || focus.visible) && !copied) {
      KetchIconImage(
        icon = KetchIcon.Copy,
        size = spacing.s3 + spacing.s0_5,
        tint = colors.textSecondary,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(top = spacing.s1, end = spacing.s1)
          .background(colors.surfaceRaised, shape)
          .border(spacing.s0_5 / 2, colors.hairline, shape)
          .padding(spacing.s0_5),
      )
    }
  }
}
