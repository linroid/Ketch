package com.linroid.ketch.app.log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_logs_open
import ketch.app.shared.generated.resources.settings_logs_open_failed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop

@Composable
internal actual fun rememberLogFilesAction(logger: FileLogger): LogFilesAction? =
  remember(logger) {
    LogFilesAction(
      title = Res.string.settings_logs_open.text(),
      description = verbatim(logger.directory.toString()),
      failed = Res.string.settings_logs_open_failed,
    ) {
      logger.flush()
      withContext(Dispatchers.IO) {
        if (!Desktop.isDesktopSupported() ||
          !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
        ) {
          throw UnsupportedOperationException("This system cannot open folders")
        }
        // Nothing may have been logged yet at a strict KETCH_LOG_LEVEL.
        val directory = logger.directory.toFile().apply { mkdirs() }
        Desktop.getDesktop().open(directory)
      }
    }
  }
