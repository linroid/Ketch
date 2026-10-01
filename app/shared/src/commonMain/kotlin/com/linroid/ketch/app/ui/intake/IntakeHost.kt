package com.linroid.ketch.app.ui.intake

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.ui.dialog.AddDownloadDialog
import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.app.util.links

/**
 * Shows the add dialog while [AppState.intakeRequest] is set, prefilled with the first link of
 * the request, and adds the download to the active device.
 */
@Composable
fun IntakeHost(state: AppState) {
  val request = state.intakeRequest
  if (!state.showAddDialog) return
  // Each opened file or request starts a fresh form.
  key(state.openedDownload, request) {
    AddDownloadDialog(
      resolveState = state.resolveState,
      onResolveUrl = { state.resolveUrl(it) },
      onResetResolve = { state.resetResolveState() },
      onDismiss = { state.closeAddDialog() },
      onDownload = { url, fileName, speedLimit, priority, schedule, resolved, selectedFileIds ->
        state.closeAddDialog()
        state.startDownload(
          url, fileName, speedLimit, priority,
          schedule, resolved, selectedFileIds
        )
      },
      droppedFileName = state.droppedFile?.name,
      onRetryDroppedFile = {
        state.droppedFile?.let { state.resolveDroppedFile(it) }
      },
      onDropFiles = { state.addDroppedFiles(it) },
      initialUrl = request?.let(::initialUrl).orEmpty(),
    )
  }
}

/** Link the add dialog starts with: the first seed of [request], else its text's first link. */
internal fun initialUrl(request: IntakeRequest): String =
  request.seeds.firstOrNull()?.url
    ?: LinkParser.parseIntake(request.text).links().firstOrNull()?.url
    ?: ""
