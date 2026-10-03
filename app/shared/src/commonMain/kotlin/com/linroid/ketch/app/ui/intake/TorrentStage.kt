package com.linroid.ketch.app.ui.intake

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchProgressBar
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.components.KetchTriStateCheckbox
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.IntakeEntry
import com.linroid.ketch.app.state.formatSpace
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.MenuLabel
import com.linroid.ketch.app.util.formatBytes

/**
 * Picks the files of a torrent: a filter, chips that pick a kind of file, the folder tree with
 * tri-state checkboxes, and how much space the choice needs. Extras such as samples and `.nfo`
 * files start unchecked.
 *
 * @param onBack goes back to the list of rows; `null` when the torrent is the only row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TorrentStage(actions: IntakeActions, entry: IntakeEntry, onBack: (() -> Unit)?) {
  val session = actions.session
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val files = entry.files
  var sort by remember(entry) { mutableStateOf(TorrentSort.Name) }
  val tree = remember(files, sort) { TorrentTree(files, sort) }
  var filter by remember(entry) { mutableStateOf("") }
  var collapsed by remember(entry) { mutableStateOf(emptySet<String>()) }
  var lastClicked by remember(entry) { mutableStateOf<String?>(null) }
  val selection = entry.selectedFiles.orEmpty()
  val rows = remember(tree, collapsed, filter) { tree.visible(collapsed, filter) }
  val skipped = remember(entry, tree) { tree.extras().filter { it !in selection }.toSet() }
  val pickerHeight = LocalTreeHeight.current
  fun select(ids: Set<String>) {
    entry.selectedFiles = ids
  }
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
      if (onBack != null) {
        KetchIconButton(
          icon = KetchIcon.ChevronLeft,
          contentDescription = "Back to every link",
          size = KetchButtonSize.Small,
          onClick = onBack,
        )
      }
      Column(Modifier.weight(1f)) {
        Text(
          text = entry.name,
          style = type.bodyStrong,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${selection.size} of ${files.size} files · " +
            "${formatBytes(tree.bytesOf(selection))} of ${formatBytes(tree.totalBytes)}",
          style = type.caption,
          color = colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
      KetchTextField(
        value = filter,
        onValueChange = { filter = it },
        placeholder = "Filter files",
        leadingIcon = KetchIcon.Search,
        modifier = Modifier.weight(1f),
      )
      SortButton(sort, onSort = { sort = it })
    }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      verticalArrangement = Arrangement.spacedBy(spacing.s2),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      for ((kind, count) in tree.kindCounts) {
        val ids = tree.idsOf(kind)
        KindChip(
          label = kind.label,
          count = count,
          selected = ids.isNotEmpty() && selection.containsAll(ids),
          onClick = { add -> select(if (add) selection + ids else ids) },
        )
      }
      KetchChip(
        label = "Largest file",
        selected = selection.size == 1 && selection.single() == tree.largest(),
        onClick = { tree.largest()?.let { select(setOf(it)) } },
      )
      val extras = tree.extras()
      if (extras.isNotEmpty()) {
        KetchChip(
          label = "Skip samples",
          selected = extras.none { it in selection },
          onClick = {
            select(if (extras.none { it in selection }) selection + extras else selection - extras)
          },
        )
      }
    }
    Box(
      modifier = Modifier.intakeCard(),
    ) {
      LazyColumn(
        modifier = Modifier
          .heightIn(max = pickerHeight)
          .padding(vertical = spacing.s1),
      ) {
        items(rows, key = { it.path + if (it is TorrentNode.Folder) "/" else "" }) { node ->
          TreeRow(
            node = node,
            state = toggleState(node, selection),
            collapsed = node.path in collapsed,
            onToggle = { shift ->
              val from = lastClicked
              select(
                if (shift && from != null) {
                  toggleRange(rows, from, node, selection)
                } else {
                  toggle(node, selection)
                },
              )
              lastClicked = node.path
            },
            onExpand = {
              val open = node.path in collapsed
              collapsed = if (open) collapsed - node.path else collapsed + node.path
            },
          )
        }
      }
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    ) {
      if (skipped.isNotEmpty() && skipped.none { it in selection }) {
        Text(
          text = "Skipped ${skipped.size} ${if (skipped.size == 1) "extra" else "extras"}",
          style = type.caption,
          color = colors.textSecondary,
        )
        KetchButton(
          text = "Undo",
          onClick = { select(selection + skipped) },
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
        )
      }
      Spacer(Modifier.weight(1f))
      SpaceNeeded(
        needed = tree.bytesOf(selection),
        free = session.targetStatus?.system?.usableSpace,
        device = session.targetName(),
      )
    }
  }
}

/** A kind chip: a click picks only that kind of file, ⌥-click adds it to the choice. */
@Composable
private fun KindChip(
  label: String,
  count: Int,
  selected: Boolean,
  onClick: (add: Boolean) -> Unit,
) {
  var alt by remember { mutableStateOf(false) }
  Box(
    modifier = Modifier.pointerInput(Unit) {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          if (event.type == PointerEventType.Press) alt = event.keyboardModifiers.isAltPressed
        }
      }
    },
  ) {
    KetchChip(
      label = label,
      count = count,
      selected = selected,
      onClick = {
        onClick(alt)
        alt = false
      },
    )
  }
}

