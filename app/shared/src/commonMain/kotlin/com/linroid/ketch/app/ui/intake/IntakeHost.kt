package com.linroid.ketch.app.ui.intake

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IntakeMode
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.ui.downloads.rememberClipboardLink
import com.linroid.ketch.config.ClipboardMode
import kotlinx.coroutines.flow.filterNotNull

/**
 * The clipboard the add sheet reads; `null`, the default, uses the system's. Snapshots provide
 * one with a link on it.
 */
internal val LocalIntakeClipboard = staticCompositionLocalOf<SystemClipboard?> { null }

/** The clipboard of [LocalIntakeClipboard], or the system's. */
@Composable
internal fun rememberIntakeClipboard(): SystemClipboard =
  LocalIntakeClipboard.current ?: rememberSystemClipboard()

/**
 * Shows the add sheet while [AppState.intakeRequest] is set: the request's links and seeds are
 * checked on its target device and added with the options chosen in the sheet.
 *
 * @param canFinishInBackground whether a torrent's file list may keep loading after the sheet
 *   closes; a host that goes away with the sheet, such as Android's quick add, leaves it out.
 */
@Composable
fun IntakeHost(state: AppState, canFinishInBackground: Boolean = true) {
  val controller = state.intake
  val clipboard = rememberIntakeClipboard()
  if (!state.showAddDialog) return
  val request = state.intakeRequest ?: IntakeRequest()
  val session = remember(controller, request) { controller.start(request) }
  // A link on the clipboard, offered as a chip while the sheet is empty. Clips offered before
  // count too: the chip waits for a click, unlike the prefill.
  val clipboardLink = rememberClipboardLink(state, clipboard, skipOffered = false)
  DisposableEffect(session) {
    onDispose { controller.release(session) }
  }
  LaunchedEffect(session) {
    // A .torrent file dropped on the window or opened from the system joins the sheet.
    snapshotFlow { state.droppedFile }.filterNotNull().collect(session::addFile)
  }
  LaunchedEffect(session) {
    val empty = request.text.isBlank() && request.seeds.isEmpty()
    if (!empty || session.mode != IntakeMode.Add || session.clipboardMode != ClipboardMode.Fill) {
      return@LaunchedEffect
    }
    // Opening the sheet is the user action that lets the clipboard be read, and only when it
    // can hold a link, so an image or a file on it is never read.
    catchingUnlessCancelled { if (clipboard.hasLink()) clipboard.readText() else null }
      .getOrNull()?.let(session::offerClipboard)
  }
  IntakeSheet(
    session = session,
    clipboardLink = clipboardLink,
    onClose = { state.closeAddDialog() },
    onFinishInBackground = if (canFinishInBackground) {
      { controller.finishInBackground(session) }
    } else {
      null
    },
  )
}
