package com.linroid.ketch.app.ui.list

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.RowGroup
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText
import com.linroid.ketch.app.ui.downloads.actions.FollowFocusedRow
import com.linroid.ketch.app.ui.downloads.actions.ListActions
import com.linroid.ketch.app.ui.downloads.actions.listKeyboard
import com.linroid.ketch.app.ui.downloads.actions.pageSizeOf
import com.linroid.ketch.app.ui.downloads.actions.rubberBand

/** One item of a grouped task list: a group's header or a row. */
internal sealed interface ListEntry {
  /** Key of the item in the lazy list. */
  val key: String

  /** The header of [group], [collapsed] or not. */
  data class Header(val group: RowGroup, val collapsed: Boolean) : ListEntry {
    override val key: String get() = "group:${group.id}"
  }

  /** A task's row in the group [groupId]. */
  data class Row(val row: TaskRow, val groupId: String) : ListEntry {
    override val key: String get() = row.key.encode()
  }
}

/**
 * Which groups of the Downloads list are collapsed. A group the user never clicked follows
 * [RowGroup.collapsedByDefault], so "Added earlier" starts collapsed once it holds many rows.
 */
@Stable
internal class GroupCollapse {
  private val toggled = mutableStateMapOf<String, Boolean>()

  /** Whether [group] shows only its header. */
  fun isCollapsed(group: RowGroup): Boolean = toggled[group.id] ?: group.collapsedByDefault

  /** Collapses [group] when it is open and opens it when it is collapsed. */
  fun toggle(group: RowGroup) {
    toggled[group.id] = !isCollapsed(group)
  }

  /** Opens [group]. */
  fun expand(group: RowGroup) {
    if (isCollapsed(group)) toggled[group.id] = false
  }
}

/**
 * The items of a list showing [groups]: each titled group's header, then its rows unless it is
 * collapsed. Untitled groups, as when the list is not grouped, have no header.
 */
internal fun listEntries(groups: List<RowGroup>, collapse: GroupCollapse): List<ListEntry> =
  buildList {
    for (group in groups) {
      val collapsed = group.title.isNotEmpty() && collapse.isCollapsed(group)
      if (group.title.isNotEmpty()) add(ListEntry.Header(group, collapsed))
      if (!collapsed) group.rows.forEach { add(ListEntry.Row(it, group.id)) }
    }
  }

/**
 * The scrolling part of the Downloads table and list: [entries] with sticky group headers, the
 * list keys of [actions], the rubber band with a pointer, and rows that glide to their new
 * places as the list re-sorts. A row the keyboard focuses inside a collapsed group opens it.
 *
 * @param header a group's header.
 * @param row a task's row.
 */
@Composable
internal fun TaskLazyList(
  entries: List<ListEntry>,
  groups: List<RowGroup>,
  actions: ListActions,
  collapse: GroupCollapse,
  listState: LazyListState,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(),
  header: @Composable (ListEntry.Header) -> Unit,
  row: @Composable LazyItemScope.(TaskRow) -> Unit,
) {
  val pointer = KetchTheme.density == KetchDensity.Compact
  val currentEntries by rememberUpdatedState(entries)
  val focused = actions.selection.focusedKey
  LaunchedEffect(focused, groups) {
    val key = focused ?: return@LaunchedEffect
    groups.firstOrNull { group -> group.rows.any { it.key == key } }?.let(collapse::expand)
  }
  FollowFocusedRow(listState, focused) { key -> indexOf(currentEntries, key) }
  remember(entries) {
    // A list at its top stays there as rows arrive or move, rather than following its first row.
    val atTop = Snapshot.withoutReadObservation {
      listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
    }
    if (atTop) listState.requestScrollToItem(0)
  }
  LazyColumn(
    state = listState,
    contentPadding = contentPadding,
    modifier = modifier
      .listKeyboard(actions.keyboard, { actions.rows }, { pageSizeOf(listState) })
      .then(
        if (pointer) {
          Modifier.rubberBand(
            listState = listState,
            selection = actions.selection,
            keyAt = { index -> (currentEntries.getOrNull(index) as? ListEntry.Row)?.row?.key },
            onStart = actions.keyboard::focus,
          )
        } else {
          Modifier
        }
      ),
  ) {
    for (entry in entries) {
      when (entry) {
        is ListEntry.Header -> stickyHeader(key = entry.key, contentType = HEADER) {
          header(entry)
        }
        is ListEntry.Row -> item(key = entry.key, contentType = ROW) { row(entry.row) }
      }
    }
  }
}

/** [Modifier.animateItem] with the list's motion, or nothing under reduce motion. */
@Composable
internal fun LazyItemScope.placement(): Modifier {
  val motion = KetchTheme.motion
  if (motion.reduced) return Modifier
  return Modifier.animateItem(
    fadeInSpec = tween(motion.short),
    placementSpec = motion.placementSpring,
    fadeOutSpec = tween(motion.short),
  )
}

/**
 * The sticky header of a group of rows: a chevron, the group's title as an eyebrow and its
 * details, such as "DOWNLOADING · 2 · 9.1 MB/s · all done ≈ 14:32". Clicking it collapses or
 * opens the group; [trailing] holds the group's own action, such as Retry all.
 *
 * @param height the header's height.
 * @param padding horizontal padding, matching the rows below.
 */
@Composable
internal fun GroupHeader(
  entry: ListEntry.Header,
  onToggle: () -> Unit,
  height: Dp,
  padding: Dp,
  modifier: Modifier = Modifier,
  trailing: @Composable RowScope.() -> Unit = {},
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val group = entry.group
  val focus = rememberFocusVisibility()
  val interactions = remember { MutableInteractionSource() }
  val hairline = colors.hairline
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = modifier
      .fillMaxWidth()
      .height(height)
      .background(colors.surface)
      .drawBehind {
        val y = size.height - density / 2
        drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = density)
      }
      .focusRing(focus.visible, KetchTheme.shapes.xs, colors.focusRing, gap = -spacing.s0_5)
      .ketchClickable(
        interactions = interactions,
        focus = focus,
        role = null,
        onClickLabel = if (entry.collapsed) "Show ${group.title}" else "Hide ${group.title}",
        onClick = onToggle,
      )
      .semantics {
        heading()
        stateDescription = if (entry.collapsed) "Collapsed" else "Expanded"
      }
      .padding(horizontal = padding),
  ) {
    KetchIconImage(
      icon = if (entry.collapsed) KetchIcon.Chevron else KetchIcon.ChevronDown,
      size = spacing.s3,
      tint = colors.textTertiary,
    )
    Text(
      text = eyebrowText(group.title),
      style = type.eyebrow,
      color = colors.textSecondary,
      maxLines = 1,
    )
    if (group.details.isNotEmpty()) {
      Text(
        text = group.details.joinToString(prefix = "· ", separator = " · "),
        style = type.numeralS,
        color = colors.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    } else {
      Spacer(Modifier.weight(1f))
    }
    trailing()
  }
}

private fun indexOf(entries: List<ListEntry>, key: TaskKey): Int =
  entries.indexOfFirst { it is ListEntry.Row && it.row.key == key }

private const val HEADER = "header"
private const val ROW = "row"
