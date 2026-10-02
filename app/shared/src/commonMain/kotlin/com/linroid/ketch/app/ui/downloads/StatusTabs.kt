package com.linroid.ketch.app.ui.downloads

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.state.GroupBy
import com.linroid.ketch.app.state.ListArrangement
import com.linroid.ketch.app.state.SortKey
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.flow.first

/**
 * The status tabs with their counts, omitted at zero; the Failed count is drawn white on the
 * danger fill. [trailing] sits at the row's end, such as the tab's own action and the sort or
 * grouping menu. Tabs that do not fit scroll.
 */
@Composable
internal fun StatusTabs(
  selected: StatusFilter,
  counts: Map<StatusFilter, Int>,
  onSelect: (StatusFilter) -> Unit,
  modifier: Modifier = Modifier,
  trailing: @Composable RowScope.() -> Unit = {},
) {
  val spacing = KetchTheme.spacing
  val scroll = rememberScrollState()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = modifier
      .fillMaxWidth()
      .height(spacing.tabRowHeight)
      .padding(horizontal = spacing.pageHeaderPadding),
  ) {
    Box(Modifier.weight(1f).scrollFade(scroll).horizontalScroll(scroll)) {
      KetchSegmented(
        options = StatusFilter.entries,
        selected = selected,
        onSelect = onSelect,
        label = { it.label },
        count = { counts[it] },
        alert = { it == StatusFilter.Failed },
        shortcut = { KetchCommands.tab(it).shortcutLabel() },
      )
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      content = trailing,
    )
  }
}

/** The status tabs as a row of chips that scrolls, for phones. */
@Composable
internal fun StatusChips(
  selected: StatusFilter,
  counts: Map<StatusFilter, Int>,
  onSelect: (StatusFilter) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spacing = KetchTheme.spacing
  val scroll = rememberScrollState()
  val margin = with(LocalDensity.current) { spacing.s4.toPx() }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier
      .fillMaxWidth()
      .height(spacing.tabRowHeight)
      .scrollFade(scroll)
      .horizontalScroll(scroll)
      .padding(horizontal = spacing.s4),
  ) {
    for (filter in StatusFilter.entries) {
      val reveal = remember { BringIntoViewRequester() }
      var size by remember { mutableStateOf(IntSize.Zero) }
      if (filter == selected) {
        // A tab picked elsewhere, such as from the menu, scrolls into view with the row's
        // margin, so the first and last chips do not end flush with the screen's edge.
        LaunchedEffect(selected) {
          val chip = snapshotFlow { size }.first { it != IntSize.Zero }
          reveal.bringIntoView(Rect(-margin, 0f, chip.width + margin, chip.height.toFloat()))
        }
      }
      KetchChip(
        label = filter.label,
        selected = filter == selected,
        onClick = { onSelect(filter) },
        count = counts[filter]?.takeIf { it > 0 },
        modifier = Modifier.bringIntoViewRequester(reveal).onSizeChanged { size = it },
      )
    }
  }
}

/**
 * The tab's own summary and action at the end of the tab row: "Clear 4 finished" on Done, and
 * on Failed "2 failed · Retry all · 1 needs a new link".
 *
 * @param needsLink failed downloads whose link has to change before a retry can work.
 */
@Composable
internal fun TabAction(
  filter: StatusFilter,
  counts: Map<StatusFilter, Int>,
  needsLink: Int,
  onClearFinished: () -> Unit,
  onRetryAll: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val count = counts[filter] ?: 0
  if (count == 0) return
  when (filter) {
    StatusFilter.Done -> TextAction(clearFinishedLabel(count), onClearFinished)
    StatusFilter.Failed -> Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s1),
    ) {
      Text(
        text = "$count failed",
        style = KetchTheme.typography.caption,
        color = colors.textSecondary,
        maxLines = 1,
      )
      Separator()
      TextAction("Retry all", onRetryAll)
      if (needsLink > 0) {
        Separator()
        Text(
          text = if (needsLink == 1) "1 needs a new link" else "$needsLink need a new link",
          style = KetchTheme.typography.caption,
          color = colors.textTertiary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    else -> Unit
  }
}

/**
 * Fades a row that scrolls sideways out toward each edge it can still scroll past, so a tab or
 * chip cut by the edge reads as more to come. Put it before [horizontalScroll] with [scroll].
 */
@Composable
internal fun Modifier.scrollFade(scroll: ScrollState): Modifier {
  val fade = KetchTheme.spacing.s6
  return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
      drawContent()
      val width = fade.toPx().coerceAtMost(size.width / 2)
      val rtl = layoutDirection == LayoutDirection.Rtl
      val left = if (rtl) scroll.canScrollForward else scroll.canScrollBackward
      val right = if (rtl) scroll.canScrollBackward else scroll.canScrollForward
      if (left) {
        drawRect(
          brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            1f to Color.Black,
            endX = width,
          ),
          size = Size(width, size.height),
          blendMode = BlendMode.DstIn,
        )
      }
      if (right) {
        val start = size.width - width
        drawRect(
          brush = Brush.horizontalGradient(
            0f to Color.Black,
            1f to Color.Transparent,
            startX = start,
            endX = size.width,
          ),
          topLeft = Offset(start, 0f),
          size = Size(width, size.height),
          blendMode = BlendMode.DstIn,
        )
      }
    }
}

