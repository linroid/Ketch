package com.linroid.ketch.app.state

import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands

/** The zooms Settings offers and the zoom commands step through, in percent of the normal size. */
internal val ZoomLevels: List<Int> = listOf(90, 100, 110, 125, 150, 175, 200)

/**
 * The first of [ZoomLevels] above [zoom], also from a zoom between two of them; `null` from the
 * largest up.
 */
internal fun zoomLevelAbove(zoom: Int): Int? = ZoomLevels.firstOrNull { it > zoom }

/**
 * The last of [ZoomLevels] below [zoom], also from a zoom between two of them; `null` from the
 * smallest down.
 */
internal fun zoomLevelBelow(zoom: Int): Int? = ZoomLevels.lastOrNull { it < zoom }

/**
 * Runs [command] when it is [KetchCommands.ZoomIn], [KetchCommands.ZoomOut] or
 * [KetchCommands.ActualSize]; returns whether it was one of them.
 */
fun AppSettingsController.runZoomCommand(command: KetchCommand): Boolean {
  when (command) {
    KetchCommands.ZoomIn -> zoomIn()
    KetchCommands.ZoomOut -> zoomOut()
    KetchCommands.ActualSize -> resetZoom()
    else -> return false
  }
  return true
}
