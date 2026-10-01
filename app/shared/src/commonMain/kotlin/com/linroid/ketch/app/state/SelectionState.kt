package com.linroid.ketch.app.state

/**
 * Which rows of the Downloads list are selected.
 *
 * Operations that span rows take the visible keys in display order: after filtering, sorting
 * and grouping, with the rows of collapsed groups included. The selection survives filter
 * changes; [prune] drops tasks that were removed. Pointer, keyboard and rubber band input map
 * onto [click], [contextClick], [moveFocus] and [band].
 *
 * @property selected the selected tasks.
 * @property anchor where a Shift range starts: the row last clicked or toggled.
 * @property focused the row with the keyboard focus.
 */
data class SelectionState(
  val selected: Set<TaskKey> = emptySet(),
  val anchor: TaskKey? = null,
  val focused: TaskKey? = null,
) {
  /** Number of selected tasks. */
  val count: Int
    get() = selected.size

  /** Whether [key] is selected. */
  operator fun contains(key: TaskKey): Boolean = key in selected

  /** A click: selects only [key]. */
  fun select(key: TaskKey): SelectionState = SelectionState(setOf(key), key, key)

  /**
   * A `⌘`-click, a click on the checkbox column or a long-press: selects [key] or deselects it,
   * keeping the rest.
   */
  fun toggle(key: TaskKey): SelectionState {
    val toggled = if (key in selected) selected - key else selected + key
    return SelectionState(toggled, anchor = key, focused = key)
  }

  /**
   * A `⇧`-click: selects the [visible] rows from [anchor] to [key]. With [additive], as on a
   * `⌘⇧`-click, the range adds to the selection instead of replacing it. Without a visible
   * anchor this is a plain [select].
   */
  fun selectRange(key: TaskKey, visible: List<TaskKey>, additive: Boolean = false): SelectionState {
    val end = visible.indexOf(key)
    if (end < 0) return this
    val start = anchor?.let(visible::indexOf) ?: -1
    if (start < 0) return select(key)
    val range = visible.subList(minOf(start, end), maxOf(start, end) + 1)
    val chosen = if (additive) selected + range else range.toSet()
    return copy(selected = chosen, focused = key)
  }

  /**
   * A primary click on [key]: with [range] (⇧) it selects from the anchor, with [toggle] (⌘ on
   * Apple keyboards, Ctrl elsewhere) it toggles the row, and with both it adds the range.
   * A plain click selects only [key].
   */
  fun click(
    key: TaskKey,
    visible: List<TaskKey>,
    toggle: Boolean = false,
    range: Boolean = false,
  ): SelectionState = when {
    range -> selectRange(key, visible, additive = toggle)
    toggle -> toggle(key)
    else -> select(key)
  }

  /**
   * A right-click or a menu key on [key]: inside the selection it keeps the selection, so the
   * menu acts on every selected row; outside it selects only [key].
   */
  fun contextClick(key: TaskKey): SelectionState =
    if (key in selected) copy(focused = key) else select(key)

  /**
   * The rows an action started from [key] acts on, in [visible] order: the visible selected
   * rows when [key] is one of them, otherwise [key] alone. Selected rows that a filter hides
   * are left out.
   */
  fun targets(key: TaskKey, visible: List<TaskKey>): List<TaskKey> =
    if (key in selected) visible.filter { it in selected } else listOf(key)

  /** The selected rows among [visible], in their order. */
  fun visibleSelection(visible: List<TaskKey>): List<TaskKey> = visible.filter { it in selected }

  /**
   * A rubber band over [covered], in display order: selects those rows, after the rows in
   * [base], which a ⌘-drag keeps. The first covered row becomes the anchor and the last the
   * focus. With nothing covered only [base] stays selected.
   */
  fun band(covered: List<TaskKey>, base: Set<TaskKey> = emptySet()): SelectionState =
    SelectionState(
      selected = base + covered,
      anchor = covered.firstOrNull() ?: anchor,
      focused = covered.lastOrNull() ?: focused,
    )

  /** `⌘A`: selects every [visible] row, keeping the anchor when it is one of them. */
  fun selectAllVisible(visible: List<TaskKey>): SelectionState {
    if (visible.isEmpty()) return this
    return SelectionState(
      selected = visible.toSet(),
      anchor = anchor?.takeIf { it in visible } ?: visible.first(),
      focused = focused?.takeIf { it in visible } ?: visible.first(),
    )
  }

  /**
   * Arrow keys, Home, End and the page keys: moves the focus [offset] rows through [visible],
   * stopping at either end (pass [Int.MIN_VALUE] or [Int.MAX_VALUE] to jump there) and
   * selecting the row reached. With [extend], as with `⇧`, the selection runs from [anchor] to
   * that row. From no focused row, a move down starts at the first row and a move up at the
   * last.
   */
  fun moveFocus(visible: List<TaskKey>, offset: Int, extend: Boolean = false): SelectionState {
    if (visible.isEmpty()) return this
    val current = focused?.let(visible::indexOf) ?: -1
    val index = when {
      current >= 0 -> (current.toLong() + offset).coerceIn(0L, visible.lastIndex.toLong()).toInt()
      offset >= 0 -> 0
      else -> visible.lastIndex
    }
    val target = visible[index]
    if (!extend) return select(target)
    val start = anchor?.takeIf { it in visible } ?: focused?.takeIf { it in visible } ?: target
    return SelectionState(anchor = start).selectRange(target, visible)
  }

  /** Esc: clears the selection and keeps the focus. */
  fun clear(): SelectionState = SelectionState(focused = focused)

  /** Drops tasks that are not in [existing], such as removed ones. */
  fun prune(existing: Set<TaskKey>): SelectionState {
    if (existing.containsAll(selected) && (anchor == null || anchor in existing) &&
      (focused == null || focused in existing)
    ) {
      return this
    }
    return SelectionState(
      selected = selected.filterTo(LinkedHashSet()) { it in existing },
      anchor = anchor?.takeIf { it in existing },
      focused = focused?.takeIf { it in existing },
    )
  }
}
