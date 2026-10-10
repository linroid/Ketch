package com.linroid.ketch.app.state

import androidx.compose.ui.state.ToggleableState

/** Whether every, some or none of [fileIds] are in [selection]. */
internal fun toggleState(fileIds: List<String>, selection: Set<String>): ToggleableState {
  val selected = fileIds.count { it in selection }
  return when (selected) {
    0 -> ToggleableState.Off
    fileIds.size -> ToggleableState.On
    else -> ToggleableState.Indeterminate
  }
}

/**
 * [selection] after clicking a row of [fileIds]: a fully selected row clears, any other selects
 * all.
 */
internal fun toggle(fileIds: List<String>, selection: Set<String>): Set<String> =
  if (toggleState(fileIds, selection) == ToggleableState.On) {
    selection - fileIds.toSet()
  } else {
    selection + fileIds
  }
