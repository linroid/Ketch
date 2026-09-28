package com.linroid.ketch.app.log

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.linroid.ketch.app.shared.R
import okio.Path.Companion.toOkioPath

@Composable
internal actual fun rememberLogFilesAction(logger: FileLogger): LogFilesAction? {
  val context = LocalContext.current
  return remember(logger, context) {
    shareLogsAction {
      // Only this folder is exposed, by LogFileProvider.
      val file = context.cacheDir.resolve("logs").resolve(SHARED_LOG_FILE_NAME)
      logger.exportTo(file.toOkioPath())
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
      val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Ketch logs")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      context.startActivity(Intent.createChooser(send, "Share logs"))
    }
  }
}

/**
 * Serves the copies of the log files made for sharing. A subclass, so that it cannot clash
 * with another `FileProvider` in the merged manifest.
 */
internal class LogFileProvider : FileProvider(R.xml.ketch_log_paths)