@Composable
private fun SortButton(sort: TorrentSort, onSort: (TorrentSort) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    MenuLabel(label = "Sort", value = sort.label, open = expanded, onClick = { expanded = true })
    KetchMenu(expanded = expanded, onDismissRequest = { expanded = false }, title = "Sort files") {
      for (option in TorrentSort.entries) {
        item(label = option.label, checked = option == sort, onClick = { onSort(option) })
      }
    }
  }
}

@Composable
private fun TreeRow(
  node: TorrentNode,
  state: ToggleableState,
  collapsed: Boolean,
  onToggle: (shift: Boolean) -> Unit,
  onExpand: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val glyph = KetchTheme.density.controlGlyph
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  var shift by remember { mutableStateOf(false) }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = IntakeSheetDefaults.TreeRowHeight)
      // Drawn inside the row, since the tree clips anything outside it.
      .focusRing(focus.visible, KetchTheme.shapes.sm, colors.focusRing, gap = -spacing.s0_5)
      .background(overlay)
      .trackFocusVisibility(focus)
      .pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) {
              shift = event.keyboardModifiers.isShiftPressed
            }
          }
        }
      }
      .triStateToggleable(
        state = state,
        interactionSource = interactions,
        indication = null,
        role = Role.Checkbox,
        onClick = {
          onToggle(shift)
          shift = false
        },
      )
      .padding(start = spacing.s2 + spacing.s4 * node.depth, end = spacing.s3),
  ) {
    if (node is TorrentNode.Folder) {
      KetchIconImage(
        icon = if (collapsed) KetchIcon.Chevron else KetchIcon.ChevronDown,
        size = glyph,
        tint = colors.textSecondary,
        modifier = Modifier.clickable(
          onClickLabel = if (collapsed) "Expand" else "Collapse",
          role = Role.Button,
          onClick = onExpand,
        ),
      )
    } else {
      Spacer(Modifier.width(glyph))
    }
    KetchTriStateCheckbox(state = state, onClick = null, modifier = Modifier.size(spacing.s5))
    if (node is TorrentNode.File) {
      KetchFileTypeChip(fileName = node.name, size = KetchFileTypeChipDefaults.TableSize)
    } else {
      KetchIconImage(KetchIcon.Folder, size = glyph, tint = colors.textSecondary)
    }
    Text(
      text = if (node is TorrentNode.Folder) "${node.name}/" else node.name,
      style = KetchTheme.typography.bodyS,
      color = colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = formatBytes(node.size),
      style = KetchTheme.typography.numeral,
      color = colors.textSecondary,
      maxLines = 1,
    )
  }
}

/** "▰▰▱▱ needs 4.2 GB · 1.8 TB free on NAS", red when it does not fit. */
@Composable
private fun SpaceNeeded(needed: Long, free: Long?, device: String) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
  ) {
    if (free != null && free > 0) {
      KetchProgressBar(
        progress = (needed.toDouble() / free).toFloat(),
        fillColor = if (needed > free) colors.status.failed.color else colors.accent,
        modifier = Modifier.width(IntakeSheetDefaults.SpaceBarWidth),
      )
    }
    Text(
      text = buildString {
        append("needs ${formatBytes(needed)}")
        if (free != null && free > 0) append(" · ${formatSpace(free)} free on $device")
      },
      style = KetchTheme.typography.caption,
      color = if (free != null && needed > free) {
        colors.status.failed.color
      } else {
        colors.textSecondary
      },
      maxLines = 1,
    )
  }
}
