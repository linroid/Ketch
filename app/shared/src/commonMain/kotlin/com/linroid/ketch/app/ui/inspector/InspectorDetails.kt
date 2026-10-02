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
import com.linroid.ketch.app.components.connectionLabel
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.feedback.MessageLevel
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
import com.linroid.ketch.app.util.formatBytes
import com.linroid.ketch.app.util.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

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

  /** Copies [text], called [what] in an error, and runs [onCopied] once it is copied. */
  fun copy(text: String, what: String, onCopied: () -> Unit = {}) {
    val clipboard = clipboard ?: return
    scope.launch {
      catchingUnlessCancelled { clipboard.writeText(text) }
        .onSuccess { onCopied() }
        .onFailure { e ->
          log.w { "Couldn't copy the $what: ${e.describeCauses()}" }
          state.messages.post(MessageLevel.Error, "Couldn't copy the $what", cause = e)
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
  InspectorSection("Details", modifier) {
    DetailRow("Source", copier, sourceLabel(request, row.isTorrent))
    LinkRow(request.url, copier)
    val path = row.outputPath
    if (path != null) {
      val reveal = !row.device.capabilities.isRemote &&
        runner.commands.canRun(RowAction.ShowInFolder, row)
      DetailRow(
        label = "Saved to",
        copier = copier,
        copy = path,
        mono = true,
        trailing = if (reveal) {
          {
            KetchIconButton(
              icon = KetchIcon.Reveal,
              contentDescription = runner.files?.revealLabel ?: RowAction.ShowInFolder.label,
              onClick = { runner.run(RowAction.ShowInFolder, listOf(row)) },
              size = KetchButtonSize.Small,
            )
          }
        } else {
          null
        },
      ) { MiddleText(path, KetchTheme.typography.monoS, KetchTheme.colors.textPrimary) }
    }
    row.sizeBytes?.let { DetailRow("Size", copier, formatBytes(it)) }
    val zone = remember { TimeZone.currentSystemDefault() }
    DetailRow("Added", copier, addedDetail(row, LocalClock.current.now(), zone))
    val time = completed?.downloadTime
    if (completed != null && time != null) {
      DetailRow("Time spent", copier, formatDuration(time))
      val total = completed.totalBytes
      val average = total?.let { averageSpeed(it, time) }
      if (average != null) DetailRow("Avg speed", copier, "${formatBytes(average)}/s")
    }
    // While the Controls show, they show the connections too.
    if (!row.state.hasControls && !row.isTorrent) {
      val auto = state.instanceSettings.download?.maxConnectionsPerDownload?.takeIf { it > 0 }
      DetailRow("Connections", copier, connectionLabel(request.connections, auto))
    }
    DetailRow("Device", copier, copy = device.name) {
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
    capturedText(request)?.let { DetailRow("Captured", copier, it) }
    Advanced(row, copier)
  }
}

/** The link: host in a heavier weight and the path shortened in the middle on one line. */
@Composable
private fun LinkRow(url: String, copier: Copier) {
  val parts = remember(url) { linkParts(url) }
  var full by remember(url) { mutableStateOf(false) }
  DetailRow("Link", copier, copy = url) {
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
        TextLink(if (full) "Hide full link" else "Show full link") { full = !full }
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
  Disclosure("Advanced", open) { open = !open }
  if (!open) return
  val request = row.request
  DetailRow("Task ID", copier, row.key.taskId, mono = true)
  if (request.headers.isNotEmpty()) {
    val names = request.headers.keys.sortedBy { it.lowercase() }.joinToString(", ")
    DetailRow("Headers", copier, names)
  }
  for ((name, value) in request.properties.entries.sortedBy { it.key }) {
    DetailRow("Property", copier, "$name = $value", mono = true)
  }
}

@Composable
private fun DetailRow(label: String, copier: Copier, value: String, mono: Boolean = false) {
  DetailRow(label = label, copier = copier, copy = value, mono = mono) {
    Text(
      text = value,
      style = if (mono) KetchTheme.typography.monoS else KetchTheme.typography.bodyS,
      color = KetchTheme.colors.textPrimary,
    )
  }
}

/**
 * One detail: [label] in the label column, then the value, which [copy] puts on the clipboard
 * on a click, or a long press on touch. While the pointer is over it a copy glyph shows at its
 * end, over the value, and after a copy the value reads "Copied" for a moment.
 *
 * @param trailing a button after the value, such as Show in Finder; the copy glyph then stays
 *   hidden.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailRow(
  label: String,
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
  val copyIt: () -> Unit = {
    if (copy != null) copier.copy(copy, label.lowercase()) { copies++ }
  }
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
        onClickLabel = "Copy $label",
        onLongClickLabel = "Copy $label",
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
            text = "Copied",
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
