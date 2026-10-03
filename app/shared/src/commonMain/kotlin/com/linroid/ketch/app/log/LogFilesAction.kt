package com.linroid.ketch.app.log

import androidx.compose.runtime.Composable
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_logs_share
import ketch.app.shared.generated.resources.settings_logs_share_failed
import ketch.app.shared.generated.resources.settings_logs_share_hint
import org.jetbrains.compose.resources.StringResource

/**
 * How Settings → About hands the app's log files to the user for a bug report.
 *
 * @property title row label, such as "Open log folder"
 * @property description hint under the title, such as where the files are
 * @property failed what the row says when [run] fails, with the reason as `%1$s`, such as
 *   "Couldn't open log folder: %1$s"
 * @property run performs the action; it throws when the platform cannot
 */
internal class LogFilesAction(
  val title: UiText,
  val description: UiText,
  val failed: StringResource,
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
  title = Res.string.settings_logs_share.text(),
  description = Res.string.settings_logs_share_hint.text(),
  failed = Res.string.settings_logs_share_failed,
  run = share,
)
