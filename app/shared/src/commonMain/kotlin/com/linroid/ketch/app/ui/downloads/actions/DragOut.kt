package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchSpacing
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.KetchTypography
import com.linroid.ketch.app.ui.list.outputFile

/**
 * What dragging rows out of the Downloads list carries.
 *
 * @property files paths of finished files on this device, which file managers and other apps
 *   take as files.
 * @property links the rows' links, for browsers, text fields and other devices.
 * @property keys the rows' keys, for drops inside the app, such as onto another device.
 */
@Immutable
internal data class DragPayload(
  val files: List<String>,
  val links: List<String>,
  val keys: List<TaskKey>,
) {
  /** The in-app payload: one `ketch-task://` key per line; see [TaskKey.encode]. */
  val keysText: String get() = keys.joinToString("\n") { it.encode() }

  /** What a text drop gets: the file paths when there are files, else the links. */
  val text: String get() = (files.ifEmpty { links }).joinToString("\n")

  /** The `text/uri-list` form of the links, lines ending in CRLF as the format asks. */
  val uriList: String get() = links.joinToString("\r\n", postfix = "\r\n")

  companion object {
    /** MIME type of [keysText] in drags. */
    const val KEYS_MIME_TYPE: String = "application/x-ketch-tasks"

    /** The payload of dragging [rows]: finished files of this device as files, all as links. */
    fun of(rows: List<TaskRow>): DragPayload = DragPayload(
      files = rows.mapNotNull { row ->
        row.outputFile?.takeIf {
          row.key.deviceId == LOCAL_DEVICE_ID && !it.startsWith("content://")
        }
      },
      links = rows.map { it.request.url },
      keys = rows.map { it.key },
    )

    /** The keys in [text] made by [keysText]; lines that are not keys are skipped. */
    fun keysOf(text: String): List<TaskKey> = text.lines().mapNotNull { TaskKey.decode(it.trim()) }
  }
}

/** The platform's transfer data for [payload], or `null` where nothing can be dragged out. */
internal expect fun dragTransferData(payload: DragPayload): DragAndDropTransferData?

/** Keys of rows dragged within the app in [event], or empty when it carries none. */
internal expect fun draggedTaskKeys(event: DragAndDropEvent): List<TaskKey>

/**
 * Lets rows be dragged out of the list with a pointer: finished files of this device go to file
 * managers and other apps as files, everything else as its link, and drops inside the app get
 * the rows' keys. The drag shows [drawDragPreview].
 *
 * @param rows the rows a drag that starts here carries: the selection when this row is in it,
 *   otherwise this row.
 */
@Composable
internal fun Modifier.taskDragSource(rows: () -> List<TaskRow>): Modifier {
  val measurer = rememberTextMeasurer()
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val currentRows by rememberUpdatedState(rows)
  return dragAndDropSource(
    drawDragDecoration = { drawDragPreview(currentRows(), measurer, colors, spacing, type) },
  ) { _ ->
    val dragged = currentRows()
    if (dragged.isEmpty()) null else dragTransferData(DragPayload.of(dragged))
  }
}

/**
 * Draws what a drag of [rows] shows under the pointer, in the theme's [colors], [spacing] and
 * [type]: a pill with the first row's name and, for several rows, how many files or downloads
 * come along, at the start of the drawing area.
 */
internal fun DrawScope.drawDragPreview(
  rows: List<TaskRow>,
  measurer: TextMeasurer,
  colors: KetchColors,
  spacing: KetchSpacing,
  type: KetchTypography,
) {
  val first = rows.firstOrNull() ?: return
  val padding = spacing.s3.toPx()
  val gap = spacing.s2.toPx()
  val height = spacing.s8.toPx().coerceAtMost(size.height)
  val badge = if (rows.size > 1) {
    val files = DragPayload.of(rows).files.size
    val noun = if (files == rows.size) "files" else "downloads"
    measurer.measure("${rows.size} $noun", type.numeralS.copy(color = colors.onAccent))
  } else {
    null
  }
  val badgeWidth = badge?.let { it.size.width + gap * 2 } ?: 0f
  val nameMax = (size.width - padding * 2 - badgeWidth - gap).coerceAtLeast(0f)
  val name = measurer.measure(
    text = first.name,
    style = type.label.copy(color = colors.textPrimary),
    overflow = TextOverflow.Ellipsis,
    maxLines = 1,
    constraints = Constraints(maxWidth = nameMax.toInt()),
  )
  val width = (name.size.width + padding * 2 + if (badge != null) badgeWidth + gap else 0f)
    .coerceAtMost(size.width)
  val corner = CornerRadius(height / 2)
  val top = (size.height - height) / 2
  drawRoundRect(colors.surfaceRaised, Offset(0f, top), Size(width, height), corner)
  // One dp.
  drawRoundRect(colors.borderStrong, Offset(0f, top), Size(width, height), corner, Stroke(density))
  drawText(name, topLeft = Offset(padding, top + (height - name.size.height) / 2))
  if (badge != null) {
    val badgeHeight = badge.size.height + spacing.s0_5.toPx() * 2
    val left = padding + name.size.width + gap
    val badgeTop = top + (height - badgeHeight) / 2
    drawRoundRect(
      color = colors.accent,
      topLeft = Offset(left, badgeTop),
      size = Size(badgeWidth, badgeHeight),
      cornerRadius = CornerRadius(badgeHeight / 2),
    )
    drawText(badge, topLeft = Offset(left + gap, badgeTop + spacing.s0_5.toPx()))
  }
}
