package com.linroid.ketch.app.log

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.linroid.ketch.app.shared.R
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_logs_share
import ketch.app.shared.generated.resources.settings_logs_share_subject
import okio.Path.Companion.toOkioPath
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun rememberLogFilesAction(logger: FileLogger): LogFilesAction? {
  val context = LocalContext.current
  val chooserTitle = stringResource(Res.string.settings_logs_share)
  val subject = stringResource(Res.string.settings_logs_share_subject)
  return remember(logger, context, chooserTitle, subject) {
    shareLogsAction {
      // Only this folder is exposed, by LogFileProvider.
      val file = context.cacheDir.resolve("logs").resolve(SHARED_LOG_FILE_NAME)
      logger.exportTo(file.toOkioPath())
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
      val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      context.startActivity(Intent.createChooser(send, chooserTitle))
    }
  }
}

/**
 * Serves the copies of the log files made for sharing. A subclass, so that it cannot clash
 * with another `FileProvider` in the merged manifest.
 */
internal class LogFileProvider : FileProvider(R.xml.ketch_log_paths)
