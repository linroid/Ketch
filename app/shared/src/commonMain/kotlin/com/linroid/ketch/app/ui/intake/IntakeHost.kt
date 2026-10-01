package com.linroid.ketch.app.ui.intake

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IntakeController
import com.linroid.ketch.app.state.IntakeMode
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.app.util.links
import com.linroid.ketch.config.ClipboardMode
import kotlinx.coroutines.flow.filterNotNull

/**
 * Shows the add sheet while [AppState.intakeRequest] is set: the request's links and seeds are
 * checked on its target device and added with the options chosen in the sheet.
 */
@Composable
fun IntakeHost(state: AppState) {
  val scope = rememberCoroutineScope()
  val controller = remember(state, scope) { IntakeController(state, scope) }
  val clipboard = rememberSystemClipboard()
  if (!state.showAddDialog) return
  val request = state.intakeRequest ?: IntakeRequest()
  val session = remember(controller, request) { controller.start(request) }
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
    onClose = { state.closeAddDialog() },
    onFinishInBackground = { controller.finishInBackground(session) },
  )
}

/** Link the add sheet starts with: the first seed of [request], else its text's first link. */
internal fun initialUrl(request: IntakeRequest): String =
  request.seeds.firstOrNull()?.url
    ?: LinkParser.parseIntake(request.text).links().firstOrNull()?.url
    ?: ""