/** The " · " between the parts of a tab's summary. */
@Composable
private fun Separator() {
  Text(
    text = "·",
    style = KetchTheme.typography.caption,
    color = KetchTheme.colors.textTertiary,
    maxLines = 1,
  )
}

/**
 * "Sort: Smart ▾" for list rows, or "Group: Smart ▾" for the table, whose column headers sort
 * it. Picking the current sort again reverses it.
 */
@Composable
internal fun ArrangementMenu(
  arrangement: ListArrangement,
  table: Boolean,
  onChange: (ListArrangement) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    if (table) {
      MenuLabel("Group", arrangement.group.label, open) { open = true }
    } else {
      val arrow = when {
        arrangement.sort == SortKey.Smart -> ""
        arrangement.descending -> " ↓"
        else -> " ↑"
      }
      MenuLabel("Sort", arrangement.sort.label + arrow, open) { open = true }
    }
    KetchMenu(expanded = open, onDismissRequest = { open = false }) {
      if (table) {
        for (group in GroupBy.entries.filter { it != GroupBy.Device }) {
          item(
            label = group.label,
            checked = arrangement.group == group,
            onClick = { onChange(arrangement.copy(group = group)) },
          )
        }
      } else {
        header("Sort by")
        for (key in ListSortKeys) {
          item(
            label = key.label,
            checked = arrangement.sort == key,
            onClick = { onChange(arrangement.sortedBy(key)) },
          )
        }
        divider()
        header("Group by")
        for (group in GroupBy.entries.filter { it != GroupBy.Device }) {
          item(
            label = group.label,
            checked = arrangement.group == group,
            onClick = { onChange(arrangement.copy(group = group)) },
          )
        }
      }
    }
  }
}

/** A quiet "Label: value ▾" button that opens a menu. */
@Composable
internal fun MenuLabel(label: String, value: String, open: Boolean, onClick: () -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.sm
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = Modifier
      .focusRing(focus.visible, shape, colors.focusRing)
      .height(KetchTheme.density.chip)
      .clip(shape)
      .background(if (open) colors.surfaceHover else Color.Transparent)
      .background(overlay)
      .ketchClickable(interactions, focus, onClick = onClick)
      .padding(horizontal = spacing.s2),
  ) {
    Text(
      text = buildAnnotatedString {
        withStyle(SpanStyle(color = colors.textTertiary)) { append("$label: ") }
        append(value)
      },
      style = KetchTheme.typography.labelS,
      color = colors.textPrimary,
      maxLines = 1,
    )
    KetchIconImage(KetchIcon.ChevronDown, size = spacing.s3, tint = colors.textTertiary)
  }
}

/** An accent text button for a header's action, such as "Retry all". */
@Composable
internal fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.xs
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  Text(
    text = text,
    style = KetchTheme.typography.labelS,
    color = colors.accentText,
    maxLines = 1,
    modifier = modifier
      .focusRing(focus.visible, shape, colors.focusRing)
      .clip(shape)
      .background(overlay)
      .ketchClickable(interactions, focus, onClick = onClick)
      .padding(horizontal = spacing.s1, vertical = spacing.s0_5),
  )
}

/** Sort keys the list rows' Sort menu offers; the table sorts by any column. */
private val ListSortKeys = listOf(
  SortKey.Smart,
  SortKey.Name,
  SortKey.Added,
  SortKey.Size,
  SortKey.Progress,
  SortKey.Speed,
  SortKey.Status
)
