package com.linroid.ketch.app.log

import androidx.compose.runtime.Composable

/**
 * How Settings → About hands the app's log files to the user for a bug report.
 *
 * @property title row label, such as "Open log folder"
 * @property description hint under the title, such as where the files are
 * @property run performs the action; it throws when the platform cannot
 */
internal class LogFilesAction(
  val title: String,
  val description: String,
  val run: suspend () -> Unit,
)

/**
 * The platform's way to reach [logger]'s files: opening their folder on desktop, sharing a copy
 * on phones. `null` where the app keeps no log files.
 */
@Composable
internal expect fun rememberLogFilesAction(logger: FileLogger): LogFilesAction?

/** Name of the copy that phones share: every log file joined, oldest records first. */
internal const val SHARED_LOG_FILE_NAME = "ketch-logs.txt"

/** The phone variant, where [share] hands a copy of the logs to the system share sheet. */
internal fun shareLogsAction(share: suspend () -> Unit) = LogFilesAction(
  title = "Share logs",
  description = "Send a copy to attach to a bug report",
  run = share,
)
